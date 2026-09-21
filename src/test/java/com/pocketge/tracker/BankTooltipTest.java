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

	/**
	 * One hue in the bank, and it is the SELL colour, because everything a
	 * bank square marks is something to sell. The live suggestion is picked
	 * out in white instead — the card's own LEAD_RIM, which is already how
	 * the sidebar says "this is the one you are acting on".
	 *
	 * Two earlier versions got this wrong: a hardcoded gold and the PROFIT
	 * green (which moved with nothing and made a claim about a stack the
	 * plugin had not valued), then the theme's buy/sell pair, which left one
	 * sell square painted in the BUY colour.
	 */
	@Test
	public void theBankSpeaksInTheThemesSellColourAndWhite() throws Exception
	{
		final PocketGeTrackerConfig.ColourTheme neon = PocketGeTrackerConfig.ColourTheme.NEON;
		BankHighlightOverlay.setTheme(neon.sell());
		try
		{
			final Advisor.Suggestion s = sell(1_635, 18_608, 29_600_000L, null);
			Assert.assertTrue("the live suggestion is white, like the card's lead box",
				tip(s, true).toLowerCase().contains("f2f2f2"));
			Assert.assertTrue("the rest are the theme's sell colour",
				tip(s, false).toLowerCase().contains("22e0ff"));
			Assert.assertFalse("never the buy colour — nothing here is a buy",
				tip(s, false).toLowerCase().contains("ff44b0"));
			Assert.assertFalse("and never the old hardcoded green",
				tip(s, false).toLowerCase().contains("1fb85c"));
		}
		finally
		{
			BankHighlightOverlay.setTheme(PocketGeTrackerConfig.ColourTheme.TERMINAL.sell());
		}
	}

	/**
	 * The stack's worth is proceeds, not profit, so it must not wear the
	 * profit colour. The sidebar spent three attempts learning that a figure
	 * in the profit green reads as profit however it is worded.
	 */
	@Test
	public void doesNotPaintProceedsAsProfit() throws Exception
	{
		final String t = tip(sell(1_635, 18_608, 29_600_000L, null), true);
		final String money = t.split("</br>")[1];
		Assert.assertFalse(money, money.toLowerCase().contains("1fb85c"));
	}

	/**
	 * Every colour in the tooltip moves with the theme, including the
	 * "why now" line.
	 *
	 * That one was a hardcoded 26a9ab — Terminal's teal — so it stayed teal
	 * under Neon, Cobalt and Orchid while the header and the square itself
	 * moved. A literal hex anywhere in this string is the bug.
	 */
	@Test
	public void noFrozenColourAnywhere() throws Exception
	{
		final PocketGeTrackerConfig.ColourTheme neon = PocketGeTrackerConfig.ColourTheme.NEON;
		BankHighlightOverlay.setTheme(neon.sell());
		try
		{
			final String t = tip(sell(1_635, 18_608, 29_600_000L, "rated sell right now"), false)
				.toLowerCase();
			Assert.assertFalse("Terminal's teal is frozen in", t.contains("26a9ab"));
			Assert.assertTrue("the why-now line is the theme's sell colour",
				t.contains("22e0ff"));
		}
		finally
		{
			BankHighlightOverlay.setTheme(PocketGeTrackerConfig.ColourTheme.TERMINAL.sell());
		}
	}

	/**
	 * What you paid, and on how many — asked for after the sidebar card grew
	 * the same line: "can we add hover over, paid x for x many, on bank stacks
	 * too i like that".
	 *
	 * The count is the half that cannot be dropped. A partly tracked stack is
	 * the ordinary case in a real bank, and a bare "Paid 1,141 gp each" on an
	 * 18,608 stack is a claim about all 18,608 when it is only true of 1,456.
	 */
	@Test
	public void saysWhatYouPaidAndOnHowManyOfThem() throws Exception
	{
		final Advisor.Suggestion s = sell(1_635, 18_608, 29_600_000L, null);
		s.unitCost = 1_141;
		s.trackedQty = 1_456;
		final String t = tip(s, true);
		Assert.assertTrue(t, t.contains("Paid 1,141 gp each on 1,456 of 18,608"));
	}

	/** A stack bought entirely through the plugin says so, rather than
	 *  printing "18,608 of 18,608" and leaving the reader to compare them. */
	@Test
	public void aFullyTrackedStackSaysAll() throws Exception
	{
		final Advisor.Suggestion s = sell(1_635, 18_608, 29_600_000L, null);
		s.unitCost = 1_141;
		s.trackedQty = 18_608;
		Assert.assertTrue(tip(s, true).contains("Paid 1,141 gp each on all 18,608"));
	}

	/**
	 * No tracked purchase, no line. An untracked stack has no honest "you
	 * paid" — and a dash or a zero in that slot is a worse answer than the
	 * absence of one.
	 */
	@Test
	public void saysNothingAboutCostItDoesNotHave() throws Exception
	{
		final Advisor.Suggestion s = sell(1_635, 18_608, 29_600_000L, null);
		s.hasTrackedCost = false;
		Assert.assertFalse(tip(s, true).contains("Paid"));
		s.unitCost = 1_141;
		s.trackedQty = 0;
		Assert.assertFalse("a price with nothing to apply it to", tip(s, true).contains("Paid"));
	}

	/** trackedQty can outrun the stack in the bank once part of it has been
	 *  withdrawn; "1,456 of 900" would be nonsense on a scoping line. */
	@Test
	public void neverScopesToMoreUnitsThanAreThere() throws Exception
	{
		final Advisor.Suggestion s = sell(1_635, 900, 1_400_000L, null);
		s.unitCost = 1_141;
		s.trackedQty = 1_456;
		Assert.assertTrue(tip(s, true).contains("Paid 1,141 gp each on all 900"));
	}

	/** Muted, like the count line it sits under — never the buy colour,
	 *  however much the word "paid" wants it. One hue in the bank. */
	@Test
	public void theCostLineDoesNotImportTheBuyColour() throws Exception
	{
		BankHighlightOverlay.setTheme(PocketGeTrackerConfig.ColourTheme.NEON.sell());
		try
		{
			final Advisor.Suggestion s = sell(1_635, 18_608, 29_600_000L, null);
			s.unitCost = 1_141;
			s.trackedQty = 1_456;
			final String paid = tip(s, true).split("</br>")[3];
			Assert.assertTrue(paid, paid.contains("Paid"));
			Assert.assertTrue("muted, like the arithmetic above it", paid.contains("a5a5a5"));
			Assert.assertFalse("nothing here is a buy",
				paid.toLowerCase().contains("ff44b0"));
		}
		finally
		{
			BankHighlightOverlay.setTheme(PocketGeTrackerConfig.ColourTheme.TERMINAL.sell());
		}
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
