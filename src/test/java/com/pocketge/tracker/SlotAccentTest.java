package com.pocketge.tracker;

import java.awt.Color;
import org.junit.Assert;
import org.junit.Test;

/**
 * The sidebar strip and the in-game GE box agree about the same slot.
 *
 * Telling the plugin "I am pricing this one myself" muted the in-game box and
 * did nothing at all to the sidebar square, so the two surfaces described the
 * same offer differently — one saying "you have this in hand", the other
 * saying "priced fine", with no way to tell which was current.
 *
 * The data was already there. GeSlotsPanel.SlotInfo.adviceSkipped has been
 * populated all along; only the paint code ignored it, because the colour was
 * chosen from the STATE enum and "you are pricing this yourself" is not a
 * state — the offer is still perfectly active.
 */
public class SlotAccentTest
{
	private static GeSlotsPanel.SlotInfo slot(GeSlotsPanel.SlotState state, boolean skipped)
	{
		final GeSlotsPanel.SlotInfo s = new GeSlotsPanel.SlotInfo();
		s.state = state;
		s.adviceSkipped = skipped;
		return s;
	}

	@Test
	public void aSlotYouArePricingYourselfGoesMuted()
	{
		Assert.assertEquals("an offer priced fine, but yours to manage",
			GeSlotsPanel.MUTED_COLOR, GeSlotsPanel.accent(slot(GeSlotsPanel.SlotState.ACTIVE_OK, true)));
		Assert.assertEquals("and one the plugin would otherwise flag red",
			GeSlotsPanel.MUTED_COLOR, GeSlotsPanel.accent(slot(GeSlotsPanel.SlotState.ACTIVE_ADJUST, true)));
	}

	/**
	 * The muted grey is the SAME grey the in-game box uses. Two hand-written
	 * copies of a colour that has to match is how they stop matching, so
	 * there is one constant and this asserts the overlay still reads it.
	 */
	@Test
	public void bothSurfacesUseOneGrey()
	{
		Assert.assertEquals(GeSlotsPanel.OK_COLOR, GeSlotsPanel.MUTED_COLOR);
	}

	/**
	 * Three meanings, in the game's own colours: "green makes me think it
	 * means is done / red makes me think its done/canceled". Grey while it
	 * works, yellow when it needs a new price (losing included), green when
	 * it is done. No red anywhere on the squares.
	 */
	@Test
	public void greyWorkingYellowNeedsYouGreenDone()
	{
		Assert.assertEquals(new Color(0x9C, 0x95, 0x8A), GeSlotsPanel.OK_COLOR);
		Assert.assertEquals(new Color(0xFF, 0xCC, 0x33), GeSlotsPanel.ADJUST_COLOR);
		Assert.assertEquals("losing is a reprice too", GeSlotsPanel.ADJUST_COLOR, GeSlotsPanel.LOSING_COLOR);
		Assert.assertEquals(TipStyle.GOOD, GeSlotsPanel.COLLECT_COLOR);
		for (GeSlotsPanel.SlotState st : GeSlotsPanel.SlotState.values())
		{
			final Color c = GeSlotsPanel.accent(slot(st, false));
			Assert.assertFalse(st + " is not red", c.getRed() > 200 && c.getGreen() < 120);
		}
	}

	/**
	 * Not a collectable slot. The flag outlives the offer it was set on, and
	 * muting a slot that is ready to collect would hide the one state that
	 * actually wants a click. The in-game overlay never meets this case
	 * because it drops inactive offers before painting.
	 */
	@Test
	public void aCollectableSlotIsNeverMuted()
	{
		Assert.assertEquals(GeSlotsPanel.COLLECT_COLOR,
			GeSlotsPanel.accent(slot(GeSlotsPanel.SlotState.READY_COLLECT, true)));
	}

	@Test
	public void nothingElseMoves()
	{
		Assert.assertEquals(GeSlotsPanel.OK_COLOR,
			GeSlotsPanel.accent(slot(GeSlotsPanel.SlotState.ACTIVE_OK, false)));
		Assert.assertEquals(GeSlotsPanel.ADJUST_COLOR,
			GeSlotsPanel.accent(slot(GeSlotsPanel.SlotState.ACTIVE_ADJUST, false)));
		Assert.assertEquals(GeSlotsPanel.COLLECT_COLOR,
			GeSlotsPanel.accent(slot(GeSlotsPanel.SlotState.READY_COLLECT, false)));
	}

	/** A null info is an empty square, not a crash. */
	@Test
	public void noOfferIsNotAColour()
	{
		Assert.assertNotNull(GeSlotsPanel.accent((GeSlotsPanel.SlotInfo) null));
	}
}
