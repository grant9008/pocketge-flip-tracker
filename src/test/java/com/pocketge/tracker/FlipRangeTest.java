package com.pocketge.tracker;

import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 * The flip list under the stats shows the range picked above them: "if you
 * have 1h youd see the flips you made the last 1h. if you have all time,
 * then youd see all the flips youve ever made".
 */
public class FlipRangeTest
{
	private static final long HOUR = 3_600_000L;
	private static final long NOW = 1_000 * HOUR;

	private static Flip flip(long closedAt, long token)
	{
		return new Flip(token, closedAt - 60_000L, closedAt, 1621, "Uncut emerald", 100, 31_700, 33_400, 700);
	}

	private static List<Flip> flips()
	{
		final List<Flip> l = new ArrayList<>();
		l.add(flip(NOW - 40 * 24 * HOUR, 1)); // over a month ago
		l.add(flip(NOW - 3 * HOUR, 2));
		l.add(flip(NOW - 30 * 60_000L, 3));
		l.add(flip(NOW - 5 * 60_000L, 4));
		return l;
	}

	@Test
	public void eachRangeListsItsOwnFlips()
	{
		Assert.assertEquals(2, FlipStats.inRange(flips(), FlipStats.Range.H1, NOW, NOW - 2 * HOUR).size());
		Assert.assertEquals(3, FlipStats.inRange(flips(), FlipStats.Range.H4, NOW, NOW - 2 * HOUR).size());
		Assert.assertEquals("the session started two hours ago",
			2, FlipStats.inRange(flips(), FlipStats.Range.SESSION, NOW, NOW - 2 * HOUR).size());
		Assert.assertEquals("all of them", 4, FlipStats.inRange(flips(), FlipStats.Range.ALL, NOW, NOW - 2 * HOUR).size());
	}

	/** The list and the stats above it count the same flips. */
	@Test
	public void theListAndTheStatsAgree()
	{
		for (FlipStats.Range r : FlipStats.Range.values())
		{
			final List<Flip> all = flips();
			final FlipStats.Stats s = FlipStats.compute(all, r, NOW, NOW - 2 * HOUR, all.get(0).closedAt, 0);
			Assert.assertEquals(r.toString(), s.flipCount, Flip.byTrade(FlipStats.inRange(all, r, NOW, NOW - 2 * HOUR)).size());
		}
	}

	@Test
	public void theCountSaysWhichRange()
	{
		Assert.assertEquals("2 flips in the last hour", HistoryPanel.countText(2, FlipStats.Range.H1));
		Assert.assertEquals("1 flip this session", HistoryPanel.countText(1, FlipStats.Range.SESSION));
		Assert.assertEquals("4 flips recorded", HistoryPanel.countText(4, FlipStats.Range.ALL));
		Assert.assertEquals("No flips in the last 4 hours", HistoryPanel.countText(0, FlipStats.Range.H4));
		Assert.assertEquals("No flips recorded yet", HistoryPanel.countText(0, FlipStats.Range.ALL));
	}
}
