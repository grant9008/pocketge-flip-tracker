package com.pocketge.tracker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure advice engine (no RuneLite types — unit-testable).
 *
 * Given live quotes, item metadata, and the player's situation (cash,
 * holdings, active offers), produce a ranked list of suggestions:
 *   1. ADJUST_* — an active offer has drifted off the market and will
 *      likely never fill at its price; re-list at the current level.
 *   2. SELL — you already hold a stack whose spread pays after tax.
 *   3. BUY — best fillable flips for your cash, capped by buy limit and
 *      daily volume, never suggesting items you blocked/skipped or are
 *      already trading.
 *
 * All profits are AFTER the 2% GE tax via FlipTracker.taxPerItem.
 */
public class Advisor
{
	public static class Quote
	{
		public long high;      // insta-buy price (what buyers pay now)
		public long low;       // insta-sell price (what sellers accept now)
		public long highTime;  // epoch seconds of last insta-buy print
		public long lowTime;   // epoch seconds of last insta-sell print
	}

	public static class ItemMeta
	{
		public int id;
		public String name;
		public int limit;        // GE buy limit per 4h (0 = unknown)
		public long dailyVolume; // units/day from the volumes endpoint
	}

	public static class OfferView
	{
		public int slot;
		public int itemId;
		public String itemName;
		public boolean buy;
		public long price;
		public int totalQuantity;
		public int quantitySold;
		public boolean active;   // BUYING / SELLING (not bought/sold/empty)
	}

	public static class Suggestion
	{
		public enum Type { ADJUST_BUY, ADJUST_SELL, SELL, BUY }

		public Type type;
		public int itemId;
		public String name;
		public long price;       // the price to (re)list at
		public int quantity;
		public long expectedProfit; // after tax, for the suggested quantity
		public String reason;
		public int slot = -1;    // for adjusts: which GE slot

		Suggestion(Type t, int id, String name, long price, int qty, long profit, String reason)
		{
			this.type = t;
			this.itemId = id;
			this.name = name;
			this.price = price;
			this.quantity = qty;
			this.expectedProfit = profit;
			this.reason = reason;
		}
	}

	/** Quotes older than this are considered stale and unusable. */
	private static final long MAX_QUOTE_AGE_SEC = 15 * 60;
	/** Don't bother suggesting flips below this total expected profit. */
	private static final long MIN_TOTAL_PROFIT = 2_000;

