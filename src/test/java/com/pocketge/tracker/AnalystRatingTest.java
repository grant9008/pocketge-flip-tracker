package com.pocketge.tracker;

import org.junit.Assert;
import org.junit.Test;

public class AnalystRatingTest
{
	private static Advisor.Quote quote(long low, long high)
	{
		Advisor.Quote q = new Advisor.Quote();
		q.low = low;
		q.high = high;
		return q;
	}

	private static AnalystRating.Average avg(long lo, long hi)
	{
		AnalystRating.Average a = new AnalystRating.Average();
		a.avgLowPrice = lo;
		a.avgHighPrice = hi;
		return a;
	}

	@Test
	public void atTypicalPriceIsHold()
	{
		AnalystRating.Grade g = AnalystRating.grade(quote(100, 100), avg(100, 100));
		Assert.assertEquals(AnalystRating.Label.HOLD, g.label);
		Assert.assertEquals(50, g.score);
	}

	@Test
	public void wellBelowTypicalIsStrongBuy()
	{
		AnalystRating.Grade g = AnalystRating.grade(quote(80, 80), avg(100, 100));
		Assert.assertEquals(AnalystRating.Label.STRONG_BUY, g.label);
		Assert.assertTrue(g.score >= 95);
	}

	@Test
	public void wellAboveTypicalIsStrongSell()
	{
		AnalystRating.Grade g = AnalystRating.grade(quote(120, 120), avg(100, 100));
		Assert.assertEquals(AnalystRating.Label.STRONG_SELL, g.label);
		Assert.assertTrue(g.score <= 5);
	}

	@Test
	public void scoreClampsAtBounds()
	{
		Assert.assertEquals(0, AnalystRating.grade(quote(1000, 1000), avg(100, 100)).score);
		Assert.assertEquals(100, AnalystRating.grade(quote(1, 1), avg(100, 100)).score);
	}

	@Test
	public void missingDataDegradesToNeutralHold()
	{
		Assert.assertEquals(AnalystRating.Label.HOLD, AnalystRating.grade(null, avg(100, 100)).label);
		Assert.assertEquals(AnalystRating.Label.HOLD, AnalystRating.grade(quote(100, 100), null).label);
	}
}
