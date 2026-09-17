package com.pocketge.tracker;

import org.junit.Assert;
import org.junit.Test;

/**
 * When the live spread cannot clear the tax, the plugin stops quoting the
 * traded price.
 *
 * The report, for the third time and with the numbers to hand: the plugin
 * offered to sell Ruby at 779 while pocketge.com, on the same market at the
 * same moment, said 788. 779 was the insta-buy — the price rubies were
 * actually changing hands at — presented as a target.
 *
 * The cause was a gap in the port, not a bug in the engine. Ruby's live book
 * was insta-sell 767, insta-buy 779. Tax on 779 is 15, so buying at 767 and
 * selling at 779 nets MINUS THREE gp a unit: the spread is dead. TradeEngine
 * says so honestly, converging both targets on the live quote and reporting
 * viable=false — and every caller then fell back to the raw print.
 *
 * The website does one more thing, in computeViewTargets, which had never been
 * ported: when the live spread is dead it anchors to the DAY's recency-weighted
 * dip and peak instead, because prints all day at 758 and 788 are levels a
 * patient order actually reaches even though the current quote is not. It uses
 * that pair only when it clears the tax; otherwise it keeps the engine's honest
 * no-margin answer, because a fabricated margin is worse than no margin.
 */
public class DeadSpreadTargetsTest
{
	private static final int RUBY = 1603;
	private static final long NOW = 1_700_000_000L;

	/** The live book from the report: 767 / 779, which the tax eats whole. */
	private static final long LIVE_LOW = 767;
	private static final long LIVE_HIGH = 779;

	@Test
	public void theLiveRubySpreadGenuinelyCannotBeatTheTax()
	{
		final long tax = FlipTracker.taxPerItem(LIVE_HIGH, RUBY);
		Assert.assertEquals("2% of 779, floored", 15, tax);
		Assert.assertTrue("crossing the live spread loses money",
			LIVE_HIGH - tax - LIVE_LOW < 0);
	}

	/**
	 * The shape that actually produces this, which took two wrong fixtures to
	 * find.
	 *
	 * The engine's window is LIQUIDITY-SIZED. It walks newest to oldest and
	 * stops once it has TARGET_TRADES (2,000) with at least MIN_AGE (one hour)
	 * and eight usable buckets. Ruby trades 2.57M a day — roughly nine
	 * thousand a five-minute bucket — so two thousand trades is one bucket,
	 * and the window closes at the one-hour floor. The engine only ever sees
	 * the LAST HOUR.
	 *
	 * swingTargets reads the whole series. That is the entire difference, and
	 * it is why the website could show 788 while the plugin showed 779 with
	 * both running the same engine on the same market: an hour of flat book
	 * over a day that ranged.
	 *
	 * So: twelve recent buckets pinned at the live quote with an 8gp spread
	 * the 15gp tax eats whole, over a session that ranged from the mid-740s
	 * to the high 780s. Volume high enough that the engine's window really
	 * does stop at the hour.
	 *
	 * The two fixtures that failed first are worth recording, because both
	 * looked right: one gave every bucket a 22gp spread (the tax leaves 7, so
	 * the engine was viable and the test measured nothing), and one oscillated
	 * the whole day with a thin spread (the engine's own window still caught
	 * the range, because an hour of it was enough).
	 */
	private static TradeEngine.Series dayWithRange()
	{
		final int n = 120;                 // ten hours of 5m buckets
		final int flatTail = 12;           // the last hour, which is all the engine sees
		final TradeEngine.Series s = new TradeEngine.Series();
		s.labels = new long[n];
		s.low = new double[n];
		s.high = new double[n];
		s.lowVol = new double[n];
		s.highVol = new double[n];
		for (int i = 0; i < n; i++)
		{
			s.labels[i] = NOW - (long) (n - 1 - i) * 300L;
			if (i >= n - flatTail)
			{
				s.low[i] = LIVE_LOW;       // 767
				s.high[i] = LIVE_HIGH;     // 779 — 12 apart, tax is 15
			}
			else
			{
				final double swing = Math.sin(i / 9.0) * 20;
				s.low[i] = 763 + swing;
				s.high[i] = 775 + swing;
			}
			s.lowVol[i] = 4500;
			s.highVol[i] = 4500;
		}
		return s;
	}

	/**
	 * The engine, looking only at the last hour, correctly finds nothing —
	 * and its answer is the live quote, which is how the traded price ends up
	 * on the card presented as a target.
	 */
	@Test
	public void theEngineAloneSeesOnlyTheDeadHourAndQuotesTheLiveQuote()
	{
		final TradeEngine.Result engine =
			TradeEngine.compute(LIVE_LOW, LIVE_HIGH, NOW, NOW, dayWithRange(), RUBY);
		Assert.assertNotNull(engine);
		final long tax = FlipTracker.taxPerItem(engine.sell, RUBY);
		Assert.assertTrue("the engine's own pair does not clear the tax"
				+ " (buy " + engine.buy + ", sell " + engine.sell + ", tax " + tax + ")",
			engine.sell - engine.buy - tax <= 0);
		Assert.assertEquals("and its ask IS the traded price — the reported bug",
			LIVE_HIGH, engine.sell);
	}