	public static List<Suggestion> advise(
		long nowSec,
		Map<Integer, Quote> quotes,
		Map<Integer, ItemMeta> meta,
		long cash,
		Map<Integer, Integer> holdings,      // itemId -> qty held (bank+inv, excl. coins)
		List<OfferView> offers,
		Set<Integer> skipped,                // session skips
		Set<Integer> blocked,                // persistent blocklist
		long minVolume,                      // risk-level volume floor
		double adjustThresholdPct,           // e.g. 0.01 = 1% drift triggers adjust
		int maxBuySuggestions,
		Map<Integer, long[]> costBasis)      // itemId -> [qtyTracked, gpSpent] from FlipTracker's
		                                      // open buy lots; null/missing = unknown cost
	{
		List<Suggestion> out = new ArrayList<>();

		// 1) Adjust checks on active offers
		for (OfferView o : offers)
		{
			if (!o.active)
			{
				continue;
			}
			Quote q = quotes.get(o.itemId);
			if (q == null || !fresh(q, nowSec))
			{
				continue;
			}
			if (o.buy && q.low > 0 && q.low > Math.round(o.price * (1 + adjustThresholdPct)))
			{
				Suggestion s = new Suggestion(Suggestion.Type.ADJUST_BUY, o.itemId, o.itemName,
					q.low, o.totalQuantity - o.quantitySold, 0,
					"sellers now accept " + q.low + " gp — your " + o.price + " gp bid is below the market");
				s.slot = o.slot;
				out.add(s);
			}
			else if (!o.buy && q.high > 0 && q.high < Math.round(o.price * (1 - adjustThresholdPct)))
			{
				Suggestion s = new Suggestion(Suggestion.Type.ADJUST_SELL, o.itemId, o.itemName,
					q.high, o.totalQuantity - o.quantitySold, 0,
					"buyers now pay " + q.high + " gp — your " + o.price + " gp ask is above the market");
				s.slot = o.slot;
				out.add(s);
			}
		}

		// Items already being traded shouldn't be re-suggested
		Set<Integer> inFlight = new java.util.HashSet<>();
		for (OfferView o : offers)
		{
			if (o.active)
			{
				inFlight.add(o.itemId);
			}
		}

		// 2) Sell what you already hold, if the spread pays
		Suggestion bestSell = null;
		if (holdings != null)
		{
			for (Map.Entry<Integer, Integer> h : holdings.entrySet())
			{
				int id = h.getKey();
				int qty = h.getValue();
				if (qty <= 0 || blocked.contains(id) || skipped.contains(id) || inFlight.contains(id))
				{
					continue;
				}
				Quote q = quotes.get(id);
				ItemMeta m = meta.get(id);
				if (q == null || m == null || !fresh(q, nowSec) || q.high <= 0)
				{
					continue;
				}
				long net = q.high - FlipTracker.taxPerItem(q.high, id);
				long value = net * qty;
				if (value < 50_000)
				{
					continue; // not worth a slot
				}

				/* If we tracked the buy (an open lot from FlipTracker), show
				   real profit against what was actually paid — matching how
				   completed flips are scored everywhere else in the plugin —
				   instead of just "here's what it's worth". A stack bigger
				   than the tracked lot (older stock, drops, etc.) still shows
				   its untracked portion, just without a profit claim on it. */
				long rankValue;
				String reason;
				long[] basis = costBasis != null ? costBasis.get(id) : null;
				if (basis != null && basis[0] > 0)
				{
					long trackedQty = Math.min((long) qty, basis[0]);
					long untrackedQty = qty - trackedQty;
					long trackedCost = Math.round(basis[1] * (double) trackedQty / basis[0]);
					long profit = net * trackedQty - trackedCost;
					long untrackedValue = net * untrackedQty;
					rankValue = profit + untrackedValue;
					reason = (profit >= 0 ? "+" : "") + profit + " gp profit vs your tracked buy price"
						+ (untrackedQty > 0 ? " (plus " + untrackedValue + " gp from " + untrackedQty + " untracked units)" : "")
						+ " — sell " + qty + " at " + q.high + " gp.";
				}
				else
				{
					rankValue = value;
					reason = "you hold " + qty + " — worth ~" + value + " gp after tax at the current " + q.high + " gp";
				}

				Suggestion s = new Suggestion(Suggestion.Type.SELL, id, m.name, q.high, qty, rankValue, reason);
				if (bestSell == null || s.expectedProfit > bestSell.expectedProfit)
				{
					bestSell = s;
				}
			}
		}
		if (bestSell != null)
		{
			out.add(bestSell);
		}

		// 3) Buy recommendations sized to cash. Prefer the liquid, comfortably
		// profitable set (minVolume/MIN_TOTAL_PROFIT); if that's empty, fall
		// back to whatever's affordable and still has positive edge rather
		// than ever showing nothing — matching pocketge.com, which always
		// has a pick.
		List<Suggestion> buys = cash > 0 ? buildBuys(nowSec, quotes, meta, cash, blocked, skipped, inFlight, minVolume, MIN_TOTAL_PROFIT) : List.of();
		if (buys.isEmpty() && cash > 0)
		{
			buys = buildBuys(nowSec, quotes, meta, cash, blocked, skipped, inFlight, 0, 1);
		}
		buys.sort(Comparator.comparingLong((Suggestion s) -> s.expectedProfit).reversed());
		for (int i = 0; i < Math.min(maxBuySuggestions, buys.size()); i++)
		{
			out.add(buys.get(i));
		}
		return out;
	}

	private static List<Suggestion> buildBuys(long nowSec, Map<Integer, Quote> quotes, Map<Integer, ItemMeta> meta,
		long cash, Set<Integer> blocked, Set<Integer> skipped, Set<Integer> inFlight, long minVolume, long minProfit)
	{
		List<Suggestion> buys = new ArrayList<>();
		for (Map.Entry<Integer, Quote> e : quotes.entrySet())
		{
			int id = e.getKey();
			Quote q = e.getValue();
			ItemMeta m = meta.get(id);
			if (m == null || blocked.contains(id) || skipped.contains(id) || inFlight.contains(id))
			{
				continue;
			}
			if (!fresh(q, nowSec) || q.low <= 0 || q.high <= q.low || q.low > cash)
			{
				continue;
			}
			if (m.dailyVolume < minVolume)
			{
				continue;
			}
			long edge = q.high - q.low - FlipTracker.taxPerItem(q.high, id);
			if (edge <= 0)
			{
				continue;
			}
			long qtyByCash = cash / q.low;
			long qtyByLimit = m.limit > 0 ? m.limit : qtyByCash;
			long qtyByVolume = Math.max(1, m.dailyVolume / 12); // don't try to be >8% of a day
			int qty = (int) Math.min(Math.min(qtyByCash, qtyByLimit), qtyByVolume);
			long profit = edge * qty;
			if (qty <= 0 || profit < minProfit)
			{
				continue;
			}
			buys.add(new Suggestion(Suggestion.Type.BUY, id, m.name, q.low, qty, profit,
				"+" + edge + " gp/ea after tax · " + m.dailyVolume + "/day volume"));
		}
		return buys;
	}

	private static boolean fresh(Quote q, long nowSec)
	{
		long newest = Math.max(q.highTime, q.lowTime);
		return newest > 0 && (nowSec - newest) <= MAX_QUOTE_AGE_SEC;
	}
}
