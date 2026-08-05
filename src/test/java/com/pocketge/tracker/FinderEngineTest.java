package com.pocketge.tracker;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class FinderEngineTest
{
	private static Advisor.Quote quote(long high, long low)
	{
		Advisor.Quote q = new Advisor.Quote();
		q.high = high;
		q.low = low;
		return q;
	}

	private static AnalystRating.Average avg(long avgHigh, long avgLow, long hiVol, long loVol)
	{
		AnalystRating.Average a = new AnalystRating.Average();
		a.avgHighPrice = avgHigh;
		a.avgLowPrice = avgLow;
		a.highPriceVolume = hiVol;
		a.lowPriceVolume = loVol;
		return a;
	}

	@Test
	public void marginRows_splitsByVolumeTier()
	{
		Map<Integer, Advisor.Quote> quotes = new HashMap<>();
		quotes.put(1, quote(1100, 1000)); // margin ~78 after tax, high vol
		quotes.put(2, quote(2200, 2000)); // margin ~156 after tax, low vol
		Map<Integer, AnalystRating.Average> averages = new HashMap<>();
		averages.put(1, avg(1050, 1050, 10, 10));
		averages.put(2, avg(2100, 2100, 10, 10));
		Map<Integer, Long> volumes = new HashMap<>();
		volumes.put(1, 500_000L);  // high vol
		volumes.put(2, 5_000L);    // low vol

		List<FinderEngine.Row> highVol = FinderEngine.marginRows(quotes, averages, volumes, false);
		Assert.assertEquals(1, highVol.size());
		Assert.assertEquals(1, highVol.get(0).id);
		Assert.assertTrue(highVol.get(0).margin > 0);

		List<FinderEngine.Row> lowVol = FinderEngine.marginRows(quotes, averages, volumes, true);
		Assert.assertEquals(1, lowVol.size());
		Assert.assertEquals(2, lowVol.get(0).id);
	}

	@Test
	public void marginRows_requiresBothSidesRecentlyTraded()
	{
		Map<Integer, Advisor.Quote> quotes = new HashMap<>();
		quotes.put(1, quote(1100, 1000));
		Map<Integer, AnalystRating.Average> averages = new HashMap<>();
		averages.put(1, avg(1050, 1050, 0, 10)); // no high-side prints in the last 24h
		Map<Integer, Long> volumes = new HashMap<>();
		volumes.put(1, 500_000L);

		Assert.assertTrue(FinderEngine.marginRows(quotes, averages, volumes, false).isEmpty());
	}

	@Test
	public void loserRows_flagsAtLeastThreePercentDrop()
	{
		Map<Integer, Advisor.Quote> quotes = new HashMap<>();
		quotes.put(1, quote(970, 950));  // mid 960, down from avg mid 1000 -> -4%
		quotes.put(2, quote(995, 985));  // mid 990, down from avg mid 1000 -> -1%, shouldn't qualify
		Map<Integer, AnalystRating.Average> averages = new HashMap<>();
		averages.put(1, avg(1010, 990, 200_000, 200_000));
		averages.put(2, avg(1010, 990, 200_000, 200_000));
		Map<Integer, Long> volumes = new HashMap<>();
		volumes.put(1, 300_000L);
		volumes.put(2, 300_000L);

		List<FinderEngine.Row> out = FinderEngine.loserRows(quotes, averages, volumes);
		Assert.assertEquals(1, out.size());
		Assert.assertEquals(1, out.get(0).id);
		Assert.assertTrue(out.get(0).pct < -3);
	}

	@Test
	public void loserRows_ignoresThinVolume()
	{
		Map<Integer, Advisor.Quote> quotes = new HashMap<>();
		quotes.put(1, quote(970, 950));
		Map<Integer, AnalystRating.Average> averages = new HashMap<>();
		averages.put(1, avg(1010, 990, 500, 500)); // well under the 100k volume floor
		Map<Integer, Long> volumes = new HashMap<>();
		volumes.put(1, 1_000L);

		Assert.assertTrue(FinderEngine.loserRows(quotes, averages, volumes).isEmpty());
	}
}
