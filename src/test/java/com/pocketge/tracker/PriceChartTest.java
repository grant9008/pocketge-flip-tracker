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
		Assert.assertEquals("a billion-gp axis keeps its decimals", "1.80367B", PriceChart.fmtYAxis(1_803_668_250L, 250));
		Assert.assertEquals("1.45000B", PriceChart.fmtYAxis(1_450_000_000L, 500));
		Assert.assertEquals("rounded as toFixed rounds the double", "2.93915M", PriceChart.fmtYAxis(2_939_155, 1));
		Assert.assertEquals("2.93916M", PriceChart.fmtYAxis(2_939_156, 1));
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

	/** The trend starts once both sides have printed, as the site's does:
	 *  never along one side's dots alone. */
	@Test
	public void trendWaitsForBothSides()
	{
		final long[] t = new long[30];
		final double[] lo = new double[30];
		final double[] hi = new double[30];
		for (int i = 0; i < 30; i++)
		{
			t[i] = i * 300L;
			lo[i] = 100;
			hi[i] = i < 4 ? 0 : 120;
		}
		final double[] ema = PriceChart.trend(PriceChart.prepare(series(t, lo, hi), 86_400L));
		for (int i = 0; i < 4; i++)
		{
			Assert.assertTrue("no line before the first high, at " + i, Double.isNaN(ema[i]));
		}
		Assert.assertEquals(110, ema[4], 1e-9);
		Assert.assertEquals(110, ema[29], 1e-9);
		final double[] oneSided = PriceChart.trend(PriceChart.prepare(series(t, lo, new double[30]), 86_400L));
		Assert.assertTrue("one side all window: no trend", java.util.Arrays.stream(oneSided).allMatch(Double::isNaN));
	}

	/** The tooltip names the mark under the cursor, a Period mark over a
	 *  Recent one, and shows a side that did not print as a dash. */
	@Test
	public void theTooltipNamesTheMarkAndBothSides()
	{
		final long[] t = new long[8];
		final double[] lo = {100, 98, 101, 102, 103, 104, 99, 105};
		final double[] hi = {110, 111, 120, 112, 113, 114, 0, 116};
		for (int i = 0; i < 8; i++)
		{
			t[i] = i * 300L;
		}
		final PriceChart.Data d = PriceChart.prepare(series(t, lo, hi), 86_400L);
		final List<String[]> low = PriceChart.tooltipRows(d, 1);
		Assert.assertEquals("Period Low", low.get(1)[0]);
		Assert.assertEquals("solid-buy", low.get(1)[1]);
		Assert.assertEquals("Period High", PriceChart.tooltipRows(d, 2).get(1)[0]);
		final List<String[]> recent = PriceChart.tooltipRows(d, 6);
		Assert.assertEquals("Recent Low", recent.get(1)[0]);
		Assert.assertEquals("hollow-buy", recent.get(1)[1]);
		Assert.assertEquals("Buy: 99", recent.get(2)[0]);
		Assert.assertEquals("a side with no print", "Sell: —", recent.get(3)[0]);
		Assert.assertEquals("Recent High", PriceChart.tooltipRows(d, 7).get(1)[0]);
		final List<String[]> plain = PriceChart.tooltipRows(d, 3);
		Assert.assertEquals("no mark: time, both sides, volume", 4, plain.size());
	}

	/** A tick per 40px of pane, but not five in a 100px one. */
	@Test
	public void ticksFitTheChatPane()
	{
		Assert.assertEquals(2, PriceChart.tickCount(96));
		Assert.assertEquals(5, PriceChart.tickCount(200));
		Assert.assertEquals(14, PriceChart.tickCount(900));
	}

	/** The chart keeps off its area's left, right and bottom edge — the
	 *  overlay panel's border — with no clip to lean on, in both layouts and
	 *  at narrow and wide prices: the hover's pills end inside. And with no
	 *  volume bars, a series' volume shows only as the profile in the plot:
	 *  the axis and the left gutter are the same with it as without. */
	@Test
	public void theChartLaysOutInsideItsArea()
	{
		final double[][][] prices = {
			{{100, 101}, {110, 112}},
			{{50_000, 50_500}, {51_000, 51_200}},
			{{15_000_000, 15_050_000}, {15_100_000, 15_120_000}},
		};
		for (Rectangle area : new Rectangle[]{new Rectangle(150, 140, 519, 122), new Rectangle(150, 140, 519, 320)})
		{
			for (double[][] p : prices)
			{
				final TradeEngine.Series two = series(new long[]{0, 300}, p[0], p[1]);
				final BufferedImage img = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
				final Graphics2D g = img.createGraphics();
				for (java.awt.Point mouse : new java.awt.Point[]{null, new java.awt.Point(300, 240),
					new java.awt.Point(160, 238), new java.awt.Point(area.x + 460, area.y + 10)})
				{
					PriceChart.paint(g, area, PriceChart.prepare(two, 86_400L), new PriceChart.Style(), new PriceChart.Lines(), mouse);
				}
				g.dispose();
				for (int y = 0; y < img.getHeight(); y++)
				{
					for (int x = 0; x < img.getWidth(); x++)
					{
						final boolean inside = x > area.x && x < area.x + area.width - 1
							&& y >= area.y && y < area.y + area.height - 1;
						if (!inside)
						{
							Assert.assertEquals("painted on or past the area's edge at " + x + "," + y + " for " + p[0][0],
								0, img.getRGB(x, y) >>> 24);
						}
					}
				}
			}

			/* The axis column and the left gutter are the same with the
			   volume as without it. */
			final TradeEngine.Series two = series(new long[]{0, 300}, new double[]{100, 101}, new double[]{110, 112});
			final TradeEngine.Series quiet = series(two.labels, two.low, two.high);
			quiet.lowVol = new double[2];
			quiet.highVol = new double[2];
			final BufferedImage withVol = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
			final BufferedImage without = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
			final Graphics2D g1 = withVol.createGraphics();
			PriceChart.paint(g1, area, PriceChart.prepare(two, 86_400L), new PriceChart.Style(), new PriceChart.Lines(), null);
			g1.dispose();
			final Graphics2D g2 = without.createGraphics();
			PriceChart.paint(g2, area, PriceChart.prepare(quiet, 86_400L), new PriceChart.Style(), new PriceChart.Lines(), null);
			g2.dispose();
			final int axisFrom = area.x + 8 + (area.width - 8 - 45) + 2;
			int gutter = 0;
			for (int y = area.y; y < area.y + area.height; y++)
			{
				for (int x = axisFrom; x < area.x + area.width; x++)
				{
					Assert.assertEquals("volume reached the axis at " + x + "," + y, without.getRGB(x, y), withVol.getRGB(x, y));
				}
				for (int x = area.x + 1; x < area.x + 8; x++)
				{
					gutter += withVol.getRGB(x, y) != without.getRGB(x, y) ? 1 : 0;
				}
			}
			Assert.assertEquals("no volume bars in the left gutter", 0, gutter);
		}
	}

	/** The overlay clips to the chat box it covers: nothing at all lands
	 *  outside it. */
	@Test
	public void theChartStaysInsideItsBox()
	{
		final BufferedImage img = new BufferedImage(800, 400, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = img.createGraphics();
		final Rectangle box = new Rectangle(150, 120, 519, 142);
		for (java.awt.Point mouse : new java.awt.Point[]{null, new java.awt.Point(300, 240), new java.awt.Point(160, 238)})
		{
			PriceChartOverlay.paint(g, box, "Twisted bow", null, PriceChart.Range.D1,
				PriceChart.prepare(series(new long[]{0, 300}, new double[]{100, 101}, new double[]{110, 112}), 86_400L),
				false, true, new PriceChart.Style(), new PriceChart.Lines(), mouse, false, new HashMap<>());
		}
		g.dispose();
		for (int y = 0; y < img.getHeight(); y++)
		{
			for (int x = 0; x < img.getWidth(); x++)
			{
				if (!box.contains(x, y))
				{
					Assert.assertEquals("painted outside the box at " + x + "," + y, 0, img.getRGB(x, y) >>> 24);
				}
			}
		}
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
	public void volumeIsBinnedByItsOwnSide()
	{
		final long[] t = {0, 300, 600, 900};
		final PriceChart.Data d = PriceChart.prepare(series(t,
			new double[]{100, 100, 100, 100}, new double[]{200, 200, 200, 200}), 86_400L);
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
		Assert.assertArrayEquals(new String[]{"5m", "1h", "6h", "24h"}, PriceChart.Range.D1.steps);
		Assert.assertArrayEquals(new String[]{"1h", "5m", "6h", "24h"}, PriceChart.Range.D5.steps);
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
			new java.awt.Point(200, 80), false, hit);
		Assert.assertEquals("the ranges and VP; no Fill", java.util.Set.of("D1", "D5", "M1", "M6", "Y1", "vp"), hit.keySet());
		Assert.assertTrue("the buttons sit in the header", hit.get("Y1").y < PriceChartOverlay.HEADER);
		Assert.assertTrue("1D left of 1Y", hit.get("D1").x < hit.get("Y1").x);
		Assert.assertTrue("VP right of the ranges", hit.get("vp").x > hit.get("Y1").x + hit.get("Y1").width);
		Assert.assertTrue("the grab strip stays above the buttons",
			PriceChartOverlay.gripOf(new Rectangle(0, 0, 506, 135)).getMaxY() <= hit.get("Y1").y);
		hit.clear();
		PriceChartOverlay.paint(g, new Rectangle(0, 0, 506, 135), "Revenant ether", null, PriceChart.Range.M1,
			null, true, false, new PriceChart.Style(), ln, null, true, hit);
		g.dispose();
	}

	/** The panel stands on the chat's bottom edge and grows upward to the
	 *  height dragged, never shorter than a readable chart nor past the top
	 *  of the game; 0 is the chat's own height. */
	@Test
	public void thePanelGrowsUpwardFromTheChat()
	{
		final Rectangle chat = new Rectangle(0, 360, 519, 142); // bottom at 502
		Assert.assertEquals("0: the chat's own box", chat, PriceChartOverlay.panelBox(chat, 0));
		final Rectangle tall = PriceChartOverlay.panelBox(chat, 300);
		Assert.assertEquals(new Rectangle(0, 202, 519, 300), tall);
		Assert.assertEquals("never shorter than the minimum", PriceChartOverlay.MIN_HEIGHT,
			PriceChartOverlay.panelBox(chat, 40).height);
		final Rectangle top = PriceChartOverlay.panelBox(chat, 5_000);
		Assert.assertEquals("never past the top of the game", PriceChartOverlay.TOP_MARGIN, top.y);
		Assert.assertEquals("and always on the chat's bottom edge", 502, top.y + top.height);
		final Rectangle small = new Rectangle(0, 400, 519, 90);
		Assert.assertEquals("a chat shorter than the minimum keeps its own height", 90,
			PriceChartOverlay.panelBox(small, 0).height);
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
