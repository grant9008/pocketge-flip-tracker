package com.pocketge.tracker;

import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/**
 * One stack, one price. "should say +117k gp on that hover": 6,193 Dragon
 * darts read 994 and "+124K" on the hover beside an inspect card at 993 and
 * +117K, Rune darts -110K against -132K, Mithril dart tips 101 against 100,
 * the ice sack 304 against 303. The hovers, the bank marks and the sell
 * cards raised the website's Target Sell to the live insta-buy; the inspect
 * card did not. Asked which: the website's price, everywhere.
 */
public class OneSellPriceTest
{
	private static final long NOW = 1_700_000_000L;
	private static final int ITEM = 1601;

	private static Advisor.Quote quote(long low, long high)
	{
		final Advisor.Quote q = new Advisor.Quote();
		q.low = low;
		q.high = high;
		q.lowTime = NOW;
		q.highTime = NOW;
		return q;
	}

	/** This series has the engine ask a gp under a 2000 insta-buy. */
	private static TradeEngine.Series underTheBid()
	{
		return AdvisorTest.syntheticSeries(NOW, 200, 1900, 2000, 11);
	}

	@Test
	public void theWebsitesTargetSellAsItStands()
	{
		final Advisor.Quote q = quote(1900, 2000);
		final long website = TradeEngine.viewTargets(q.low, q.high, q.lowTime, q.highTime, underTheBid(), ITEM).sell;
		Assert.assertTrue("the case in hand: the website asks under the bid", website < q.high);
		Assert.assertEquals(website, Advisor.heldSellPrice(ITEM, q, underTheBid()));
		Assert.assertEquals("no series: the last insta-buy", 2000, Advisor.heldSellPrice(ITEM, q, null));
	}

	/** The hover, the sell card, the offer screen and the inspect card's
	 *  ask all name the same number for the same stack. */
	@Test
	public void everySurfaceNamesTheSameNumber()
	{
		final Advisor.Quote q = quote(1900, 2000);
		final TradeEngine.Series s = underTheBid();
		final long website = Advisor.heldSellPrice(ITEM, q, s);

		final Map<Integer, TradeEngine.Series> series = new HashMap<>();
		series.put(ITEM, s);
		Assert.assertEquals("the stack hover", website, PocketGeTrackerPlugin.sellPriceFor(ITEM, q, series));

		final Map<Integer, Advisor.Quote> quotes = new HashMap<>();
		quotes.put(ITEM, q);
		final Map<Integer, Advisor.ItemMeta> meta = new HashMap<>();
		final Advisor.ItemMeta m = new Advisor.ItemMeta();
		m.id = ITEM;
		m.name = "Diamond";
		m.limit = 10_000;
		m.dailyVolume = 500_000;
		meta.put(ITEM, m);
		final Map<Integer, Integer> holdings = new HashMap<>();
		holdings.put(ITEM, 1_000);
		final Advisor.Suggestion card = Advisor.sellCandidates(NOW, quotes, meta, holdings,
			new java.util.ArrayList<>(), new java.util.HashSet<>(), new java.util.HashSet<>(), null, series).get(0);
		Assert.assertEquals("the sell card and the bank mark", website, card.price);

		final TradeEngine.Result engine = TradeEngine.viewTargets(q.low, q.high, q.lowTime, q.highTime, s, ITEM);
		Assert.assertEquals("the offer screen", website, PocketGeTrackerPlugin.offerScreenPrice(q, engine, false));
		/* The inspect card asks the watchlist row's targetSell, which is
		   engine.sell for a viable engine: the same expression. */
		Assert.assertTrue(engine.viable);
		Assert.assertEquals("the inspect card", website, engine.sell);
	}

	/** The adjust nudge on a live sell keeps its clamp: an ask at what
	 *  buyers are paying is never told to come down to the website's
	 *  lower target. */
	@Test
	public void theAdjustNudgeNeverSaysToUndercutBuyers()
	{
		final Advisor.Quote q = quote(1900, 2000);
		final long website = Advisor.heldSellPrice(ITEM, q, underTheBid());
		Assert.assertTrue(website < 2000);
		Assert.assertEquals(2000, TradeEngine.sellTarget(website, q.high));
	}
}
