package com.pocketge.tracker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure trade-tracking logic (no RuneLite types) so it is unit-testable.
 *
 * Feed it cumulative offer snapshots (slot, itemId, qtySold, gpSpent,
 * isBuy); it emits incremental {@link TradeFill}s and matches sells
 * against earlier buys FIFO to produce {@link Flip}s with profit AFTER
 * the 2% GE tax.
 *
 * Baseline rule: the first snapshot seen for a slot (login replay, or an
 * offer that predates the tracker) sets a baseline WITHOUT emitting fills
 * — only growth we actually witness is counted, so nothing double-counts.
 */
public class FlipTracker
{
	/** Mirror of the site's tax rules: 2% == floor(price/50) per item,
	 *  nothing under 50 gp, capped at 5M per item, and a short list of
	 *  exempt items (bond + classic tools). */
	private static final Set<Integer> TAX_EXEMPT_IDS = new HashSet<>(List.of(
		13190, // Old school bond
		1755,  // Chisel
		5325,  // Gardening trowel
		11364, // Glassblowing pipe
		2347,  // Hammer
		1733,  // Needle
		233,   // Pestle and mortar
		5341,  // Rake
		8794,  // Saw
		5329,  // Secateurs
		5343,  // Seed dibber
		1735,  // Shears
		952,   // Spade
		5331   // Watering can
	));

	public static long taxPerItem(long unitPrice, int itemId)
	{
		if (unitPrice < 50 || TAX_EXEMPT_IDS.contains(itemId))
		{
			return 0;
		}
		return Math.min(unitPrice / 50, 5_000_000L);
	}

	private static class SlotState
	{
		int itemId;
		boolean buy;
		int qtySold;
		long spent;
		/** The offer's asking price and its full size. Neither can change
		 *  while an offer is live — the Exchange has no way to edit one —
		 *  so together with the item they say WHICH offer a slot is holding.
		 *  That is what tells a resumed offer apart from a different one
		 *  placed in the same slot since this baseline was written. 0 when
		 *  not supplied; see the 8-argument {@link #onOffer}. */
		long price;
		int totalQuantity;
		/** True for a baseline read back off disk that no live event has
		 *  confirmed yet. Growth measured against one of these happened at
		 *  an unknown moment while the client was closed, so the lot it
		 *  opens carries no fill time. Cleared the first time this slot is
		 *  seen live. */
		boolean restored;
		/** Identifies THIS offer, so the flips its fills book can be
		 *  recognised as one trade. Minted when the slot takes a new offer,
		 *  and kept for that offer's whole life. See {@link Flip#offerId}. */
		long offerToken;
	}

	private static class BuyLot
	{
		int qty;
		long spent;
		/** When this lot filled, epoch millis; 0 for a lot restored from a
		 *  save written before the tracker recorded it. Survives partial
		 *  consumption — selling half a lot does not change when the other
		 *  half was bought. */
		long time;

		BuyLot(int qty, long spent, long time)
		{
			this.qty = qty;
			this.spent = spent;
			this.time = time;
		}
	}

	/** Cap on persisted/held flip history so state stays small. */
	private static final int MAX_FLIPS = 500;
	private static final int MAX_FILLS = 1000;

