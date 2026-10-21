package com.pocketge.tracker;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The pocketge.com price chart, drawn with Java2D.
 *
 * A port of drawChart in the site's app.js, not a lookalike: the same
 * dots for each side's prints fading with age, the same Period and Recent
 * marks, the same EMA trend, target line and badge, volume bars and
 * volume-by-price profile, the same tick and label rules — so a chart in
 * the game and the page beside it say the same thing in the same way.
 * The site line each rule comes from is cited beside it.
 *
 * What it leaves out, and why: zoom and pan (the in-game chart is a
 * glance, not a workbench), the 5Y range (its data comes from a second
 * host the plugin does not otherwise talk to), and the legend (the site
 * hides it below 640px, and this is narrower than that).
 *
 * Pure: everything is worked out from the data and painted into the
 * rectangle given, so it can be tested and rendered off the client.
 */
final class PriceChart
{
	/**
	 * The range buttons, and the Wiki timesteps each draws from, first
	 * choice first — seriesForView (app.js:4767-4775) without the history
	 * source. The first step with at least five prints is used, else the
	 * first with any.
	 */
	enum Range
	{
		D1("1D", 86_400L, "5m", "1h", "6h", "24h"),
		D5("5D", 432_000L, "1h", "5m", "6h", "24h"),
		M1("1M", 2_592_000L, "6h", "24h", "1h"),
		M6("6M", 15_552_000L, "24h", "6h"),
		Y1("1Y", 31_536_000L, "24h");

		final String label;
		final long periodSec;
		final String[] steps;

		Range(String label, long periodSec, String... steps)
		{
			this.label = label;
			this.periodSec = periodSec;
			this.steps = steps;
		}

		static Range of(String name)
		{
			for (Range r : values())
			{
				if (r.name().equals(name))
				{
					return r;
				}
			}
			return D1;
		}
	}

	// ---- colours the site paints with (app.css, app.js) --------------------

	static final Color TEXT_MAIN = new Color(0xD9, 0xD3, 0xC7);
	static final Color TEXT_MUTED = new Color(0x8A, 0x82, 0x74);
	static final Color POSITIVE = new Color(0x10, 0xB9, 0x81);
	static final Color NEGATIVE = new Color(0xEF, 0x53, 0x50);

	private static final Color GRID_H = new Color(255, 255, 255, Math.round(0.05f * 255));
	private static final Color GRID_V = new Color(255, 255, 255, Math.round(0.03f * 255));
	private static final Color TREND_CASING = new Color(8, 10, 14, Math.round(0.55f * 255));
	private static final Color BADGE_PLATE = new Color(19, 17, 16, Math.round(0.92f * 255));
	private static final Color CROSSHAIR = new Color(255, 255, 255, Math.round(0.35f * 255));
	private static final Color PILL = new Color(244, 241, 232, Math.round(0.97f * 255));
	private static final Color PILL_EDGE = new Color(10, 9, 8, Math.round(0.9f * 255));
	private static final Color PILL_TEXT = new Color(0x0B, 0x0E, 0x14);
	private static final Color TIP_BG = new Color(10, 9, 8, Math.round(0.97f * 255));
	private static final Color TIP_EDGE = new Color(0x3C, 0x35, 0x2B);
	private static final Color TIP_RULE = new Color(0x2B, 0x26, 0x21);
	private static final Color SAME_PRICE = new Color(0xC2, 0xCB, 0xDC);

	private static final double DOT_ALPHA_OLD = 0.40;
	private static final double DOT_ALPHA_NEW = 1.0;

