package com.pocketge.tracker;

import java.util.List;
import java.util.Map;

/**
 * Pure net-worth calculator (no RuneLite types — unit-testable). Everything
 * you own, priced at the current instant-sell rate — the same conservative
 * "what you'd actually get right now" valuation the flip advisor itself
 * uses, not the (usually higher) instant-buy price.
 *
 * Total = cash on hand
 *       + bank + inventory + equipped items, priced at current low
 *       + open GRAND EXCHANGE offers (see {@link #offerValue}), which needs
 *         its own accounting because gp and items move between "yours
 *         directly" and "escrowed in an offer" as it fills.
 */
public final class PortfolioValuer
{
	private PortfolioValuer() {}

	public static final int COINS_ID = 995;
	/**
	 * Platinum tokens are cash, not stock.
	 *
	 * Anyone flipping at real scale holds most of their gp as tokens — they
	 * are how you carry more than the 2,147,483,647 a coin stack can hold,
	 * and they are common well below that too. Treating them as just another
	 * bankable item has two consequences, both bad: the advisor's idea of
	 * your LIQUID cash — the number every buy recommendation is sized
	 * against — misses the bulk of it, so a player sitting on 500M in tokens
	 * gets told they can't afford anything; and the tokens themselves become
	 * eligible to be "sold", which is not a flip, it's breaking a note.
	 *
	 * Redeemed 1:1000 at any bank with no spread and no tax, so face value
	 * is exact — no quote lookup needed or wanted.
	 */
	public static final int PLATINUM_TOKEN_ID = 13204;
	public static final int PLATINUM_VALUE = 1000;

	/** True for the item ids that ARE money rather than something you trade.
	 *  Holdings maps must exclude these and count them as cash instead —
	 *  see {@link #cashValue}. */
	public static boolean isCash(int itemId)
	{
		return itemId == COINS_ID || itemId == PLATINUM_TOKEN_ID;
	}

	/** Face value in gp of a cash stack; 0 for anything that isn't cash. */
	public static long cashValue(int itemId, long quantity)
	{
		if (quantity <= 0)
		{
			return 0;
		}
		if (itemId == COINS_ID)
		{
			return quantity;
		}
		return itemId == PLATINUM_TOKEN_ID ? quantity * PLATINUM_VALUE : 0;
	}

	public static class Result
	{
		public long cash;
		public long itemsValue;     // bank + inventory + equipped, at current low
		public long offersValue;    // net value tied up in active GE offers
		public long total;
	}

	public static Result value(
		long cash,
		Map<Integer, Integer> holdings,   // bank+inventory, excl. coins
		Map<Integer, Integer> equipped,
		List<Advisor.OfferView> offers,
		Map<Integer, Advisor.Quote> quotes)
	{
		Result r = new Result();
		r.cash = Math.max(0, cash);
		r.itemsValue = itemsValue(holdings, quotes) + itemsValue(equipped, quotes);
		r.offersValue = 0;
		if (offers != null)
		{
			for (Advisor.OfferView o : offers)
			{
				r.offersValue += offerValue(o, quotes);
			}
		}
		r.total = r.cash + r.itemsValue + r.offersValue;
		return r;
	}

	/** What a stack you're currently holding is worth, and how much of that
	 *  is measurable gain. See {@link #heldPosition}. */
	public static class HeldPosition
	{
		/** Units in bank + inventory. 0 means there is nothing to report. */
		public int heldQty;
		/** After-tax proceeds for all of heldQty at the current instant-buy. */
		public long sellValue;
		/** True only when the plugin actually watched the purchase. */
		public boolean hasCostBasis;
		/** How many of heldQty the cost basis covers. */
		public int pricedQty;
		/** Proceeds minus cost, over pricedQty only. Meaningless unless
		 *  hasCostBasis. May be negative — you can be underwater. */
		public long heldProfit;
	}

