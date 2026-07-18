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
	}

	private static class BuyLot
	{
		int qty;
		long spent;
		BuyLot(int qty, long spent) { this.qty = qty; this.spent = spent; }
	}

	private final Map<Integer, SlotState> slots = new HashMap<>();
	private final Map<Integer, Deque<BuyLot>> openBuys = new HashMap<>();
	private final List<TradeFill> fills = new ArrayList<>();
	private final List<Flip> flips = new ArrayList<>();
	private long sessionProfit = 0;

	/**
	 * Consume a cumulative offer snapshot. Returns the fill this snapshot
	 * produced, or null (baseline set / no growth / slot cleared).
	 */
	public synchronized TradeFill onOffer(long now, int slot, int itemId, String itemName,
		boolean buy, int qtySold, long spent, boolean emptied)
	{
		if (emptied)
		{
			slots.remove(slot);
			return null;
		}
		SlotState st = slots.get(slot);
		boolean fresh = st == null || st.itemId != itemId || st.buy != buy || qtySold < st.qtySold;
		if (fresh)
		{
			st = new SlotState();
			st.itemId = itemId;
			st.buy = buy;
			/* An offer we've never seen with qtySold == 0 is brand new — a
			   baseline of zero means its very first fill IS witnessed growth.
			   Anything already partially filled baselines as-is (we can't
			   know when those earlier items traded). */
			st.qtySold = qtySold;
			st.spent = spent;
			slots.put(slot, st);
			return null;
		}
		int dQty = qtySold - st.qtySold;
		long dSpent = spent - st.spent;
		st.qtySold = qtySold;
		st.spent = spent;
		if (dQty <= 0 || dSpent < 0)
		{
			return null;
		}
		TradeFill fill = new TradeFill(now, itemId, itemName, buy, dQty, dSpent);
		fills.add(fill);
		if (buy)
		{
			openBuys.computeIfAbsent(itemId, k -> new ArrayDeque<>()).addLast(new BuyLot(dQty, dSpent));
		}
		else
		{
			matchSell(fill);
		}
		return fill;
	}

	/** FIFO-match a sell fill against open buy lots of the same item. */
	private void matchSell(TradeFill sell)
	{
		Deque<BuyLot> lots = openBuys.get(sell.itemId);
		if (lots == null || lots.isEmpty())
		{
			return; // sold something we never saw bought — no flip to close
		}
		int remaining = sell.quantity;
		long buySpent = 0;
		int matched = 0;
		while (remaining > 0 && !lots.isEmpty())
		{
			BuyLot lot = lots.peekFirst();
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
		Flip flip = new Flip(sell.time, sell.itemId, sell.itemName, matched, buySpent, sellGross, tax);
		flips.add(flip);
		sessionProfit += flip.profit;
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

	public synchronized void reset()
	{
		slots.clear();
		openBuys.clear();
		fills.clear();
		flips.clear();
		sessionProfit = 0;
	}
}