	/** Baselines per character, then per slot. Keyed by account because slot 3
	 *  is a different offer for every character and this state is shared by
	 *  all of them; key 0 is "logged in as nobody yet". Kept rather than
	 *  cleared on a change of character, so switching back and forth cannot
	 *  make the same offer look new twice — which, now that a new offer gets
	 *  its filled portion booked, would be a double count rather than merely
	 *  a forgotten one. */
	private final Map<Long, Map<Integer, SlotState>> slotsByAccount = new HashMap<>();
	/** A backstop on {@link #slotsByAccount}, not a real limit — nobody plays
	 *  twenty characters, and if the account hash ever misbehaved this stops
	 *  the state blob growing without bound. */
	private static final int MAX_ACCOUNTS = 20;
	/** Members get eight offer slots; free-to-play uses the first three. */
	private static final int GE_SLOTS = 8;
	/** An item id no offer can have, marking a slot as "on record, but not as
	 *  any actual offer" — see the upgrade path in {@link #restore}. */
	private static final int NO_OFFER = -1;
	private final Map<Integer, Deque<BuyLot>> openBuys = new HashMap<>();
	private final List<TradeFill> fills = new ArrayList<>();
	private final List<Flip> flips = new ArrayList<>();
	private long sessionProfit = 0;
	private long lifetimeProfit = 0;
	/** Wall-clock start of the current session, for "Session" time-range
	 *  stats and the hourly-profit-rate calc. Reset alongside the session
	 *  counter, not on restore (a restored client keeps its own session). */
	private long sessionStartMillis = System.currentTimeMillis();
	/** Whose slot baselines are in play, 0 until a character logs in. See
	 *  {@link #setAccountHash}. */
	private long accountHash;
	/** Set whenever a slot baseline moves, whether or not a fill came with
	 *  it. See {@link #takeSlotsDirty}. */
	private boolean slotsDirty;
	/** Mints {@link SlotState#offerToken}. Persisted so a token is never
	 *  reused across restarts — two different offers sharing one would
	 *  merge two unrelated trades into a single history row. */
	private long nextOfferToken = 1;

	/**
	 * Told about each flip the moment it is booked, so it can be written to
	 * the permanent ledger.
	 *
	 * A callback rather than the tracker owning a file, because the whole
	 * point of this class is that it has no I/O and no RuneLite types in it —
	 * the FIFO matching below is the part that must stay unit-testable. It
	 * also means the sink sees flips that {@link #MAX_FLIPS} will later push
	 * out of the in-memory window, which is the entire reason it exists.
	 *
	 * Never null; a no-op until someone sets one.
	 */
	private java.util.function.Consumer<Flip> flipSink = f -> { };

	public synchronized void setFlipSink(java.util.function.Consumer<Flip> sink)
	{
		this.flipSink = sink != null ? sink : f -> { };
	}

	/** Serializable snapshot of everything worth keeping across client
	 *  restarts: lifetime P/L, flip history, and the open buy lots so a
	 *  flip still books correctly when the sell happens tomorrow. */
	public static class State
	{
		public long lifetimeProfit;
		public List<Flip> flips;
		public Map<Integer, List<long[]>> openBuys; // itemId -> [qty, spent, fillTime] lots
		/** slot -> [itemId, buy?1:0, qtySold, spent, price, totalQuantity].
		 *
		 *  Offers keep filling while you are logged out, and without this the
		 *  tracker met every one of them as a stranger on the next login: a
		 *  slot it had never seen baselines instead of booking, so an
		 *  overnight buy was silently uncosted forever. Remembering where each
		 *  slot stood turns that login replay from "no idea" into a
		 *  measurement — the growth since the last save is real growth. */
		public Map<Integer, long[]> slots;
		/** The same thing, per character: accountHash -> slot -> row. Written
		 *  instead of {@link #slots} from 0.6.5 on; {@link #slots} is still
		 *  read so a state file from the version between does not lose its
		 *  baselines on upgrade. */
		public Map<Long, Map<Integer, long[]>> slotsByAccount;
		/** Which character the slots above belong to, or 0 for a save written
		 *  before this was recorded. Slot baselines are the one piece of state
		 *  here that is meaningless on another account: slot 3 is a different
		 *  offer for every character, and this file is shared by all of them. */
		public long accountHash;
		/** Next value for {@link SlotState#offerToken}; see nextOfferToken. */
		public long nextOfferToken;
	}

