package com.pocketge.tracker;

import org.junit.Assert;
import org.junit.Test;

/**
 * How many 5-day extremes requests one advisor cycle may send.
 *
 * Each item is due an hour after its last fetch. Fifteen a cycle covers any
 * list within the hour on the short intervals, but on a two- or eight-hour
 * interval fifteen a cycle meant the same fifteen items every time and the
 * rest of the list never — so the cap has to grow with the interval.
 */
public class ExtremesPacingTest
{
	private static final long MIN = 60_000;

	@Test
	public void fifteenOnTheShortIntervals()
	{
		Assert.assertEquals(15, PocketGeTrackerPlugin.extremesPerCycle(100, MIN));
		Assert.assertEquals(15, PocketGeTrackerPlugin.extremesPerCycle(100, 5 * MIN));
	}

	@Test
	public void neverMoreThanTheListHolds()
	{
		Assert.assertEquals(4, PocketGeTrackerPlugin.extremesPerCycle(4, 5 * MIN));
		Assert.assertEquals(0, PocketGeTrackerPlugin.extremesPerCycle(0, 8 * 60 * MIN));
	}

	/** Thirty minutes: half the list a cycle, so all of it each hour. */
	@Test
	public void halfTheListOnHalfHourCycles()
	{
		Assert.assertEquals(50, PocketGeTrackerPlugin.extremesPerCycle(100, 30 * MIN));
	}

	/** An interval longer than the hour has to cover the whole list each
	 *  time, which is what the old single-timer pass always sent. */
	@Test
	public void theWholeListOnLongIntervals()
	{
		Assert.assertEquals(100, PocketGeTrackerPlugin.extremesPerCycle(100, 2 * 60 * MIN));
		Assert.assertEquals(100, PocketGeTrackerPlugin.extremesPerCycle(100, 8 * 60 * MIN));
	}
}
