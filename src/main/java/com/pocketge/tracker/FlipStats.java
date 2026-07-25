package com.pocketge.tracker;

import java.util.List;

/**
 * Pure time-range stats over completed flips (no RuneLite types —
 * unit-testable): total profit, flip count, ROI%, and an hourly profit rate,
 * for whichever window the user has selected in the panel — matching
 * Flipping Copilot's Session / 1h / 4h / 12h / 1d / 1w / 1m / All-time
 * dropdown, computed from data we already track (every {@link Flip} carries
 * its own close timestamp).
 */
public final class FlipStats
{
	private FlipStats() {}

	public enum Range
	{
		SESSION("Session"),
		H1("1h", 3_600_000L),
		H4("4h", 4 * 3_600_000L),
		H12("12h", 12 * 3_600_000L),
		D1("1d", 24 * 3_600_000L),
		W1("1w", 7 * 24 * 3_600_000L),
		M1("1m", 30L * 24 * 3_600_000L),
		ALL("All time");

		private final String label;
		private final long windowMillis; // 0 for SESSION/ALL (resolved separately)

		Range(String label) { this(label, 0); }
		Range(String label, long windowMillis) { this.label = label; this.windowMillis = windowMillis; }

		@Override public String toString() { return label; }
	}

	public static class Stats
	{
		public long profit;
		public long buySpent;
		public int flipCount;
		public double roiPct;      // profit / buySpent * 100, 0 if nothing bought in-window
		public long hourlyRate;    // gp/hr, extrapolated over the window's elapsed time
		public long unrealizedProfit;
	}

	/**
	 * @param nowMillis           current wall-clock time
	 * @param sessionStartMillis  when the current session began (for SESSION)
	 * @param firstFlipMillis     timestamp of the earliest known flip, or
	 *                            nowMillis if there is none (for ALL)
	 * @param unrealizedProfit    mark-to-market P/L on currently open buys,
	 *                            just passed through onto the result — it
	 *                            isn't itself time-windowed (it's a snapshot
	 *                            of right now, same for every range)
	 */
	public static Stats compute(List<Flip> flips, Range range, long nowMillis,
		long sessionStartMillis, long firstFlipMillis, long unrealizedProfit)
	{
		long windowStart;
		switch (range)
		{
			case SESSION:
				windowStart = sessionStartMillis;
				break;
			case ALL:
				windowStart = firstFlipMillis;
				break;
			default:
				windowStart = nowMillis - range.windowMillis;
		}

		Stats s = new Stats();
		s.unrealizedProfit = unrealizedProfit;
		if (flips == null)
		{
			return s;
		}
		for (Flip f : flips)
		{
			if (f.closedAt < windowStart)
			{
				continue;
			}
			s.profit += f.profit;
			s.buySpent += f.buySpent;
			s.flipCount++;
		}
		s.roiPct = s.buySpent > 0 ? (double) s.profit / s.buySpent * 100.0 : 0.0;

		long elapsedMs = Math.max(1, nowMillis - windowStart);
		double hours = elapsedMs / 3_600_000.0;
		s.hourlyRate = Math.round(s.profit / hours);
		return s;
	}
}