	/**
	 * Tell the tracker which character is logged in.
	 *
	 * Only the slot baselines care. Everything else here — lifetime profit,
	 * flip history, open lots — has always been shared across a player's
	 * accounts, and merging those is at worst a presentation question. A slot
	 * baseline is different in kind: slot 3 holds a different offer on every
	 * character, so carrying one across would measure account B's offer
	 * against account A's numbers and book the difference as a real fill.
	 * Changing character therefore throws the baselines away and starts over,
	 * which costs nothing worse than the behaviour before they existed.
	 */
	public synchronized void setAccountHash(long hash)
	{
		if (hash <= 0 || hash == accountHash)
		{
			return;
		}
		/* Anything baselined before we knew who was logged in belongs to
		   whoever that turned out to be. In practice this is empty — the
		   offer handler learns the account before it records anything — but a
		   state file written by an older version has its slots here. */
		final Map<Integer, SlotState> unknown = slotsByAccount.remove(0L);
		accountHash = hash;
		if (unknown != null && !unknown.isEmpty())
		{
			slotsByAccount.computeIfAbsent(hash, k -> new HashMap<>()).putAll(unknown);
			slotsDirty = true;
		}
		if (slotsByAccount.size() > MAX_ACCOUNTS)
		{
			slotsByAccount.keySet().removeIf(k -> k != accountHash
				&& slotsByAccount.size() > MAX_ACCOUNTS);
			slotsDirty = true;
		}
	}

	/** This character's slot baselines. */
	private Map<Integer, SlotState> slots()
	{
		return slotsByAccount.computeIfAbsent(accountHash, k -> new HashMap<>());
	}

	/**
	 * Whether a slot baseline has moved since this was last asked, clearing
	 * the flag.
	 *
	 * The caller has to persist those, and most of them produce no fill: an
	 * offer being placed, or collected, only moves a baseline. An unsaved
	 * baseline is re-taken from scratch on the next start, and re-taking one
	 * is precisely how an offline fill goes uncounted — so "nothing to show
	 * the user" and "nothing to write down" are not the same question, and
	 * this is the second one.
	 */
	public synchronized boolean takeSlotsDirty()
	{
		final boolean d = slotsDirty;
		slotsDirty = false;
		return d;
	}

	public synchronized State snapshot()
	{
		State s = new State();
		s.lifetimeProfit = lifetimeProfit;
		s.accountHash = accountHash;
		s.nextOfferToken = nextOfferToken;
		s.slotsByAccount = new HashMap<>();
		for (Map.Entry<Long, Map<Integer, SlotState>> acc : slotsByAccount.entrySet())
		{
			final Map<Integer, long[]> rows = new HashMap<>();
			for (Map.Entry<Integer, SlotState> e : acc.getValue().entrySet())
			{
				final SlotState st = e.getValue();
				rows.put(e.getKey(), new long[]{
					st.itemId, st.buy ? 1 : 0, st.qtySold, st.spent, st.price, st.totalQuantity,
					st.offerToken});
			}
			if (!rows.isEmpty())
			{
				s.slotsByAccount.put(acc.getKey(), rows);
			}
		}
		s.flips = new ArrayList<>(flips);
		s.openBuys = new HashMap<>();
		for (Map.Entry<Integer, Deque<BuyLot>> e : openBuys.entrySet())
		{
			List<long[]> lots = new ArrayList<>();
			for (BuyLot lot : e.getValue())
			{
				lots.add(new long[]{lot.qty, lot.spent, lot.time});
			}
			if (!lots.isEmpty())
			{
				s.openBuys.put(e.getKey(), lots);
			}
		}
		return s;
	}

