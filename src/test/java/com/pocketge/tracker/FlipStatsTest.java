package com.pocketge.tracker;

import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class FlipStatsTest
{
	private static final long NOW = 1_000_000_000_000L; // arbitrary epoch millis

	@Test
	public void windowFiltersOutOlderFlips()
	{
		List<Flip> flips = new ArrayList<>();
		flips.add(new Flip(NOW - 2 * 3_600_000L, 1, "A", 10, 9_000, 10_200, 200));  // 2h ago, profit 1,000
		flips.add(new Flip(NOW - 10L * 24 * 3_600_000L, 2, "B", 10, 40_000, 45_500, 500)); // 10d ago, profit 5,000

		FlipStats.Stats h4 = FlipStats.compute(flips, FlipStats.Range.H4, NOW, NOW - 3_600_000L, NOW - 20L * 24 * 3_600_000L, 0);
		Assert.assertEquals(1, h4.flipCount);
		Assert.assertEquals(1_000L, h4.profit);

		FlipStats.Stats w1 = FlipStats.compute(flips, FlipStats.Range.W1, NOW, NOW - 3_600_000L, NOW - 20L * 24 * 3_600_000L, 0);
		Assert.assertEquals(1, w1.flipCount); // the 10-day-old flip falls outside a 1-week window

		FlipStats.Stats all = FlipStats.compute(flips, FlipStats.Range.ALL, NOW, NOW - 3_600_000L, NOW - 20L * 24 * 3_600_000L, 0);
		Assert.assertEquals(2, all.flipCount);
		Assert.assertEquals(6_000L, all.profit);
	}

	@Test
	public void roiIsProfitOverBuySpent()
	{
		List<Flip> flips = new ArrayList<>();
		flips.add(new Flip(NOW - 3_600_000L, 1, "A", 10, 9_000, 10_200, 200)); // profit 1,000 / spent 9,000
		FlipStats.Stats s = FlipStats.compute(flips, FlipStats.Range.H4, NOW, NOW, NOW, 0);
		Assert.assertEquals(1_000.0 / 9_000 * 100, s.roiPct, 0.0001);
	}

	@Test
	public void emptyWindowNeverDividesByZero()
	{
		FlipStats.Stats s = FlipStats.compute(new ArrayList<>(), FlipStats.Range.H1, NOW, NOW - 3_600_000L, NOW, 0);
		Assert.assertEquals(0.0, s.roiPct, 0.0);
		Assert.assertEquals(0, s.flipCount);
		Assert.assertEquals(0L, s.hourlyRate);
	}

	@Test
	public void unrealizedProfitPassesThroughUnchanged()
	{
		FlipStats.Stats s = FlipStats.compute(new ArrayList<>(), FlipStats.Range.ALL, NOW, NOW, NOW, -12_345L);
		Assert.assertEquals(-12_345L, s.unrealizedProfit);
	}

	@Test
	public void sessionRangeUsesSessionStart()
	{
		List<Flip> flips = new ArrayList<>();
		// one flip just before session start (shouldn't count), one just after (should)
		long sessionStart = NOW - 3_600_000L;
		flips.add(new Flip(sessionStart - 1_000L, 1, "Before", 1, 100, 200, 0));
		flips.add(new Flip(sessionStart + 1_000L, 2, "After", 1, 100, 300, 0));

		FlipStats.Stats s = FlipStats.compute(flips, FlipStats.Range.SESSION, NOW, sessionStart, NOW, 0);
		Assert.assertEquals(1, s.flipCount);
		Assert.assertEquals(200L, s.profit); // 300 - 0 - 100
	}
}
