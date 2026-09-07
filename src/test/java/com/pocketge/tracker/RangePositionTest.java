package com.pocketge.tracker;

import org.junit.Assert;
import org.junit.Test;

public class RangePositionTest
{
	private static RangePosition of(long lo, long hi, long opening)
	{
		final RangePosition r = new RangePosition();
		r.lo30 = lo;
		r.hi30 = hi;
		r.openingPrice = opening;
		return r;
	}

	/** The case that prompted the whole thing: Sapphire necklace, a real
	 *  30 gp spread, sitting at the top of a range it climbed 26% to reach. */
	@Test
	public void theSapphireNecklaceCaseReadsAsNearItsHigh()
	{
		final RangePosition r = of(426, 580, 452);
		Assert.assertTrue(r.usable());
		Assert.assertEquals(0.929, r.position(569), 0.002);
		Assert.assertEquals(25.9, r.runPct(569), 0.1);
		Assert.assertEquals("93% of 30d range · +26% in 30d", r.footnote(569));
	}

	/** Silence is the default. A card that says something about every item
	 *  says nothing about any of them. */
	@Test
	public void theMiddleOfTheRangeSaysNothing()
	{
		final RangePosition r = of(426, 580, 500);
		Assert.assertNull(r.footnote(500));
		Assert.assertNull(r.footnote(470));
		Assert.assertNull(r.footnote(530));
	}

	@Test
	public void theBottomOfTheRangeAlsoSpeaks()
	{
		final RangePosition r = of(426, 580, 560);
		Assert.assertEquals("7% of 30d range · -22% in 30d", r.footnote(437));
	}

	/**
	 * A flat item must not report "100% of its range".
	 *
	 * Arithmetically a 100-101 month puts 101 at the very top. It is also
	 * meaningless, and without this guard every stable item on the list would
	 * wear the loudest version of the badge.
	 */
	@Test
	public void anItemThatHasBarelyMovedHasNoUsableRange()
	{
		final RangePosition flat = of(100, 101, 100);
		Assert.assertFalse(flat.usable());
		Assert.assertEquals(-1, flat.position(101), 0.0001);
		Assert.assertNull(flat.footnote(101));
	}

	/** Exactly at the 5% floor is still too narrow; comfortably past it is not. */
	@Test
	public void theRangeFloorIsFivePercentOfTheHigh()
	{
		Assert.assertFalse("a 4.9% range is noise", of(951, 1000, 960).usable());
		Assert.assertTrue("a 10% range is real", of(900, 1000, 950).usable());
	}

	/**
	 * A live quote can legitimately sit above every completed bucket in the
	 * window — the range is history, the price is now.
	 */
	@Test
	public void aNewHighClampsToOneHundredPercentRatherThanOverflowing()
	{
		final RangePosition r = of(426, 580, 452);
		Assert.assertEquals(1.0, r.position(640), 0.0001);
		Assert.assertTrue(r.footnote(640).startsWith("100% of 30d range"));
	}

	/** "It did not move" and "we could not tell" are different claims. */
	@Test
	public void aMissingOpeningPriceIsNotAZeroPercentMove()
	{
		final RangePosition r = of(426, 580, 0);
		Assert.assertTrue(Double.isNaN(r.runPct(569)));
		Assert.assertEquals("no move to report, so the line carries only the position",
			"93% of 30d range", r.footnote(569));
	}

	/** A move too small to matter is left off rather than printed as +0%. */
	@Test
	public void aTinyMoveIsOmittedFromTheLine()
	{
		final RangePosition r = of(426, 580, 566);
		Assert.assertEquals("93% of 30d range", r.footnote(569));
	}

	@Test
	public void noDataMeansNoClaim()
	{
		final RangePosition empty = new RangePosition();
		Assert.assertFalse(empty.usable());
		Assert.assertNull(empty.footnote(569));
		Assert.assertEquals(-1, empty.position(569), 0.0001);
	}

	/* ── fromBuckets ─────────────────────────────────────────────────────── */

	@Test
	public void bucketsOutsideTheWindowAreIgnoredEntirely()
	{
		final long[] ts   = {100, 200, 300, 400};
		final long[] high = {900, 580, 560, 570};   // 900 is the stale one
		final long[] low  = {880, 426, 540, 560};
		final RangePosition r = RangePosition.fromBuckets(ts, high, low, 200);
		Assert.assertEquals("the pre-window 900 high must not widen the range", 580, r.hi30);
		Assert.assertEquals(426, r.lo30);
		Assert.assertEquals("opening is the first bucket INSIDE the window", 503, r.openingPrice);
	}

	/**
	 * A 6-hour bucket with no trades reports zero. That is an absence, not a
	 * price of nothing — treating it as a low would drag every range to 0 and
	 * make every item look like it was sitting on its high.
	 */
	@Test
	public void emptyBucketsDoNotDragTheRangeToZero()
	{
		final long[] ts   = {100, 200, 300};
		final long[] high = {580, 0, 570};
		final long[] low  = {520, 0, 540};
		final RangePosition r = RangePosition.fromBuckets(ts, high, low, 0);
		Assert.assertEquals(580, r.hi30);
		Assert.assertEquals(520, r.lo30);
	}

	@Test
	public void noBucketsYieldsNoClaim()
	{
		Assert.assertFalse(RangePosition.fromBuckets(null, null, null, 0).usable());
		Assert.assertFalse(RangePosition.fromBuckets(new long[0], new long[0], new long[0], 0).usable());
	}

	/** The line has to survive a 182px card. Longest realistic shape. */
	@Test
	public void theLineStaysShort()
	{
		final String longest = of(426, 580, 452).footnote(569);
		Assert.assertTrue("footnote was " + longest.length() + " chars: " + longest,
			longest.length() <= 32);
	}
}