	public synchronized void restore(State s)
	{
		if (s == null)
		{
			return;
		}
		lifetimeProfit = s.lifetimeProfit;
		accountHash = s.accountHash;
		/* Never go backwards: a save from before tokens existed reads 0, and
		   starting from 1 again would hand an old token to a new offer. */
		nextOfferToken = Math.max(nextOfferToken, Math.max(1L, s.nextOfferToken));
		slotsByAccount.clear();
		if (s.slotsByAccount != null)
		{
			for (Map.Entry<Long, Map<Integer, long[]>> acc : s.slotsByAccount.entrySet())
			{
				if (acc.getKey() == null || acc.getValue() == null)
				{
					continue;
				}
				slotsByAccount.put(acc.getKey(), readSlots(acc.getValue()));
			}
		}
		else if (s.slots != null)
		{
			/* A save from the one version that kept a single flat map. Those
			   baselines are the account that file recorded, so file them under
			   it rather than dropping them. */
			slotsByAccount.put(s.accountHash, readSlots(s.slots));
		}
		else if (s.lifetimeProfit != 0
			|| (s.flips != null && !s.flips.isEmpty())
			|| (s.openBuys != null && !s.openBuys.isEmpty()))
		{
			/*
			 * A save from before baselines were kept at all, carrying history.
			 *
			 * Its open lots may already include part of whatever is sitting in
			 * the slots right now — that version counted the growth it
			 * witnessed before the client last closed — and seeding those
			 * offers would count the same gold a second time. So every slot
			 * gets a placeholder: a record that matches no real offer, which
			 * sends the first sighting down the baseline path exactly as the
			 * old version would have, and lets normal behaviour resume from
			 * the second.
			 *
			 * One pass, on one upgrade. Filed under the unknown account, which
			 * setAccountHash hands to whoever logs in first — so a player with
			 * several characters gets this protection on the first one they
			 * open, and the others seed immediately. That is the narrow case
			 * where a single item's cost basis can come out high, once.
			 */
			final Map<Integer, SlotState> placeholders = new HashMap<>();
			for (int slot = 0; slot < GE_SLOTS; slot++)
			{
				final SlotState st = new SlotState();
				st.itemId = NO_OFFER;
				st.offerToken = nextOfferToken++;
				placeholders.put(slot, st);
			}
			slotsByAccount.put(0L, placeholders);
		}
		flips.clear();
		if (s.flips != null)
		{
			flips.addAll(s.flips);
		}
		openBuys.clear();
		if (s.openBuys != null)
		{
			for (Map.Entry<Integer, List<long[]>> e : s.openBuys.entrySet())
			{
				Deque<BuyLot> lots = new ArrayDeque<>();
				for (long[] l : e.getValue())
				{
					if (l == null || l.length < 2)
					{
						continue;
					}
					/* Length 2 is a save written before lots carried a time.
					   Those lots restore with time 0, so flips closed against
					   them report an unknown hold rather than a wrong one. */
					lots.add(new BuyLot((int) l[0], l[1], l.length > 2 ? l[2] : 0L));
				}
				openBuys.put(e.getKey(), lots);
			}
		}
	}

	/** One character's saved slot rows, back into baselines. Every one is
	 *  marked restored: it came off disk, so growth measured against it
	 *  happened at a moment nobody recorded. */
	private Map<Integer, SlotState> readSlots(Map<Integer, long[]> rows)
	{
		final Map<Integer, SlotState> out = new HashMap<>();
		for (Map.Entry<Integer, long[]> e : rows.entrySet())
		{
			final long[] l = e.getValue();
			if (e.getKey() == null || l == null || l.length < 4)
			{
				continue;
			}
			final SlotState st = new SlotState();
			st.itemId = (int) l[0];
			st.buy = l[1] != 0;
			st.qtySold = (int) l[2];
			st.spent = l[3];
			/* Terms were added after the rest. A save without them restores as
			   0, which onOffer reads as "not recorded" rather than as a
			   mismatch — see sameTerms. */
			st.price = l.length > 4 ? l[4] : 0L;
			st.totalQuantity = l.length > 5 ? (int) l[5] : 0;
			/* Carried across the restart so an offer resumed tomorrow still
			   groups with the fills it booked today. A save from before tokens
			   existed reads 0, and gets a fresh one — those earlier rows stand
			   alone, which is the honest outcome: nothing recorded says they
			   belonged together. */
			st.offerToken = l.length > 6 && l[6] != 0 ? l[6] : nextOfferToken++;
			st.restored = true;
			out.put(e.getKey(), st);
		}
		return out;
	}

	/**
	 * Consume a cumulative offer snapshot. Returns the fill this snapshot
	 * produced, or null (baseline set / no growth / slot cleared).
	 */
	public synchronized TradeFill onOffer(long now, int slot, int itemId, String itemName,
		boolean buy, int qtySold, long spent, boolean emptied)
	{
		return onOffer(now, slot, itemId, itemName, buy, qtySold, spent, 0L, 0, emptied);
	}

