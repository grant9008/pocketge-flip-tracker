package com.pocketge.tracker;

import java.lang.reflect.Method;
import org.junit.Assert;
import org.junit.Test;

/**
 * The GE slot's hover says what to do, in a couple of words.
 *
 * It ran to six lines — item, "Buying N of M", projected profit, profit so
 * far, a diagnosis and an instruction. Every line was true and the whole was
 * no easier to read than the coloured border it existed to explain. The report
 * was "such a long hover tool tip … can't it just turn red and say something
 * like adjust price, or lower price, raise price, no margin".
 *
 * So: at most three lines, and the state line is a VERB. "Off the market"
 * named a problem and left you to go and find the number.
 */
public class SlotTooltipTest
{
	private static String tip(SlotShape shape) throws Exception
	{
		final Method m = GeOfferGridOverlay.class
			.getDeclaredMethod("tooltipText", GeOfferGridOverlay.SlotView.class);
		m.setAccessible(true);
		return (String) m.invoke(null, shape.view);
	}

	/** A slot, built by hand — SlotView is a plain data holder. */
	private static final class SlotShape
	{
		final GeOfferGridOverlay.SlotView view = new GeOfferGridOverlay.SlotView();

		SlotShape(boolean buy)
		{
			view.itemName = "Ruby";
			view.buy = buy;
			view.filled = 4_000;
			view.total = 12_328;
			view.offerPrice = 791;
		}

		SlotShape adjust(long target)
		{
			view.needsAdjust = true;
			view.targetPrice = target;
			return this;
		}

		SlotShape noMargin()
		{
			view.needsAdjust = true;
			view.noMargin = true;
			return this;
		}

		SlotShape skipped()
		{
			view.adviceSkipped = true;
			return this;
		}

		SlotShape profit(long p)
		{
			view.projectedProfit = p;
			return this;
		}
	}

	/** Lines, as the client counts them. */
	private static int lines(String tip)
	{
		return tip.split("</br>", -1).length;
	}

	@Test
	public void aMispricedSellSaysWhichWayToMoveAndToWhat() throws Exception
	{
		final String t = tip(new SlotShape(false).adjust(788).profit(145_000));
		Assert.assertTrue("names the direction: " + t, t.contains("Lower your ask to "));
		Assert.assertTrue("and the exact price, with separators", t.contains("788 gp"));
		Assert.assertTrue("and what you currently have in", t.contains("yours: 791"));
	}

	@Test
	public void aMispricedBuySaysTheOtherDirection() throws Exception
	{
		final String t = tip(new SlotShape(true).adjust(103).profit(80_000));
		Assert.assertTrue("a bid is always too LOW when it is wrong: " + t,
			t.contains("Raise your bid to "));
		Assert.assertFalse("never both", t.contains("Lower your ask"));
	}

	/**
	 * The green state now says something. It has always meant "not red",
	 * which is true and tells you nothing — reported as: I ignored the
	 * suggested price, typed my own, and the box stayed green, was it even
	 * listening? It was; the difference was inside the drift threshold.
	 */
	@Test
	public void aFineSlotSaysSoRatherThanJustNotBeingRed() throws Exception
	{
		final String t = tip(new SlotShape(false).profit(145_000));
		Assert.assertTrue("says it is fine: " + t, t.contains("Priced fine"));
		Assert.assertFalse("and does not ask for anything", t.contains("re-list"));
	}

	@Test
	public void noMarginSaysToWalkAwayRatherThanReprice() throws Exception
	{
		final String t = tip(new SlotShape(false).noMargin().profit(-4_000));
		Assert.assertTrue("no margin: " + t, t.contains("No margin left"));
		Assert.assertTrue("and what to do instead", t.contains("take a new flip"));
		Assert.assertFalse("repricing is the thing that does NOT help here",
			t.contains("Lower your ask"));
	}

	@Test
	public void aSlotYouArePricingYourselfSaysOnlyThat() throws Exception
	{
		final String t = tip(new SlotShape(false).skipped().profit(1));
		Assert.assertTrue(t.contains("You are pricing this one"));
		Assert.assertFalse("no instruction, because you turned them off",
			t.contains("Lower your ask") || t.contains("Priced fine"));
	}

	/** The whole point: it has to be SHORT. */
	@Test
	public void noShapeRunsPastThreeLines() throws Exception
	{
		Assert.assertTrue(lines(tip(new SlotShape(false).adjust(788).profit(145_000))) <= 3);
		Assert.assertTrue(lines(tip(new SlotShape(true).adjust(103).profit(80_000))) <= 3);
		Assert.assertTrue(lines(tip(new SlotShape(false).noMargin().profit(-4_000))) <= 3);
		Assert.assertTrue(lines(tip(new SlotShape(false).skipped().profit(1))) <= 3);
		Assert.assertTrue(lines(tip(new SlotShape(false).profit(145_000))) <= 3);
		/* And the one with no tracked cost, which is a different third line. */
		Assert.assertTrue(lines(tip(new SlotShape(false).adjust(788))) <= 3);
	}

	/**
	 * The lines that went, and must not creep back: the game already prints
	 * the quantity in the box, and "So far" is the same figure as the
	 * projection until an offer is part-filled.
	 */
	@Test
	public void theDuplicatedLinesAreGone() throws Exception
	{
		final SlotShape s = new SlotShape(false).adjust(788).profit(145_000);
		s.view.filledProfit = 40_000L;
		final String t = tip(s);
		Assert.assertFalse("the game prints this in the box itself: " + t, t.contains(" of "));
		Assert.assertFalse("a detail, not a verdict", t.contains("So far"));
	}

	/**
	 * A sell the plugin never watched being bought must not claim a profit
	 * measured from a cost of zero.
	 */
	@Test
	public void anUntrackedSellClaimsNoProfit() throws Exception
	{
		final String t = tip(new SlotShape(false).adjust(788));
		Assert.assertTrue("says why there is no figure: " + t, t.contains("Cost unknown"));
		Assert.assertFalse(t.contains("Profit:"));
	}
}
