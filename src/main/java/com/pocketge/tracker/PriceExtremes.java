package com.pocketge.tracker;

/**
 * Where an item is sitting inside its own recent trading range, over two
 * windows at once — the last day and the last 5 days.
 *
 * This is the plugin's half of pocketge.com's ▲/▼ badge system, ported so the
 * two agree. The website computes the same thing in app.js's dayState(); the
 * thresholds and comparisons below are that function's, verbatim, because a
 * badge that fires in the browser and not in the client (or the other way
 * round) is worse than no badge — you cannot tell which one is lying.
 *
 * Deliberately free of RuneLite and okhttp imports, like {@link FinderEngine}:
 * the numbers arrive from {@link MarketClient} but the judgement about what
 * they mean is plain arithmetic, and plain arithmetic should be unit-testable
 * without a game client on the classpath.
 */
public class PriceExtremes
{
	/** Highest and lowest hourly VWAP seen in the last 24 hours. */
	public long hi1d;
	public long lo1d;
	/** …in the last 5 days. Same 1-hour series — the wiki's 1h timeseries
	 *  reaches back about 15 days, so one fetch covers both windows. */
	public long hi5d;
	public long lo5d;
	/** Which badge, if any, the item has earned.
	 *
	 *  There is deliberately no 30-day tier, and the reason is worth keeping:
	 *  a percentage band does NOT get rarer as the window widens, it gets
	 *  commoner. The band is 8% OF THE RANGE, so a 30-day range of 1,000gp
	 *  gives an 80gp band where a 5-day range of 100gp gives an 8gp one —
	 *  ten times easier to land in. A 30-day tier stacked on top therefore
	 *  fired on nearly every row and buried the 5-day one underneath it.
	 *  The website has only these two tiers, and this is why. */
	public enum Tier
	{
		NONE,
		HIGH_1D,
		LOW_1D,
		HIGH_5D,
		LOW_5D;

		public boolean isHigh()
		{
			return this == HIGH_1D || this == HIGH_5D;
		}

		public boolean isLow()
		{
			return this == LOW_1D || this == LOW_5D;
		}
	}

	/** Multi-day tiers fire anywhere in the outermost 8% of the range. A
	 *  multi-day extreme is a slow thing — insisting on touching it exactly
	 *  would mean the badge blinks off while the item is still obviously
	 *  parked at the top of its range. */
	private static final double BAND = 0.08;
	/** …but only when the range is worth having an opinion about. Without
	 *  this a near-flat item flashes on a one-gp wiggle, since 8% of almost
	 *  nothing is nothing. */
	private static final double MIN_RANGE_PCT = 0.03;
	/** The day tier is STRICT instead: the live price must actually meet or
	 *  beat the day's most extreme hourly bucket. The website tried a fuzzy
	 *  1.5% band here first and had to abandon it — intraday, almost every
	 *  favourite hangs around near its own daily edge, so nearly every row
	 *  wore an arrow all the time and the badge stopped meaning anything. */
	private static final double MIN_RANGE_PCT_1D = 0.04;

	/**
	 * @param high the live insta-buy price — what the item sells FOR right
	 *             now, hence the one to compare against range highs
	 * @param low  the live insta-sell price, compared against range lows
	 */
	public Tier tier(long high, long low)
	{
		if (inTopBand(high, hi5d, lo5d))
		{
			return Tier.HIGH_5D;
		}
		if (inBottomBand(low, hi5d, lo5d))
		{
			return Tier.LOW_5D;
		}
		/* Day tier last, and only when the day's range is at least 4% wide —
		   see MIN_RANGE_PCT_1D. */
		if (hi1d > 0 && lo1d > 0 && hi1d - lo1d >= lo1d * MIN_RANGE_PCT_1D)
		{
			if (high > 0 && high >= hi1d)
			{
				return Tier.HIGH_1D;
			}
			if (low > 0 && low <= lo1d)
			{
				return Tier.LOW_1D;
			}
		}
		return Tier.NONE;
	}

	private static boolean usableRange(long hi, long lo)
	{
		return hi > 0 && lo > 0 && hi - lo >= lo * MIN_RANGE_PCT;
	}

	private static boolean inTopBand(long high, long hi, long lo)
	{
		return usableRange(hi, lo) && high > 0 && (hi - high) / (double) (hi - lo) <= BAND;
	}

	private static boolean inBottomBand(long low, long hi, long lo)
	{
		return usableRange(hi, lo) && low > 0 && (low - lo) / (double) (hi - lo) <= BAND;
	}
}
