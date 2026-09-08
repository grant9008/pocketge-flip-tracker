package com.pocketge.tracker;

import org.junit.Assert;
import org.junit.Test;

/**
 * The fill percentage on a GE slot tooltip. Every case here is a boundary,
 * which is the whole reason it is a pure static rather than a method buried
 * in a Swing component.
 */
public class GeSlotsPercentTest
{
	/** The case that prompted the change: a real fill against a five-figure
	 *  4-hour limit. As an integer this floored to "0", which says nothing
	 *  has happened about an offer that has genuinely started. */
	@Test
	public void aSmallFillAgainstABigLimitIsNotZero()
	{
		Assert.assertEquals("0.76", GeSlotsPanel.percentText(138, 18_000));
	}

	/** One item out of a huge limit is still a fill. It must not round away
	 *  to nothing — that is the same bug in a smaller number. */
	@Test
	public void aSingleItemNeverReportsAsZero()
	{
		Assert.assertEquals("0.01", GeSlotsPanel.percentText(1, 18_000));
		Assert.assertEquals("0.01", GeSlotsPanel.percentText(1, 1_000_000));
	}

	/** Nothing filled reads a flat 0 — "0.00%" for an offer that has not
	 *  moved is false precision. */
	@Test
	public void nothingFilledIsAFlatZero()
	{
		Assert.assertEquals("0", GeSlotsPanel.percentText(0, 18_000));
	}

	/** Whole numbers once there is a whole number to show. */
	@Test
	public void aboveOnePercentIsAWholeNumber()
	{
		Assert.assertEquals("1", GeSlotsPanel.percentText(180, 18_000));
		Assert.assertEquals("50", GeSlotsPanel.percentText(9_000, 18_000));
		Assert.assertEquals("33", GeSlotsPanel.percentText(6_000, 18_000));
	}

	/**
	 * Short of complete never reads 100. An offer 35,999 of 36,000 done is
	 * the one misreading that sends you to the GE for nothing.
	 */
	@Test
	public void almostDoneIsNeverAHundred()
	{
		Assert.assertEquals("99", GeSlotsPanel.percentText(35_999, 36_000));
		Assert.assertEquals("99", GeSlotsPanel.percentText(17_999, 18_000));
	}

	@Test
	public void completeIsAHundred()
	{
		Assert.assertEquals("100", GeSlotsPanel.percentText(18_000, 18_000));
		Assert.assertEquals("100", GeSlotsPanel.percentText(19_000, 18_000)); // over-fill clamps
	}

	/** Junk in, silence out — never a divide by zero or a negative. */
	@Test
	public void nonsenseInputsDoNotThrow()
	{
		Assert.assertEquals("0", GeSlotsPanel.percentText(5, 0));
		Assert.assertEquals("0", GeSlotsPanel.percentText(-5, 100));
		Assert.assertEquals("0", GeSlotsPanel.percentText(0, 0));
	}

	/** The crossover: just under 1% keeps decimals, exactly 1% does not. */
	@Test
	public void theCrossoverAtOnePercent()
	{
		Assert.assertEquals("0.99", GeSlotsPanel.percentText(99, 10_000));
		Assert.assertEquals("1", GeSlotsPanel.percentText(100, 10_000));
	}
}
