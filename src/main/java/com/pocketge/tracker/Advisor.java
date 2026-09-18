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
		/**
		 * After tax. A gain for BUY/ADJUST and for a fully tracked SELL; the
		 * stack's gross proceeds for a SELL with no tracked cost at all (see
		 * {@link #hasTrackedCost}).
		 *
		 * On a PARTLY tracked sell it covers {@link #trackedQty} units only.
		 * It never mixes the two: proceeds from units whose cost is unknown
		 * are not a gain, and adding them to one produces a number that is
		 * neither — which is what it used to do, turning a 381K loss on a
		 * held stack into a "+6.81M P&amp;L" on the card.
		 */
		public long expectedProfit;
		/** Ranking score, never displayed. Sells are ordered by profit where
		 *  it is known plus proceeds where it isn't, so the biggest position
		 *  worth liquidating still comes first — a sort key can mix those,
		 *  a label cannot. */
		public long rank;
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
		/**
		 * Why this is worth selling NOW, in a few words, or null.
		 *
		 * The bank mark said a stack was worth selling and what it would
		 * fetch, and never why this moment rather than any other — asked as
		 * "why is this good to sell right now, is it up 30% since you
		 * purchased or what". Set only when there is a real answer; an
		 * invented reason is worse than none.
		 */
		public String whyNow;
		/** SELL only: how many of {@link #quantity} the plugin actually
		 *  watched you buy, so {@link #expectedProfit} is a claim about
		 *  exactly this many units. Below quantity whenever the stack is
		 *  older than the plugin, was partly bought elsewhere, or predates
		 *  the tracker's baseline for that slot. */
		public long trackedQty;
		/** SELL only: after-tax proceeds from the {@code quantity -
		 *  trackedQty} units whose cost is unknown. Money arriving, not a
		 *  gain — it belongs in its own sentence, never added to one. */
		public long untrackedValue;
		/**
		 * SELL only: how long ago the bid this is priced off last printed, in
		 * seconds. 0 when it is current enough not to be worth saying.
		 *
		 * A sell gets a two-hour freshness window (see
		 * SELL_QUOTE_MAX_AGE_SEC) because a thin, expensive item would
		 * otherwise never be sellable at all. The cost of that window is that
		 * the price on the card may be an hour old, and a price quoted
		 * without its age is a price presented as current.
		 */
		public long quoteAgeSec;

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

	/** How old a print may be before it stops being evidence of anything.
	 *  Applies to every leg a decision to SPEND gold rests on. */
	private static final long MAX_QUOTE_AGE_SEC = 15 * 60;
	/**
	 * The same question asked of a stack you already own gets a longer window.
	 *
	 * Selling is not symmetrical with buying. A buy stakes new gold on a
	 * spread being real; a sell only asks what today's bid is for something
	 * already sitting in your bank, and refusing to answer because the last
	 * print was twenty minutes ago is a refusal to talk about your biggest
	 * positions at all — a Twisted bow trades a few dozen times a day, so
	 * under the buy window it was NEVER a sell candidate while the portfolio
	 * value and the watchlist row happily priced it off that same print.
	 *
	 * Two hours, matching what pocketge.com's own flip finder accepts. Past
	 * about that the price is a guess, and the card says how old it is
	 * anyway — see {@link Suggestion#quoteAgeSec}.
	 */
	private static final long SELL_QUOTE_MAX_AGE_SEC = 2 * 3600;
	/** Don't bother suggesting flips below this total expected profit. */
	private static final long MIN_TOTAL_PROFIT = 2_000;
	/**
	 * The volume floor the widen-retry drops to when nothing clears the
	 * normal one — NOT zero.
	 *
	 * The retry used to relax volume to 0 and profit to 1gp together, which
	 * is how a 344gp purse got told to "Buy 40 Lobster pot for 1 gp ea". Two
	 * different relaxations were being made at once and only one of them is
	 * defensible: a quiet market genuinely is a reason to look further down
	 * the volume curve, and never a reason to call a 40gp trade an idea.
	 */
	private static final long FALLBACK_MIN_VOLUME = 25_000;
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
		Map<Integer, TradeEngine.Series> seriesByItem, // itemId -> recent price history, active-offer
		                                      // items only (see TradeEngine); null/missing item
		                                      // falls back to the raw live quote below
		long minTotalProfit)                  // hard floor on a buy idea's whole-limit profit, or
		                                      // 0 for "advisor's choice" — see below
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
			if (q == null)
			{
				continue;
			}
			/* The leg this offer would be repriced AGAINST has to be live.
			   Acting on an adjust costs you your place in the queue, so the
			   strict window applies even though nothing is being bought. */
			if (!legFresh(o.buy ? q.lowTime : q.highTime, nowSec, MAX_QUOTE_AGE_SEC))
			{
				continue;
			}
			TradeEngine.Series series = seriesByItem != null ? seriesByItem.get(o.itemId) : null;
			TradeEngine.Result engine = series != null ? TradeEngine.viewTargets(q.low, q.high, q.lowTime, q.highTime, series, o.itemId) : null;
			/*
			 * Decided on the TARGET, not on the raw print.
			 *
			 * These two were doing different jobs with different numbers: the
			 * gate asked "has the last print moved past your price?" while the
			 * advice came from the engine, which knows the last print is not
			 * the only fillable level. They disagree exactly when the engine
			 * says your price is still reachable — and then the card read
			 * "your 972 ask is above the market — re-list at 975", which is an
			 * instruction to cancel a working offer and ask for MORE.
			 *
			 * One number now answers both questions. Without a series the
			 * target falls back to the raw print and this is the old gate
			 * exactly, so nothing changes for items the engine cannot price.
			 */
			if (o.buy && q.low > 0)
			{
				final long target = TradeEngine.buyTarget(
					(engine != null && engine.viable) ? engine.buy : q.low, q.low);
				if (target > Math.round(o.price * (1 + adjustThresholdPct)))
				{
					Suggestion s = new Suggestion(Suggestion.Type.ADJUST_BUY, o.itemId, o.itemName,
						target, o.totalQuantity - o.quantitySold, 0,
						"the current target buy is " + target + " gp — your " + o.price
							+ " gp bid is below it (sellers now accept " + q.low + " gp)");
					s.slot = o.slot;
					out.add(s);
				}
			}
			else if (!o.buy && q.high > 0)
			{
				final long target = TradeEngine.sellTarget(
					(engine != null && engine.viable) ? engine.sell : q.high, q.high);
				if (target < Math.round(o.price * (1 - adjustThresholdPct)))
				{
					Suggestion s = new Suggestion(Suggestion.Type.ADJUST_SELL, o.itemId, o.itemName,
						target, o.totalQuantity - o.quantitySold, 0,
						"the current target sell is " + target + " gp — your " + o.price
							+ " gp ask is above it (buyers now pay " + q.high + " gp)");
					s.slot = o.slot;
					out.add(s);
				}
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
		/* The series map goes in rather than being applied afterwards.
		   Repricing the winner out here meant patching its already-built
		   reason string by search-and-replace, and left every number on the
		   suggestion — profit, gross, the untracked remainder — costed at the
		   old price while the card showed the new one. sellCandidates has all
		   the pieces in scope and now does the whole job in one place. */
		final List<Suggestion> sells = sellCandidates(nowSec, quotes, meta, holdings, offers, skipped, blocked,
			costBasis, seriesByItem);
		Suggestion bestSell = sells.isEmpty() ? null : sells.get(0);
		if (bestSell != null)
		{
			/* Held ONLY when the plugin watched you buy it. Placed here,
			   ahead of the buys, because closing a position it can actually
			   measure a profit on is the more urgent of the two.

			   An untracked stack is deferred to the end of the list instead —
			   see the tail of this method for why. */
			if (bestSell.hasTrackedCost)
			{
				out.add(bestSell);
			}
		}

		// 3) Buy recommendations sized to cash. Prefer the liquid, comfortably
		// profitable set (minVolume/MIN_TOTAL_PROFIT); if that's empty, fall
		// back to whatever's affordable and still has positive edge rather
		// than ever showing nothing — matching pocketge.com, which always
		// has a pick.
		//
		// A caller-set floor changes both halves of that. It replaces the
		// default floor, and the fallback stops relaxing past it: dropping to
		// 1gp is the right answer to "the market is thin today" and the wrong
		// one to "don't show me anything under 500k", and the two are
		// indistinguishable by the time we get here. Someone who set a floor
		// would rather read an empty list than a 3k idea — the empty list is
		// itself the information that nothing clears their bar.
		final boolean floorIsUsers = minTotalProfit > 0;
		final long floor = floorIsUsers ? minTotalProfit : MIN_TOTAL_PROFIT;
		List<Suggestion> buys = cash > 0 ? buildBuys(nowSec, quotes, meta, cash, blocked, skipped, inFlight, minVolume, floor) : new ArrayList<>();
		if (buys.isEmpty() && cash > 0)
		{
			/* Volume relaxes; the profit floor does NOT. Those were dropped
			   together — to 0 and to 1gp — and the second one is what put
			   "Buy 40 Lobster pot for 1 gp ea" in front of a player with a
			   bank full of stock. "The market is quiet, look wider" is a
			   reasonable thing to do about an empty list. "Here is a 40gp
			   trade" is not; an empty list at least says something true. */
			buys = buildBuys(nowSec, quotes, meta, cash, blocked, skipped, inFlight,
				Math.min(minVolume, FALLBACK_MIN_VOLUME), floor);
		}
		/* Something you do not already own outranks something you do, and only
		   then does profit decide.

		   "Buy more Uncut diamond" is the weakest idea on the list when there
		   is a stack of them in the bank already. You may well have spent the
		   4-hour limit getting them, so the idea can be unactionable outright;
		   and even when it isn't, adding to a position you are already carrying
		   is a different (and more concentrated) bet than the fresh one sitting
		   below it. The held ones are still here, just last — this reorders the
		   list, it never drops anything, so nothing that used to be reachable
		   by pressing Next stops being reachable.

		   Ties inside each group are broken by expected profit exactly as
		   before, so among genuinely new ideas the ranking is unchanged. */
		final Map<Integer, Integer> owned = holdings != null ? holdings : Map.of();
		buys.sort(Comparator
			.comparing((Suggestion s) -> owned.getOrDefault(s.itemId, 0) > 0)
			.thenComparing(Comparator.comparingLong((Suggestion s) -> s.expectedProfit).reversed()));
		for (int i = 0; i < Math.min(maxBuySuggestions, buys.size()); i++)
		{
			out.add(buys.get(i));
		}

		/* 4) LAST: a stack you hold that the plugin never watched you buy.
		
		   This used to lead the whole list, and on a new install it led it
		   every single time. Two things put it there. It was added ahead of
		   the buys unconditionally; and with no cost basis, sellCandidates
		   ranks it on gross after-tax SALE VALUE rather than profit — so a
		   46.7M bank stack scored 150x a genuine 296K flip. Those are not the
		   same kind of number: one is what a thing is worth, the other is what
		   a trade earns, and comparing them ranked "you own something" above
		   every real opportunity.

		   The effect was worst for exactly the people it should be best for.
		   Everything in a new user's bank predates the plugin, so every
		   holding is untracked, so the advisor spent their first sessions
		   telling them to liquidate their bank — a flip advisor that reads as
		   a bank-clearing tool until you have traded through it for a while.

		   It stays in the stream, because selling old stock is a real thing to
		   want. It goes last because the plugin has no evidence it is a good
		   trade — only that you own it. A tracked sell keeps its place ahead
		   of the buys above: there, the profit is measured, not assumed. */
		if (bestSell != null && !bestSell.hasTrackedCost)
		{
			out.add(bestSell);
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
	 * {@code seriesByItem} is optional and usually sparse — a per-item price
	 * series is only fetched for a handful of ids, so a whole-bank list
	 * cannot have one for everything. Where there IS one the stack is priced
	 * at the engine's target instead of the raw bid, which is the difference
	 * between the card saying 942 and saying 957 for the same wine. Where
	 * there is not, the raw bid stands.
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
		return sellCandidates(nowSec, quotes, meta, holdings, offers, skipped, blocked, costBasis, null);
	}

	/** As above, able to reprice through {@link TradeEngine} for the items a
	 *  price series was fetched for. */
	public static List<Suggestion> sellCandidates(
		long nowSec,
		Map<Integer, Quote> quotes,
		Map<Integer, ItemMeta> meta,
		Map<Integer, Integer> holdings,
		List<OfferView> offers,
		Set<Integer> skipped,
		Set<Integer> blocked,
		Map<Integer, long[]> costBasis,
		Map<Integer, TradeEngine.Series> seriesByItem)
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
			if (q == null || m == null || !freshForSell(q, nowSec) || q.high <= 0)
			{
				continue;
			}
			/*
			 * The price this card will TELL you to list at, which is not
			 * always the last bid.
			 *
			 * The engine's whole job is to find a level the market reaches
			 * that the last two prints do not happen to show, and the card
			 * was the one place still refusing to ask it — hence the sidebar
			 * saying 942 for a wine the website and the offer screen both
			 * priced at 957. Clamped so it can only ever be at or above the
			 * standing bid: you have decided to sell, so the only question
			 * left is the price, and asking under the bid gives gold away.
			 */
			long price = q.high;
			final TradeEngine.Series series = seriesByItem != null ? seriesByItem.get(id) : null;
			if (series != null)
			{
				final TradeEngine.Result eng =
					TradeEngine.viewTargets(q.low, q.high, q.lowTime, q.highTime, series, id);
				if (eng != null && eng.viable && eng.sell > 0)
				{
					price = TradeEngine.sellTarget(eng.sell, q.high);
				}
			}
			/* Everything below is at the price named above, so the card cannot
			   quote one number and cost it at another. Falls back to exactly
			   PortfolioValuer.netExit when no target was found, which is what
			   keeps the untouched case identical to the portfolio's marking. */
			long net = price - FlipTracker.taxPerItem(price, id);
			if (net <= 0)
			{
				continue;
			}
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
			long headline;      // what the card is allowed to call the suggestion's number
			long rankValue;     // what the list is ordered by, which may mix kinds
			long trackedQty = 0;
			long untrackedValue = 0;
			String reason;
			long[] basis = costBasis != null ? costBasis.get(id) : null;
			if (basis != null && basis[0] > 0)
			{
				trackedQty = Math.min((long) qty, basis[0]);
				long untrackedQty = qty - trackedQty;
				long trackedCost = Math.round(basis[1] * (double) trackedQty / basis[0]);
				untrackedValue = net * untrackedQty;
				/* The tracked units ONLY. Selling 18,000 necklaces bought at
				   517 into a 500 market is a loss, and it stays a loss however
				   many other necklaces of unknown provenance ride along in the
				   same stack — those have their own line. */
				headline = net * trackedQty - trackedCost;
				rankValue = headline + untrackedValue;
				reason = (headline >= 0 ? "+" : "") + headline + " gp profit vs your tracked buy price"
					+ (untrackedQty > 0 ? " (plus " + untrackedValue + " gp from " + untrackedQty + " untracked units)" : "")
					+ " — sell " + qty + " at " + price + " gp.";
			}
			else
			{
				headline = value;
				rankValue = value;
				untrackedValue = value;
				reason = "you hold " + qty + " — worth ~" + value + " gp after tax at " + price + " gp";
			}

			Suggestion s = new Suggestion(Suggestion.Type.SELL, id, m.name, price, qty, headline, reason);
			s.rank = rankValue;
			s.hasTrackedCost = basis != null && basis[0] > 0;
			s.grossValue = value;
			s.trackedQty = trackedQty;
			s.untrackedValue = untrackedValue;
			s.unitCost = s.hasTrackedCost ? Math.round(basis[1] / (double) basis[0]) : 0;
			/* The answer that was actually asked for: how this stack has moved
			   against what you paid for it. Only with a tracked cost — without
			   one there is no "since you bought" to measure from, and a
			   percentage off an assumed cost would be fiction. */
			if (s.unitCost > 0 && price > 0)
			{
				final long pct = Math.round((price - s.unitCost) * 100.0 / s.unitCost);
				if (pct >= 5)
				{
					s.whyNow = "up " + pct + "% on what you paid";
				}
				else if (pct <= -5)
				{
					s.whyNow = "down " + Math.abs(pct) + "% on what you paid";
				}
			}
			/* Only once it is old enough to change how you read the price.
			   Inside the buy window it is simply "now" and saying so would be
			   noise on every card. */
			final long age = q.highTime > 0 ? nowSec - q.highTime : 0;
			s.quoteAgeSec = age > MAX_QUOTE_AGE_SEC ? age : 0;
			out.add(s);
		}
		out.sort(Comparator.comparingLong((Suggestion s) -> s.rank).reversed());
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
			if (!freshForBuy(q, nowSec) || q.low <= 0 || q.high <= q.low || q.low > cash)
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

	/** One side of the book, against one age limit. */
	private static boolean legFresh(long printTime, long nowSec, long maxAgeSec)
	{
		return printTime > 0 && (nowSec - printTime) <= maxAgeSec;
	}

	/**
	 * BOTH legs, because a buy idea is a claim about the SPREAD and a spread
	 * needs two live sides to exist.
	 *
	 * This used to be max(highTime, lowTime) — either leg fresh was enough.
	 * On a falling item that is precisely backwards: the bid keeps printing
	 * as sellers hit it while the ask goes quiet, so a three-hour-old high
	 * sat next to a one-minute-old low and the difference between them —
	 * mostly the market having moved since — was scored as edge. The fatter
	 * the fake edge, the higher it ranked. A knife-catcher generator, sorted
	 * best first.
	 */
	private static boolean freshForBuy(Quote q, long nowSec)
	{
		return legFresh(q.lowTime, nowSec, MAX_QUOTE_AGE_SEC)
			&& legFresh(q.highTime, nowSec, MAX_QUOTE_AGE_SEC);
	}

	/** Only the bid, and on the longer window — see SELL_QUOTE_MAX_AGE_SEC.
	 *  What sellers are accepting says nothing about what YOUR stack fetches. */
	private static boolean freshForSell(Quote q, long nowSec)
	{
		return legFresh(q.highTime, nowSec, SELL_QUOTE_MAX_AGE_SEC);
	}
}