	/**
	 * And the day it could not see really did have a range, or the fallback
	 * would have nothing to find and the test above would be the whole story.
	 */
	@Test
	public void theDayItCannotSeeHasARealRange()
	{
		final long[] day = TradeEngine.swingTargets(dayWithRange());
		Assert.assertNotNull("the whole session ranged", day);
		Assert.assertTrue("wide enough to clear the tax",
			day[1] - day[0] - FlipTracker.taxPerItem(day[1], RUBY) > 0);
	}

	/**
	 * The fix. viewTargets is the port of the website's own wrapper, and on
	 * this book it must produce a pair that actually makes money — and an ask
	 * ABOVE the traded price, not equal to it.
	 */
	@Test
	public void viewTargetsAnchorsToTheDayWhenTheSpreadIsDead()
	{
		final TradeEngine.Result t =
			TradeEngine.viewTargets(LIVE_LOW, LIVE_HIGH, NOW, NOW, dayWithRange(), RUBY);
		Assert.assertNotNull(t);
		Assert.assertTrue("it found a workable pair", t.viable);
		Assert.assertTrue("the ask is above the traded price, not equal to it — this is the bug",
			t.sell > LIVE_HIGH);
		Assert.assertTrue("and the bid is below the traded price", t.buy < LIVE_LOW);
		Assert.assertTrue("and the pair clears the tax",
			t.sell - t.buy - FlipTracker.taxPerItem(t.sell, RUBY) > 0);
	}

	/**
	 * The guard that stops this becoming wishful thinking: a genuinely flat
	 * item has no day range to fall back on, and must keep the engine's
	 * no-margin answer rather than being handed an invented one.
	 */
	@Test
	public void aTrulyFlatItemKeepsTheHonestNoMarginAnswer()
	{
		final int n = 120;
		final TradeEngine.Series flat = new TradeEngine.Series();
		flat.labels = new long[n];
		flat.low = new double[n];
		flat.high = new double[n];
		flat.lowVol = new double[n];
		flat.highVol = new double[n];
		for (int i = 0; i < n; i++)
		{
			flat.labels[i] = NOW - (long) (n - 1 - i) * 300L;
			flat.low[i] = 767;
			flat.high[i] = 779;
			flat.lowVol[i] = 400;
			flat.highVol[i] = 400;
		}

		final TradeEngine.Result t =
			TradeEngine.viewTargets(LIVE_LOW, LIVE_HIGH, NOW, NOW, flat, RUBY);
		Assert.assertNotNull(t);
		Assert.assertFalse("no day range, so no invented margin", t.viable);
	}

	/**
	 * And when the live spread IS alive, nothing changes at all — viewTargets
	 * has to be a pure extension, or every price in the plugin moves.
	 */
	@Test
	public void aLiveSpreadIsLeftExactlyAsTheEngineFoundIt()
	{
		final int n = 120;
		final TradeEngine.Series wide = new TradeEngine.Series();
		wide.labels = new long[n];
		wide.low = new double[n];
		wide.high = new double[n];
		wide.lowVol = new double[n];
		wide.highVol = new double[n];
		for (int i = 0; i < n; i++)
		{
			wide.labels[i] = NOW - (long) (n - 1 - i) * 300L;
			wide.low[i] = 1900 + Math.sin(i / 9.0) * 15;
			wide.high[i] = 2000 + Math.sin(i / 9.0) * 15;
			wide.lowVol[i] = 400;
			wide.highVol[i] = 400;
		}

		final TradeEngine.Result engine = TradeEngine.compute(1900, 2000, NOW, NOW, wide, 1601);
		final TradeEngine.Result view = TradeEngine.viewTargets(1900, 2000, NOW, NOW, wide, 1601);
		Assert.assertNotNull(view);
		Assert.assertTrue("this fixture has to be viable for the test to mean anything",
			engine.viable);
		Assert.assertEquals("same buy", engine.buy, view.buy);
		Assert.assertEquals("same sell", engine.sell, view.sell);
	}

	@Test
	public void aSeriesTooSparseToTrustYieldsNoDayPair()
	{
		Assert.assertNull("fewer than 8 prints a side", TradeEngine.swingTargets(null));
		final TradeEngine.Series thin = new TradeEngine.Series();
		thin.labels = new long[]{NOW - 600, NOW - 300, NOW};
		thin.low = new double[]{760, 761, 762};
		thin.high = new double[]{790, 791, 792};
		thin.lowVol = new double[]{1, 1, 1};
		thin.highVol = new double[]{1, 1, 1};
		Assert.assertNull(TradeEngine.swingTargets(thin));
	}
}
