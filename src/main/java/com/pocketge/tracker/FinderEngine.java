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
 * Reliable 14-Day Margins and At 5D Highs/Lows aren't computed here: those
 * need a historical scan across many items (14 days of prints, or a 5-day
 * high/low per candidate), which would mean either a burst of extra
 * per-item timeseries calls on every advisor cycle or a much heavier
 * one-time scan — real cost for a client that's meant to sit in the
 * background during gameplay. pocketge.com's own versions of those two
 * stay methodology pages rather than live lists for the same reason.
 */
public final class FinderEngine
{
	private FinderEngine() {}

	private static final long LOW_VOL_THRESHOLD = 100_000;

	public static class Row
	{
		public int id;
		public long margin;  // gp, after tax — set for margin rows, 0 for mover rows
		public double pct;   // live mid vs 24h avg mid, percent — set for mover rows, 0 for margin rows
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
}
