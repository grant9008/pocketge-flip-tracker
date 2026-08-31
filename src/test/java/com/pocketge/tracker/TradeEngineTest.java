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
}