	/**
	 * Values a stack the player is holding right now.
	 *
	 * The two quantities involved are NOT the same, and conflating them is
	 * the whole trap here:
	 *
	 *  - {@code held} is what is physically in the bank and inventory. That
	 *    is the cap on what could actually be sold.
	 *  - {@code tracked} is what the plugin WATCHED being bought and has not
	 *    yet matched against a sale ({@link FlipTracker#getOpenBuyTotals},
	 *    as {@code [qty, spent]}). That is the cap on what there is a
	 *    purchase price for.
	 *
	 * They drift apart constantly: buy 1,000 nature runes through the GE and
	 * cast 400, and held is 600 while tracked is still 1,000; inherit a stack
	 * from before the plugin was installed and held is 5,000 while tracked is
	 * nothing at all. So profit is claimed only over {@code min(held,
	 * tracked)} — the units that can both be sold and priced — and anything
	 * above that line is reported as sale value, never as profit. Getting
	 * this wrong prints a large green number for a stack the player has sat
	 * on for a year, which is the same fake win the recommendation card's
	 * "gp value" wording already avoids.
	 *
	 * Items sitting in an open GE sell offer are in neither the bank nor the
	 * inventory, so they never reach this method and cannot be double counted
	 * against {@link #offerValue}'s own accounting.
	 *
	 * @param tracked {@code [qty, spent]} from getOpenBuyTotals, or null when
	 *                no purchase was ever seen.
	 */
	public static HeldPosition heldPosition(int itemId, int held, Advisor.Quote q, long[] tracked)
	{
		final HeldPosition p = new HeldPosition();
		if (held <= 0 || q == null || q.high <= 0)
		{
			return p; // hold none of it, or no live price to value it at
		}
		/* Sold into the standing bid (q.high), the same side Advisor prices a
		   SELL suggestion at, minus the same 2% tax the rest of the plugin
		   applies. Per item, not on the total: that is how the GE actually
		   charges it, and the rounding differs. */
		final long netPerItem = q.high - FlipTracker.taxPerItem(q.high, itemId);
		if (netPerItem <= 0)
		{
			return p; // tax eats the whole price — nothing to report
		}
		p.heldQty = held;
		p.sellValue = (long) held * netPerItem;

		if (tracked == null || tracked.length < 2 || tracked[0] <= 0 || tracked[1] <= 0)
		{
			return p; // no purchase on record — sellValue only, labelled as such
		}
		final long trackedQty = tracked[0];
		final long trackedSpent = tracked[1];
		final long priced = Math.min(held, trackedQty);
		/* Round the COST UP, which rounds the reported profit DOWN. Every
		   rounding choice here errs against us: a tag that overstates by a
		   few gp is a tag that lied, and this one is telling the player to go
		   sell something. Exact when priced == trackedQty, the common case.
		   The double is safe — the product is bounded by trackedSpent, far
		   below the 2^53 exact-integer limit. */
		final long costOfPriced = (long) Math.ceil(trackedSpent * (priced / (double) trackedQty));
		p.hasCostBasis = true;
		p.pricedQty = (int) priced;
		p.heldProfit = priced * netPerItem - costOfPriced;
		return p;
	}

	private static long itemsValue(Map<Integer, Integer> items, Map<Integer, Advisor.Quote> quotes)
	{
		if (items == null)
		{
			return 0;
		}
		long total = 0;
		for (Map.Entry<Integer, Integer> e : items.entrySet())
		{
			int qty = e.getValue();
			if (qty <= 0)
			{
				continue;
			}
			if (isCash(e.getKey()))
			{
				/* Cash reaches value() through the `cash` argument. Callers
				   are expected to have stripped it from holdings already, but
				   a stray coin or token stack here would be counted twice —
				   and for platinum at a live quote rather than its exact
				   1:1000 face value. Belt and braces on a total the player
				   makes decisions against. */
				continue;
			}
			Advisor.Quote q = quotes.get(e.getKey());
			if (q == null || q.low <= 0)
			{
				continue; // unknown/untradeable item — can't price it, don't guess
			}
			total += (long) qty * q.low;
		}
		return total;
	}

	/**
	 * Value still tied up in a single active offer.
	 *
	 * BUY offer: the unfilled portion sits as gp in escrow (already deducted
	 * from your coin pouch by the game, so it must be counted here or it
	 * vanishes from the total); the filled-but-not-yet-collected portion has
	 * already become items, priced at today's market like any other holding.
	 *
	 * SELL offer: the unsold portion is still your item, sitting in the
	 * offer instead of your bank — priced the same as any other holding. The
	 * sold portion has already become gp (tax already deducted at the
	 * moment of sale under current GE rules) waiting to be collected.
	 */
	private static long offerValue(Advisor.OfferView o, Map<Integer, Advisor.Quote> quotes)
	{
		if (o == null || !o.active)
		{
			return 0;
		}
		int unfilled = Math.max(0, o.totalQuantity - o.quantitySold);
		int filled = Math.max(0, o.quantitySold);
		Advisor.Quote q = quotes.get(o.itemId);
		long marketLow = (q != null && q.low > 0) ? q.low : o.price;

		if (o.buy)
		{
			long escrow = (long) unfilled * o.price;
			long boughtValue = (long) filled * marketLow;
			return escrow + boughtValue;
		}
		else
		{
			long unsoldValue = (long) unfilled * marketLow;
			long tax = FlipTracker.taxPerItem(o.price, o.itemId) * filled;
			long soldProceeds = (long) filled * o.price - tax;
			return unsoldValue + soldProceeds;
		}
	}
}
