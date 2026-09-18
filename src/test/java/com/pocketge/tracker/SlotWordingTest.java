package com.pocketge.tracker;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import org.junit.Assert;
import org.junit.Test;

/**
 * The sidebar square and the in-game GE box say the same words about the same
 * slot.
 *
 * There are two hovers, and both were long: the in-game one ran to six lines,
 * and the sidebar's was a single run-on sentence — "Selling Ruby — 4,000 of
 * 12,328 (32%) (needs a new price). The market moved to 788 gp; yours is at
 * 791 gp. Re-list at 788 gp — aborting keeps whatever already filled.
 * Right-click to stop being told to reprice it. Click to inspect it." Every
 * clause true, and nobody reads a paragraph off a 32px square.
 *
 * Both are three short lines now. The part worth a test is not the length but
 * the AGREEMENT: two surfaces describing the same eight slots, and a state
 * that is called one thing in the sidebar and another in the Exchange window
 * is worse than either wording on its own. Nothing in the compiler stops them
 * drifting, because they are different classes with different data holders.
 */
public class SlotWordingTest
{
	/** The verbs both surfaces have to use, for the states both can be in. */
	private static final String PRICED_FINE = "Priced fine";
	private static final String LOWER = "Lower your ask to ";
	private static final String RAISE = "Raise your bid to ";
	private static final String NO_MARGIN = "No margin left";
	private static final String YOURS = "You are pricing this one";

	// ---- the in-game box ---------------------------------------------------

	private static String inGame(boolean buy, boolean adjust, boolean noMargin,
		boolean skipped, long target) throws Exception
	{
		final GeOfferGridOverlay.SlotView v = new GeOfferGridOverlay.SlotView();
		v.itemName = "Ruby";
		v.buy = buy;
		v.needsAdjust = adjust;
		v.noMargin = noMargin;
		v.adviceSkipped = skipped;
		v.targetPrice = target;
		v.offerPrice = 791;
		final Method m = GeOfferGridOverlay.class
			.getDeclaredMethod("stateLine", GeOfferGridOverlay.SlotView.class);
		m.setAccessible(true);
		return strip((String) m.invoke(null, v));
	}

	// ---- the sidebar square ------------------------------------------------

	private static String sidebar(boolean buy, GeSlotsPanel.SlotState state, boolean noMargin,
		boolean skipped, long target) throws Exception
	{
		final GeSlotsPanel.SlotInfo s = new GeSlotsPanel.SlotInfo();
		s.itemName = "Ruby";
		s.buy = buy;
		s.state = state;
		s.noMargin = noMargin;
		s.adviceSkipped = skipped;
		s.targetPrice = target;
		s.offerPrice = 791;

		/* The cell is a private inner class and its stateLine is an instance
		   method on it. Reached reflectively rather than widening either for
		   a test. */
		final Class<?> cell = Class.forName("com.pocketge.tracker.GeSlotsPanel$Cell");
		final Constructor<?> ctor = cell.getDeclaredConstructors()[0];
		ctor.setAccessible(true);
		final Object instance = alloc(cell);
		final Method m = cell.getDeclaredMethod("stateLine", GeSlotsPanel.SlotInfo.class);
		m.setAccessible(true);
		return strip((String) m.invoke(instance, s));
	}

	private static Object alloc(Class<?> c) throws Exception
	{
		final java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
		f.setAccessible(true);
		final sun.misc.Unsafe unsafe = (sun.misc.Unsafe) f.get(null);
		return unsafe.allocateInstance(c);
	}

	/** The in-game text carries RuneScape colour tags; the sidebar's does not.
	 *  Compare the words, not the markup. */
	private static String strip(String s)
	{
		return s.replaceAll("<[^>]*>", "").trim();
	}

	// ---- the agreement -----------------------------------------------------

	@Test
	public void bothCallAFineSlotTheSameThing() throws Exception
	{
		Assert.assertTrue(inGame(false, false, false, false, 0).startsWith(PRICED_FINE));
		Assert.assertTrue(
			sidebar(false, GeSlotsPanel.SlotState.ACTIVE_OK, false, false, 0).startsWith(PRICED_FINE));
	}

	@Test
	public void bothTellYouWhichWayToMoveASell() throws Exception
	{
		final String a = inGame(false, true, false, false, 788);
		final String b = sidebar(false, GeSlotsPanel.SlotState.ACTIVE_ADJUST, false, false, 788);
		Assert.assertTrue("in-game: " + a, a.startsWith(LOWER) && a.contains("788"));
		Assert.assertTrue("sidebar: " + b, b.startsWith(LOWER) && b.contains("788"));
		Assert.assertTrue("both show what you have in now",
			a.contains("791") && b.contains("791"));
	}

	@Test
	public void bothTellYouWhichWayToMoveABuy() throws Exception
	{
		Assert.assertTrue(inGame(true, true, false, false, 103).startsWith(RAISE));
		Assert.assertTrue(
			sidebar(true, GeSlotsPanel.SlotState.ACTIVE_ADJUST, false, false, 103).startsWith(RAISE));
	}

	@Test
	public void bothSayNoMarginRatherThanReprice() throws Exception
	{
		final String a = inGame(false, true, true, false, 0);
		final String b = sidebar(false, GeSlotsPanel.SlotState.ACTIVE_ADJUST, true, false, 0);
		Assert.assertTrue(a.startsWith(NO_MARGIN));
		Assert.assertTrue(b.startsWith(NO_MARGIN));
		Assert.assertFalse("repricing is the thing that does not help here", a.contains(LOWER));
		Assert.assertFalse(b.contains(LOWER));
	}

	@Test
	public void bothSayWhenYouHaveTakenTheSlotOver() throws Exception
	{
		Assert.assertTrue(inGame(false, true, false, true, 788).startsWith(YOURS));
		Assert.assertTrue(
			sidebar(false, GeSlotsPanel.SlotState.ACTIVE_ADJUST, false, true, 788).startsWith(YOURS));
	}

	/**
	 * Only the sidebar has this one: the in-game overlay never draws a
	 * collected offer, because buildSlotViews drops inactive ones before it
	 * gets there. So it is checked on the surface that has it.
	 */
	@Test
	public void theSidebarNamesACollectableSlot() throws Exception
	{
		Assert.assertEquals("Ready to collect.",
			sidebar(false, GeSlotsPanel.SlotState.READY_COLLECT, false, false, 0));
	}

	/** And neither of them is a paragraph any more. */
	@Test
	public void everyStateLineIsOneShortSentence() throws Exception
	{
		final String[] all = {
			inGame(false, false, false, false, 0),
			inGame(false, true, false, false, 788),
			inGame(true, true, false, false, 103),
			inGame(false, true, true, false, 0),
			inGame(false, true, false, true, 788),
			sidebar(false, GeSlotsPanel.SlotState.ACTIVE_OK, false, false, 0),
			sidebar(false, GeSlotsPanel.SlotState.ACTIVE_ADJUST, false, false, 788),
			sidebar(true, GeSlotsPanel.SlotState.ACTIVE_ADJUST, false, false, 103),
			sidebar(false, GeSlotsPanel.SlotState.ACTIVE_ADJUST, true, false, 0),
			sidebar(false, GeSlotsPanel.SlotState.READY_COLLECT, false, false, 0),
		};
		for (String line : all)
		{
			Assert.assertTrue("still a paragraph (" + line.length() + "): " + line,
				line.length() <= 50);
		}
	}
}
