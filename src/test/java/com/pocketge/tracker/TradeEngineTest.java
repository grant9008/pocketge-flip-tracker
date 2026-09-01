package com.pocketge.tracker;

import java.util.Random;
import org.junit.Assert;
import org.junit.Test;

public class TradeEngineTest
{
	private static TradeEngine.Series syntheticSeries(long now, int n, double baseLow, double baseHigh, long seed)
	{
		Random rnd = new Random(seed);
		TradeEngine.Series s = new TradeEngine.Series();
		s.labels = new long[n];
		s.low = new double[n];
		s.high = new double[n];
		s.lowVol = new double[n];
		s.highVol = new double[n];
		double low = baseLow, high = baseHigh;
		for (int i = 0; i < n; i++)
		{
			low += rnd.nextInt(3) - 1;
			high += rnd.nextInt(3) - 1;
			if (high < low + 40)
			{
				high = low + 40; // keep a real spread — 2% tax must stay clearable
			}
			s.labels[i] = now - (long) (n - 1 - i) * 300L;
			s.low[i] = low;
			s.high[i] = high;
			s.lowVol[i] = 50 + rnd.nextInt(200);
			s.highVol[i] = 50 + rnd.nextInt(200);
		}
		return s;
	}

	@Test
	public void nullSeriesFallsBackToRawQuote()
	{
		TradeEngine.Result r = TradeEngine.compute(836, 845, 1_000_000L, 1_000_000L, null, 1618);
		Assert.assertFalse(r.viable);
		Assert.assertEquals(836, r.buy);
		Assert.assertEquals(845, r.sell);
		Assert.assertTrue(r.lowConf);
	}

	@Test
	public void tooFewBucketsFallsBackToRawQuote()
	{
		TradeEngine.Series series = syntheticSeries(1_000_000L, 5, 800, 820, 42);
		TradeEngine.Result r = TradeEngine.compute(805, 815, 1_000_000L, 1_000_000L, series, 1618);
		Assert.assertFalse(r.viable);
		Assert.assertEquals(805, r.buy);
		Assert.assertEquals(815, r.sell);
	}

	@Test
	public void viableSeriesProducesSensibleFillableSpread()
	{
		int n = 200;
		long now = 1_000_000L;
		TradeEngine.Series series = syntheticSeries(now, n, 800, 850, 7);
		TradeEngine.Result r = TradeEngine.compute((long) series.low[n - 1], (long) series.high[n - 1], now, now, series, 1618);
		Assert.assertTrue(r.viable);
		Assert.assertTrue(r.buy > 0);
		Assert.assertTrue(r.sell > r.buy);
		Assert.assertTrue(r.edge >= TradeEngine.minEdge(r.buy));
	}

	@Test
	public void minEdgeScalesWithPriceAndHasAFloor()
	{
		Assert.assertEquals(2, TradeEngine.minEdge(100));
		Assert.assertTrue(TradeEngine.minEdge(1_000_000) > 2);
	}

	/* ── sellTarget / buyTarget: never quote the wrong side of the book ───
	   Regression cover for the live Yew logs case: insta-buy was 115, the
	   engine returned a 107/111 flip pair, and the offer screen told the
	   player to SELL at 111 — 4 gp under a standing bid, and disagreeing
	   with both pocketge.com and Flipping Copilot on the same screen. */

	@Test
	public void sellTarget_neverQuotesUnderTheStandingBid()
	{
		Assert.assertEquals(115L, TradeEngine.sellTarget(111L, 115L)); // the Yew logs case
	}

	@Test
	public void sellTarget_leavesAHigherAskAlone()
	{
		// Asking above the bid is the normal patient sell — not clamped down.
		Assert.assertEquals(120L, TradeEngine.sellTarget(120L, 115L));
		Assert.assertEquals(115L, TradeEngine.sellTarget(115L, 115L));
	}

	@Test
	public void sellTarget_withoutAQuoteChangesNothing()
	{
		Assert.assertEquals(111L, TradeEngine.sellTarget(111L, 0L));
		Assert.assertEquals(111L, TradeEngine.sellTarget(111L, -1L));
	}

	@Test
	public void buyTarget_neverBidsOverTheStandingAsk()
	{
		Assert.assertEquals(112L, TradeEngine.buyTarget(120L, 112L));
	}

	@Test
	public void buyTarget_leavesALowerBidAlone()
	{
		// Bidding under the ask is the normal patient buy — not clamped up.
		Assert.assertEquals(111L, TradeEngine.buyTarget(111L, 112L));
		Assert.assertEquals(112L, TradeEngine.buyTarget(112L, 112L));
	}

	@Test
	public void buyTarget_withoutAQuoteChangesNothing()
	{
		Assert.assertEquals(120L, TradeEngine.buyTarget(120L, 0L));
	}

	@Test
	public void clampedPairStillLeavesTheSpreadIntact()
	{
		// Whatever the engine picked, the clamped pair must never cross:
		// buy <= insta-sell <= insta-buy <= sell.
		final long rawLow = 112, rawHigh = 115;
		long b = TradeEngine.buyTarget(107L, rawLow);
		long s = TradeEngine.sellTarget(111L, rawHigh);
		Assert.assertTrue("buy " + b + " must not exceed insta-sell " + rawLow, b <= rawLow);
		Assert.assertTrue("sell " + s + " must not undercut insta-buy " + rawHigh, s >= rawHigh);
		Assert.assertTrue("clamped pair crossed: buy " + b + " >= sell " + s, b < s);
	}
}
