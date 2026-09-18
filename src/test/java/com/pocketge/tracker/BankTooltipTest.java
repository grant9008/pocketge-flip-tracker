package com.pocketge.tracker;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 * The hover text on a marked bank stack.
 *
 * Reported twice as hard to read. The faults were structural rather than
 * cosmetic, so they are pinned here: the two marks laid the same facts out in
 * different orders, the one figure you came for was buried behind two words,
 * the per-unit line was painted a brown two shades off the tooltip
 * background, and the count that connects the two numbers was missing
 * entirely.
 */
public class BankTooltipTest
{
	private static String tip(Advisor.Suggestion s, boolean recommended) throws Exception
	{
		final Method m = BankHighlightOverlay.class
			.getDeclaredMethod("tooltipText", Advisor.Suggestion.class, boolean.class);
		m.setAccessible(true);
		return (String) m.invoke(null, s, recommended);
	}

	private static Advisor.Suggestion sell(long price, int qty, long gross, String whyNow)
	{
		final Advisor.Suggestion s = new Advisor.Suggestion(
			Advisor.Suggestion.Type.SELL, 1601, "Diamond", price, qty, gross, "reason");
		s.grossValue = gross;
		s.whyNow = whyNow;
		return s;
	}

	/** Both marks, same four lines, same order. */
	@Test
	public void bothMarksLayTheSameFactsOutTheSameWay()throws Exception
	{
		final Advisor.Suggestion s = sell(1_635, 18_608, 29_600_000L, "up 12% on what you paid");
		final List<String> mine = List.of(tip(s, true).split("</br>"));
		final List<String> also = List.of(tip(s, false).split("</br>"));
		Assert.assertEquals("same number of lines", mine.size(), also.size());
		Assert.assertEquals(4, mine.size());
		for (int i = 1; i < mine.size(); i++)
		{
			Assert.assertEquals("line " + (i + 1) + " is identical on both marks",
				mine.get(i), also.get(i));
		}
	}

	/** Line one names the mark in the legend's own words, so the player is
	 *  not matching two vocabularies for one colour. */
	@Test
	public void namesTheMarkTheWayTheLegendDoes() throws Exception
	{
		final Advisor.Suggestion s = sell(1_635, 18_608, 29_600_000L, null);
		Assert.assertTrue(tip(s, true).contains("Your current suggestion"));
		Assert.assertTrue(tip(s, false).contains("Also worth selling"));
		Assert.assertFalse("the old panel-speak is gone",
			tip(s, true).contains("This is the flip on your panel"));
	}

	/**
	 * The figure leads its line. "Worth selling: 29.6M gp" put two words in
	 * front of the only number the player opened the tooltip for.
	 */
	@Test
	public void theMoneyLeadsRatherThanFollowingALabel() throws Exception
	{
		final String t = tip(sell(1_635, 18_608, 29_600_000L, null), true);
		final String money = t.split("</br>")[1];
		Assert.assertFalse(money, money.contains("Worth selling"));
		Assert.assertTrue("the figure is first on its line",
			money.indexOf("29.6M gp") < money.indexOf("after tax"));
	}

	/**
	 * The count, which was missing. Claiming "29.6M gp" and "1,635 gp each"
	 * without the number between them left the headline uncheckable.
	 */
	@Test
	public void showsTheArithmeticItIsClaiming() throws Exception
	{
		final String t = tip(sell(1_635, 18_608, 29_600_000L, null), true);
		Assert.assertTrue(t, t.contains("18,608 at 1,635 gp each"));
	}

	/** Thousands separators, like everything else the plugin prints. */
	@Test
	public void separatesThousands() throws Exception
	{
		final String t = tip(sell(1_635, 18_608, 29_600_000L, null), true);
		Assert.assertFalse("no unseparated run of digits", t.contains("18608"));
		Assert.assertFalse(t.contains("1635"));
	}

	/**
	 * The unreadable brown is gone. 8a8274 sat two shades off the tooltip's
	 * own background, which is the line that came back in a screenshot as
	 * hard to read.
	 */
	@Test
	public void noLowContrastText() throws Exception
	{
		for (boolean rec : new boolean[]{true, false})
		{
			final String t = tip(sell(1_635, 18_608, 29_600_000L, "rated sell right now"), rec);
			Assert.assertFalse(t, t.contains("8a8274"));
		}
	}

	/** A recommended stack the suggestion map has no entry for still gets a
	 *  header, and must not throw or print a stray price. */
	@Test
	public void survivesAStackWithNoSuggestion() throws Exception
	{
		final String t = tip(null, true);
		Assert.assertTrue(t, t.contains("Your current suggestion"));
		Assert.assertFalse("nothing to price", t.contains("gp"));
	}

	/** whyNow is optional, and its absence drops the line rather than
	 *  printing an empty one. */
	@Test
	public void dropsTheWhyLineWhenThereIsNoAnswer() throws Exception
	{
		final Advisor.Suggestion s = sell(1_635, 18_608, 29_600_000L, null);
		Assert.assertEquals(3, tip(s, true).split("</br>").length);
		s.whyNow = "up 12% on what you paid";
		Assert.assertEquals(4, tip(s, true).split("</br>").length);
	}
}
