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
		/** True unless this is a SELL suggestion for a stack with no tracked
		 *  purchase cost (held since before the plugin ever saw you buy it,
		 *  or acquired some other way) — in that case expectedProfit is the
		 *  stack's full sale value, not a real gain over what you paid, and
		 *  callers should label it "value" rather than "profit". Always true
		 *  for ADJUST/BUY, which are forward-looking estimates, not
		 *  cost-basis-dependent. */
		public boolean hasTrackedCost = true;
		/** SELL only: what the whole stack fetches after tax, regardless of
		 *  what you paid. expectedProfit already folds in cost basis when
		 *  it's known, so the two differ for a tracked stack — the sell box
		 *  needs both ("sell X for 11.0M" and "+1.1M profit"). */
		public long grossValue;
		/** SELL only: average price per unit actually paid, from the tracked
		 *  open buy lot. 0 when the stack has no tracked purchase — held
		 *  since before the plugin saw it, dropped, or bought elsewhere —
		 *  in which case there is no honest "you bought at" to show. */
		public long unitCost;

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
	/** A held stack worth less than this isn't worth spending a GE slot on. */
	private static final long MIN_SELL_VALUE = 50_000;

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
		Map<Integer, long[]> costBasis,       // itemId -> [qtyTracked, gpSpent] from FlipTracker's
		                                      // open buy lots; null/missing = unknown cost
		Map<Integer, TradeEngine.Series> seriesByItem) // itemId -> recent price history, active-offer
		                                      // items only (see TradeEngine); null/missing item
		                                      // falls back to the raw live quote below
	{
		List<Suggestion> out = new ArrayList<>();

		// 1) Adjust checks on active offers. Whether an offer needs adjusting
		// is decided on the raw live quote (has the market genuinely moved past
		// your price?) — but WHAT to reprice to comes from the same trade
		// engine that drives pocketge.com's Target Buy/Sell, when a recent
		// price series is available, instead of just the raw live print. That
		// print is always fillable RIGHT NOW but leaves gp on the table; the
		// engine picks the best reachable price, same as the website.
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
			TradeEngine.Series series = seriesByItem != null ? seriesByItem.get(o.itemId) : null;
			TradeEngine.Result engine = series != null ? TradeEngine.compute(q.low, q.high, q.lowTime, q.highTime, series, o.itemId) : null;
			if (o.buy && q.low > 0 && q.low > Math.round(o.price * (1 + adjustThresholdPct)))
			{
				long target = TradeEngine.buyTarget(
					(engine != null && engine.viable) ? engine.buy : q.low, q.low);
				Suggestion s = new Suggestion(Suggestion.Type.ADJUST_BUY, o.itemId, o.itemName,
					target, o.totalQuantity - o.quantitySold, 0,
					"the current target buy is " + target + " gp — your " + o.price
						+ " gp bid is below the market (sellers now accept " + q.low + " gp)");
				s.slot = o.slot;
				out.add(s);
			}
			else if (!o.buy && q.high > 0 && q.high < Math.round(o.price * (1 - adjustThresholdPct)))
			{
				long target = TradeEngine.sellTarget(
					(engine != null && engine.viable) ? engine.sell : q.high, q.high);
				Suggestion s = new Suggestion(Suggestion.Type.ADJUST_SELL, o.itemId, o.itemName,
					target, o.totalQuantity - o.quantitySold, 0,
					"the current target sell is " + target + " gp — your " + o.price
						+ " gp ask is above the market (buyers now pay " + q.high + " gp)");
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
		final List<Suggestion> sells = sellCandidates(nowSec, quotes, meta, holdings, offers, skipped, blocked, costBasis);
		Suggestion bestSell = sells.isEmpty() ? null : sells.get(0);
		if (bestSell != null)
		{
			/* Ranking above stays on the raw live quote (q.high) — deciding
			   WHICH held item is worth selling doesn't need series data for
			   every item you hold. But the price actually shown/used for the
			   winner should match pocketge.com's own target, same as
			   ADJUST_SELL above, so reprice just the winner here. */
			TradeEngine.Series series = seriesByItem != null ? seriesByItem.get(bestSell.itemId) : null;
			Quote bq = quotes.get(bestSell.itemId);
			TradeEngine.Result engine = (series != null && bq != null)
				? TradeEngine.compute(bq.low, bq.high, bq.lowTime, bq.highTime, series, bestSell.itemId) : null;
			if (engine != null && engine.viable)
			{
				// Never below the standing bid — see TradeEngine.sellTarget.
				final long target = TradeEngine.sellTarget(engine.sell, bq != null ? bq.high : 0);
				if (target != bestSell.price)
				{
					bestSell.reason = bestSell.reason.replace(
						"at the current " + bestSell.price + " gp", "at the target " + target + " gp")
						.replace("sell " + bestSell.quantity + " at " + bestSell.price + " gp",
							"sell " + bestSell.quantity + " at " + target + " gp");
					bestSell.price = target;
				}
			}
			out.add(bestSell);
		}

		// 3) Buy recommendations sized to cash. Prefer the liquid, comfortably
		// profitable set (minVolume/MIN_TOTAL_PROFIT); if that's empty, fall
		// back to whatever's affordable and still has positive edge rather
		// than ever showing nothing — matching pocketge.com, which always
		// has a pick.
		List<Suggestion> buys = cash > 0 ? buildBuys(nowSec, quotes, meta, cash, blocked, skipped, inFlight, minVolume, MIN_TOTAL_PROFIT) : new ArrayList<>();
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

	/**
	 * Every holding worth selling right now, best first.
	 *
	 * {@link #advise} shows only the top one — it drives a single-suggestion
	 * card — but the sidebar's "Sell from bank" box lists several, and both
	 * must score them identically or the same stack would rank differently
	 * in two places on screen. One ranking, two readers.
	 *
	 * Note this deliberately does NOT reprice to a TradeEngine target the
	 * way advise() does for its single winner: that costs a per-item price
	 * series, which is only fetched for a handful of items (active offers
	 * and the current sell candidate), so a whole-bank list can't have it.
	 * These are raw live-quote valuations.
	 */
	public static List<Suggestion> sellCandidates(
		long nowSec,
		Map<Integer, Quote> quotes,
		Map<Integer, ItemMeta> meta,
		Map<Integer, Integer> holdings,
		List<OfferView> offers,
		Set<Integer> skipped,
		Set<Integer> blocked,
		Map<Integer, long[]> costBasis)
	{
		final List<Suggestion> out = new ArrayList<>();
		if (holdings == null)
		{
			return out;
		}
		final Set<Integer> inFlight = new java.util.HashSet<>();
		if (offers != null)
		{
			for (OfferView o : offers)
			{
				if (o.active)
				{
					inFlight.add(o.itemId);
				}
			}
		}
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
			if (value < MIN_SELL_VALUE)
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
			s.hasTrackedCost = basis != null && basis[0] > 0;
			s.grossValue = value;
			s.unitCost = s.hasTrackedCost ? Math.round(basis[1] / (double) basis[0]) : 0;
			out.add(s);
		}
		out.sort(Comparator.comparingLong((Suggestion s) -> s.expectedProfit).reversed());
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