	/**
	 * As above, told the offer's terms as well.
	 *
	 * {@code price} and {@code totalQuantity} are never used as numbers — only
	 * to recognise an offer. See {@link SlotState#price}.
	 */
	public synchronized TradeFill onOffer(long now, int slot, int itemId, String itemName,
		boolean buy, int qtySold, long spent, long price, int totalQuantity, boolean emptied)
	{
		final Map<Integer, SlotState> slots = slots();
		if (emptied)
		{
			slotsDirty |= slots.remove(slot) != null;
			return null;
		}
		SlotState st = slots.get(slot);
		if (st == null)
		{
			/*
			 * Never seen this slot on this character — so whatever the offer
			 * has already bought is history nobody has counted, and the offer
			 * itself is carrying the receipt: quantitySold and spent are the
			 * exact units and the exact gold, straight off the Exchange.
			 *
			 * This used to baseline at those numbers and book nothing, which
			 * threw the receipt away. That was the right call when baselines
			 * only lived in memory, because then every login looked like a
			 * first sighting and counting one would count the same offer again
			 * on every relog. Now that they persist per character, a first
			 * sighting really is the first, so the honest move is to start
			 * from zero and let the ordinary delta path book what is there.
			 *
			 * The one thing the offer does not carry is WHEN any of it filled,
			 * so this lot is marked the same way an offline one is.
			 */
			st = new SlotState();
			st.itemId = itemId;
			st.buy = buy;
			st.qtySold = 0;
			st.spent = 0;
			st.price = price;
			st.totalQuantity = totalQuantity;
			/* Only if there is history to attribute. A brand-new offer at zero
			   is about to be watched from the start, and its fills carry real
			   times. */
			st.restored = qtySold > 0;
			st.offerToken = nextOfferToken++;
			slots.put(slot, st);
			slotsDirty = true;
		}
		else if (st.itemId != itemId
			|| st.buy != buy
			/* Backwards is not the same offer. A slot holding less than it did
			   was emptied and refilled while we were not looking. */
			|| qtySold < st.qtySold
			|| !sameTerms(st, price, totalQuantity))
		{
			/*
			 * A DIFFERENT offer in a slot we have a record for. Baseline where
			 * it stands and book nothing — deliberately not the seeding above.
			 *
			 * "Replaced" is the ambiguous case: we may already have counted
			 * part of its predecessor, and telling a replacement apart from a
			 * misread of the same offer is exactly where a mistake would
			 * double count. "No record at all" is not ambiguous, which is why
			 * that branch is allowed to book and this one is not.
			 */
			st = new SlotState();
			st.itemId = itemId;
			st.buy = buy;
			st.qtySold = qtySold;
			st.spent = spent;
			st.price = price;
			st.totalQuantity = totalQuantity;
			st.offerToken = nextOfferToken++;
			slots.put(slot, st);
			slotsDirty = true;
			return null;
		}
		int dQty = qtySold - st.qtySold;
		long dSpent = spent - st.spent;
		/* This baseline came off disk, so whatever grew against it grew while
		   the client was shut. The gold is real and the cost is exact; only
		   the moment is unknowable, and a lot that claims a fill time it does
		   not have would turn into a hold duration that was never measured. */
		final boolean offline = st.restored;
		st.qtySold = qtySold;
		st.spent = spent;
		st.restored = false;
		/* Learn the terms if the save predates them, so the NEXT restart can
		   tell this offer apart from its replacement. */
		st.price = price;
		st.totalQuantity = totalQuantity;
		slotsDirty = true;
		if (dQty <= 0 || dSpent < 0)
		{
			return null;
		}
		TradeFill fill = new TradeFill(now, itemId, itemName, buy, dQty, dSpent);
		fills.add(fill);
		if (fills.size() > MAX_FILLS)
		{
			fills.remove(0);
		}
		if (buy)
		{
			openBuys.computeIfAbsent(itemId, k -> new ArrayDeque<>())
				.addLast(new BuyLot(dQty, dSpent, offline ? 0L : now));
		}
		else
		{
			matchSell(fill, st.offerToken);
		}
		return fill;
	}

	/**
	 * Whether this event's terms match the ones the baseline was taken under.
	 *
	 * A 0 on either side means "not recorded" — a save written before terms
	 * were kept, or the 8-argument entry point the tests use — and is not a
	 * mismatch. Treating unknown as different would throw away a baseline
	 * that is very probably the same offer, and throwing one away is the
	 * failure this whole mechanism exists to stop.
	 */
	private static boolean sameTerms(SlotState st, long price, int totalQuantity)
	{
		if (st.price > 0 && price > 0 && st.price != price)
		{
			return false;
		}
		return !(st.totalQuantity > 0 && totalQuantity > 0 && st.totalQuantity != totalQuantity);
	}

