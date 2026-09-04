package com.pocketge.tracker;

/**
 * Where an item is sitting inside its own recent trading range, over three
 * windows at once — the last day, the last 5 days, and the last 30.
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
	/** …in the last 30 days. NOT from the 1h series, which does not reach
	 *  that far; these need a second fetch at timestep=6h. Left at 0 when
	 *  that fetch was skipped or failed, which simply means no 30-day tier. */
	public long hi30d;
	public long lo30d;

	/** Which badge, if any, the item has earned. Ordered weakest to
	 *  strongest so a longer window always outranks a shorter one: being at
	 *  the edge of a 30-day range is a rarer, larger claim than being at the
	 *  edge of today's. */
	public enum Tier
	{
		NONE,
		HIGH_1D,
		LOW_1D,
		HIGH_5D,
		LOW_5D,
		HIGH_30D,
		LOW_30D;

		public boolean isHigh()
		{
			return this == HIGH_1D || this == HIGH_5D || this == HIGH_30D;
		}

		public boolean isLow()
		{
			return this == LOW_1D || this == LOW_5D || this == LOW_30D;
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
		if (inTopBand(high, hi30d, lo30d))
		{
			return Tier.HIGH_30D;
		}
		if (inBottomBand(low, hi30d, lo30d))
		{
			return Tier.LOW_30D;
		}
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
