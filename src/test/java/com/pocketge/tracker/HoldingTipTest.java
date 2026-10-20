package com.pocketge.tracker;

import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/**
 * The hover over a stack you bought: "i just bought these, hovering over
 * them should show how much profit available or losing".
 */
public class HoldingTipTest
{
	private static final int SAPPHIRE = 1607;

	private static String words(String markup)
	{
		return markup.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
	}

	@Test
	public void aStackInProfitSaysWhatIsAvailable()
	{
		final String t = words(BankHighlightOverlay.holdingText(SAPPHIRE, 5_789, 5_789, 5_789L * 400, 420));
		Assert.assertTrue(t, t.startsWith("+"));
		Assert.assertTrue(t, t.contains("gp profit available"));
		Assert.assertTrue(t, t.contains("Paid 400 gp each"));
		Assert.assertTrue(t, t.contains("Sells for 420 (412 after tax)"));
	}

	@Test
	public void aStackUnderwaterSaysWhatSellingLoses()
	{
		final String t = words(BankHighlightOverlay.holdingText(SAPPHIRE, 5_789, 5_789, 5_789L * 430, 420));
		Assert.assertTrue(t, t.startsWith("-"));
		Assert.assertTrue(t, t.contains("gp if you sell"));
	}

	/** A stack bigger than what the plugin saw bought: the cost covers the
	 *  tracked part, and the hover says so. */
	@Test
	public void onlyTheTrackedPartIsCosted()
	{
		final String t = words(BankHighlightOverlay.holdingText(SAPPHIRE, 8_000, 5_789, 5_789L * 400, 420));
		Assert.assertTrue(t, t.contains("Paid 400 gp each on 5,789 of 8,000"));
	}

	/** Only items the plugin has a cost for, priced at the insta-buy when
	 *  there is no series — what a sell card would ask. */
	@Test
	public void tipsAreForCostedItemsAtTheSellCardsPrice()
	{
		final Map<Integer, long[]> lots = new HashMap<>();
		lots.put(SAPPHIRE, new long[]{5_789, 5_789L * 400});
		lots.put(2359, new long[]{0, 0});
		final Map<Integer, Advisor.Quote> quotes = new HashMap<>();
		final Advisor.Quote q = new Advisor.Quote();
		q.low = 405;
		q.high = 420;
		quotes.put(SAPPHIRE, q);
		quotes.put(2359, q);
		final Map<Integer, long[]> tips = PocketGeTrackerPlugin.holdingTipsFor(lots, quotes, null);
		Assert.assertArrayEquals(new long[]{5_789, 5_789L * 400, 420}, tips.get(SAPPHIRE));
		Assert.assertFalse("no cost, no tip", tips.containsKey(2359));
	}
}