	/** FIFO-match a sell fill against open buy lots of the same item. */
	private void matchSell(TradeFill sell, long offerToken)
	{
		Deque<BuyLot> lots = openBuys.get(sell.itemId);
		if (lots == null || lots.isEmpty())
		{
			return; // sold something we never saw bought — no flip to close
		}
		int remaining = sell.quantity;
		long buySpent = 0;
		int matched = 0;
		/* The fill time of the FIRST lot this sell consumes. FIFO means that
		   is the oldest one, which is the moment the gold in this flip was
		   committed — and it is the same lot buySpent starts from, so the
		   duration and the cost always describe the same units. */
		long openedAt = 0;
		boolean firstLot = true;
		while (remaining > 0 && !lots.isEmpty())
		{
			BuyLot lot = lots.peekFirst();
			if (firstLot)
			{
				openedAt = lot.time;
				firstLot = false;
			}
			int take = Math.min(remaining, lot.qty);
			long slice = Math.round((double) lot.spent * take / lot.qty);
			buySpent += slice;
			lot.qty -= take;
			lot.spent -= slice;
			if (lot.qty <= 0)
			{
				lots.pollFirst();
			}
			remaining -= take;
			matched += take;
		}
		if (matched <= 0)
		{
			return;
		}
		long unitSell = Math.round((double) sell.spent / sell.quantity);
		long sellGross = unitSell * matched;
		long tax = taxPerItem(unitSell, sell.itemId) * matched;
		Flip flip = new Flip(offerToken, openedAt, sell.time, sell.itemId, sell.itemName, matched, buySpent, sellGross, tax);
		flips.add(flip);
		if (flips.size() > MAX_FLIPS)
		{
			flips.remove(0);
		}
		sessionProfit += flip.profit;
		lifetimeProfit += flip.profit;
		/* Last, and swallowed. The ledger is a record OF the flip; it must
		   never be able to stop one being booked, and a sidebar that has
		   stopped counting your profit because a disk was full is a far worse
		   failure than a history page missing a row. */
		try
		{
			flipSink.accept(flip);
		}
		catch (RuntimeException ignore)
		{
			// the flip itself is booked; the ledger can miss it
		}
	}

	public synchronized List<Flip> getFlips()
	{
		return new ArrayList<>(flips);
	}

	public synchronized List<TradeFill> getFills()
	{
		return new ArrayList<>(fills);
	}

	public synchronized long getSessionProfit()
	{
		return sessionProfit;
	}

	public synchronized long getLifetimeProfit()
	{
		return lifetimeProfit;
	}

	public synchronized long getSessionStartMillis()
	{
		return sessionStartMillis;
	}

	/** Open buy lots totalled per item (qty still unsold, gp still spent on
	 *  it) — the basis for mark-to-market "unrealized profit". Copies out so
	 *  callers can't mutate tracker state. */
	public synchronized Map<Integer, long[]> getOpenBuyTotals()
	{
		Map<Integer, long[]> out = new HashMap<>();
		for (Map.Entry<Integer, Deque<BuyLot>> e : openBuys.entrySet())
		{
			long qty = 0, spent = 0;
			for (BuyLot lot : e.getValue())
			{
				qty += lot.qty;
				spent += lot.spent;
			}
			if (qty > 0)
			{
				out.put(e.getKey(), new long[]{qty, spent});
			}
		}
		return out;
	}

	/** Reset the SESSION counter only — lifetime and history survive. */
	public synchronized void resetSession()
	{
		sessionProfit = 0;
		sessionStartMillis = System.currentTimeMillis();
	}

	/** Full wipe: session, lifetime, history, open lots. */
	public synchronized void reset()
	{
		slotsByAccount.clear();
		slotsDirty = true;
		openBuys.clear();
		fills.clear();
		flips.clear();
		sessionProfit = 0;
		lifetimeProfit = 0;
		sessionStartMillis = System.currentTimeMillis();
	}
}
