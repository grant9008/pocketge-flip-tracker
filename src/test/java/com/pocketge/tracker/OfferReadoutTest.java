package com.pocketge.tracker;

import org.junit.Assert;
import org.junit.Test;

/**
 * Reading the Grand Exchange's own "N coins" readouts.
 *
 * The gold ring walks price -> quantity -> Confirm by asking whether the
 * running total the screen shows is target x quantity. That test used to
 * strip every non-digit from the line and parse the remainder as one number,
 * which works on a buy and cannot work on a sell: the Exchange prints a
 * sell's total NET of the 2% tax and puts the gross and the rate in brackets
 * after it.
 *
 * So the ring reached Confirm on a buy and stuck on the quantity control on a
 * sell no matter how complete the offer was — "it should be highlighting the
 * confirm now in this example. i already selected price and quantity".
 */
public class OfferReadoutTest
{
	/** The string that broke it, verbatim from the offer screen. */
	private static final String SELL_TOTAL = "8,502,000 coins (8,658,000 - 2%)";

	@Test
	public void findsTheGrossOnASellTotal()
	{
		Assert.assertTrue("the gross, which is target x quantity",
			GeOfferPriceOverlay.textShowsValue(SELL_TOTAL, 8_658_000L));
	}

	/** The old rule's actual output, pinned so nobody reinvents it. */
	@Test
	public void doesNotConcatenateEveryDigitOnTheLine()
	{
		Assert.assertFalse("850200086580002 is not a number on this screen",
			GeOfferPriceOverlay.textShowsValue(SELL_TOTAL, 850_200_086_580_002L));
	}

	/** The net is on the line too, and is a real number on it. */
	@Test
	public void findsTheNetAsWell()
	{
		Assert.assertTrue(GeOfferPriceOverlay.textShowsValue(SELL_TOTAL, 8_502_000L));
	}

	/** A buy has no bracket and never did break. */
	@Test
	public void stillReadsAPlainBuyTotal()
	{
		Assert.assertTrue(GeOfferPriceOverlay.textShowsValue("8,658,000 coins", 8_658_000L));
		Assert.assertTrue(GeOfferPriceOverlay.textShowsValue("333 coins", 333L));
	}

	/** And it is exact: a total that merely contains the digits is not a
	 *  match, or the ring would advance on a number nobody entered. */
	@Test
	public void doesNotMatchASubstringOfANumber()
	{
		Assert.assertFalse(GeOfferPriceOverlay.textShowsValue("8,658,000 coins", 658L));
		Assert.assertFalse(GeOfferPriceOverlay.textShowsValue("8,658,000 coins", 8_658L));
	}

	@Test
	public void refusesTheDegenerateCases()
	{
		Assert.assertFalse(GeOfferPriceOverlay.textShowsValue(null, 100L));
		Assert.assertFalse(GeOfferPriceOverlay.textShowsValue("100 coins", 0L));
		Assert.assertFalse(GeOfferPriceOverlay.textShowsValue("100 coins", -5L));
		Assert.assertFalse("no digits at all",
			GeOfferPriceOverlay.textShowsValue("Enter price", 100L));
	}

	/**
	 * A run of digits far too long to be gp does not throw — it is skipped and
	 * the scan carries on to the numbers that matter.
	 */
	@Test
	public void survivesANumberTooBigForALong()
	{
		Assert.assertTrue(GeOfferPriceOverlay.textShowsValue(
			"99999999999999999999999 coins (333 - 2%)", 333L));
	}
}
