package com.pocketge.tracker;

/**
 * Where a price sits inside the range an item has actually traded in over the
 * last 30 days, and how far it moved to get there.
 *
 * This is the one thing the buy ranking cannot see. A buy idea is scored
 * entirely on {@code (insta-buy - insta-sell - tax) x quantity}: today's
 * spread and how much of it you can take. Two items with the same spread rank
 * identically whether one has been flat for a month and the other has run 26%
 * in a week and is sitting on its high. The spread is real in both cases; the
 * gp you can lose getting out is not remotely the same.
 *
 * Deliberately a MEASUREMENT, not a verdict. It reports where the price has
 * been and refuses to say where it goes next — a 30-day window cannot support
 * a forecast, and a card that implied one would be worse than saying nothing.
 * A run-up is not automatically a bad flip: momentum is a real strategy, and
 * plenty of good trades happen at the top of a range. What the card owes you
 * is the fact that you are there.
 *
 * Pure and dependency-free so the arithmetic can be tested without a client
 * or a network — the same reason PriceExtremes is.
 *
 * <h2>Why 30 days and not the 15 the plugin already fetches</h2>
 * The plugin's existing per-item call is {@code timestep=1h}, which reaches
 * back about 15 days. It would have been free to widen that window instead.
 * It is not free in the way that matters: a recommended buy is essentially
 * never in the set that call covers (favourites plus the top 40 items by
 * volume), so EVERY design here costs one new per-item request per plan
 * position regardless of timestep. Given the request is being spent either
 * way, {@code timestep=6h} spends it on 30 days and on agreeing exactly with
 * the 30-DAY RANGE meter pocketge.com already shows for the same item —
 * rather than on 15 days and a number that visibly disagrees with the site.
 */
public class RangePosition
{
	/** Lowest and highest price seen in the window, 0 when unknown. */
	public long lo30;
	public long hi30;
	/** The oldest price in the window, for the "+26% in 30d" figure. 0 when
	 *  the series did not reach back far enough to have one. */
	public long openingPrice;

	/**
	 * A range narrower than this fraction of its own high is treated as no
	 * range at all.
	 *
	 * Without it, an item that has traded 100-101 all month reports a price of
	 * 101 as "100% of its 30-day range", which is arithmetically true and
	 * completely useless — every stable item on the list would wear the
	 * loudest possible version of this signal. Matches the spirit of
	 * PriceExtremes.MIN_RANGE_PCT, which exists for the same reason.
	 */
	public static final double MIN_RANGE_PCT = 0.05;

	/** At or above this share of the range, the card says so. Chosen to match
	 *  the top quarter, the same zone the website's own meter renders as the
	 *  high end. */
	public static final double HIGH_ZONE = 0.75;
	/** ...and the bottom quarter. */
	public static final double LOW_ZONE = 0.25;

	/**
	 * Build from a wiki timeseries response, already split into arrays.
	 *
	 * A pure static rather than a loop inside MarketClient on purpose: this is
	 * the arithmetic worth testing, and MarketClient is the one class in the
	 * plugin that cannot be tested at all without a network. The parse stays
	 * there; the maths lives here where a test can reach it.
	 *
	 * Buckets are the wiki's own averages, so a bucket contributes its high to
	 * the top of the range and its low to the bottom. Zero means "no print in
	 * this bucket" and is skipped on both sides — a quiet 6-hour window is not
	 * evidence that the item traded at 0.
	 */
	public static RangePosition fromBuckets(long[] timestamps, long[] avgHigh, long[] avgLow, long cutoffSec)
	{
		final RangePosition r = new RangePosition();
		if (timestamps == null || avgHigh == null || avgLow == null)
		{
			return r;
		}
		final int n = Math.min(timestamps.length, Math.min(avgHigh.length, avgLow.length));
		long lo = Long.MAX_VALUE, hi = 0;
		for (int i = 0; i < n; i++)
		{
			if (timestamps[i] < cutoffSec)
			{
				continue;
			}
			final long h = avgHigh[i];
			final long l = avgLow[i];
			if (h > hi)
			{
				hi = h;
			}
			if (l > 0 && l < lo)
			{
				lo = l;
			}
			/* The first in-window bucket with any print at all is the opening
			   price. The series arrives oldest-first (the same assumption
			   MarketClient.fetchTimeseries5m and the website's loadTS both
			   make), so the first one that qualifies is the oldest one. */
			if (r.openingPrice <= 0)
			{
				final long mid = h > 0 && l > 0 ? (h + l) / 2 : Math.max(h, l);
				if (mid > 0)
				{
					r.openingPrice = mid;
				}
			}
		}
		r.hi30 = hi;
		r.lo30 = lo < Long.MAX_VALUE ? lo : 0;
		return r;
	}

	/** True when the window is wide enough for "where in it" to mean
	 *  anything. */
	public boolean usable()
	{
		return lo30 > 0 && hi30 > lo30
			&& (hi30 - lo30) >= hi30 * MIN_RANGE_PCT;
	}

	/**
	 * Where {@code price} sits in the range, 0.0 at the low and 1.0 at the
	 * high, or -1 when there is no usable range.
	 *
	 * Clamped, because the price being measured is a LIVE quote while the
	 * range comes from completed 6-hour buckets: an item making a new high
	 * right now is legitimately above everything in the window, and reporting
	 * 108% would be both wrong-looking and unrenderable as a percentage of a
	 * bar.
	 */
	public double position(long price)
	{
		if (!usable() || price <= 0)
		{
			return -1;
		}
		final double raw = (price - lo30) / (double) (hi30 - lo30);
		return Math.max(0.0, Math.min(1.0, raw));
	}

	/**
	 * Percentage move across the window, or {@link Double#NaN} when the
	 * series had no opening price to measure from.
	 *
	 * NaN rather than 0: "this item did not move" and "we could not tell"
	 * are different statements, and only one of them belongs on a card.
	 */
	public double runPct(long price)
	{
		if (openingPrice <= 0 || price <= 0)
		{
			return Double.NaN;
		}
		return ((price - openingPrice) / (double) openingPrice) * 100.0;
	}

	/**
	 * The card's footnote line, or null when there is nothing worth saying.
	 *
	 * Null in the middle of the range on purpose. The line costs a row on a
	 * card that has had six things cut from it, and "this item is somewhere in
	 * the middle of its own range" is the default state of most items most of
	 * the time — it is not news, and printing it on every card is how a signal
	 * becomes wallpaper. It speaks at the edges, where being told is worth the
	 * pixels.
	 *
	 * The wording is flat and unqualified — a percentage and a move, no verb,
	 * no adjective, no arrow. "Overextended" or "risky" would be the plugin
	 * arguing with its own recommendation on the same card, which is exactly
	 * what got Analyst Rating removed.
	 */
	public String footnote(long price)
	{
		final double pos = position(price);
		if (pos < 0 || (pos < HIGH_ZONE && pos > LOW_ZONE))
		{
			return null;
		}
		final StringBuilder sb = new StringBuilder();
		sb.append(Math.round(pos * 100)).append("% of 30d range");
		final double run = runPct(price);
		if (!Double.isNaN(run) && Math.abs(run) >= 1.0)
		{
			sb.append(" · ").append(run >= 0 ? "+" : "")
				.append(Math.round(run)).append("% in 30d");
		}
		return sb.toString();
	}
}
