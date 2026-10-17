package com.pocketge.tracker;

import org.junit.Assert;
import org.junit.Test;

/**
 * A buy offer that fills at a loss is flagged, with a bid that stops it.
 *
 * From a live screen: 10,000 Mithril bars bid at 876 while the price they
 * sell back at had fallen to 888 — 871 after tax. Every bar bought lost
 * 5 gp, the box said "Profit if it flips: -50K gp", and the slot stayed
 * green. "it should turn the box ... notifying me to adjust the price to a
 * price that makes this a profitable trade again ... or at least try that
 * price ... to make it at least +0".
 */
public class LosingBuyTest
{
	private static final int MITHRIL_BAR = 2359;
	private static final long NOW = 1_800_000_000L;

	private static Advisor.OfferView buy(long price, int total, int sold)
	{
		final Advisor.OfferView o = new Advisor.OfferView();
		o.slot = 0;
		o.itemId = MITHRIL_BAR;
		o.itemName = "Mithril bar";
		o.buy = true;
		o.price = price;
		o.totalQuantity = total;
		o.quantitySold = sold;
		o.active = true;
		return o;
	}

	private static Advisor.Quote quote(long low, long high)
	{
		final Advisor.Quote q = new Advisor.Quote();
		q.low = low;
		q.high = high;
		q.lowTime = NOW - 60;
		q.highTime = NOW - 60;
		return q;
	}

	/** The reported case, with no price series: the exit is the insta-buy,
	 *  and the most that can be bid for +0 is 888 less 17 tax. */
	@Test
	public void theMithrilBarBidIsFlaggedAndToldItsBreakEven()
	{
		final Advisor.Suggestion s = Advisor.losingBuy(buy(876, 10_000, 3_738), quote(870, 888), null, NOW);
		Assert.assertNotNull(s);
		Assert.assertTrue(s.losing);
		Assert.assertEquals(Advisor.Suggestion.Type.ADJUST_BUY, s.type);
		Assert.assertEquals(871, s.breakEven);
		Assert.assertEquals("with nothing better, the break-even", 871, s.price);
		Assert.assertEquals("on what is left to buy", 10_000 - 3_738, s.quantity);
		Assert.assertEquals(0, s.slot);
	}

	/** With the engine, its target buy wins when it still makes something:
	 *  the best price it can find, with break-even as the ceiling. */
	@Test
	public void theEnginesTargetBuyWinsWhenItStillProfits()
	{
		final TradeEngine.Result eng = new TradeEngine.Result(860, 888, true, "ok", 11, false);
		final Advisor.Suggestion s = Advisor.losingBuy(buy(876, 10_000, 0), quote(870, 900), eng, NOW);
		Assert.assertNotNull(s);
		Assert.assertEquals("measured against the engine's sell, not the insta-buy", 871, s.breakEven);
		Assert.assertEquals(860, s.price);
	}

	/** At the break-even it is +0 — the "at least" the player asked for —
	 *  and that is left alone. */
	@Test
	public void breakEvenIsLeftAlone()
	{
		Assert.assertNull(Advisor.losingBuy(buy(871, 10_000, 0), quote(870, 888), null, NOW));
		Assert.assertNull(Advisor.losingBuy(buy(860, 10_000, 0), quote(850, 888), null, NOW));
	}

	@Test
	public void sellsAreNotThisCheck()
	{
		final Advisor.OfferView o = buy(876, 10_000, 0);
		o.buy = false;
		Assert.assertNull(Advisor.losingBuy(o, quote(870, 888), null, NOW));
	}

	/** No series and a stale insta-buy: nothing honest to measure against. */
	@Test
	public void aStaleExitSaysNothing()
	{
		final Advisor.Quote q = quote(870, 888);
		q.highTime = NOW - 6 * 60 * 60;
		Assert.assertNull(Advisor.losingBuy(buy(876, 10_000, 0), q, null, NOW));
	}

	/** And advise() itself carries it, with the slot, so the box goes amber. */
	@Test
	public void adviseRaisesIt()
	{
		final java.util.Map<Integer, Advisor.Quote> quotes = new java.util.HashMap<>();
		quotes.put(MITHRIL_BAR, quote(870, 888));
		final java.util.List<Advisor.Suggestion> out = Advisor.advise(NOW, quotes, new java.util.HashMap<>(),
			0, new java.util.HashMap<>(), java.util.List.of(buy(876, 10_000, 3_738)),
			new java.util.HashSet<>(), new java.util.HashSet<>(), 0, 0.01, 0, null, null, 0);
		Assert.assertTrue(out.stream().anyMatch(s -> s.losing && s.slot == 0 && s.price == 871));
	}
}
