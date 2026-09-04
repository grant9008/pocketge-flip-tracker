package com.pocketge.tracker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pure logic (no RuneLite types — unit-testable) behind the plugin's "Find
 * Opportunities" panel — the same three live categories pocketge.com's
 * sidebar shows (High Vol Margins, Low Vol Margins, Biggest Losers 24H),
 * computed from data the advisor cycle already fetches every run
 * (fetchLatest/fetch24hAverages/fetchVolumes) — no extra API calls. Mirrors
 * the math in pocketge.com's finder-common.js exactly so the numbers agree
 * with the website.
 *
 * Reliable 14-Day Margins isn't computed here: it needs a 14-day historical
 * scan across many items, which would mean a much heavier one-time fetch
 * than anything else this file does — real cost for a client that's meant
 * to sit in the background during gameplay. pocketge.com's own version of
 * that one stays a methodology page rather than a live list for the same
 * reason. At 5D Highs/Lows below IS computed, but only across whatever
 * bounded candidate pool the caller already fetched 5-day extremes for
 * (see PocketGeTrackerPlugin.refreshDayExtremes) — never the whole item
 * universe.
 */
public final class FinderEngine
{
	private FinderEngine() {}

	private static final long LOW_VOL_THRESHOLD = 100_000;
	/** Same "near the extreme" definition as the Favorites list's own ▲/▼
	 *  5D badge (see PocketGeTrackerPlugin.refreshStatsAndFavorites) — kept
	 *  in sync by hand since that one runs inline against a single row
	 *  rather than through this file. */
	private static final double EXTREME_BAND_PCT = 0.08;
	private static final double EXTREME_MIN_RANGE_PCT = 0.03;

	public static class Row
	{
		public int id;
		public long margin;  // gp, after tax — set for margin rows, 0 otherwise
		public double pct;   // meaning depends on the list: mover rows use it as live-vs-24h-avg %; extreme rows use it as % distance from the 5D high/low (0 = at the exact extreme)
		public long vol;
	}

	/** High/Low Vol Margins — live insta-buy/insta-sell spread after tax,
	 *  split by daily volume tier. Both sides need a recent print (24h
	 *  volume > 0) and the margin can't be an absurd >70%-of-price outlier
	 *  (usually a stale/bad print), same filters as the website. */
	public static List<Row> marginRows(Map<Integer, Advisor.Quote> quotes, Map<Integer, AnalystRating.Average> averages,
		Map<Integer, Long> volumes, boolean wantLowVol)
	{
		final List<Row> out = new ArrayList<>();
		for (Map.Entry<Integer, Advisor.Quote> e : quotes.entrySet())
		{
			final int id = e.getKey();
			final Advisor.Quote q = e.getValue();
			if (q == null || !(q.high > 0) || !(q.low > 0) || q.high <= q.low)
			{
				continue;
			}
			final AnalystRating.Average avg = averages.get(id);
			if (avg == null || !(avg.highPriceVolume > 0) || !(avg.lowPriceVolume > 0))
			{
				continue;
			}
			final long margin = q.high - q.low - FlipTracker.taxPerItem(q.high, id);
			if (margin <= 0 || margin >= q.high * 0.7)
			{
				continue;
			}
			final long vol = volumes.getOrDefault(id, 0L);
			final boolean lowVol = vol < LOW_VOL_THRESHOLD;
			if (lowVol != wantLowVol)
			{
				continue;
			}
			final Row r = new Row();
			r.id = id;
			r.margin = margin;
			r.vol = vol;
			out.add(r);
		}
		out.sort((a, b) -> Long.compare(b.margin, a.margin));
		return out;
	}

