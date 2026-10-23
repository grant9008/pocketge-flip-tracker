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
 * against earlier buys to produce {@link Flip}s with profit AFTER the 2% GE
 * tax — newest first among this character's buys from before the sell was
 * listed, leaving out units still in a buy's box: see {@link #charge}.
 *
 * Newest first, not oldest: oldest-first charged a sale of 13,418 Aether
 * catalyst bought at 277 against 24,500 left on record at 359 from an
 * earlier stack sold where the plugin did not see — -1.12M booked on a sale
 * that lost 26.8K: "its remembering an old buy price. it thinks i paid 359
 * each for these, i only paid 280 each".
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
		/** True for a baseline taken before the player last logged out and
		 *  not seen live since. While logged out the slot can be collected
		 *  and an identical offer placed — on mobile — so the next sighting
		 *  may be a different offer that only looks like this one grown.
		 *  See the over-bid branch of {@link #onOffer}. */
		boolean unwatched;
		/** True for a baseline written by a build that filed a character
		 *  with a negative account hash under some other character — every
		 *  build before 0.7.2. Such a row may hold another character's offer,
		 *  so it is never taken as proof that a NEW offer replaced it: the
		 *  first sighting against it measures or re-baselines, as those
		 *  builds did, and books no whole receipt. Persisted until seen. */
		boolean legacy;
		/** Identifies THIS offer, so the flips its fills book can be
		 *  recognised as one trade. Minted when the slot takes a new offer,
		 *  and kept for that offer's whole life. See {@link Flip#offerId}. */
		long offerToken;
		/** What this offer's sales have booked: units, and their profit.
		 *  Kept on the offer as it books rather than counted off the flips
		 *  list, which holds the newest 500 across every slot — a slow sell
		 *  lost its early sales from "made so far" as other slots traded. */
		long bookedQty;
		long bookedProfit;
		/** How much of the booked total has been shown as a profit figure
		 *  over the slot — see {@link #unseenBy}. Saved with the rest, so a
		 *  sale that finished with the Exchange shut still pops after a
		 *  restart, and starts at nothing with each new offer. */
		long shownQty;
		long shownProfit;
		/** What this offer sold that no buy on record could be charged for —
		 *  cooked, fished, looted, bought before the plugin — in units, and
		 *  the coins they brought in after tax. Never a flip and never
		 *  profit; only the gold figure over the square, which these and
		 *  the two after them are for — see {@link #unseenBy}. Saved after
		 *  {@link #boxedAtListing} in the row; a save without them starts
		 *  all four at 0, so an upgrade pops nothing old. */
		long looseQty;
		long looseCoins;
		long shownLooseQty;
		long shownLooseCoins;
		/** Restored from a save written before bookedQty/bookedProfit were
		 *  kept: seeded once from the flips list — see restore. */
		transient boolean seedBooked;
		/** The first lot sequence number handed out after this offer was
		 *  first seen. A lot at or past it was bought after the offer was
		 *  listed, so none of the offer's units can be in it — see
		 *  {@link #matchSell}. 0 until set: a row restored from a save that
		 *  did not record it gets one at the end of {@link #restore}. */
		long lotsBefore;
		/** A buy's collection box, live: units of the item waiting in it, or
		 *  -1 when the box has not been seen since this record was made or
		 *  since the last logout. Never saved. */
		transient long boxUnits = -1;
		/** A sell only: {lot seq, units} pairs that were waiting in a buy's
		 *  collection box when this offer was listed, so are not in it.
		 *  Saved; empty for an offer from a save without it. */
		long[] boxedAtListing = new long[0];
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
		/** Order of creation, in memory only, so a forget can drop exactly
		 *  the lots its prompt showed and none booked after. */
		final long seq;
		/** The character that bought it; 0 = not recorded (older saves),
		 *  which any character may sell. */
		final long account;
		/** offerToken of the buy offer that booked it; 0 = not recorded. */
		final long source;

		BuyLot(int qty, long spent, long time, long seq)
		{
			this(qty, spent, time, seq, 0L, 0L);
		}

		BuyLot(int qty, long spent, long time, long seq, long account, long source)
		{
			this.qty = qty;
			this.spent = spent;
			this.time = time;
			this.seq = seq;
			this.account = account;
			this.source = source;
		}
	}

	/** Whether the character now logged in may be charged for this lot. */
	private boolean mine(BuyLot lot)
	{
		return lot.account == 0 || accountHash == 0 || lot.account == accountHash;
	}

	/**
	 * Units of each lot still waiting in a buy offer's collection box, so in
	 * no sell offer. A box holds the fills since its last collect, which are
	 * that offer's newest lots. Its count is the slot's live box when seen
	 * this login; unseen, a finished buy seen in its slot since the last
	 * gap counts as wholly uncollected (collecting a finished buy empties
	 * the slot), and anything else as wholly collected.
	 */
	private long[] boxedNow(int itemId, int exceptSlot)
	{
		final Deque<BuyLot> lots = openBuys.get(itemId);
		if (lots == null)
		{
			return new long[0];
		}
		final List<Long> out = new ArrayList<>();
		for (Map.Entry<Integer, SlotState> e : slots().entrySet())
		{
			final SlotState b = e.getValue();
			if (!b.buy || b.itemId != itemId || e.getKey() == exceptSlot)
			{
				continue;
			}
			/* Unseen box: a finished buy is still in its box only if its slot
			   has been seen holding it since the last gap — a record off disk
			   or from before a logout may be a slot already collected on
			   mobile, whose EMPTY the login clear has not delivered yet. */
			final boolean confirmed = !b.restored && !b.unwatched;
			long n = b.boxUnits >= 0 ? b.boxUnits
				: confirmed && b.totalQuantity > 0 && b.qtySold >= b.totalQuantity ? Long.MAX_VALUE : 0;
			for (java.util.Iterator<BuyLot> it = lots.descendingIterator(); n > 0 && it.hasNext(); )
			{
				final BuyLot lot = it.next();
				if (lot.source != 0 && lot.source == b.offerToken)
				{
					final int take = (int) Math.min(n, lot.qty);
					out.add(lot.seq);
					out.add((long) take);
					n -= take;
				}
			}
		}
		final long[] a = new long[out.size()];
		for (int i = 0; i < a.length; i++)
		{
			a[i] = out.get(i);
		}
		return a;
	}

	/** The live box of {@code slot}'s buy of {@code itemId}: units in it, or
	 *  -1 for unknown. Ignored for any other offer. */
	public synchronized void noteBox(int slot, int itemId, long units)
	{
		final SlotState st = slots().get(slot);
		if (st != null && st.buy && st.itemId == itemId)
		{
			st.boxUnits = units;
		}
	}

	/**
	 * What the unsold rest of {@code slot}'s sell would be charged if it sold
	 * now — {units, gp} — by the same rule a sale books with. {0, 0} when the
	 * slot has a record and nothing on record covers it: the sale will book
	 * no cost, so there is no profit to project. Null only when there is no
	 * sell record for the slot at all.
	 */
	public synchronized long[] previewSale(int slot)
	{
		final SlotState st = slots().get(slot);
		if (st == null || st.buy || st.totalQuantity <= 0)
		{
			return null;
		}
		final long[] r = charge(st.itemId, Math.max(0, st.totalQuantity - st.qtySold), st, false);
		return new long[]{r[0], r[1]};
	}

	/**
	 * The one rule for what a sale of {@code qty} from {@code offer} is
	 * charged: this character's lots (or unattributed ones), bought before
	 * the offer was listed, not waiting in a buy's box — newest first.
	 * Returns {matched, buySpent, openedAt}; consumes them when {@code take}.
	 */
	private long[] charge(int itemId, int qty, SlotState offer, boolean take)
	{
		final Deque<BuyLot> lots = openBuys.get(itemId);
		if (lots == null || lots.isEmpty() || qty <= 0)
		{
			return new long[]{0, 0, 0};
		}
		final Map<Long, Long> boxed = new HashMap<>();
		for (int i = 0; i + 1 < offer.boxedAtListing.length; i += 2)
		{
			boxed.merge(offer.boxedAtListing[i], offer.boxedAtListing[i + 1], Long::sum);
		}
		int remaining = qty;
		long buySpent = 0, openedAt = Long.MAX_VALUE;
		int matched = 0;
		boolean untimed = false;
		for (java.util.Iterator<BuyLot> it = lots.descendingIterator(); remaining > 0 && it.hasNext(); )
		{
			final BuyLot lot = it.next();
			if (!mine(lot) || (offer.lotsBefore > 0 && lot.seq >= offer.lotsBefore))
			{
				continue;
			}
			final int free = (int) (lot.qty - Math.min(lot.qty, boxed.getOrDefault(lot.seq, 0L)));
			if (free <= 0)
			{
				continue;
			}
			final int n = Math.min(remaining, free);
			final long slice = Math.round((double) lot.spent * n / lot.qty);
			buySpent += slice;
			if (lot.time <= 0)
			{
				untimed = true;
			}
			else
			{
				openedAt = Math.min(openedAt, lot.time);
			}
			if (take)
			{
				lot.qty -= n;
				lot.spent -= slice;
				if (lot.qty <= 0)
				{
					it.remove();
				}
			}
			remaining -= n;
			matched += n;
		}
		if (take && lots.isEmpty())
		{
			openBuys.remove(itemId);
		}
		return new long[]{matched, buySpent, untimed || matched == 0 ? 0L : openedAt};
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

	/**
	 * The Grand Exchange's buy limit runs on a four-hour clock that starts at
	 * the first purchase: buy the whole limit and the item cannot be bought
	 * again until four hours after that first unit, however long ago the rest
	 * went through.
	 */
	static final long LIMIT_WINDOW_MS = 4L * 60 * 60 * 1000;
	/**
	 * Each character's open buy-limit windows: accountHash -> itemId ->
	 * {when the window started, units bought in it}. "i just bought 25k and
	 * flipped them, and its still reccomending me that i buy this flip, it
	 * should know im on cool down for 4h". Kept in the saved state, because a
	 * restart inside the four hours does not reset the game's clock either.
	 */
	private final Map<Long, Map<Integer, long[]>> limitWindows = new HashMap<>();
	/** Members get eight offer slots; free-to-play uses the first three. */
	private static final int GE_SLOTS = 8;
	/** An item id no offer can have, marking a slot as "on record, but not as
	 *  any actual offer" — see the upgrade path in {@link #restore}. */
	private static final int NO_OFFER = -1;
	private final Map<Integer, Deque<BuyLot>> openBuys = new HashMap<>();
	private long nextLotSeq = 1;
	/** See State#negativeHashMigration. */
	private boolean negativeHashMigration;
	private final java.util.Set<Long> negativeHashesHandled = new java.util.HashSet<>();
	/** What {@link State#slotsFormat} this build writes. */
	static final int SLOTS_FORMAT = 2;
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
	 * the matching below is the part that must stay unit-testable. It
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
		/** Next {@link BuyLot#seq}; absent (0) before lots kept theirs. */
		public long nextLotSeq;
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
		/** 2 from 0.7.2: a character with a negative account hash has its own
		 *  slot baselines. Absent (0) in every save before, which filed such a
		 *  character under whoever logged in before it. */
		public int slotsFormat;
		/** This install's history predates 0.7.2, so a character with a
		 *  negative hash may have offers the tracker already booked, filed
		 *  under someone else. The first time each such character is seen it
		 *  gets placeholders instead of an empty record, and its first
		 *  sightings only baseline — never re-book a receipt. Kept until
		 *  every such character has been seen once; see negativeHashesHandled. */
		public boolean negativeHashMigration;
		public List<Long> negativeHashesHandled;
		/** accountHash -> itemId -> {window start millis, units bought}; see
		 *  limitWindows. Absent in saves before 0.7.2's limit tracking. */
		public Map<Long, Map<Integer, long[]>> limitWindows;
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
		/* -1 is the client's "not logged in" and 0 is this class's own
		   "unknown". Only those: a hash is a 64-bit value, and treating a
		   negative one as no account left that character measured against
		   whoever logged in before it. */
		if (hash == -1 || hash == 0 || hash == accountHash)
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
		/* A negative-hash character on an install older than 0.7.2: its
		   offers were tracked under another character, and some of what they
		   show is already booked. With no record of its own, its first
		   sighting would book every receipt again — so it starts from
		   placeholders, as a pre-baseline save does, once.

		   Not repaired: the rows those builds left under the OTHER character
		   (or under 0, shared by two negative-hash characters) may hold the
		   wrong character's offer, and a delta measured against one can book
		   twice — exactly as continuing on 0.7.1 would. Undoing that would
		   mean dropping every character's offline fills once on upgrade, for
		   everyone; it is left as it was, and Forget is the repair. */
		if (hash < 0 && negativeHashMigration && negativeHashesHandled.add(hash))
		{
			final Map<Integer, SlotState> own = slotsByAccount.computeIfAbsent(hash, k -> new HashMap<>());
			for (int slot = 0; slot < GE_SLOTS; slot++)
			{
				if (!own.containsKey(slot))
				{
					final SlotState st = new SlotState();
					st.itemId = NO_OFFER;
					st.offerToken = nextOfferToken++;
					own.put(slot, st);
				}
			}
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
		s.nextLotSeq = nextLotSeq;
		s.accountHash = accountHash;
		s.nextOfferToken = nextOfferToken;
		s.slotsFormat = SLOTS_FORMAT;
		s.negativeHashMigration = negativeHashMigration;
		s.negativeHashesHandled = new ArrayList<>(negativeHashesHandled);
		s.slotsByAccount = new HashMap<>();
		for (Map.Entry<Long, Map<Integer, SlotState>> acc : slotsByAccount.entrySet())
		{
			final Map<Integer, long[]> rows = new HashMap<>();
			for (Map.Entry<Integer, SlotState> e : acc.getValue().entrySet())
			{
				final SlotState st = e.getValue();
				rows.put(e.getKey(), new long[]{
					st.itemId, st.buy ? 1 : 0, st.qtySold, st.spent, st.price, st.totalQuantity,
					st.offerToken, st.legacy ? 1 : 0, st.bookedQty, st.bookedProfit, st.shownQty, st.shownProfit,
					st.lotsBefore, st.boxedAtListing.length / 2});
				/* Then the pairs, then the loose sale — after the pairs so a
				   build that reads only as far as the pairs reads what it
				   always did. */
				final long[] row = rows.get(e.getKey());
				final long[] longer = java.util.Arrays.copyOf(row, row.length + st.boxedAtListing.length + 4);
				System.arraycopy(st.boxedAtListing, 0, longer, row.length, st.boxedAtListing.length);
				final int at = row.length + st.boxedAtListing.length;
				longer[at] = st.looseQty;
				longer[at + 1] = st.looseCoins;
				longer[at + 2] = st.shownLooseQty;
				longer[at + 3] = st.shownLooseCoins;
				rows.put(e.getKey(), longer);
			}
			if (!rows.isEmpty())
			{
				s.slotsByAccount.put(acc.getKey(), rows);
			}
		}
		s.limitWindows = new HashMap<>();
		for (Map.Entry<Long, Map<Integer, long[]>> acc : limitWindows.entrySet())
		{
			final Map<Integer, long[]> rows = new HashMap<>();
			for (Map.Entry<Integer, long[]> e : acc.getValue().entrySet())
			{
				rows.put(e.getKey(), e.getValue().clone());
			}
			if (!rows.isEmpty())
			{
				s.limitWindows.put(acc.getKey(), rows);
			}
		}
		s.flips = new ArrayList<>(flips);
		s.openBuys = new HashMap<>();
		for (Map.Entry<Integer, Deque<BuyLot>> e : openBuys.entrySet())
		{
			List<long[]> lots = new ArrayList<>();
			for (BuyLot lot : e.getValue())
			{
				lots.add(new long[]{lot.qty, lot.spent, lot.time, lot.seq, lot.account, lot.source});
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
		final boolean oldFormat = s.slotsFormat < SLOTS_FORMAT;
		negativeHashesHandled.clear();
		if (oldFormat)
		{
			/* Any history at all means characters may have been filed under
			   each other; an empty save means a fresh install, and nothing to
			   migrate. */
			negativeHashMigration = s.lifetimeProfit != 0 || s.accountHash != 0
				|| (s.slotsByAccount != null && !s.slotsByAccount.isEmpty())
				|| (s.slots != null && !s.slots.isEmpty())
				|| (s.flips != null && !s.flips.isEmpty())
				|| (s.openBuys != null && !s.openBuys.isEmpty());
		}
		else
		{
			negativeHashMigration = s.negativeHashMigration;
			if (s.negativeHashesHandled != null)
			{
				for (Long h : s.negativeHashesHandled)
				{
					if (h != null)
					{
						negativeHashesHandled.add(h);
					}
				}
			}
		}
		slotsByAccount.clear();
		if (s.slotsByAccount != null)
		{
			for (Map.Entry<Long, Map<Integer, long[]>> acc : s.slotsByAccount.entrySet())
			{
				if (acc.getKey() == null || acc.getValue() == null)
				{
					continue;
				}
				slotsByAccount.put(acc.getKey(), readSlots(acc.getValue(), oldFormat));
			}
		}
		else if (s.slots != null)
		{
			/* A save from the one version that kept a single flat map. Those
			   baselines are the account that file recorded, so file them under
			   it rather than dropping them. */
			slotsByAccount.put(s.accountHash, readSlots(s.slots, true));
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
		limitWindows.clear();
		if (s.limitWindows != null)
		{
			for (Map.Entry<Long, Map<Integer, long[]>> acc : s.limitWindows.entrySet())
			{
				if (acc.getKey() == null || acc.getValue() == null)
				{
					continue;
				}
				final Map<Integer, long[]> rows = new HashMap<>();
				for (Map.Entry<Integer, long[]> e : acc.getValue().entrySet())
				{
					final long[] w = e.getValue();
					if (e.getKey() != null && w != null && w.length >= 2 && w[1] > 0)
					{
						rows.put(e.getKey(), new long[]{w[0], w[1]});
					}
				}
				if (!rows.isEmpty())
				{
					limitWindows.put(acc.getKey(), rows);
				}
			}
		}
		flips.clear();
		if (s.flips != null)
		{
			flips.addAll(s.flips);
		}
		/* A sell already part-sold when this build first runs: its total was
		   counted off the flips list until now, and those flips are still
		   here under its token. Seeded once; the next save carries it. */
		for (Map<Integer, SlotState> account : slotsByAccount.values())
		{
			for (SlotState st : account.values())
			{
				if (!st.seedBooked)
				{
					continue;
				}
				st.seedBooked = false;
				for (Flip f : flips)
				{
					if (f.offerId == st.offerToken && f.itemId == st.itemId)
					{
						st.bookedQty += f.quantity;
						st.bookedProfit += f.profit;
					}
				}
				/* Booked under a build with no profit figures: shown as far
				   as anyone is concerned, so an upgrade pops nothing old. */
				st.shownQty = st.bookedQty;
				st.shownProfit = st.bookedProfit;
			}
		}
		openBuys.clear();
		nextLotSeq = Math.max(nextLotSeq, Math.max(1L, s.nextLotSeq));
		if (s.openBuys != null)
		{
			/* Sequence numbers are written from this build on; a save from
			   before has none, and its lots are numbered here, after every
			   number the save could have handed out, in the order they sit. */
			final boolean numbered = s.nextLotSeq > 0;
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
					final long seq = numbered && l.length > 3 && l[3] > 0 && l[3] < s.nextLotSeq ? l[3] : nextLotSeq++;
					lots.add(new BuyLot((int) l[0], l[1], l.length > 2 ? l[2] : 0L, seq,
						l.length > 4 ? l[4] : 0L, l.length > 5 ? l[5] : 0L));
				}
				/* Newest first means highest seq first: keep the deque in seq
				   order whatever order the file had them in. */
				final List<BuyLot> sorted = new ArrayList<>(lots);
				sorted.sort((x, y) -> Long.compare(x.seq, y.seq));
				openBuys.put(e.getKey(), new ArrayDeque<>(sorted));
			}
		}
		/* A slot row from a save that did not record its bound: its offer was
		   listed before this restore, so every lot on record may be in it and
		   none bought from here on can. */
		for (Map<Integer, SlotState> account : slotsByAccount.values())
		{
			for (SlotState st : account.values())
			{
				if (st.lotsBefore <= 0 || st.lotsBefore > nextLotSeq)
				{
					st.lotsBefore = nextLotSeq;
				}
			}
		}
		if (s.limitWindows == null)
		{
			seedLimitWindows(System.currentTimeMillis());
		}
	}

	/**
	 * A save from before buy limits were tracked: rebuild the windows from
	 * what it does know — the flips it closed and the lots it still holds,
	 * each with the time it was bought — so a limit bought out just before
	 * the update is not offered again straight after it. Filed under the
	 * character the save was written for, the one most likely playing.
	 */
	private void seedLimitWindows(long now)
	{
		final List<long[]> buys = new ArrayList<>(); // {time, itemId, qty}
		for (Flip f : flips)
		{
			if (f.openedAt > 0 && f.openedAt <= now && now - f.openedAt < LIMIT_WINDOW_MS)
			{
				buys.add(new long[]{f.openedAt, f.itemId, f.quantity});
			}
		}
		for (Map.Entry<Integer, Deque<BuyLot>> e : openBuys.entrySet())
		{
			for (BuyLot lot : e.getValue())
			{
				if (lot.time > 0 && lot.time <= now && now - lot.time < LIMIT_WINDOW_MS)
				{
					buys.add(new long[]{lot.time, e.getKey(), lot.qty});
				}
			}
		}
		buys.sort((x, y) -> Long.compare(x[0], y[0]));
		for (long[] b : buys)
		{
			noteBuyForLimit((int) b[1], (int) b[2], b[0]);
		}
	}

	/** One character's saved slot rows, back into baselines. Every one is
	 *  marked restored: it came off disk, so growth measured against it
	 *  happened at a moment nobody recorded. */
	private Map<Integer, SlotState> readSlots(Map<Integer, long[]> rows, boolean oldFormat)
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
			st.lotsBefore = l.length > 12 ? l[12] : 0L;
			final int pairs = l.length > 13 ? (int) Math.max(0, Math.min(l[13], (l.length - 14) / 2)) : 0;
			st.boxedAtListing = pairs > 0 ? java.util.Arrays.copyOfRange(l, 14, 14 + 2 * pairs) : new long[0];
			final int loose = 14 + 2 * pairs;
			if (l.length >= loose + 4)
			{
				st.looseQty = l[loose];
				st.looseCoins = l[loose + 1];
				st.shownLooseQty = l[loose + 2];
				st.shownLooseCoins = l[loose + 3];
			}
			st.legacy = oldFormat || (l.length > 7 && l[7] != 0);
			/* Added after the rest: a save without them has booked nothing
			   that this can vouch for, and starts at 0. Only with the token
			   they were booked under. */
			if (l.length > 9 && l[6] != 0)
			{
				st.bookedQty = l[8];
				st.bookedProfit = l[9];
				/* And how much of it has popped. A save from before the
				   figures counts all of it as shown: what it booked was
				   booked before there was anything to see. */
				st.shownQty = l.length > 11 ? l[10] : st.bookedQty;
				st.shownProfit = l.length > 11 ? l[11] : st.bookedProfit;
			}
			else
			{
				st.seedBooked = l.length > 6 && l[6] != 0;
			}
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
			st.lotsBefore = nextLotSeq;
			if (!buy)
			{
				st.boxedAtListing = boxedNow(itemId, slot);
			}
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
			 *
			 * The buy limit is another matter. A buy that replaced the slot's
			 * offer while nobody was watching — collected and re-placed on
			 * mobile, or with the client shut — has really bought what it
			 * shows, and the game counts it. Counted here, from now as any
			 * offline fill is, with no lot booked: for the limit an over-count
			 * costs a wait and an under-count is "still reccomending me that i
			 * buy this flip". Not for a legacy row or an upgrade placeholder,
			 * whose offers may already be in the lots the limit was seeded
			 * from, nor for a mismatch seen live, which is a misread.
			 */
			final boolean unseenGap = (st.restored || st.unwatched) && !st.legacy && st.itemId != NO_OFFER;
			st = new SlotState();
			st.itemId = itemId;
			st.buy = buy;
			st.qtySold = qtySold;
			st.spent = spent;
			st.price = price;
			st.totalQuantity = totalQuantity;
			st.offerToken = nextOfferToken++;
			st.lotsBefore = nextLotSeq;
			if (!buy)
			{
				st.boxedAtListing = boxedNow(itemId, slot);
			}
			slots.put(slot, st);
			slotsDirty = true;
			if (buy && qtySold > 0 && unseenGap)
			{
				noteBuyForLimit(itemId, qtySold, now);
			}
			return null;
		}
		int dQty = qtySold - st.qtySold;
		long dSpent = spent - st.spent;
		/* This baseline came off disk, so whatever grew against it grew while
		   the client was shut. The gold is real and the cost is exact; only
		   the moment is unknowable, and a lot that claims a fill time it does
		   not have would turn into a hold duration that was never measured. */
		boolean offline = st.restored;
		/* The slot could have changed hands since this baseline: it came off
		   disk, or from before a logout. Not when the baseline itself may be
		   another character's — see SlotState#legacy. */
		final boolean unseen = (st.restored || st.unwatched) && !st.legacy;
		st.qtySold = qtySold;
		st.spent = spent;
		st.restored = false;
		st.unwatched = false;
		st.legacy = false;
		/* Learn the terms if the save predates them, so the NEXT restart can
		   tell this offer apart from its replacement. */
		st.price = price;
		st.totalQuantity = totalQuantity;
		slotsDirty = true;
		/* One buy offer only ever spends more, and only by buying more, at no
		   more than its bid. A snapshot that breaks any of that is not the
		   baselined offer: less spent, or spent changed with nothing bought,
		   or charged over the bid. Across a gap the plugin could not see,
		   that is an identical offer collected and placed again — the routine
		   way to buy another limit's worth — and below it is booked from
		   zero. Watched live it can only be a misread, and books nothing. */
		final boolean notThisOffer = buy && price > 0
			&& (dSpent < 0 || (dQty == 0 && dSpent != 0) || (dQty > 0 && dSpent > (long) dQty * price));
		if (!notThisOffer && (dQty <= 0 || dSpent < 0))
		{
			return null;
		}
		/* A buy is never charged more than its bid. A delta that says it was
		   was measured against some other offer's baseline — a save from
		   before terms were kept matches anything — and booking it would put
		   a lot on the books above anything the player paid. The baseline has
		   already moved to this event, so the next delta is clean. Buys only:
		   whether a sell's spent is net of tax is not something to bet a
		   real sale on. */
		if (notThisOffer)
		{
			/* ...and that proves the baseline was another offer's: one offer
			   cannot charge over its own bid (nor spend less, nor spend
			   without buying). So this snapshot is a new offer
			   in the slot, and everything it shows is its own receipt. It used
			   to be dropped, which lost the replacement's whole buy — an
			   identical offer collected and re-placed while the plugin was not
			   watching (on mobile, or with the plugin off) went unrecorded.
			   Booked from zero instead, with no fill time since the moment is
			   unknown, and only if the receipt is itself within its bid.

			   Only across a gap the plugin could not see: a baseline off disk,
			   or from before a logout. Watched live, a slot cannot change
			   offers without an EMPTY in between, so there an over-bid delta
			   is a misread — and booking a whole receipt on a misread is how
			   the same purchase gets counted twice. */
			if (!unseen || qtySold <= 0 || spent > (long) qtySold * price)
			{
				return null;
			}
			st.offerToken = nextOfferToken++;
			st.bookedQty = 0;
			st.bookedProfit = 0;
			st.shownQty = 0;
			st.shownProfit = 0;
			st.looseQty = 0;
			st.looseCoins = 0;
			st.shownLooseQty = 0;
			st.shownLooseCoins = 0;
			dQty = qtySold;
			dSpent = spent;
			offline = true;
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
				.addLast(new BuyLot(dQty, dSpent, offline ? 0L : now, nextLotSeq++, accountHash, st.offerToken));
			/* An offline fill's moment is unknown, so it is counted from now:
			   the window then ends a little later than the game's does, which
			   costs a wait, never a buy over the limit. */
			noteBuyForLimit(itemId, dQty, now);
		}
		else
		{
			matchSell(fill, st);
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

	/**
	 * Match a sell fill against the open buy lots of its item, newest first,
	 * among the lots bought before its offer was listed, and book what it
	 * made against the offer it came from.
	 */
	private void matchSell(TradeFill sell, SlotState offer)
	{
		final long offerToken = offer.offerToken;
		final long[] c = charge(sell.itemId, sell.quantity, offer, true);
		final int matched = (int) c[0];
		final long buySpent = c[1];
		final long openedAt = c[2];
		long unitSell = Math.round((double) sell.spent / sell.quantity);
		/* Units nothing on record paid for — "oo you right i just cooked
		   these": what they brought in, after tax, at the same unit price
		   the flips are booked at. Not profit; the square pops it in gold. */
		final int loose = sell.quantity - Math.max(0, matched);
		if (loose > 0)
		{
			offer.looseQty += loose;
			offer.looseCoins += (unitSell - taxPerItem(unitSell, sell.itemId)) * loose;
		}
		if (matched <= 0)
		{
			return;
		}
		long sellGross = unitSell * matched;
		long tax = taxPerItem(unitSell, sell.itemId) * matched;
		Flip flip = new Flip(offerToken, openedAt, sell.time, sell.itemId, sell.itemName, matched, buySpent, sellGross, tax);
		offer.bookedQty += matched;
		offer.bookedProfit += flip.profit;
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

	/**
	 * A booked trade corrected by the player: the recent flips {@code fix}
	 * changes are replaced, and lifetime and session profit move by what the
	 * correction changed — worked out by the caller over the whole ledger,
	 * which reaches further back than the flips kept here.
	 */
	public synchronized void applyCorrection(java.util.function.UnaryOperator<Flip> fix, long lifetimeDelta,
		long sessionDelta, long offerToken, int itemId)
	{
		flips.replaceAll(fix);
		lifetimeProfit += lifetimeDelta;
		sessionProfit += sessionDelta;
		/* The offer may still be in its slot, selling: its "Made X so far"
		   moves by the same amount. The shown mark by the share of it on
		   units already shown, so the correction itself never pops as a
		   sale and a part not yet shown pops at its corrected figure. */
		for (Map<Integer, SlotState> account : slotsByAccount.values())
		{
			for (SlotState st : account.values())
			{
				if (!st.buy && st.offerToken == offerToken && st.itemId == itemId && st.bookedQty > 0)
				{
					st.bookedProfit += lifetimeDelta;
					st.shownProfit += Math.round(lifetimeDelta * (Math.min(st.shownQty, st.bookedQty) / (double) st.bookedQty));
					slotsDirty = true;
				}
			}
		}
	}

	public synchronized List<Flip> getFlips()
	{
		return new ArrayList<>(flips);
	}

	/**
	 * What the offer now in {@code slot} has booked so far: {units, profit}
	 * over the flips its own sales closed, or null when it has booked none.
	 *
	 * The slot's hover priced only the part still listed — "+85.7K" on an
	 * offer that had already sold 6,162 for +295K: "it should atleast say
	 * 295k of x amount total 85k left". Units as well as profit, because a
	 * sale the plugin could not book (sold while it was not watching, or
	 * with no buy on record) is in the offer's own sold count and not here,
	 * and the hover has to say how much its figure covers.
	 */
	public synchronized long[] bookedBy(int slot)
	{
		final SlotState st = slots().get(slot);
		return st != null && st.bookedQty > 0 ? new long[]{st.bookedQty, st.bookedProfit} : null;
	}

	/**
	 * What the sell now in {@code slot} has booked that has not yet been
	 * shown as a profit figure over its square: {units, profit}, or null.
	 *
	 * "even if i missed it live, the next time i load into this ge screen,
	 * it should have said +64kgp over rune darts in that square". Kept here,
	 * on the offer and saved with it, rather than queued in the overlay:
	 * a queue in memory lost the figure to a restart, carried it across to
	 * a new offer of the same item relisted on mobile, and had to be told
	 * whose it was. The slot's record already knows all three.
	 *
	 * Only for a sell of {@code itemId}, the offer the Exchange is showing in
	 * the slot; anything else answers null.
	 *
	 * {units, gp, kind}: kind {@link #UNSEEN_PROFIT} for booked profit, or
	 * {@link #UNSEEN_COINS} for coins from units no buy on record paid for —
	 * "yes add the gold coins pop", for the sharks that were cooked, not
	 * bought. One figure a square: when both are waiting, the profit is the
	 * one shown and {@link #markShown} counts the coins as shown with it.
	 */
	public synchronized long[] unseenBy(int slot, int itemId)
	{
		final SlotState st = slots().get(slot);
		if (st == null || st.buy || st.itemId != itemId)
		{
			return null;
		}
		if (st.bookedQty > st.shownQty)
		{
			return new long[]{st.bookedQty - st.shownQty, st.bookedProfit - st.shownProfit, UNSEEN_PROFIT};
		}
		if (st.looseQty > st.shownLooseQty)
		{
			return new long[]{st.looseQty - st.shownLooseQty, st.looseCoins - st.shownLooseCoins, UNSEEN_COINS};
		}
		return null;
	}

	/** Kinds of figure {@link #unseenBy} answers with. */
	public static final long UNSEEN_PROFIT = 0;
	public static final long UNSEEN_COINS = 1;

	/** Every character's slots: all they have booked counts as shown. For
	 *  the figures being switched off — nothing waits to pop later. */
	public synchronized void markAllShown()
	{
		for (Map<Integer, SlotState> account : slotsByAccount.values())
		{
			for (SlotState st : account.values())
			{
				slotsDirty |= allShown(st);
			}
		}
	}

	/** Everything {@code slot}'s offer has sold has now been shown. */
	public synchronized void markShown(int slot)
	{
		final SlotState st = slots().get(slot);
		if (st != null)
		{
			slotsDirty |= allShown(st);
		}
	}

	/** Whether {@code slot}'s offer has sold anything not yet shown. */
	public synchronized boolean waiting(int slot)
	{
		final SlotState st = slots().get(slot);
		return st != null && (st.bookedQty != st.shownQty || st.looseQty != st.shownLooseQty);
	}

	/** {@code st}'s profit and coins all count as shown; true if that moved
	 *  either mark. */
	private static boolean allShown(SlotState st)
	{
		if (st.shownQty == st.bookedQty && st.shownProfit == st.bookedProfit
			&& st.shownLooseQty == st.looseQty && st.shownLooseCoins == st.looseCoins)
		{
			return false;
		}
		st.shownQty = st.bookedQty;
		st.shownProfit = st.bookedProfit;
		st.shownLooseQty = st.looseQty;
		st.shownLooseCoins = st.looseCoins;
		return true;
	}

	/**
	 * Count {@code qty} bought at {@code now} against this character's buy
	 * limit for the item: into the open window, or as the start of a new one
	 * when the last has run its four hours. Windows that have ended are
	 * dropped as it goes, so the map holds no more than the last four hours.
	 */
	synchronized void noteBuyForLimit(int itemId, int qty, long now)
	{
		if (qty <= 0)
		{
			return;
		}
		final Map<Integer, long[]> mine = limitWindows.computeIfAbsent(accountHash, k -> new HashMap<>());
		mine.values().removeIf(w -> now >= w[0] + LIMIT_WINDOW_MS);
		final long[] w = mine.get(itemId);
		if (w == null)
		{
			mine.put(itemId, new long[]{now, qty});
		}
		else
		{
			w[1] += qty;
		}
		if (limitWindows.size() > MAX_ACCOUNTS)
		{
			limitWindows.keySet().removeIf(k -> k != accountHash && limitWindows.size() > MAX_ACCOUNTS);
		}
	}

	/** Units of the item this character has bought in its open buy-limit
	 *  window, or 0 when there is none (never bought, or four hours up). */
	public synchronized int boughtTowardLimit(int itemId, long now)
	{
		final long[] w = openWindow(itemId, now);
		return w == null ? 0 : (int) Math.min(Integer.MAX_VALUE, w[1]);
	}

	/** When this character's buy limit on the item is whole again, or 0
	 *  when it already is. */
	public synchronized long limitResetsAt(int itemId, long now)
	{
		final long[] w = openWindow(itemId, now);
		return w == null ? 0 : w[0] + LIMIT_WINDOW_MS;
	}

	/** How long until a time {@code ms} away, for a person: "2h 14m",
	 *  "38m", "under a minute". */
	static String untilText(long ms)
	{
		final long mins = Math.max(0, ms) / 60_000;
		if (mins < 1)
		{
			return "under a minute";
		}
		return mins >= 60 ? (mins / 60) + "h " + (mins % 60) + "m" : mins + "m";
	}

	/** A card's note when the 4h limit is used up. Short, and with no
	 *  " · ", because a buy card shows its note up to that dot on one line
	 *  of a 182px card — and when it comes back is the part that matters. */
	static String limitUsedNote(long msLeft)
	{
		return "4h limit used, back in " + untilText(msLeft);
	}

	/** A card's note when some of the 4h limit is left: how much on the
	 *  card, when the rest comes back on the hover. */
	static String limitLeftNote(long left, long msLeft)
	{
		return net.runelite.client.util.QuantityFormatter.quantityToStackSize(left) + " left of 4h limit"
			+ " \u00b7 the whole limit back in " + untilText(msLeft);
	}

	private long[] openWindow(int itemId, long now)
	{
		final Map<Integer, long[]> mine = limitWindows.get(accountHash);
		final long[] w = mine != null ? mine.get(itemId) : null;
		return w != null && now < w[0] + LIMIT_WINDOW_MS ? w : null;
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

	/**
	 * Open lots priced against what is actually held: per item, {qty, spent}
	 * of the NEWEST lots covering at most {@code held} units, then {qty,
	 * spent} of the older ones left over — {qty, spent, olderQty, olderSpent}.
	 *
	 * Sales are charged to the newest lots too (see {@link #charge}), so this
	 * and the booking agree. When lots add up to
	 * more than is held, the extra is older stock that left without the
	 * plugin seeing it go — sold on another client, with the plugin off, or
	 * held on another character. Averaging it in priced a stack bought at
	 * 277 at PAID @ 330: 13,418 at 277 blended with 24,500 left over from an
	 * earlier stack at 359. What you hold is what you bought last.
	 *
	 * An item with nothing held (or not in the map) gets its plain totals.
	 */
	public synchronized Map<Integer, long[]> getHeldCost(Map<Integer, Long> held)
	{
		final Map<Integer, long[]> out = new HashMap<>();
		for (Map.Entry<Integer, Deque<BuyLot>> e : openBuys.entrySet())
		{
			/* The same lots a sale listed now could be charged: this
			   character's, not waiting in a buy's box. Box units are in
			   {@code held} when the box was seen; they are priced by their
			   own lots, not the stack's. */
			final Map<Long, Long> boxed = new HashMap<>();
			final long[] pairs = boxedNow(e.getKey(), -1);
			for (int i = 0; i + 1 < pairs.length; i += 2)
			{
				boxed.merge(pairs[i], pairs[i + 1], Long::sum);
			}
			long seenInBoxes = 0;
			for (SlotState b : slots().values())
			{
				if (b.buy && b.itemId == e.getKey() && b.boxUnits > 0)
				{
					seenInBoxes += b.boxUnits;
				}
			}
			long qty = 0, spent = 0;
			final List<long[]> free = new ArrayList<>(); // newest first: {qty, spent}
			for (java.util.Iterator<BuyLot> it = e.getValue().descendingIterator(); it.hasNext(); )
			{
				final BuyLot lot = it.next();
				if (!mine(lot))
				{
					continue;
				}
				final long n = lot.qty - Math.min(lot.qty, boxed.getOrDefault(lot.seq, 0L));
				if (n <= 0)
				{
					continue;
				}
				final long sp = n == lot.qty ? lot.spent : Math.round(lot.spent * (n / (double) lot.qty));
				free.add(new long[]{n, sp});
				qty += n;
				spent += sp;
			}
			if (qty <= 0)
			{
				continue;
			}
			final long h = held != null ? Math.max(0, held.getOrDefault(e.getKey(), 0L) - seenInBoxes) : 0L;
			if (h <= 0 || qty <= h)
			{
				out.put(e.getKey(), new long[]{qty, spent, 0, 0});
				continue;
			}
			long keepQty = 0, keepSpent = 0;
			for (long[] f : free)
			{
				if (keepQty >= h)
				{
					break;
				}
				final long take = Math.min(f[0], h - keepQty);
				keepSpent += take == f[0] ? f[1] : Math.round(f[1] * (take / (double) f[0]));
				keepQty += take;
			}
			out.put(e.getKey(), new long[]{keepQty, keepSpent, qty - keepQty, spent - keepSpent});
		}
		return out;
	}

	/**
	 * The batches behind the stack you hold, for the hover: per item, {qty,
	 * spent} pairs, oldest first, flattened — the same lots {@link
	 * #getHeldCost} prices the stack from, one batch per buy offer (its fills
	 * added together). "it cant remember 2 costs paid?" It does: 10K @ 349,
	 * 10K @ 333, 10K @ 329, and a sale uses the newest first. Lots from saves
	 * before buys recorded their offer are batched by price.
	 */
	public synchronized Map<Integer, long[]> heldBatches(Map<Integer, Long> held)
	{
		final Map<Integer, long[]> out = new HashMap<>();
		for (Map.Entry<Integer, Deque<BuyLot>> e : openBuys.entrySet())
		{
			final Map<Long, Long> boxed = new HashMap<>();
			final long[] pairs = boxedNow(e.getKey(), -1);
			for (int i = 0; i + 1 < pairs.length; i += 2)
			{
				boxed.merge(pairs[i], pairs[i + 1], Long::sum);
			}
			long seenInBoxes = 0;
			for (SlotState b : slots().values())
			{
				if (b.buy && b.itemId == e.getKey() && b.boxUnits > 0)
				{
					seenInBoxes += b.boxUnits;
				}
			}
			long total = 0;
			for (BuyLot lot : e.getValue())
			{
				if (mine(lot))
				{
					total += lot.qty - Math.min(lot.qty, boxed.getOrDefault(lot.seq, 0L));
				}
			}
			final long h = held != null ? Math.max(0, held.getOrDefault(e.getKey(), 0L) - seenInBoxes) : 0L;
			final long want = h <= 0 || total <= h ? total : h;
			/* Newest first, as getHeldCost walks them, up to what is held;
			   each piece {qty, spent, batch} where batch is the buy offer, or
			   for an unrecorded one its rounded price. */
			final List<long[]> pieces = new ArrayList<>();
			long taken = 0;
			for (java.util.Iterator<BuyLot> it = e.getValue().descendingIterator(); it.hasNext() && taken < want; )
			{
				final BuyLot lot = it.next();
				if (!mine(lot))
				{
					continue;
				}
				final long n = lot.qty - Math.min(lot.qty, boxed.getOrDefault(lot.seq, 0L));
				if (n <= 0)
				{
					continue;
				}
				final long take = Math.min(n, want - taken);
				final long sp = take == lot.qty ? lot.spent : Math.round(lot.spent * (take / (double) lot.qty));
				final long batch = lot.source != 0 ? lot.source : -Math.round(lot.spent / (double) lot.qty) - 1;
				final long[] last = pieces.isEmpty() ? null : pieces.get(pieces.size() - 1);
				if (last != null && last[2] == batch)
				{
					last[0] += take;
					last[1] += sp;
				}
				else
				{
					pieces.add(new long[]{take, sp, batch});
				}
				taken += take;
			}
			if (pieces.isEmpty())
			{
				continue;
			}
			final long[] flat = new long[pieces.size() * 2];
			for (int i = 0; i < pieces.size(); i++)
			{
				final long[] pc = pieces.get(pieces.size() - 1 - i); // oldest first
				flat[2 * i] = pc[0];
				flat[2 * i + 1] = pc[1];
			}
			out.put(e.getKey(), flat);
		}
		return out;
	}

	/**
	 * Drop the oldest lots of one item until at most {@code keep} units are
	 * left — the player confirming that the older ones {@link #getHeldCost}
	 * set aside are gone. Sales are charged to the newest lots, so these are
	 * reached only by a sale bigger than everything bought since; this takes
	 * them off the record and out of the older-buys note. Returns what was
	 * dropped, {qty, spent}, or null when nothing was over.
	 */
	public synchronized long[] forgetOlderThan(int itemId, long keep)
	{
		final Deque<BuyLot> lots = openBuys.get(itemId);
		if (lots == null)
		{
			return null;
		}
		long total = 0;
		for (BuyLot lot : lots)
		{
			total += mine(lot) ? lot.qty : 0;
		}
		long excess = total - Math.max(0, keep);
		if (excess <= 0)
		{
			return null;
		}
		long qty = 0, spent = 0;
		for (java.util.Iterator<BuyLot> it = lots.iterator(); excess > 0 && it.hasNext(); )
		{
			final BuyLot lot = it.next();
			if (!mine(lot))
			{
				continue;
			}
			if (lot.qty <= excess)
			{
				it.remove();
				qty += lot.qty;
				spent += lot.spent;
				excess -= lot.qty;
			}
			else
			{
				final long cut = Math.round(lot.spent * (excess / (double) lot.qty));
				lot.qty -= (int) excess;
				lot.spent -= cut;
				qty += excess;
				spent += cut;
				excess = 0;
			}
		}
		if (lots.isEmpty())
		{
			openBuys.remove(itemId);
		}
		return new long[]{qty, spent};
	}

	/** One item's open lots totalled, {qty, spent}, or null when none. */
	public synchronized long[] getOpenBuyTotal(int itemId)
	{
		final Deque<BuyLot> lots = openBuys.get(itemId);
		long qty = 0, spent = 0;
		for (BuyLot lot : lots != null ? lots : new ArrayDeque<BuyLot>())
		{
			if (!mine(lot))
			{
				continue;
			}
			qty += lot.qty;
			spent += lot.spent;
		}
		return qty > 0 ? new long[]{qty, spent} : null;
	}

	/** One item's open lots for a forget prompt: {qty, spent, newest lot},
	 *  or null when none. The third is what {@link #forgetOpenBuys} takes. */
	public synchronized long[] getOpenBuyRecord(int itemId)
	{
		final Deque<BuyLot> lots = openBuys.get(itemId);
		long qty = 0, spent = 0, newest = 0;
		for (BuyLot lot : lots != null ? lots : new ArrayDeque<BuyLot>())
		{
			if (!mine(lot))
			{
				continue;
			}
			qty += lot.qty;
			spent += lot.spent;
			newest = Math.max(newest, lot.seq);
		}
		return qty > 0 ? new long[]{qty, spent, newest} : null;
	}

	/** The player has logged out. Every baseline is now from before a gap
	 *  in which the slots could have changed hands — see SlotState#unwatched. */
	public synchronized void markSlotsUnwatched()
	{
		for (Map<Integer, SlotState> account : slotsByAccount.values())
		{
			for (SlotState st : account.values())
			{
				st.unwatched = true;
				st.boxUnits = -1;
			}
		}
	}

	/**
	 * Drop one item's open buy lots, up to and including lot {@code upTo}
	 * from {@link #getOpenBuyRecord}: the player telling the tracker that
	 * what it says they paid is wrong. Lots booked after the prompt was
	 * read — a buy filling while it was open — are kept. Returns what was
	 * dropped, {qty, spent}, or null when there was nothing.
	 *
	 * For lots the tracker cannot be trusted on — builds before 0.7.2
	 * booked a live offer's whole receipt again after a world hop, so an
	 * item could hold the same purchase twice. Lots cannot be rebuilt from
	 * the fills (those are kept only for the session), so forgetting is the
	 * repair: the item shows as cost-unknown until it is bought again.
	 * Live offers keep their baselines, so fills after this book normally.
	 * Flip history and lifetime profit are left alone.
	 */
	public synchronized long[] forgetOpenBuys(int itemId, long upTo)
	{
		final Deque<BuyLot> lots = openBuys.get(itemId);
		if (lots == null)
		{
			return null;
		}
		long qty = 0, spent = 0;
		for (java.util.Iterator<BuyLot> it = lots.iterator(); it.hasNext(); )
		{
			final BuyLot lot = it.next();
			if (lot.seq <= upTo && mine(lot))
			{
				qty += lot.qty;
				spent += lot.spent;
				it.remove();
			}
		}
		if (lots.isEmpty())
		{
			openBuys.remove(itemId);
		}
		return qty > 0 ? new long[]{qty, spent} : null;
	}

	/** Reset the SESSION counter only — lifetime and history survive. */
	public synchronized void resetSession()
	{
		sessionProfit = 0;
		sessionStartMillis = System.currentTimeMillis();
	}

}
