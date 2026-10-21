package com.pocketge.tracker;

import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/** The flip list's rolling window and its "Show all". */
public class RangeListTest
{
	private static final long HOUR = 3_600_000L;

	private static Flip flip(long closedAt)
	{
		return new Flip(closedAt, closedAt - 60_000L, closedAt, 1621, "Uncut emerald", 100, 31_700, 33_400, 700);
	}

	/** The list is redrawn when its oldest flip ages out of a rolling range. */
	@Test
	public void aRollingRangeKnowsWhenItsOldestFlipLeaves()
	{
		final List<Flip> in = List.of(flip(10 * HOUR), flip(10 * HOUR + 600_000L));
		Assert.assertEquals(11 * HOUR + 1, FlipStats.nextExpiry(in, FlipStats.Range.H1));
		Assert.assertEquals("session does not roll", 0, FlipStats.nextExpiry(in, FlipStats.Range.SESSION));
		Assert.assertEquals("nor does all time", 0, FlipStats.nextExpiry(in, FlipStats.Range.ALL));
		Assert.assertEquals("nothing listed, nothing to expire", 0, FlipStats.nextExpiry(List.of(), FlipStats.Range.H1));
	}

	@Test
	public void theLatestFiveThenTheRestOnRequest()
	{
		Assert.assertEquals(5, HistoryPanel.shown(30, false));
		Assert.assertEquals("Show all 30", HistoryPanel.moreText(30, false));
		Assert.assertEquals(30, HistoryPanel.shown(30, true));
		Assert.assertEquals("Show fewer", HistoryPanel.moreText(30, true));
		Assert.assertEquals("nothing to toggle", "", HistoryPanel.moreText(4, false));
		Assert.assertEquals(HistoryPanel.SHOW_ALL_CAP, HistoryPanel.shown(5_000, true));
	}
}
