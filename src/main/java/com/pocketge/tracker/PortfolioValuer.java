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
