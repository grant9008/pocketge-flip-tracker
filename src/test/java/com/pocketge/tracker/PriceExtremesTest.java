package com.pocketge.tracker;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

/**
 * The badge rules, pinned against pocketge.com's dayState(). Every threshold
 * here is one the website also enforces, so a change that quietly drifts the
 * plugin away from the site fails a test rather than showing the user two
 * different answers for the same item.
 */
public class PriceExtremesTest
{
	/** 1000-1200: a 20% range, comfortably past every range floor. */
	private static PriceExtremes fiveDay(long lo, long hi)
	{
		final PriceExtremes ex = new PriceExtremes();
		ex.lo5d = lo;
		ex.hi5d = hi;
		return ex;
	}

	@Test
	public void nothingAtAllWhenNoExtremesAreKnown()
	{
		assertEquals(PriceExtremes.Tier.NONE, new PriceExtremes().tier(1000, 990));
	}

	@Test
	public void topEightPercentOfTheFiveDayRangeIsAFiveDayHigh()
	{
		// Range 200 wide; 8% band = the top 16 gp, so 1184 and up.
		assertEquals(PriceExtremes.Tier.HIGH_5D, fiveDay(1000, 1200).tier(1184, 1100));
		assertEquals(PriceExtremes.Tier.NONE, fiveDay(1000, 1200).tier(1183, 1100));
	}

	@Test
	public void bottomEightPercentOfTheFiveDayRangeIsAFiveDayLow()
	{
		assertEquals(PriceExtremes.Tier.LOW_5D, fiveDay(1000, 1200).tier(1100, 1016));
		assertEquals(PriceExtremes.Tier.NONE, fiveDay(1000, 1200).tier(1100, 1017));
	}

	@Test
	public void aNearlyFlatItemNeverFlags()
	{
		/* 1000-1020 is a 2% range, under the 3% floor — without it, an item
		   that has not moved all week flags every time it ticks one gp. */
		final PriceExtremes flat = fiveDay(1000, 1020);
		assertEquals(PriceExtremes.Tier.NONE, flat.tier(1020, 1000));
	}

	@Test
	public void theDayTierNeedsThePriceToActuallyReachTheDaysExtreme()
	{
		final PriceExtremes ex = new PriceExtremes();
		ex.lo1d = 1000;
		ex.hi1d = 1100; // a 10% day range, past the 4% floor
		assertEquals(PriceExtremes.Tier.HIGH_1D, ex.tier(1100, 1050));
		assertEquals(PriceExtremes.Tier.HIGH_1D, ex.tier(1101, 1050)); // broke out
		assertEquals(PriceExtremes.Tier.NONE, ex.tier(1099, 1050));    // merely close
		assertEquals(PriceExtremes.Tier.LOW_1D, ex.tier(1050, 1000));
		assertEquals(PriceExtremes.Tier.NONE, ex.tier(1050, 1001));
	}

	@Test
	public void aQuietDayNeverFlagsEvenAtItsExactEdge()
	{
		final PriceExtremes ex = new PriceExtremes();
		ex.lo1d = 1000;
		ex.hi1d = 1030; // 3% — under the day tier's stricter 4% floor
		assertEquals(PriceExtremes.Tier.NONE, ex.tier(1030, 1000));
	}

	@Test
	public void aWiderWindowMakesTheBandEasierToLandIn_whichIsWhyThereIsNoThirtyDayTier()
	{
		/* This is the arithmetic that killed the 30-day badge, pinned so it
		   cannot be reintroduced by the same mistaken reasoning.

		   The band is 8% OF THE RANGE, so it scales WITH the range: a wide
		   window has a wide band and is EASIER to sit inside, not harder. A
		   price of 1,080 is inside the bottom band of the wide range and
		   nowhere near the narrow one, at the same instant, for the same
		   item. Stacked as a tier above 5-day it therefore fired on nearly
		   every row and buried the tier underneath it. */
		final PriceExtremes wide = fiveDay(1000, 2000);   // band = bottom 80gp
		final PriceExtremes narrow = fiveDay(1000, 1100); // band = bottom 8gp
		assertEquals(PriceExtremes.Tier.LOW_5D, wide.tier(1500, 1080));
		assertEquals(PriceExtremes.Tier.NONE, narrow.tier(1050, 1080));
	}

	@Test
	public void multiDayTiersOutrankTheDayTier()
	{
		final PriceExtremes ex = fiveDay(1000, 1200);
		ex.lo1d = 1000;
		ex.hi1d = 1190;
		// 1190 meets the day high AND sits in the 5-day top band.
		assertEquals(PriceExtremes.Tier.HIGH_5D, ex.tier(1190, 1100));
	}

	@Test
	public void anUnknownLivePriceCannotFlag()
	{
		/* A missing quote reads as 0, and 0 is below every low ever recorded.
		   Without the >0 guards that would badge the whole watchlist "at a
		   5-day low" the moment a price fetch came back empty. */
		final PriceExtremes ex = fiveDay(1000, 1200);
		ex.lo1d = 1000;
		ex.hi1d = 1100;
		assertEquals(PriceExtremes.Tier.NONE, ex.tier(0, 0));
	}

	@Test
	public void aMissingDayWindowDoesNotSuppressTheFiveDayAnswer()
	{
		/* The 1h fetch fills both windows at once, but a short series (a
		   newly tradeable item) can leave the day window empty. The 5-day
		   answer still has to come through. */
		final PriceExtremes ex = fiveDay(1000, 1200);
		ex.hi1d = 0;
		ex.lo1d = 0;
		assertEquals(PriceExtremes.Tier.HIGH_5D, ex.tier(1190, 1100));
	}

	@Test
	public void highAndLowAreJudgedOnTheirOwnSidesOfTheBook()
	{
		/* The insta-buy decides highs and the insta-sell decides lows, never
		   crossed. Each case puts exactly ONE side in its band and parks the
		   other mid-range, so a crossed comparison changes the answer. */
		final PriceExtremes ex = fiveDay(1000, 1200);
		assertEquals(PriceExtremes.Tier.HIGH_5D, ex.tier(1190, 1100));
		assertEquals(PriceExtremes.Tier.LOW_5D, ex.tier(1100, 1010));
	}

	@Test
	public void aWideSpreadTouchingBothEndsReadsAsAHigh()
	{
		/* An illiquid item can have an ask in the top band and a bid in the
		   bottom one at the same time. The website resolves that by testing
		   the high side first (app.js dayState), and the plugin has to agree
		   or the same item wears different badges in the two places. */
		final PriceExtremes ex = fiveDay(1000, 1200);
		assertEquals(PriceExtremes.Tier.HIGH_5D, ex.tier(1190, 1010));
	}
}
