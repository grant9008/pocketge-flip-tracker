package com.pocketge.tracker;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/**
 * The offer-screen chart is a port of pocketge.com's, so its rules are
 * held to the site's: the tick steps, the axis labels, which buckets are
 * drawn, where the marks go, how volume is grouped and binned.
 */
public class PriceChartTest
{
	private static TradeEngine.Series series(long[] t, double[] lo, double[] hi)
	{
		final TradeEngine.Series s = new TradeEngine.Series();
		s.labels = t;
		s.low = lo;
		s.high = hi;
		s.lowVol = new double[t.length];
		s.highVol = new double[t.length];
		for (int i = 0; i < t.length; i++)
		{
			s.lowVol[i] = lo[i] > 0 ? 10 : 0;
			s.highVol[i] = hi[i] > 0 ? 20 : 0;
		}
		return s;
	}

	/** niceTicks snaps the step DOWN to 5, 2.5 (tens and up), 2 or 1 —
	 *  and never under 1 gp. */
	@Test
	public void ticksStepAsTheSitesDo()
	{
		Assert.assertEquals(5, PriceChart.tickStep(150, 182, 5), 0); // 6.4 -> 5
		Assert.assertEquals(25, PriceChart.tickStep(0, 140, 5), 0); // 28 -> 25
		Assert.assertEquals(2, PriceChart.tickStep(0, 11, 5), 0); // 2.2 (under 10) -> 2
		Assert.assertEquals(1, PriceChart.tickStep(100, 101, 5), 0); // 0.2 -> never under 1 gp
		Assert.assertEquals(List.of(150L, 155L, 160L, 165L, 170L, 175L, 180L), PriceChart.niceTicks(148.2, 182.4, 5));
	}

	@Test
	public void axisLabelsAsTheSitesAre()
	{
		Assert.assertEquals("1,267", PriceChart.fmtYAxis(1267, 100));
		Assert.assertEquals("12.5K", PriceChart.fmtYAxis(12_500, 500));
		Assert.assertEquals("12K", PriceChart.fmtYAxis(12_000, 1_000));
		Assert.assertEquals("1.25M", PriceChart.fmtYAxis(1_250_000, 50_000));
		Assert.assertEquals("2.4B", PriceChart.fmtYAxis(2_400_000_000L, 100_000_000));
		Assert.assertEquals("crosshair: two places, trailing zeros trimmed", "12.5K", PriceChart.fmtYAxis(12_500, 0));
	}

	/** Buckets with no print on either side are dropped, and only the range
	 *  back from the newest bucket is kept. */
	@Test
	public void preparedDataIsTheRangesPrintsOnly()
	{
		final long day = 86_400L;
		final TradeEngine.Series s = series(
			new long[]{0, day / 2, day, day + 300, 2 * day},
			new double[]{100, 0, 101, 0, 103},
			new double[]{110, 0, 0, 0, 112});
		final PriceChart.Data d = PriceChart.prepare(s, day);
		Assert.assertArrayEquals("all-empty buckets gone, a day back from the newest",
			new long[]{day, 2 * day}, d.t);
		Assert.assertEquals(5, PriceChart.prints(series(new long[]{1, 2, 3, 4, 5, 6},
			new double[]{1, 1, 1, 0, 1, 1}, new double[]{0, 0, 0, 0, 0, 0})));
		Assert.assertTrue(PriceChart.usable(series(new long[]{1, 2, 3, 4, 5},
			new double[]{1, 1, 1, 1, 1}, new double[5])));
		Assert.assertFalse(PriceChart.usable(series(new long[]{1, 2, 3, 4},
			new double[]{1, 1, 1, 1}, new double[4])));
	}

	@Test
	public void periodMarksTheWholeWindowAndRecentItsLastQuarter()
	{
		final long[] t = new long[8];
		for (int i = 0; i < 8; i++)
		{
			t[i] = i * 300L;
		}
		final PriceChart.Data d = PriceChart.prepare(series(t,
			new double[]{90, 95, 96, 97, 98, 99, 94, 93},
			new double[]{120, 110, 111, 112, 113, 114, 116, 115}), 86_400L);
		Assert.assertArrayEquals("period: highest high at 0, lowest low at 0", new int[]{0, 0}, PriceChart.extremes(d, 0));
		Assert.assertEquals(6, PriceChart.recentStart(8));
		Assert.assertArrayEquals("recent: from index 6 on", new int[]{6, 7}, PriceChart.extremes(d, 6));
	}

	/** The trend is an EMA of the mid, a side carried over a gap. */
	@Test
	public void trendFollowsTheMid()
	{
		final long[] t = new long[30];
		final double[] lo = new double[30];
		final double[] hi = new double[30];
		for (int i = 0; i < 30; i++)
		{
			t[i] = i * 300L;
			lo[i] = 100;
			hi[i] = i == 10 ? 0 : 110; // one gap on the high side
		}
		final double[] ema = PriceChart.trend(PriceChart.prepare(series(t, lo, hi), 86_400L));
		Assert.assertEquals(105, ema[0], 1e-9);
		Assert.assertEquals("the missing high is carried", 105, ema[10], 1e-9);
		Assert.assertEquals(105, ema[29], 1e-9);
		Assert.assertTrue("two points draw no trend", Double.isNaN(PriceChart.trend(PriceChart.prepare(
			series(new long[]{0, 300}, new double[]{1, 1}, new double[]{2, 2}), 86_400L))[1]));
	}