	/** A dashed line, the site's setLineDash([4,4]). */
	private static Stroke dashed(float width)
	{
		return new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{4f, 4f}, 0f);
	}

	private static Color alpha(Color c, double a)
	{
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) Math.round(Math.max(0, Math.min(1, a)) * 255));
	}

	// ---- data ------------------------------------------------------------

	/** One range's points, oldest first. A side with no print is 0. */
	static final class Data
	{
		final long[] t;
		final double[] lo;
		final double[] hi;
		final double[] loVol;
		final double[] hiVol;

		Data(long[] t, double[] lo, double[] hi, double[] loVol, double[] hiVol)
		{
			this.t = t;
			this.lo = lo;
			this.hi = hi;
			this.loVol = loVol;
			this.hiVol = hiVol;
		}

		int size()
		{
			return t.length;
		}
	}

	/** Prints in a series: buckets with a price on at least one side. */
	static int prints(TradeEngine.Series s)
	{
		if (s == null || s.labels == null)
		{
			return 0;
		}
		int n = 0;
		for (int i = 0; i < s.labels.length; i++)
		{
			if (s.low[i] > 0 || s.high[i] > 0)
			{
				n++;
			}
		}
		return n;
	}

	/** Enough to draw: the site takes the first source with five. */
	static boolean usable(TradeEngine.Series s)
	{
		return prints(s) >= 5;
	}

	/**
	 * A series as one range draws it: buckets with no print on either side
	 * dropped (seriesFromTS, app.js:4729), and only the last {@code period}
	 * seconds back from the newest bucket kept (filterSeries, 4748-4757).
	 * Index-spaced from here on, as the site's x axis is.
	 */
	static Data prepare(TradeEngine.Series s, long periodSec)
	{
		if (s == null || s.labels == null)
		{
			return new Data(new long[0], new double[0], new double[0], new double[0], new double[0]);
		}
		long newest = Long.MIN_VALUE;
		for (int i = 0; i < s.labels.length; i++)
		{
			if (s.low[i] > 0 || s.high[i] > 0)
			{
				newest = Math.max(newest, s.labels[i]);
			}
		}
		final List<Integer> keep = new ArrayList<>();
		for (int i = 0; i < s.labels.length; i++)
		{
			if ((s.low[i] > 0 || s.high[i] > 0) && s.labels[i] >= newest - periodSec)
			{
				keep.add(i);
			}
		}
		keep.sort((a, b) -> Long.compare(s.labels[a], s.labels[b]));
		final int n = keep.size();
		final Data d = new Data(new long[n], new double[n], new double[n], new double[n], new double[n]);
		for (int j = 0; j < n; j++)
		{
			final int i = keep.get(j);
			d.t[j] = s.labels[i];
			d.lo[j] = Math.max(0, s.low[i]);
			d.hi[j] = Math.max(0, s.high[i]);
			d.loVol[j] = s.lowVol != null ? Math.max(0, s.lowVol[i]) : 0;
			d.hiVol[j] = s.highVol != null ? Math.max(0, s.highVol[i]) : 0;
		}
		return d;
	}

	// ---- the site's maths --------------------------------------------------

	/** A bucket's mid, one side standing in for the other (app.js:4798-4802). */
	static double mid(double lo, double hi)
	{
		if (lo > 0 && hi > 0)
		{
			return (lo + hi) / 2;
		}
		return lo > 0 ? lo : hi;
	}

	/** Change from the first mid to the last, in percent (pctChange, 4787-4796). */
	static double pctChange(Data d)
	{
		double first = 0;
		double last = 0;
		for (int i = 0; i < d.size(); i++)
		{
			final double m = mid(d.lo[i], d.hi[i]);
			if (m > 0)
			{
				if (first == 0)
				{
					first = m;
				}
				last = m;
			}
		}
		return first > 0 ? (last - first) / first * 100 : 0;
	}

	/** Tick values on a price axis (niceTicks, 4828-4849). */
	static List<Long> niceTicks(double min, double max, int count)
	{
		final List<Long> out = new ArrayList<>();
		final double range = max - min;
		if (!(range > 0) || count <= 0)
		{
			return out;
		}
		final double step = tickStep(min, max, count);
		for (double v = Math.ceil(min / step) * step; v <= max + 1e-9; v += step)
		{
			final long r = Math.round(v);
			if (r >= min - 1e-9 && r <= max + 1e-9 && (out.isEmpty() || out.get(out.size() - 1) != r))
			{
				out.add(r);
			}
		}
		return out;
	}

	/**
	 * How many ticks to ask niceTicks for: one per 40px of price pane, at
	 * most 14 (app.js:8605). The site's floor is five, but its chart is
	 * never under about 230px tall; in the ~100px chat pane five, snapped
	 * down, came out at up to 13 labels 8px apart. So the floor here is two.
	 */
	static int tickCount(double hPrice)
	{
		return Math.max(2, Math.min(14, (int) Math.round(hPrice / 40)));
	}

	/** The step niceTicks uses: the base snaps DOWN to 5, 2.5 (from tens
	 *  up only), 2 or 1, and is never under 1 gp. */
	static double tickStep(double min, double max, int count)
	{
		final double rawStep = (max - min) / count;
		final double mag = Math.pow(10, Math.floor(Math.log10(rawStep)));
		final double norm = rawStep / mag;
		final double base = norm >= 5 ? 5 : norm >= 2.5 && mag >= 10 ? 2.5 : norm >= 2 ? 2 : 1;
		return Math.max(1, base * mag);
	}

	/**
	 * A price-axis label (fmtYAxis, 1418-1444): whole gp under 10,000;
	 * otherwise K, M or B with the fewest decimals (at most 5) that make
	 * the tick step whole, so a label is the gridline's own value. Rounded
	 * as toFixed rounds: the double's exact value, halves up.
	 */
	static String fmtYAxis(double v, double step)
	{
		final double a = Math.abs(v);
		if (a < 10_000)
		{
			return String.format(Locale.US, "%,d", Math.round(v));
		}
		final double unit = a >= 1e9 ? 1e9 : a >= 1e6 ? 1e6 : 1e3;
		final String suffix = unit == 1e9 ? "B" : unit == 1e6 ? "M" : "K";
		int dec = 0;
		if (step > 0)
		{
			final double su = step / unit;
			while (dec < 5 && Math.abs(su * Math.pow(10, dec) - Math.round(su * Math.pow(10, dec))) > 1e-9)
			{
				dec++;
			}
		}
		else
		{
			dec = a / unit < 100 ? 2 : 0;
		}
		String s = new java.math.BigDecimal(v / unit).setScale(dec, java.math.RoundingMode.HALF_UP).toPlainString();
		if (step <= 0 && s.contains("."))
		{
			s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
		}
		return s + suffix;
	}

	/** The data's lowest and highest print, {min, max}, or null for none. */
	static double[] dataExtent(Data d)
	{
		double min = Double.MAX_VALUE;
		double max = -Double.MAX_VALUE;
		for (int i = 0; i < d.size(); i++)
		{
			for (double v : new double[]{d.lo[i], d.hi[i]})
			{
				if (v > 0)
				{
					min = Math.min(min, v);
					max = Math.max(max, v);
				}
			}
		}
		return min > max ? null : new double[]{min, max};
	}

	/**
	 * The price scale: the data's extent, widened to take in each line that
	 * is near it (the target joins on the site, app.js:8519-8524), then 5%
	 * either side, ±1 when flat (fitPriceRange, 4387-4392).
	 */
	static double[] priceScale(Data d, long... lines)
	{
		final double[] ext = dataExtent(d);
		if (ext == null)
		{
			return new double[]{0, 1};
		}
		double min = ext[0];
		double max = ext[1];
		for (long v : lines)
		{
			if (v > 0 && nearData(v, ext[0], ext[1]))
			{
				min = Math.min(min, v);
				max = Math.max(max, v);
			}
		}
		if (max - min < 1e-9)
		{
			return new double[]{min - 1, max + 1};
		}
		final double pad = (max - min) * 0.05;
		return new double[]{min - pad, max + pad};
	}

	/** Whether a line belongs on the scale: within the data's own span, or
	 *  8% of its top, of the data (app.js:4384-4386). */
	static boolean nearData(double v, double dataMin, double dataMax)
	{
		final double slack = Math.max(dataMax - dataMin, dataMax * 0.08);
		return v >= dataMin - slack && v <= dataMax + slack;
	}

	/**
	 * The trend: an EMA of the mid, each side carried forward over a gap,
	 * the pen lifted after a long run with neither (app.js:9065-9103). It
	 * starts once both sides have printed. NaN where there is no line.
	 */
	static double[] trend(Data d)
	{
		final int n = d.size();
		final double[] out = new double[n];
		java.util.Arrays.fill(out, Double.NaN);
		if (n <= 2)
		{
			return out;
		}
		final int period = Math.max(2, (int) Math.round(n * 0.08));
		final double k = 2.0 / (period + 1);
		final int carryMax = Math.max(3, (int) Math.round(n * 0.02));
		double lastLo = Double.NaN;
		double lastHi = Double.NaN;
		double ema = Double.NaN;
		int missing = 0;
		for (int i = 0; i < n; i++)
		{
			if (d.lo[i] > 0)
			{
				lastLo = d.lo[i];
			}
			if (d.hi[i] > 0)
			{
				lastHi = d.hi[i];
			}
			if (d.lo[i] <= 0 && d.hi[i] <= 0)
			{
				if (++missing > carryMax)
				{
					lastLo = Double.NaN;
					lastHi = Double.NaN;
					ema = Double.NaN;
				}
				continue;
			}
			missing = 0;
			if (Double.isNaN(lastLo) || Double.isNaN(lastHi))
			{
				continue; // nothing to average yet
			}
			final double m = (lastLo + lastHi) / 2;
			ema = Double.isNaN(ema) ? m : m * k + ema * (1 - k);
			out[i] = ema;
		}
		return out;
	}

	/** Index of the highest high and lowest low from {@code from} on, or
	 *  -1 for a side with none: {hiIdx, loIdx}. Period marks use the whole
	 *  window, Recent marks its last quarter (app.js:8575-8585). */
	static int[] extremes(Data d, int from)
	{
		int hiIdx = -1;
		int loIdx = -1;
		for (int i = Math.max(0, from); i < d.size(); i++)
		{
			if (d.hi[i] > 0 && (hiIdx < 0 || d.hi[i] > d.hi[hiIdx]))
			{
				hiIdx = i;
			}
			if (d.lo[i] > 0 && (loIdx < 0 || d.lo[i] < d.lo[loIdx]))
			{
				loIdx = i;
			}
		}
		return new int[]{hiIdx, loIdx};
	}

	static int recentStart(int n)
	{
		return (int) Math.floor(n * 0.75);
	}

	/** Volume bars: groups about 14px apart, each {centreIndex, lowSum,
	 *  highSum} (drawVolumeBars, 8715-8736). */
	static List<double[]> volumeGroups(Data d, double stepX)
	{
		final int groupSize = Math.max(1, (int) Math.round(14 / Math.max(0.5, stepX)));
		final List<double[]> out = new ArrayList<>();
		for (int i = 0; i < d.size(); i += groupSize)
		{
			final int end = Math.min(d.size(), i + groupSize);
			double lo = 0;
			double hi = 0;
			for (int j = i; j < end; j++)
			{
				lo += d.loVol[j];
				hi += d.hiVol[j];
			}
			out.add(new double[]{i + (end - i - 1) / 2.0, lo, hi, groupSize});
		}
		return out;
	}

	/** Volume by price: {@code buckets} bands over [vmin, vmax], each
	 *  print adding its own side's volume (app.js:8791-8800). */
	static double[] volumeProfile(Data d, double vmin, double vmax, int buckets)
	{
		final double[] out = new double[buckets];
		final double span = vmax - vmin;
		if (!(span > 0))
		{
			return out;
		}
		for (int i = 0; i < d.size(); i++)
		{
			addToProfile(out, d.hi[i], d.hiVol[i], vmin, span);
			addToProfile(out, d.lo[i], d.loVol[i], vmin, span);
		}
		return out;
	}

	private static void addToProfile(double[] out, double price, double vol, double vmin, double span)
	{
		if (price <= 0 || vol <= 0)
		{
			return;
		}
		final double f = (price - vmin) / span;
		if (f < 0 || f > 1)
		{
			return;
		}
		out[Math.min(out.length - 1, (int) (f * out.length))] += vol;
	}

	// ---- painting ----------------------------------------------------------

	/** Which colours, and the two switches the site has under its chart. */
	static final class Style
	{
		Color buy = new Color(0xE5, 0xB8, 0x42);
		Color sell = new Color(0x26, 0xA9, 0xAB);
		/** The spread band, off by default on the site. */
		boolean fill;
		/** The volume-by-price profile, on by default. */
		boolean vp = true;
	}

	/** The horizontal lines: the target for the offer's side, and the
	 *  price set in the offer. 0 for none. */
	static final class Lines
	{
		long target;
		boolean targetBuy;
		long offer;
	}

	/** Where the plot ended up, for a caller that wants to place things. */
	static final class Layout
	{
		int x0;
		int y0;
		int w;
		int h;
		double stepX;
	}

	/**
	 * Draw the chart into {@code area}. {@code mouse} (in the same
	 * coordinates, or null) drives the crosshair and tooltip.
	 */
	static Layout paint(Graphics2D g0, Rectangle area, Data d, Style st, Lines ln, Point mouse)
	{
		final Graphics2D g = (Graphics2D) g0.create();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			return paintInto(g, area, d, st, ln != null ? ln : new Lines(), mouse);
		}
		finally
		{
			g.dispose();
		}
	}

	private static Layout paintInto(Graphics2D g, Rectangle area, Data d, Style st, Lines ln, Point mouse)
	{
		final int W = area.width;
		final int H = area.height;
		/* Layout (app.js:8470-8477): axis on the right, 8px in from the
		   left, the bottom 20px for time labels and the hover's time pill.
		   The top pad is smaller than the site's 14px: the header sits
		   directly above. */
		final int axisW = W < 480 ? 38 : 45;
		final Layout lay = new Layout();
		lay.x0 = area.x + 8;
		lay.y0 = area.y + 6;
		lay.w = W - 8 - axisW;
		lay.h = H - 6 - 20;
		final int x0 = lay.x0;
		final int y0 = lay.y0;
		final int w = lay.w;
		final int h = lay.h;
		final Font axisFont = new Font(Font.SANS_SERIF, Font.PLAIN, W >= 800 ? 12 : 10);
		g.setFont(axisFont);
		final FontMetrics fm = g.getFontMetrics();

		final int n = d.size();
		if (n == 0)
		{
			drawCentred(g, area, "No trade data for this timeframe — try a longer one.", TEXT_MUTED, 12);
			return lay;
		}

		/* Panes (8567-8571): under 290px tall, volume sits behind the
		   prices at 28% of the height; taller, it gets its own pane. */
		final boolean volOverlay = h < 290;
		final double hPrice = volOverlay ? h : h * (1 - 0.16);
		final double hVol = volOverlay ? h * 0.28 : h * 0.16;
		final double y0Vol = volOverlay ? y0 + h - hVol : y0 + hPrice;

		final double dataW = w - 12;
		final double stepX = n > 1 ? dataW / (n - 1) : 0;
		lay.stepX = stepX;
		/* The target joins the scale when it is near the data, and so does
		   the price in the offer. */
		final double[] scale = priceScale(d, ln.target, ln.offer);
		final double vmin = scale[0];
		final double vmax = scale[1];
		final double lo = vmin;
		final double hi = vmax;
		final java.util.function.DoubleUnaryOperator ypx = v -> y0 + (1 - (v - lo) / (hi - lo)) * hPrice;
		final java.util.function.IntToDoubleFunction xpx = i -> x0 + i * stepX;

		// 1. price grid and labels (8597-8620)
		final int count = tickCount(hPrice);
		final double step = tickStep(vmin, vmax, count);
		g.setStroke(new BasicStroke(1f));
		String prevTick = null;
		double prevTickY = Double.NaN;
		for (long v : niceTicks(vmin, vmax, count))
		{
			final double y = ypx.applyAsDouble(v);
			g.setColor(GRID_H);
			g.draw(new Line2D.Double(x0, Math.round(y) + 0.5, x0 + w, Math.round(y) + 0.5));
			/* A label that would touch the one below it, or repeat it (a
			   billion-gp axis past five decimals), is left to its gridline. */
			final String label = fmtYAxis(v, step);
			if (label.equals(prevTick) || Math.abs(prevTickY - y) < fm.getAscent() + 2)
			{
				continue;
			}
			g.setColor(TEXT_MUTED);
			g.drawString(label, x0 + w + 6, (float) (y + fm.getAscent() / 2.0 - 1));
			prevTick = label;
			prevTickY = y;
		}

		// 2. time grid and labels (8623-8680)
		final long span = d.t[n - 1] - d.t[0];
		final DateTimeFormatter fmt = DateTimeFormatter.ofPattern(
			span <= 2 * 86_400L ? "HH:mm" : span <= 180 * 86_400L ? "MMM d" : "MMM yy", Locale.US);
		final int linesX = Math.min(n, Math.max(3, Math.min(9, (int) Math.round(dataW / 170))));
		final int xStep = Math.max(1, n / Math.max(1, linesX));
		String prevLabel = null;
		double prevRight = Double.NEGATIVE_INFINITY;
		for (int i = 0; i < n; i += xStep)
		{
			final double x = xpx.applyAsDouble(i);
			g.setColor(GRID_V);
			g.draw(new Line2D.Double(Math.round(x) + 0.5, y0, Math.round(x) + 0.5, y0 + h));
			final String label = fmt.format(Instant.ofEpochSecond(d.t[i]).atZone(ZoneId.systemDefault()));
			if (label.equals(prevLabel))
			{
				continue;
			}
			final int lw = fm.stringWidth(label);
			final double half = lw / 2.0;
			final double cx = Math.max(area.x + half + 2, Math.min(area.x + W - half - 2, x));
			if (cx - half < prevRight + 6)
			{
				continue;
			}
			g.setColor(TEXT_MUTED);
			g.drawString(label, (float) (cx - half), y0 + h + 4 + fm.getAscent());
			prevLabel = label;
			prevRight = cx + half;
		}

		/* With a handful of points each volume bar is most of the point
		   spacing wide. They stop at the plot's right edge, off the price
		   labels; on the left they run into the 8px gutter as on the site,
		   whose canvas is their only clip, short of the panel's 1px border.
		   One row above y0Vol holds a full stacked bar's 1px gap. */
		final Rectangle volClip = new Rectangle(area.x + 1, (int) Math.floor(y0Vol) - 1,
			x0 + w - area.x - 1, (int) Math.ceil(hVol) + 2);

		// 3. volume behind the prices, in overlay mode (8770)
		final Shape oldClip = g.getClip();
		if (volOverlay)
		{
			g.clip(volClip);
			drawVolume(g, d, st, xpx, stepX, y0Vol, hVol, 0.16);
			g.setClip(oldClip);
		}

		g.clip(new Rectangle(x0 - 1, y0 - 9, w + 2, (int) hPrice + 12));

		// 4. volume by price, behind everything else in the clip (8791-8898)
		if (st.vp)
		{
			drawProfile(g, d, vmin, vmax, x0, y0, w, hPrice, ypx);
		}

		// 5. the spread band (8904-8930)
		if (st.fill)
		{
			drawFill(g, d, st, xpx, ypx);
		}

		/* 6. dots, each side followed by its Period orb: highs, then lows
		   over them (8947-8982, 9055-9056) */
		final double dotR = Math.max(1.5, Math.min(2.4, stepX * 0.42));
		final int[] period = extremes(d, 0);
		drawDots(g, d, d.hi, st.sell, xpx, ypx, dotR);
		orb(g, period[0] >= 0 ? xpx.applyAsDouble(period[0]) : Double.NaN,
			period[0] >= 0 ? ypx.applyAsDouble(d.hi[period[0]]) : Double.NaN, st.sell);
		drawDots(g, d, d.lo, st.buy, xpx, ypx, dotR);
		orb(g, period[1] >= 0 ? xpx.applyAsDouble(period[1]) : Double.NaN,
			period[1] >= 0 ? ypx.applyAsDouble(d.lo[period[1]]) : Double.NaN, st.buy);

		// 7. trend (9065-9159)
		drawTrend(g, d, xpx, ypx, x0, w);

		// 8. recent rings, after the trend (9161-9174)
		final int[] recent = extremes(d, recentStart(n));
		if (recent[0] >= 0)
		{
			ring(g, xpx.applyAsDouble(recent[0]), ypx.applyAsDouble(d.hi[recent[0]]), st.sell);
		}
		if (recent[1] >= 0)
		{
			ring(g, xpx.applyAsDouble(recent[1]), ypx.applyAsDouble(d.lo[recent[1]]), st.buy);
		}
		g.setClip(oldClip);

		// 9. volume in its own pane, when there is room for one (9183-9191)
		if (!volOverlay)
		{
			g.clip(volClip);
			drawVolume(g, d, st, xpx, stepX, y0Vol, hVol, 0.34);
			g.setClip(oldClip);
			g.setColor(new Color(255, 255, 255, Math.round(0.10f * 255)));
			g.draw(new Line2D.Double(x0, Math.round(y0 + hPrice) + 0.5, x0 + w, Math.round(y0 + hPrice) + 0.5));
		}

		// 10. the target, and the price in the offer (9195-9278)
		if (ln.target > 0)
		{
			final Color c = ln.targetBuy ? st.buy : st.sell;
			priceLine(g, ln.target, "Target", c, vmin, vmax, ypx, x0, y0, w, hPrice, true);
		}
		if (ln.offer > 0)
		{
			priceLine(g, ln.offer, "Your offer", TEXT_MAIN, vmin, vmax, ypx, x0, y0, w, hPrice, false);
		}

		// 11. hover (9281-9331, tooltip 9380-9410)
		if (mouse != null && mouse.x >= x0 && mouse.x <= x0 + dataW && mouse.y >= y0 && mouse.y <= y0 + h)
		{
			final int i = (int) Math.max(0, Math.min(n - 1, Math.round((mouse.x - x0) / Math.max(1e-9, stepX))));
			drawHover(g, area, d, st, i, mouse, xpx.applyAsDouble(i), x0, y0, w, h, hPrice, vmin, vmax, axisFont);
		}
		return lay;
	}

	private static void drawDots(Graphics2D g, Data d, double[] v, Color c,
		java.util.function.IntToDoubleFunction xpx, java.util.function.DoubleUnaryOperator ypx, double r)
	{
		final int n = d.size();
		for (int i = 0; i < n; i++)
		{
			if (v[i] <= 0)
			{
				continue;
			}
			final double a = n > 1 ? DOT_ALPHA_OLD + (DOT_ALPHA_NEW - DOT_ALPHA_OLD) * i / (n - 1) : DOT_ALPHA_NEW;
			g.setColor(alpha(c, a));
			g.fill(new Ellipse2D.Double(xpx.applyAsDouble(i) - r, ypx.applyAsDouble(v[i]) - r, r * 2, r * 2));
		}
	}

	/** Period mark: a solid orb with a white centre (8975-8980). */
	private static void orb(Graphics2D g, double x, double y, Color c)
	{
		if (Double.isNaN(x) || Double.isNaN(y))
		{
			return;
		}
		g.setColor(alpha(c, 0.9));
		g.fill(new Ellipse2D.Double(x - 7, y - 7, 14, 14));
		g.setColor(Color.WHITE);
		g.fill(new Ellipse2D.Double(x - 3, y - 3, 6, 6));
	}

	/** Recent mark: a hollow ring (9161-9166). */
	private static void ring(Graphics2D g, double x, double y, Color c)
	{
		g.setColor(c);
		g.setStroke(new BasicStroke(2.5f));
		g.draw(new Ellipse2D.Double(x - 7, y - 7, 14, 14));
		g.setStroke(new BasicStroke(1f));
	}

	private static void drawTrend(Graphics2D g, Data d, java.util.function.IntToDoubleFunction xpx,
		java.util.function.DoubleUnaryOperator ypx, int x0, int w)
	{
		final double[] ema = trend(d);
		final Path2D path = new Path2D.Double();
		boolean pen = false;
		boolean any = false;
		for (int i = 0; i < ema.length; i++)
		{
			if (Double.isNaN(ema[i]))
			{
				pen = false;
				continue;
			}
			final double x = xpx.applyAsDouble(i);
			final double y = ypx.applyAsDouble(ema[i]);
			if (pen)
			{
				path.lineTo(x, y);
			}
			else
			{
				path.moveTo(x, y);
				pen = true;
			}
			any = true;
		}
		if (!any)
		{
			return;
		}
		g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(TREND_CASING);
		g.draw(path);
		/* #D9D3C7, fading in from the left: 0.40 x 0.90 to 1.0 x 0.90. */
		g.setPaint(new LinearGradientPaint(x0, 0, x0 + Math.max(1, w), 0, new float[]{0f, 1f},
			new Color[]{alpha(TEXT_MAIN, 0.36), alpha(TEXT_MAIN, 0.90)}));
		g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(path);
		g.setStroke(new BasicStroke(1f));
	}

	private static void drawVolume(Graphics2D g, Data d, Style st, java.util.function.IntToDoubleFunction xpx,
		double stepX, double y0Vol, double hVol, double a)
	{
		final List<double[]> groups = volumeGroups(d, stepX);
		double max = 1;
		for (double[] gr : groups)
		{
			max = Math.max(max, gr[1] + gr[2]);
		}
		final double base = y0Vol + hVol;
		for (double[] gr : groups)
		{
			final double barW = Math.max(2, gr[3] * stepX * 0.72);
			final double cx = xpx.applyAsDouble(0) + gr[0] * stepX;
			final double lowH = gr[1] / max * hVol;
			final double highH = gr[2] / max * hVol;
			if (lowH + highH < 0.5)
			{
				continue;
			}
			double top = base;
			if (lowH > 0.3)
			{
				g.setColor(alpha(st.buy, a));
				g.fill(new java.awt.geom.Rectangle2D.Double(cx - barW / 2, base - lowH, barW, lowH));
				top = base - lowH - 1;
			}
			if (highH > 0.3)
			{
				g.setColor(alpha(st.sell, a));
				g.fill(new java.awt.geom.Rectangle2D.Double(cx - barW / 2, top - highH, barW, highH));
			}
		}
	}

	private static void drawProfile(Graphics2D g, Data d, double vmin, double vmax, int x0, int y0, int w,
		double hPrice, java.util.function.DoubleUnaryOperator ypx)
	{
		final int nb = Math.max(12, Math.min(30, (int) Math.round(hPrice / 12)));
		final double[] vol = volumeProfile(d, vmin, vmax, nb);
		double max = 0;
		int poc = -1;
		for (int b = 0; b < nb; b++)
		{
			if (vol[b] > max)
			{
				max = vol[b];
				poc = b;
			}
		}
		if (max <= 0)
		{
			return;
		}
		final double maxLen = 0.15 * w;
		final double bandH = hPrice / nb;
		final double barH = Math.max(1, Math.min(bandH - 3, bandH * 0.72));
		/* The busiest level's band, across the whole plot (8842-8851). */
		final double pocMid = ypx.applyAsDouble(vmin + (poc + 0.5) * (vmax - vmin) / nb);
		g.setColor(new Color(255, 255, 255, Math.round(0.045f * 255)));
		g.fill(new java.awt.geom.Rectangle2D.Double(x0, pocMid - barH / 2, w, barH));
		g.setColor(new Color(255, 255, 255, Math.round(0.20f * 255)));
		g.fill(new java.awt.geom.Rectangle2D.Double(x0, Math.round(pocMid), w, 1));
		for (int b = 0; b < nb; b++)
		{
			if (vol[b] <= 0)
			{
				continue;
			}
			final boolean isPoc = b == poc;
			final double len = vol[b] / max * maxLen;
			if (len < 0.5)
			{
				continue;
			}
			final double mid = ypx.applyAsDouble(vmin + (b + 0.5) * (vmax - vmin) / nb);
			final double bx = x0 + w - len;
			final double by = mid - barH / 2;
			g.setColor(new Color(255, 255, 255, Math.round((isPoc ? 0.08f : 0.05f) * 255)));
			g.fill(new java.awt.geom.Rectangle2D.Double(bx, by, len, barH));
			g.setColor(new Color(255, 255, 255, Math.round((isPoc ? 0.55f : 0.22f) * 255)));
			g.fill(new java.awt.geom.Rectangle2D.Double(bx, by, 1, barH));
			if (isPoc)
			{
				g.setColor(new Color(255, 255, 255, Math.round(0.30f * 255)));
				g.fill(new java.awt.geom.Rectangle2D.Double(bx, by, len, 1));
				g.fill(new java.awt.geom.Rectangle2D.Double(bx, by + barH - 1, len, 1));
			}
		}
	}

	private static void drawFill(Graphics2D g, Data d, Style st, java.util.function.IntToDoubleFunction xpx,
		java.util.function.DoubleUnaryOperator ypx)
	{
		g.setColor(alpha(st.sell, 0.13));
		int i = 0;
		while (i < d.size())
		{
			if (d.hi[i] <= 0 || d.lo[i] <= 0)
			{
				i++;
				continue;
			}
			int j = i;
			while (j + 1 < d.size() && d.hi[j + 1] > 0 && d.lo[j + 1] > 0)
			{
				j++;
			}
			if (j > i)
			{
				final Path2D p = new Path2D.Double();
				p.moveTo(xpx.applyAsDouble(i), ypx.applyAsDouble(d.hi[i]));
				for (int k = i + 1; k <= j; k++)
				{
					p.lineTo(xpx.applyAsDouble(k), ypx.applyAsDouble(d.hi[k]));
				}
				for (int k = j; k >= i; k--)
				{
					p.lineTo(xpx.applyAsDouble(k), ypx.applyAsDouble(d.lo[k]));
				}
				p.closePath();
				g.fill(p);
			}
			i = j + 1;
		}
	}

	/**
	 * A dashed level across the plot with its badge (9204-9278): the word
	 * faint and small, the price bold. Off the scale, no line, and the
	 * badge pinned to the edge it is beyond with an arrow. The target's
	 * badge sits at the left as on the site; the offer's at the right, so
	 * the two never cover each other.
	 */
	private static void priceLine(Graphics2D g, long price, String word, Color c, double vmin, double vmax,
		java.util.function.DoubleUnaryOperator ypx, int x0, int y0, int w, double hPrice, boolean left)
	{
		final boolean above = price > vmax;
		final boolean below = price < vmin;
		final Font wordFont = new Font(Font.SANS_SERIF, Font.PLAIN, 9);
		final Font priceFont = new Font(Font.SANS_SERIF, Font.BOLD, 10);
		final String shown = above ? word + " ↑" : below ? word + " ↓" : word;
		final String value = String.format(Locale.US, "%,d", price);
		final int wordW = g.getFontMetrics(wordFont).stringWidth(shown);
		final int valueW = g.getFontMetrics(priceFont).stringWidth(value);
		final double padX = 5.5;
		final double bw = padX * 2 + wordW + 4 + valueW;
		final double bh = 15;
		final double bx = left ? x0 + 4 : x0 + w - bw - 4;
		double cy;
		if (above || below)
		{
			cy = above ? y0 + 2 + bh / 2 : y0 + hPrice - 2 - bh / 2;
		}
		else
		{
			final double py = ypx.applyAsDouble(price);
			g.setColor(c);
			g.setStroke(dashed(1.4f));
			g.draw(new Line2D.Double(x0, py, x0 + w, py));
			g.setStroke(new BasicStroke(1f));
			cy = py - 7.5 - 3;
			if (cy - bh / 2 < y0 + 1)
			{
				cy = py + 7.5 + 3;
			}
		}
		g.setColor(BADGE_PLATE);
		g.fill(new RoundRectangle2D.Double(bx, cy - bh / 2, bw, bh, 6, 6));
		g.setFont(wordFont);
		g.setColor(alpha(c, 0.62));
		final double base = cy + g.getFontMetrics(priceFont).getAscent() / 2.0 - 1;
		g.drawString(shown, (float) (bx + padX), (float) base);
		g.setFont(priceFont);
		g.setColor(c);
		g.drawString(value, (float) (bx + padX + wordW + 4), (float) base);
	}

	private static void drawHover(Graphics2D g, Rectangle area, Data d, Style st, int i, Point mouse, double x,
		int x0, int y0, int w, int h, double hPrice, double vmin, double vmax, Font axisFont)
	{
		g.setColor(CROSSHAIR);
		g.setStroke(dashed(1f));
		g.draw(new Line2D.Double(x, y0, x, y0 + h));
		final boolean inPrice = mouse.y <= y0 + hPrice;
		if (inPrice)
		{
			g.draw(new Line2D.Double(x0, mouse.y, x0 + w, mouse.y));
		}
		g.setStroke(new BasicStroke(1f));
		final Font pillFont = axisFont.deriveFont(Font.BOLD);
		g.setFont(pillFont);
		final FontMetrics fm = g.getFontMetrics();
		if (inPrice)
		{
			final double v = vmin + (1 - (mouse.y - y0) / hPrice) * (vmax - vmin);
			pill(g, fmtYAxis(v, 0), x0 + w + 2, mouse.y - 8, fm);
		}
		final long span = d.t[d.size() - 1] - d.t[0];
		final String when = DateTimeFormatter.ofPattern(span <= 2 * 86_400L ? "HH:mm" : "MMM d HH:mm", Locale.US)
			.format(Instant.ofEpochSecond(d.t[i]).atZone(ZoneId.systemDefault()));
		final int tw = fm.stringWidth(when) + 10;
		pill(g, when, (int) Math.max(x0, Math.min(x0 + w - tw, x - tw / 2.0)), y0 + h + 2, fm);

		final List<String[]> rows = tooltipRows(d, i);
		final Font tipFont = new Font(Font.SANS_SERIF, Font.PLAIN, 10);
		final FontMetrics tm = g.getFontMetrics(tipFont);
		/* The mark row is the site's .tip-marker, font-weight 800 (app.css:1293). */
		final Font markFont = tipFont.deriveFont(Font.BOLD);
		final FontMetrics mm = g.getFontMetrics(markFont);
		final int markW = 12;
		int tipW = 0;
		for (String[] r : rows)
		{
			tipW = Math.max(tipW, isMark(r) ? mm.stringWidth(r[0]) + markW : tm.stringWidth(r[0]));
		}
		tipW += 18;
		final int lineH = (int) Math.round(tm.getHeight() * 1.15);
		final int tipH = lineH * rows.size() + 12;
		int tx = mouse.x + 15;
		int ty = mouse.y + 15;
		if (tx + tipW > area.x + area.width)
		{
			tx = mouse.x - 15 - tipW;
		}
		if (ty + tipH > area.y + area.height)
		{
			ty = mouse.y - 15 - tipH;
		}
		tx = Math.max(area.x, tx);
		ty = Math.max(area.y, ty);
		g.setColor(TIP_BG);
		g.fill(new RoundRectangle2D.Double(tx, ty, tipW, tipH, 10, 10));
		g.setColor(TIP_EDGE);
		g.draw(new RoundRectangle2D.Double(tx + 0.5, ty + 0.5, tipW - 1, tipH - 1, 10, 10));
		g.setFont(tipFont);
		int by = ty + 6 + tm.getAscent();
		for (String[] r : rows)
		{
			final String kind = r[1];
			if (kind.equals("head"))
			{
				g.setColor(Color.WHITE);
			}
			else if (kind.endsWith("buy"))
			{
				g.setColor(st.buy);
			}
			else if (kind.endsWith("sell"))
			{
				g.setColor(st.sell);
			}
			else if (kind.equals("same"))
			{
				g.setColor(SAME_PRICE);
			}
			else
			{
				g.setColor(TEXT_MUTED);
			}
			int textX = tx + 9;
			if (isMark(r))
			{
				/* ● for a Period mark, ◯ for a Recent one, as the site's row. */
				final double cy = by - tm.getAscent() / 2.0 + 1;
				final Ellipse2D dot = new Ellipse2D.Double(textX + 0.5, cy - 4, 8, 8);
				if (kind.startsWith("solid"))
				{
					g.fill(dot);
				}
				else
				{
					g.setStroke(new BasicStroke(1.3f));
					g.draw(dot);
					g.setStroke(new BasicStroke(1f));
				}
				textX += markW;
			}
			g.setFont(isMark(r) ? markFont : tipFont);
			g.drawString(r[0], textX, by);
			if ("head".equals(r[1]))
			{
				g.setColor(TIP_RULE);
				g.fillRect(tx + 9, by + 3, tipW - 18, 1);
			}
			by += lineH;
		}
	}

	/**
	 * The tooltip's rows, each {text, kind} (showTipFor, app.js:9380-9409):
	 * the time; the mark under the cursor when there is one, a Period mark
	 * winning over a Recent one (markerLabels, 8587-8595); the price, or
	 * each side with "—" for a side that did not print; the volume. A mark
	 * row's kind is "solid" or "hollow" plus its side, its glyph drawn as
	 * a shape so it does not depend on the font having one.
	 */
	static List<String[]> tooltipRows(Data d, int i)
	{
		final List<String[]> rows = new ArrayList<>();
		rows.add(new String[]{DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US)
			.format(Instant.ofEpochSecond(d.t[i]).atZone(ZoneId.systemDefault())), "head"});
		final int[] period = extremes(d, 0);
		final int[] recent = extremes(d, recentStart(d.size()));
		if (i == period[1])
		{
			rows.add(new String[]{"Period Low", "solid-buy"});
		}
		else if (i == period[0])
		{
			rows.add(new String[]{"Period High", "solid-sell"});
		}
		else if (i == recent[1])
		{
			rows.add(new String[]{"Recent Low", "hollow-buy"});
		}
		else if (i == recent[0])
		{
			rows.add(new String[]{"Recent High", "hollow-sell"});
		}
		if (d.lo[i] > 0 && d.hi[i] > 0 && Math.abs(d.hi[i] - d.lo[i]) < 1e-9)
		{
			rows.add(new String[]{"Price: " + gp(d.hi[i]), "same"});
		}
		else
		{
			rows.add(new String[]{"Buy: " + gp(d.lo[i]), "buy"});
			rows.add(new String[]{"Sell: " + gp(d.hi[i]), "sell"});
		}
		rows.add(new String[]{"Volume: " + String.format(Locale.US, "%,d", Math.round(d.loVol[i] + d.hiVol[i])), "muted"});
		return rows;
	}

	private static boolean isMark(String[] row)
	{
		return row[1].startsWith("solid") || row[1].startsWith("hollow");
	}

	/** A price as the tooltip shows it; "—" for a side with no print (fmtGp, 1381). */
	private static String gp(double v)
	{
		return v > 0 ? String.format(Locale.US, "%,d", Math.round(v)) : "—";
	}

	private static void pill(Graphics2D g, String text, int x, int y, FontMetrics fm)
	{
		final int pw = fm.stringWidth(text) + 10;
		g.setColor(PILL);
		g.fillRect(x, y, pw, 16);
		g.setColor(PILL_EDGE);
		g.drawRect(x, y, pw, 16);
		g.setColor(PILL_TEXT);
		g.drawString(text, x + 5, y + 8 + fm.getAscent() / 2 - 1);
	}

	static void drawCentred(Graphics2D g, Rectangle area, String text, Color c, int px)
	{
		g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, px));
		final FontMetrics fm = g.getFontMetrics();
		g.setColor(c);
		g.drawString(text, area.x + (area.width - fm.stringWidth(text)) / 2,
			area.y + (area.height + fm.getAscent()) / 2 - 2);
	}

	private PriceChart()
	{
	}
}
