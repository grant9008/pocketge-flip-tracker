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
		Assert.assertEquals(new Color(0x8A, 0x82, 0x74), GeSlotsPanel.MUTED_COLOR);
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
