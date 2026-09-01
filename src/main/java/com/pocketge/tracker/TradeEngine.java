package com.pocketge.tracker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Java port of pocketge.com's flip-pricing engine (index.html's
 * computeTargets()/ENGINE_CFG — the "trade engine" that drives the
 * website's TARGET BUY / TARGET SELL boxes). Same math, same constant
 * names and structure where practical, specifically so the two can be
 * diffed side by side when the website's version changes. Pure and
 * unit-testable — no RuneLite types.
 *
 * Only the 1D/live-engine path is ported (not the 5D+ swing-anchor
 * percentile fallback, which is tied to the website's chart-timeframe
 * concept) — this is what the plugin's ADJUST_BUY/ADJUST_SELL suggestions
 * need: "what should I reprice this stale offer to, right now."
 *
 * See the website's index.html around `const ENGINE_CFG` / `function
 * computeTargets` for the original, heavily-commented source of truth —
 * comments here are trimmed to what's Java-specific; read the JS for the
 * "why" behind each constant and step.
 */
public class TradeEngine
{
	/** One item's price history, oldest-first, as returned by the OSRS
	 *  Wiki's /timeseries endpoint. A missing low/high/volume for a bucket
	 *  should be 0 — the engine treats 0/negative as "no print." */
	public static class Series
	{
		public long[] labels;   // epoch seconds, ascending (oldest -> newest)
		public double[] low;    // avgLowPrice per bucket
		public double[] high;   // avgHighPrice per bucket
		public double[] lowVol;
		public double[] highVol;
	}

	public static class Result
	{
		public final long buy;
		public final long sell;
		public final boolean viable;
		public final String reason;
		public final long edge;
		public final boolean lowConf;

		private Result(long buy, long sell, boolean viable, String reason, long edge, boolean lowConf)
		{
			this.buy = Math.max(0, buy);
			this.sell = Math.max(0, sell);
			this.viable = viable;
			this.reason = reason;
			this.edge = edge;
			this.lowConf = lowConf;
		}

		private static Result no(String reason, double buy, double sell, boolean lowConf)
		{
			return new Result(Math.round(buy), Math.round(sell), false, reason, 0, lowConf);
		}
	}

	private static final double TARGET_TRADES = 2000, MIN_TRADES = 200;
	private static final int MIN_BUCKETS = 8;
	private static final double MIN_AGE = 60 * 60, MAX_AGE = 24 * 3600;
	private static final double ALPHA = 0.25, MIN_SCALE = 1, MAX_SPREAD_FRAC = 0.34, ABS_CAP_FRAC = 0.05;
	private static final double VOL_FLOOR = 20, VOL_CAP_W = 200;
	private static final int NEFF_MIN = 2, NEFF_MAX = 8;
	private static final double ALPHA_LIVE = 0.6, FRESH_SEC = 120, STALE_MIN = 5 * 60, STALE_MAX = 60 * 60;
	private static final double K_MAX = 6, MAX_FRAC = 0.25, P_MIN = 0.15;
	private static final double K_SAFETY = 0.002;
	private static final double DRIFT_GATE = 0.006;
	private static final double RECENT_FRAC = 0.34;
	private static final double PATIENCE_K = 0.25;

	private TradeEngine() {}

	public static long minEdge(double b)
	{
		return (long) Math.max(2, Math.ceil(K_SAFETY * b));
	}

	/**
	 * Clamps a computed sell target to the live book: never list BELOW what
	 * buyers are already paying.
	 *
	 * {@link #compute} is picking a flip PAIR, and it will settle on a lower,
	 * denser band when that band clears the tax more reliably — for Yew logs
	 * with an insta-buy of 115 it returned a 107/111 pair, because 111 is
	 * where the day's volume actually sits and 115 is the top of the range.
	 * As a flip pair that is defensible. As the number to type into a sell
	 * offer it is not: the player has already decided to sell this item, so
	 * the only question left is the price, and asking 111 with a standing 115
	 * bid gives away 4 gp an item for nothing. It also disagreed with
	 * pocketge.com's own TARGET SELL box and with Flipping Copilot, both
	 * showing 115 — and a number that loses an argument against two other
	 * tools on the same screen doesn't just cost that trade, it costs trust
	 * in every other number the plugin prints.
	 *
	 * Applied at the call sites rather than inside compute() so the ported
	 * algorithm stays diffable line-for-line against the website's
	 * computeTargets() JS, which is that class's whole reason for existing.
	 *
	 * @param rawHigh the live insta-buy quote; 0/unknown leaves the target alone
	 */
	public static long sellTarget(long engineSell, long rawHigh)
	{
		return rawHigh > 0 ? Math.max(engineSell, rawHigh) : engineSell;
	}

	/**
	 * The mirror of {@link #sellTarget}: never bid ABOVE what sellers are
	 * already accepting. Bidding over the insta-sell price cannot fill you
	 * any faster than bidding at it — the GE matches at the resting offer's
	 * price — so the excess only ever shows a worse number on screen.
	 *
	 * @param rawLow the live insta-sell quote; 0/unknown leaves the target alone
	 */
	public static long buyTarget(long engineBuy, long rawLow)
	{
		return rawLow > 0 ? Math.min(engineBuy, rawLow) : engineBuy;
	}

	private static double median(double[] a)
	{
		if (a.length == 0)
		{
			return 0;
		}
		double[] s = a.clone();
		Arrays.sort(s);
		int m = s.length / 2;
		return s.length % 2 == 1 ? s[m] : (s[m - 1] + s[m]) / 2;
	}

	/** Weighted median over (value, weight) pairs — the value at which
	 *  cumulative weight first reaches half the total, walking values in
	 *  ascending order. Non-positive values are ignored, mirroring the
	 *  JS `r[k] > 0` filter. */
	private static double weightedMedian(double[] values, double[] weights)
	{
		Integer[] order = new Integer[values.length];
		for (int i = 0; i < values.length; i++)
		{
			order[i] = i;
		}
		Arrays.sort(order, (a, b) -> Double.compare(values[a], values[b]));
		double total = 0;
		for (int i = 0; i < values.length; i++)
		{
			if (values[i] > 0)
			{
				total += weights[i];
			}
		}
		if (total <= 0)
		{
			return 0;
		}
		double acc = 0, last = 0;
		boolean any = false;
		for (int idx : order)
		{
			if (!(values[idx] > 0))
			{
				continue;
			}
			acc += weights[idx];
			last = values[idx];
			any = true;
			if (acc >= total / 2)
			{
				return values[idx];
			}
		}
		return any ? last : 0;
	}

	private static double percentile(double[] arr, double p)
	{
		if (arr.length == 0)
		{
			return Double.NaN;
		}
		double[] sorted = arr.clone();
		Arrays.sort(sorted);
		double index = (p / 100) * (sorted.length - 1);
		int lo = (int) Math.floor(index), hi = (int) Math.ceil(index);
		double w = index - lo;
		return sorted[lo] * (1 - w) + sorted[hi] * w;
	}

	/** One robust, winsorised per-bucket record — mirrors the JS `B` array
	 *  entries built inside computeTargets(). */
	private static class Bucket
	{
		double age, lo, hi, mid, spread;
		double wL, wH, wFreshL, wFreshH, wM, wReachL, wReachH;
		double volL, volH;
		boolean recent, fresh;
	}

	/** Blended live-anchor result: the price to use, and how much the live
	 *  quote was trusted (0..1) when producing it. */
	private static class Anchor
	{
		double price;
		double t;
	}

	/** Holds everything the JS closures (deblur/qTouch/touchEpisodes/Pfill/
	 *  tailTouches/reachable/score/argmax) capture from computeTargets()'s
	 *  enclosing scope — one instance per compute() call. */
	private static class Calc
	{
		List<Bucket> B;
		boolean buyEndangered, sellEndangered;
		int neffBuy, neffSell;
		boolean strongDrift;
		double effDrift;
		Anchor aBuy, aSell;
		double sAbs;
		int itemId;

		double deblur(Bucket b, boolean buySide)
		{
			double g = b.spread;
			double sc = Math.max(ALPHA * g, MIN_SCALE);
			double cap = Math.min(g > 0 ? MAX_SPREAD_FRAC * g : Double.POSITIVE_INFINITY, ABS_CAP_FRAC * b.mid);
			sc = Math.min(sc, cap);
			double volSide = buySide ? b.volL : b.volH;
			sc *= Math.min(1, volSide / VOL_FLOOR);
			return Math.max(0, sc);
		}

		double qTouch(double c, boolean buySide)
		{
			boolean endangered = buySide ? buyEndangered : sellEndangered;
			double acc = 0, W = 0;
			for (Bucket b : B)
			{
				if (endangered && !b.fresh)
				{
					continue;
				}
				double sc = deblur(b, buySide);
				double w = endangered
					? (buySide ? b.wFreshL : b.wFreshH)
					: (buySide ? b.wReachL : b.wReachH);
				double f;
				if (buySide)
				{
					double p = b.lo, ext = p - sc;
					f = c >= p ? 1 : (c <= ext ? 0 : (c - ext) / (p - ext));
				}
				else
				{
					double p = b.hi, ext = p + sc;
					f = c <= p ? 1 : (c >= ext ? 0 : (ext - c) / (ext - p));
				}
				acc += w * f;
				W += w;
			}
			return W > 0 ? acc / W : 0;
		}

		/** Distinct touch episodes (runs of consecutive touching buckets),
		 *  walking B oldest -> newest (its natural order). */
		int touchEpisodes(double c, boolean buySide)
		{
			int eps = 0;
			boolean inTouch = false;
			for (Bucket b : B)
			{
				double sc = deblur(b, buySide);
				boolean t = buySide ? (c >= b.lo - sc) : (c <= b.hi + sc);
				if (t && !inTouch)
				{
					eps++;
				}
				inTouch = t;
			}
			return eps;
		}

		double pFill(double c, boolean buySide)
		{
			int base = buySide ? neffBuy : neffSell;
			int n = Math.max(1, Math.min(base, touchEpisodes(c, buySide)));
			return 1 - Math.pow(1 - qTouch(c, buySide), n);
		}

		boolean tailTouches(double c, boolean buySide)
		{
			for (Bucket b : B)
			{
				if (!b.recent)
				{
					continue;
				}
				double sc = deblur(b, buySide);
				if (buySide ? (c >= b.lo - sc) : (c <= b.hi + sc))
				{
					return true;
				}
			}
			return false;
		}

		boolean reachable(double c, boolean buySide)
		{
			return qTouch(c, buySide) >= P_MIN && tailTouches(c, buySide);
		}

		private double netKept(double b, double s)
		{
			return s - FlipTracker.taxPerItem(Math.round(s), itemId) - b;
		}

		double score(boolean buySide, double c, double otherTarget)
		{
			if (!reachable(c, buySide))
			{
				return -1;
			}
			double p0 = pFill(c, buySide);
			if (p0 < P_MIN)
			{
				return -1;
			}
			double margin = buySide ? netKept(c, otherTarget) : netKept(otherTarget, c);
			if (margin <= 0)
			{
				return -1;
			}
			double p = p0;
			if (strongDrift)
			{
				if (buySide && effDrift < 0)
				{
					p *= 0.5;
				}
				if (!buySide && effDrift > 0)
				{
					p *= 0.5;
				}
			}
			double eB = aBuy.price - (buySide ? c : otherTarget);
			double eS = (!buySide ? c : otherTarget) - aSell.price;
			double patience = 1 + PATIENCE_K * (Math.max(0, eB) + Math.max(0, eS)) / Math.max(1, sAbs);
			return p * margin / patience;
		}

		Long argmax(List<Long> cands, boolean buySide, double other)
		{
			Long best = null;
			double bs = 0;
			for (long c : cands)
			{
				double sc = score(buySide, c, other);
				if (sc > bs)
				{
					bs = sc;
					best = c;
				}
			}
			return best;
		}
	}

	/** Given the live quote and a price-history series, return the target
	 *  buy/sell pair the same way pocketge.com's Target Buy/Sell boxes do
	 *  on the 1D view. `viable=false` still returns the tightest honestly-
	 *  fillable pair (never a stale/unreachable price) rather than nothing.
	 *  lowTime/highTime are the live quote's own print timestamps (epoch
	 *  seconds, 0/negative = unknown) — how stale they are relative to the
	 *  series' own clock decides how much the live print is trusted. */
	public static Result compute(long rawLowIn, long rawHighIn, long lowTime, long highTime, Series series, int itemId)
	{
		double rawLow = rawLowIn > 0 ? rawLowIn : 0;   // insta-sell price = you BUY at
		double rawHigh = rawHighIn > 0 ? rawHighIn : 0; // insta-buy  price = you SELL into

		if (series == null || series.labels == null || series.labels.length < MIN_BUCKETS)
		{
			return Result.no("insufficient data", rawLow, rawHigh, true);
		}
		long[] L = series.labels;
		double now = L[L.length - 1];
		if (!(now > 0))
		{
			return Result.no("insufficient data", rawLow, rawHigh, true);
		}

		List<Double> gaps = new ArrayList<>();
		for (int i = 1; i < L.length; i++)
		{
			double g = L[i] - L[i - 1];
			if (g > 0)
			{
				gaps.add(g);
			}
		}
		double dt = gaps.isEmpty() ? 300 : median(toArray(gaps));

		// ---- adaptive, liquidity-sized window (walk newest -> oldest) ----
		List<Integer> idx = new ArrayList<>();
		double cum = 0, oldest = now;
		int usable = 0;
		for (int i = L.length - 1; i >= 0; i--)
		{
			double age = now - L[i];
			if (age < 0)
			{
				continue;
			}
			if (age > MAX_AGE)
			{
				break;
			}
			idx.add(i);
			if (series.low[i] > 0 && series.high[i] > 0)
			{
				usable++;
			}
			cum += nz(series.lowVol, i) + nz(series.highVol, i);
			oldest = L[i];
			double span0 = now - oldest;
			if (cum >= TARGET_TRADES && span0 >= MIN_AGE && usable >= MIN_BUCKETS)
			{
				break;
			}
		}
		if (usable < MIN_BUCKETS)
		{
			return Result.no("insufficient buckets", rawLow, rawHigh, true);
		}
		java.util.Collections.reverse(idx); // oldest -> newest
		double span = Math.max(dt, now - L[idx.get(0)]);
		boolean lowConf = cum < MIN_TRADES;
		double halfLife = Math.max(dt, span / 4);

		// ---- robust, winsorised per-bucket records ----
		List<Double> loList = new ArrayList<>(), hiList = new ArrayList<>();
		for (int i : idx)
		{
			if (series.low[i] > 0)
			{
				loList.add(series.low[i]);
			}
			if (series.high[i] > 0)
			{
				hiList.add(series.high[i]);
			}
		}
		if (loList.size() < 4 || hiList.size() < 4)
		{
			return Result.no("thin data", rawLow, rawHigh, true);
		}
		double[] loArr = toArray(loList), hiArr = toArray(hiList);
		double P1lo = percentile(loArr, 1), P99lo = percentile(loArr, 99);
		double P1hi = percentile(hiArr, 1), P99hi = percentile(hiArr, 99);

		double[] bucketVols = new double[idx.size()];
		for (int k = 0; k < idx.size(); k++)
		{
			bucketVols[k] = nz(series.lowVol, idx.get(k)) + nz(series.highVol, idx.get(k));
		}
		double volMed = median(bucketVols);
		if (volMed <= 0)
		{
			volMed = 1;
		}

		List<Bucket> B = new ArrayList<>();
		for (int i : idx)
		{
			double lo = series.low[i], hi = series.high[i];
			if (!(lo > 0) || !(hi > 0))
			{
				continue;
			}
			lo = Math.min(Math.max(lo, P1lo), P99lo);
			hi = Math.min(Math.max(hi, P1hi), P99hi);
			if (hi < lo)
			{
				double t = lo; lo = hi; hi = t;
			}
			double mid = (lo + hi) / 2, spread = Math.max(0, hi - lo);
			double age = now - L[i];
			double decay = Math.pow(0.5, age / halfLife);
			double decayReach = Math.pow(0.5, age / span);
			double volL = Math.min(nz(series.lowVol, i, 1), 5 * volMed);
			double volH = Math.min(nz(series.highVol, i, 1), 5 * volMed);
			double wL = decay * Math.sqrt(Math.min(volL, VOL_CAP_W));
			double wH = decay * Math.sqrt(Math.min(volH, VOL_CAP_W));
			double decayFresh = Math.pow(0.5, age / dt);

			Bucket b = new Bucket();
			b.age = age; b.lo = lo; b.hi = hi; b.mid = mid; b.spread = spread;
			b.wL = wL; b.wH = wH;
			b.wFreshL = decayFresh * Math.sqrt(Math.min(volL, VOL_CAP_W));
			b.wFreshH = decayFresh * Math.sqrt(Math.min(volH, VOL_CAP_W));
			b.wM = wL + wH;
			b.wReachL = decayReach * Math.sqrt(Math.min(Math.min(volL, volMed), VOL_CAP_W));
			b.wReachH = decayReach * Math.sqrt(Math.min(Math.min(volH, volMed), VOL_CAP_W));
			b.volL = volL; b.volH = volH;
			b.recent = age <= Math.max(halfLife, 3 * dt);
			b.fresh = age <= 3 * dt;
			B.add(b);
		}
		if (B.size() < MIN_BUCKETS)
		{
			return Result.no("thin data", rawLow, rawHigh, true);
		}

		double refMid = weightedMedian(mapD(B, b -> b.mid), mapD(B, b -> b.wM));
		double relSpread = weightedMedian(mapD(B, b -> b.spread / b.mid), mapD(B, b -> b.wL + b.wH));

		// realized step-vol (MAD of log-returns), gap-skipped
		List<Double> rets = new ArrayList<>();
		for (int k = 1; k < B.size(); k++)
		{
			int iPrev = idx.get(k - 1), iCur = idx.get(k);
			if (L[iCur] - L[iPrev] <= 2 * dt && B.get(k).mid > 0 && B.get(k - 1).mid > 0)
			{
				rets.add(Math.log(B.get(k).mid / B.get(k - 1).mid));
			}
		}
		double medR = median(toArray(rets));
		double sigma = 0;
		if (!rets.isEmpty())
		{
			double[] devs = new double[rets.size()];
			for (int k = 0; k < rets.size(); k++)
			{
				devs[k] = Math.abs(rets.get(k) - medR);
			}
			sigma = 1.4826 * median(devs);
		}
		sigma = Math.max(sigma, Math.max(0.5 * relSpread, 1 / Math.max(1, refMid)));

		// ---- DRIFT (level change), independent of sigma ----
		List<Bucket> recentB = new ArrayList<>(), freshB = new ArrayList<>();
		for (Bucket b : B)
		{
			if (b.recent)
			{
				recentB.add(b);
			}
			if (b.fresh)
			{
				freshB.add(b);
			}
		}
		int third = Math.max(1, (int) Math.round(B.size() * RECENT_FRAC));
		List<Bucket> newestThird = B.subList(B.size() - third, B.size());
		List<Bucket> oldestThird = B.subList(0, third);
		double midNew = weightedMedian(mapD(newestThird, b -> b.mid), mapD(newestThird, b -> b.wM));
		double midOld = weightedMedian(mapD(oldestThird, b -> b.mid), mapD(oldestThird, b -> b.wM));
		double driftFrac = refMid > 0 ? (midNew - midOld) / refMid : 0;
		double midTail = !recentB.isEmpty() ? weightedMedian(mapD(recentB, b -> b.mid), mapD(recentB, b -> b.wM)) : refMid;
		double driftTail = refMid > 0 ? (midTail - refMid) / refMid : 0;
		boolean bothBreachDown = rawLow > 0 && rawHigh > 0 && rawLow < P1lo && rawHigh < P1hi;
		boolean bothBreachUp = rawLow > 0 && rawHigh > 0 && rawLow > P99lo && rawHigh > P99hi;

		boolean sellEndangered = driftFrac <= -DRIFT_GATE * 0.7 || 2 * driftTail <= -DRIFT_GATE || bothBreachDown;
		boolean buyEndangered = driftFrac >= DRIFT_GATE * 0.7 || 2 * driftTail >= DRIFT_GATE || bothBreachUp;
		double effDrift = Math.abs(driftFrac / 0.7) >= Math.abs(2 * driftTail) ? driftFrac / 0.7 : 2 * driftTail;

		int neffBuy = (int) Math.min(NEFF_MAX, Math.max(NEFF_MIN, Math.round((span / dt) / 3)));
		int neffSell = neffBuy;
		if (sellEndangered)
		{
			neffSell = 1;
		}
		if (buyEndangered)
		{
			neffBuy = 1;
		}

		Calc calc = new Calc();
		calc.B = B;
		calc.buyEndangered = buyEndangered;
		calc.sellEndangered = sellEndangered;
		calc.neffBuy = neffBuy;
		calc.neffSell = neffSell;
		calc.effDrift = effDrift;
		calc.itemId = itemId;

		// ---- live-anchor blend (staleness trust + slew cap) ----
		double tradesPerMin = cum / Math.max(1, span / 60);
		double A = Math.min(STALE_MAX, Math.max(STALE_MIN, 10 * (60 / Math.max(1e-6, tradesPerMin))));
		double distBuy = weightedMedian(mapD(B, b -> b.lo), mapD(B, b -> b.wL));
		double distSell = weightedMedian(mapD(B, b -> b.hi), mapD(B, b -> b.wH));
		double delta = Math.max(Math.round(0.003 * refMid), Math.round(sigma * refMid * 0.75));

		double ageLow = lowTime > 0 ? now - lowTime : -1;
		double ageHigh = highTime > 0 ? now - highTime : -1;
		Anchor aBuy = blend(distBuy, rawLow, ageLow, P1lo, P99lo, A, delta, bothBreachDown, bothBreachUp);
		Anchor aSell = blend(distSell, rawHigh, ageHigh, P1hi, P99hi, A, delta, bothBreachDown, bothBreachUp);
		calc.aBuy = aBuy;
		calc.aSell = aSell;

		double sAbs = sigma * ((aBuy.price + aSell.price) / 2);
		calc.sAbs = sAbs;
		calc.strongDrift = Math.abs(effDrift) >= 3 * DRIFT_GATE;

		double eMax = Math.max(1, Math.min(K_MAX * sAbs, MAX_FRAC * aBuy.price));
		double step = Math.max(1, Math.round(eMax / 40));
		double buyCapLive = (rawLow > 0 && aBuy.t >= 0.5) ? rawLow : Double.POSITIVE_INFINITY;

		List<Long> buyCands = new ArrayList<>(), sellCands = new ArrayList<>();
		for (double e = 0; e <= eMax; e += step)
		{
			long bb = Math.round(aBuy.price - e);
			if (bb >= 1 && bb <= buyCapLive)
			{
				buyCands.add(bb);
			}
			sellCands.add(Math.round(aSell.price + e));
		}

		List<Bucket> loEvid = !(buyEndangered ? freshB : B).isEmpty() ? (buyEndangered ? freshB : B) : B;
		List<Bucket> hiEvid = !(sellEndangered ? freshB : B).isEmpty() ? (sellEndangered ? freshB : B) : B;
		double buyFloor = Math.min(rawLow > 0 ? rawLow : Double.POSITIVE_INFINITY,
			percentile(!loEvid.isEmpty() ? mapD(loEvid, b -> b.lo) : loArr, 2));
		double sellCap = Math.max(rawHigh, percentile(!hiEvid.isEmpty() ? mapD(hiEvid, b -> b.hi) : hiArr, 98));

		// ---- coupled 2-sweep fixed point ----
		Long s = Math.round(Math.min(Math.max(distSell, aSell.price), sellCap));
		Long b = calc.argmax(buyCands, true, s);
		if (b != null)
		{
			Long s2 = calc.argmax(sellCands, false, b);
			s = s2 != null ? s2 : s;
		}
		if (b != null)
		{
			Long b2 = calc.argmax(buyCands, true, s);
			b = b2 != null ? b2 : b;
			Long s3 = calc.argmax(sellCands, false, b);
			s = s3 != null ? s3 : s;
		}
		if (s != null && !calc.reachable(s, false))
		{
			s = null;
		}
		if (b != null && (!calc.reachable(b, true) || b > buyCapLive))
		{
			b = null;
		}

		long edgeReq = lowConf
			? Math.max(minEdge(b != null ? b : rawLow), (long) Math.ceil(2 * K_SAFETY * (b != null ? b : rawLow)))
			: minEdge(b != null ? b : rawLow);

		if (b != null && s != null && calc.netKept(b, s) >= edgeReq)
		{
			if (calc.netKept(b, s - 1) >= edgeReq && calc.reachable(s - 1, false))
			{
				s -= 1;
			}
			return new Result(b, s, true, "", Math.round(calc.netKept(b, s)), lowConf);
		}

		// widen minimally, staying inside the recent fillable band + recency gate
		if (b == null)
		{
			b = (long) Math.max(1, Math.min(Math.round(aBuy.price), buyCapLive));
		}
		if (s == null)
		{
			s = Math.round(aSell.price);
		}
		int seedGuard = 0;
		while (s > Math.max(rawHigh, 1) && !calc.reachable(s, false) && ++seedGuard < 20000)
		{
			s--;
		}
		if (rawHigh > 0 && s < rawHigh)
		{
			s = Math.round(rawHigh);
		}
		int guard = 0;
		while (calc.netKept(b, s) < edgeReq)
		{
			boolean canLower = (b - 1) >= buyFloor && (b - 1) >= 1 && calc.reachable(b - 1, true);
			boolean canRaise = (s + 1) <= sellCap && calc.reachable(s + 1, false);
			if (canLower && (!canRaise || calc.qTouch(b - 1, true) >= calc.qTouch(s + 1, false)))
			{
				b -= 1;
			}
			else if (canRaise)
			{
				s += 1;
			}
			else
			{
				break; // can't reach a tax-clearing pair without leaving the fillable band
			}
			if (++guard > 20000)
			{
				break;
			}
		}
		if (calc.netKept(b, s) >= edgeReq)
		{
			return new Result(b, s, true, "", Math.round(calc.netKept(b, s)), lowConf);
		}

		// No clean flip — tightest CURRENTLY-FILLABLE honest pair.
		long honestBuy = (long) Math.min(Math.round(aBuy.price), buyCapLive);
		long honestSell = (long) Math.max(Math.round(aSell.price), rawHigh);
		if (aSell.t < 0.5)
		{
			honestSell = Math.round(aSell.price);
		}
		else if (sellEndangered && rawHigh > 0)
		{
			honestSell = Math.round(rawHigh);
		}
		return Result.no("spread does not clear the 2% tax right now", honestBuy, honestSell, lowConf);
	}

	private static Anchor blend(double dist, double live, double ageSec, double p1, double p99,
		double staleA, double delta, boolean bothBreachDown, boolean bothBreachUp)
	{
		Anchor out = new Anchor();
		if (!(live > 0))
		{
			out.price = Math.round(dist);
			out.t = 0;
			return out;
		}
		double t = trust(ageSec, staleA);
		if ((live < p1 || live > p99) && !(bothBreachDown || bothBreachUp))
		{
			t = 0;
		}
		double clamped = Math.max(dist - delta, Math.min(dist + delta, live));
		double a = ALPHA_LIVE * t;
		out.price = Math.round((1 - a) * dist + a * clamped);
		out.t = t;
		return out;
	}

	private static double trust(double ageSec, double A)
	{
		if (!(ageSec >= 0))
		{
			return 0.5; // unknown timestamp -> blend half
		}
		double t = Math.max(0, Math.min(1, 1 - ageSec / A));
		if (ageSec < FRESH_SEC)
		{
			t = Math.max(t, 0.5);
		}
		return t;
	}

	private static double nz(double[] arr, int i)
	{
		return arr != null && i < arr.length ? arr[i] : 0;
	}

	private static double nz(double[] arr, int i, double fallbackIfZero)
	{
		double v = nz(arr, i);
		return v > 0 ? v : fallbackIfZero;
	}

	private static double[] toArray(List<Double> list)
	{
		double[] out = new double[list.size()];
		for (int i = 0; i < list.size(); i++)
		{
			out[i] = list.get(i);
		}
		return out;
	}

	private interface ToDouble { double apply(Bucket b); }

	private static double[] mapD(List<Bucket> list, ToDouble f)
	{
		double[] out = new double[list.size()];
		for (int i = 0; i < list.size(); i++)
		{
			out[i] = f.apply(list.get(i));
		}
		return out;
	}
}
