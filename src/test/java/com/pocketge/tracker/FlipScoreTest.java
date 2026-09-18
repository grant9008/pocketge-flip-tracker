package com.pocketge.tracker;

import org.junit.Assert;
import org.junit.Test;

/**
 * The plugin's flip score against the website's.
 *
 * Every expected value here was produced by running pocketge.com's own
 * recScoreParts()/recVerdict() (app.js) in node over the same inputs, not
 * by working the formula out by hand — so this pins the port to the site,
 * not to the porter's understanding of it. The first two rows are real
 * cards from the site: Demon tear at 2.14% edge on 16.38M/day scored 87
 * Prime; Adamant dart tip at 1.09% on 7.19M/day scored 70 Strong.
 */
public class FlipScoreTest
{
	private static void matches(double edgePct, long vol, boolean lowConf,
		double edge, double liq, double penalty, int total, String word, int color)
	{
		final TradeEngine.FlipScore s = TradeEngine.FlipScore.of(edgePct, vol, lowConf);
		Assert.assertEquals(20, s.base, 0);
		Assert.assertEquals("edge part", edge, s.edge, 0.001);
		Assert.assertEquals("liquidity part", liq, s.liq, 0.001);
		Assert.assertEquals("thin-tape penalty", penalty, s.penalty, 0.001);
		Assert.assertEquals("total", total, s.total);
		Assert.assertEquals("verdict", word, s.band.word);
		Assert.assertEquals("band colour", color, s.band.color);
	}

	@Test
	public void demonTearScoresPrime()
	{
		matches(0.0214, 16_380_000, false, 32.1, 35, 0, 87, "Prime Flip", 0xFFFFFF);
	}

	@Test
	public void adamantDartTipScoresStrong()
	{
		matches(0.0109, 7_190_000, false, 16.35, 33.3285, 0, 70, "Strong Flip", 0xFFEFC2);
	}

	@Test
	public void bothTermsMaxedIsAHundred()
	{
		matches(0.03, 10_000_000, false, 45, 35, 0, 100, "Prime Flip", 0xFFFFFF);
		final TradeEngine.FlipScore s = TradeEngine.FlipScore.of(0.03, 10_000_000, false);
		Assert.assertTrue(s.edgeMaxed);
		Assert.assertTrue(s.liqMaxed);
	}

	@Test
	public void edgeSaturatesAtThreePercent()
	{
		/* 11.8% and 3.0% bank the same 45: past the cap the score is all
		   liquidity, which is the thing the tooltip has to be able to say. */
		matches(0.118, 250_000, false, 45, 16.3093, 0, 81, "Strong Flip", 0xFFEFC2);
		Assert.assertEquals(
			TradeEngine.FlipScore.of(0.118, 250_000, false).edge,
			TradeEngine.FlipScore.of(0.03, 250_000, false).edge, 0);
	}

	@Test
	public void thinEdgeOnThinVolumeIsThin()
	{
		matches(0.005, 50_000, false, 7.5, 8.1547, 0, 36, "Thin Flip", 0xFF9F43);
	}

	@Test
	public void thinTapeTakesAFifthOff()
	{
		matches(0.02, 2_000_000, true, 30, 26.8453, 15.3691, 61, "Solid Flip", 0xFFD24D);
	}

	@Test
	public void engineClearedIsWorthTwentyOnItsOwn()
	{
		/* The floor: a viable pair with no edge to speak of and no volume
		   still scores 20, never 0 — the bands never say "don't". */
		matches(0.0, 1, false, 0, 0, 0, 20, "Thin Flip", 0xFF9F43);
		matches(0.015, 0, false, 22.5, 0, 0, 43, "Thin Flip", 0xFF9F43);
	}

	@Test
	public void bandEdgesAreInclusive()
	{
		Assert.assertEquals("Thin Flip", TradeEngine.FlipScore.verdict(54).word);
		Assert.assertEquals("Solid Flip", TradeEngine.FlipScore.verdict(55).word);
		Assert.assertEquals("Strong Flip", TradeEngine.FlipScore.verdict(70).word);
		Assert.assertEquals("Prime Flip", TradeEngine.FlipScore.verdict(85).word);
		Assert.assertEquals("Prime Flip", TradeEngine.FlipScore.verdict(100).word);
	}
}