	/** Biggest Losers (24H) — live mid price vs its 24h volume-weighted
	 *  average mid, down at least 3%. Volume floor matches the low-vol
	 *  threshold so a single seller's dump on a thin item can't fake a
	 *  drop — the same reasoning as the website's own Biggest Losers list. */
	public static List<Row> loserRows(Map<Integer, Advisor.Quote> quotes, Map<Integer, AnalystRating.Average> averages,
		Map<Integer, Long> volumes)
	{
		final List<Row> out = new ArrayList<>();
		for (Map.Entry<Integer, Advisor.Quote> e : quotes.entrySet())
		{
			final int id = e.getKey();
			final Advisor.Quote q = e.getValue();
			if (q == null || !(q.high > 0) || !(q.low > 0))
			{
				continue;
			}
			final AnalystRating.Average avg = averages.get(id);
			if (avg == null || !(avg.avgHighPrice > 0) || !(avg.avgLowPrice > 0))
			{
				continue;
			}
			final double curMid = (q.high + q.low) / 2.0;
			final double avgMid = (avg.avgHighPrice + avg.avgLowPrice) / 2.0;
			if (!(avgMid > 0))
			{
				continue;
			}
			final double pct = (curMid - avgMid) / avgMid * 100.0;
			final long vol = volumes.getOrDefault(id, 0L);
			final long printVol = avg.highPriceVolume + avg.lowPriceVolume;
			if (curMid < 100 || printVol < LOW_VOL_THRESHOLD || pct > -3)
			{
				continue;
			}
			final Row r = new Row();
			r.id = id;
			r.pct = pct;
			r.vol = vol;
			out.add(r);
		}
		out.sort((a, b) -> Double.compare(a.pct, b.pct));
		return out;
	}

	/** At 5D Highs — live insta-sell price within EXTREME_BAND_PCT of its
	 *  own 5-day high, scanned across whatever candidate pool the caller
	 *  passes in {@code extremes} for (favorites plus a bounded top-volume
	 *  pool — see PocketGeTrackerPlugin.refreshDayExtremes). Sorted closest
	 *  to the high first. */
	public static List<Row> extremeHighRows(Map<Integer, Advisor.Quote> quotes, Map<Integer, PriceExtremes> extremes,
		Map<Integer, Long> volumes)
	{
		final List<Row> out = new ArrayList<>();
		for (Map.Entry<Integer, PriceExtremes> e : extremes.entrySet())
		{
			final int id = e.getKey();
			final PriceExtremes ex = e.getValue();
			final Advisor.Quote q = quotes.get(id);
			if (ex == null || q == null || !(q.high > 0) || !(ex.hi5d > 0) || !(ex.lo5d > 0))
			{
				continue;
			}
			final double range5d = ex.hi5d - ex.lo5d;
			if (range5d < ex.lo5d * EXTREME_MIN_RANGE_PCT)
			{
				continue; // near-flat item — a small wobble shouldn't count as "at the high"
			}
			final double distance = (ex.hi5d - q.high) / range5d;
			if (distance < 0 || distance > EXTREME_BAND_PCT)
			{
				continue;
			}
			final Row r = new Row();
			r.id = id;
			r.pct = distance * 100.0;
			r.vol = volumes.getOrDefault(id, 0L);
			out.add(r);
		}
		out.sort((a, b) -> Double.compare(a.pct, b.pct));
		return out;
	}

	/** At 5D Lows — the same definition as extremeHighRows, mirrored onto
	 *  the live insta-buy price against the 5-day low. */
	public static List<Row> extremeLowRows(Map<Integer, Advisor.Quote> quotes, Map<Integer, PriceExtremes> extremes,
		Map<Integer, Long> volumes)
	{
		final List<Row> out = new ArrayList<>();
		for (Map.Entry<Integer, PriceExtremes> e : extremes.entrySet())
		{
			final int id = e.getKey();
			final PriceExtremes ex = e.getValue();
			final Advisor.Quote q = quotes.get(id);
			if (ex == null || q == null || !(q.low > 0) || !(ex.hi5d > 0) || !(ex.lo5d > 0))
			{
				continue;
			}
			final double range5d = ex.hi5d - ex.lo5d;
			if (range5d < ex.lo5d * EXTREME_MIN_RANGE_PCT)
			{
				continue;
			}
			final double distance = (q.low - ex.lo5d) / range5d;
			if (distance < 0 || distance > EXTREME_BAND_PCT)
			{
				continue;
			}
			final Row r = new Row();
			r.id = id;
			r.pct = distance * 100.0;
			r.vol = volumes.getOrDefault(id, 0L);
			out.add(r);
		}
		out.sort((a, b) -> Double.compare(a.pct, b.pct));
		return out;
	}
}
