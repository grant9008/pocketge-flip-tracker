package com.pocketge.tracker;

import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class ClearanceTest
{
	/** 10,787 ruby necklaces against a book printing 4,700 a side. */
	@Test
	public void measuresTheStackAgainstBothSidesOfTheBook()
	{
		final Clearance c = Clearance.of(10_787_000, 4_700_000, 4_700_000);
		Assert.assertNotNull(c);
		Assert.assertEquals("both sides, averaged", 4_700_000, c.flowPerDay);
		Assert.assertEquals(10_787_000 / 4_700_000d, c.days, 1e-9);
	}

	/**
	 * The defect that killed the scored version of this: the insta-buy side
	 * collapses in a bleed while the sell side swells, so a one-sided
	 * denominator spikes exactly when the price is falling. Averaging the two
	 * keeps the same total flow reading the same either way.
	 */
	@Test
	public void aLopsidedBookReadsTheSameAsABalancedOneOfTheSameSize()
	{
		final Clearance balanced = Clearance.of(5_000_000, 2_000_000, 2_000_000);
		final Clearance bleeding = Clearance.of(5_000_000, 400_000, 3_600_000);
		Assert.assertEquals("same total prints, same reading",
			balanced.days, bleeding.days, 1e-9);
	}

	/**
	 * A blue partyhat: 3 held, 8 insta-buys in a day. Arithmetically "0.4
	 * days of demand", which reads as a liquid position off a sample of
	 * eight. Silence is the only honest output.
	 */
	@Test
	public void saysNothingWhenThereIsNotEnoughEvidence()
	{
		Assert.assertNull(Clearance.of(3, 8, 11));
		Assert.assertNull("just under the print floor", Clearance.of(500_000, 50_000, 49_999));
		Assert.assertNotNull("and on it", Clearance.of(500_000, 50_000, 50_000));
	}

	@Test
	public void refusesTheCasesItCannotMeasure()
	{
		Assert.assertNull("nothing held", Clearance.of(0, 5_000_000, 5_000_000));
		Assert.assertNull("negative holding", Clearance.of(-1, 5_000_000, 5_000_000));
		Assert.assertNull("no prints at all", Clearance.of(100, 0, 0));
		Assert.assertNull("garbage volume", Clearance.of(100, -5, 5_000_000));
	}

	/** A stack far past the scale still says how far past. */
	@Test
	public void theTopEndIsNotCapped()
	{
		final Clearance c = Clearance.of(41_000_000, 1_000_000, 1_000_000);
		Assert.assertEquals(41.0, c.days, 1e-9);
		Assert.assertEquals("41 days of demand", c.label());
	}

	/**
	 * One form, not four. Flow of 2,000,000 a side throughout, so every
	 * fixture clears MIN_PRINTS and only the stack size moves.
	 */
	@Test
	public void alwaysReadsInDays()
	{
		Assert.assertEquals("2.3 days of demand", Clearance.of(4_600_000, 2_000_000, 2_000_000).label());
		Assert.assertEquals("1.0 days of demand", Clearance.of(2_000_000, 2_000_000, 2_000_000).label());
		Assert.assertEquals("9.9 days of demand", Clearance.of(19_800_000, 2_000_000, 2_000_000).label());
		Assert.assertEquals("15 days of demand", Clearance.of(30_000_000, 2_000_000, 2_000_000).label());
	}

	/**
	 * Anything that clears inside a day gets no line at all.
	 *
	 * The first cut printed one on everything it could measure, so nearly
	 * every sell read "under an hour of demand" — true, useless, and
	 * inconsistent between adjacent cards. A stack that clears in an hour is
	 * not a stack whose size you have to think about.
	 */
	@Test
	public void saysNothingAboutAStackThatClearsInsideADay()
	{
		Assert.assertNull("one unit of something liquid",
			Clearance.of(1, 20_000_000, 20_000_000));
		Assert.assertNull("half a day", Clearance.of(1_000_000, 2_000_000, 2_000_000));
		Assert.assertNull("just under a day", Clearance.of(1_999_999, 2_000_000, 2_000_000));
		Assert.assertNotNull("and exactly a day", Clearance.of(2_000_000, 2_000_000, 2_000_000));
	}

	/**
	 * The stack size is the numerator, so the number RISES with size. The
	 * opportunity-cost design was rejected for getting this backwards: its
	 * score fell as the stack grew, so it went quiet on the biggest, deadest
	 * position on the list.
	 */
	@Test
	public void aBiggerStackAlwaysReadsAsMoreDays()
	{
		double last = -1;
		for (long qty : new long[]{2_000_000, 4_000_000, 20_000_000, 200_000_000})
		{
			final double days = Clearance.of(qty, 2_000_000, 2_000_000).days;
			Assert.assertTrue("monotonic in stack size", days > last);
			last = days;
		}
	}

	/** Cost basis cannot reach the arithmetic — there is no parameter for it. */
	@Test
	public void theFactoryHasNoCostBasisParameter()
	{
		for (java.lang.reflect.Method m : Clearance.class.getDeclaredMethods())
		{
			if (m.getName().equals("of"))
			{
				Assert.assertEquals("of(qty, buySideVol, sellSideVol) and nothing else",
					3, m.getParameterCount());
				for (Class<?> p : m.getParameterTypes())
				{
					Assert.assertEquals(long.class, p);
				}
			}
		}
	}

	/**
	 * No fixed string this class can print may tell the player what the price
	 * is going to do, or what to do about it.
	 *
	 * RangePosition's class comment states the stance — the plugin reports
	 * measurements and refuses to imply a forecast — and until now nothing
	 * enforced it. A verdict word is one careless edit away at all times, so
	 * the words are banned in a test rather than in a comment.
	 */
	@Test
	public void neverTellsThePlayerWhatHappensNext()
	{
		final String[] banned = {
			"sell now", "dump", "peak", "bottom", "top out", "recover",
			"rebound", "crash", "should", "will ", "expect", "predict",
			"good time", "bad time", "act fast", "hurry",
		};
		final List<String> strings = new ArrayList<>();
		for (long[] c : new long[][]{
			{4_600_000, 2_000_000, 2_000_000}, {2_000_000, 2_000_000, 2_000_000},
			{30_000_000, 2_000_000, 2_000_000}, {41_000_000, 1_000_000, 1_000_000}})
		{
			final Clearance cl = Clearance.of(c[0], c[1], c[2]);
			strings.add(cl.label());
			strings.addAll(List.of(cl.tooltipLines()));
		}
		for (String s : strings)
		{
			final String lower = s.toLowerCase();
			for (String b : banned)
			{
				Assert.assertFalse("\"" + s + "\" contains forecast word \"" + b + "\"",
					lower.contains(b));
			}
		}
	}

	/**
	 * The assumption that most easily makes the number wrong for you has to
	 * be stated, not buried: two players each holding 2.3 days are between
	 * them 4.6 days of flow.
	 */
	@Test
	public void statesTheAssumptionThatCouldMakeItWrong()
	{
		final String all = String.join(" ", Clearance.of(4_600_000, 2_000_000, 2_000_000).tooltipLines());
		Assert.assertTrue(all, all.contains("only seller listing"));
		Assert.assertTrue(all, all.contains("both sides of the book"));
		Assert.assertTrue("refuses cost basis and direction out loud",
			all.contains("what you paid") && all.contains("where the price goes next"));
	}
}