	@Test
	public void volumeIsGroupedAndBinnedByItsOwnSide()
	{
		final long[] t = {0, 300, 600, 900};
		final PriceChart.Data d = PriceChart.prepare(series(t,
			new double[]{100, 100, 100, 100}, new double[]{200, 200, 200, 200}), 86_400L);
		final List<double[]> groups = PriceChart.volumeGroups(d, 7); // 14px apart: two points a bar
		Assert.assertEquals(2, groups.size());
		Assert.assertEquals(20, groups.get(0)[1], 1e-9);
		Assert.assertEquals(40, groups.get(0)[2], 1e-9);
		final double[] vp = PriceChart.volumeProfile(d, 90, 210, 12);
		// bands of 10 gp from 90: 100 lands in band 1, 200 in band 11
		Assert.assertEquals("the lows' volume where the lows are", 40, vp[1], 1e-9);
		Assert.assertEquals("the highs' volume where the highs are", 80, vp[11], 1e-9);
		Assert.assertEquals("and nowhere else", 120, java.util.Arrays.stream(vp).sum(), 1e-9);
	}

	/** The target and the offer join the scale when near the data; far off,
	 *  they are drawn pinned to the edge instead. */
	@Test
	public void linesNearTheDataJoinTheScale()
	{
		final long[] t = {0, 300, 600, 900, 1200};
		final PriceChart.Data d = PriceChart.prepare(series(t,
			new double[]{160, 161, 162, 161, 160}, new double[]{170, 171, 172, 171, 170}), 86_400L);
		final double[] plain = PriceChart.priceScale(d);
		final double[] withTarget = PriceChart.priceScale(d, 150L);
		Assert.assertTrue(withTarget[0] < 150 && withTarget[0] < plain[0]);
		final double[] far = PriceChart.priceScale(d, 10L);
		Assert.assertArrayEquals("a far line leaves the scale alone", plain, far, 1e-9);
	}

	@Test
	public void rangesFallBackAsTheSitesDo()
	{
		Assert.assertArrayEquals(new String[]{"5m", "1h", "6h"}, PriceChart.Range.D1.steps);
		Assert.assertArrayEquals(new String[]{"24h", "6h"}, PriceChart.Range.M6.steps);
		Assert.assertEquals(PriceChart.Range.D1, PriceChart.Range.of("nonsense"));
		Assert.assertEquals(PriceChart.Range.Y1, PriceChart.Range.of("Y1"));
	}

	/** The panel paints, with every button placed and clickable, and says
	 *  why when there is nothing to draw. */
	@Test
	public void thePanelPaintsItsButtons()
	{
		final BufferedImage img = new BufferedImage(520, 150, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = img.createGraphics();
		final long[] t = new long[40];
		final double[] lo = new double[40];
		final double[] hi = new double[40];
		for (int i = 0; i < 40; i++)
		{
			t[i] = i * 300L;
			lo[i] = 160 + i % 5;
			hi[i] = 170 + i % 5;
		}
		final Map<String, Rectangle> hit = new HashMap<>();
		final PriceChart.Lines ln = new PriceChart.Lines();
		ln.target = 164;
		ln.offer = 160;
		PriceChartOverlay.paint(g, new Rectangle(0, 0, 506, 135), "Revenant ether", null, PriceChart.Range.D1,
			PriceChart.prepare(series(t, lo, hi), 86_400L), false, true, new PriceChart.Style(), ln,
			new java.awt.Point(200, 80), hit);
		Assert.assertEquals(java.util.Set.of("D1", "D5", "M1", "M6", "Y1", "fill", "vp"), hit.keySet());
		Assert.assertTrue("the buttons sit in the header", hit.get("Y1").y < PriceChartOverlay.HEADER);
		Assert.assertTrue("1D left of 1Y", hit.get("D1").x < hit.get("Y1").x);
		Assert.assertTrue("Fill right of the ranges", hit.get("fill").x > hit.get("Y1").x + hit.get("Y1").width);
		hit.clear();
		PriceChartOverlay.paint(g, new Rectangle(0, 0, 506, 135), "Revenant ether", null, PriceChart.Range.M1,
			null, true, false, new PriceChart.Style(), ln, null, hit);
		g.dispose();
	}

	/** The chart's target line and the offer screen's card come from one
	 *  helper: the engine's target clamped to the live book. */
	@Test
	public void theTargetIsTheOfferScreensOwn()
	{
		final Advisor.Quote q = new Advisor.Quote();
		q.low = 171;
		q.high = 172;
		Assert.assertEquals("no engine: the live print", 171, PocketGeTrackerPlugin.offerScreenPrice(q, null, true));
		Assert.assertEquals(172, PocketGeTrackerPlugin.offerScreenPrice(q, null, false));
		final TradeEngine.Result e = new TradeEngine.Result(164, 169, true, "", 0, false);
		Assert.assertEquals("buy: the engine's, under the ask", 164, PocketGeTrackerPlugin.offerScreenPrice(q, e, true));
		Assert.assertEquals("sell: never under the standing bid", 172, PocketGeTrackerPlugin.offerScreenPrice(q, e, false));
		Assert.assertEquals(0, PocketGeTrackerPlugin.offerScreenPrice(null, e, true));
	}

	@Test
	public void freshnessFollowsTheBucketSize()
	{
		Assert.assertEquals(5 * 60_000L, PocketGeTrackerPlugin.chartTtlMs("5m"));
		Assert.assertEquals(30 * 60_000L, PocketGeTrackerPlugin.chartTtlMs("1h"));
		Assert.assertEquals(2 * 3_600_000L, PocketGeTrackerPlugin.chartTtlMs("6h"));
		Assert.assertEquals(6 * 3_600_000L, PocketGeTrackerPlugin.chartTtlMs("24h"));
	}
}
