package com.pocketge.tracker;

import net.runelite.api.Item;
import org.junit.Assert;
import org.junit.Test;

/**
 * What is waiting in a GE slot's collection box, on the sidebar square's
 * hover: "add how much money i have able to collect / how many additional
 * units have sold if any since last collect".
 */
public class CollectBoxTest
{
	private static final int MITHRIL_PLATEBODY = 1121;

	@Test
	public void eachSlotHasItsBox()
	{
		for (int slot = 0; slot < 6; slot++)
		{
			Assert.assertEquals(slot, PocketGeTrackerPlugin.collectSlot(518 + slot));
		}
		Assert.assertEquals(6, PocketGeTrackerPlugin.collectSlot(539));
		Assert.assertEquals(7, PocketGeTrackerPlugin.collectSlot(540));
		Assert.assertEquals("the offer containers are not the boxes", -1, PocketGeTrackerPlugin.collectSlot(526));
		Assert.assertEquals(-1, PocketGeTrackerPlugin.collectSlot(93));
	}

	@Test
	public void coinsAndTheOffersOwnItemAreCounted()
	{
		final long[] in = PocketGeTrackerPlugin.collectContents(
			new Item[]{new Item(995, 461_616), new Item(-1, 0), new Item(MITHRIL_PLATEBODY, 3)},
			MITHRIL_PLATEBODY, id -> id);
		Assert.assertArrayEquals(new long[]{461_616, 3}, in);
	}

	@Test
	public void anEmptyBoxIsZeroNotUnknown()
	{
		Assert.assertArrayEquals(new long[]{0, 0},
			PocketGeTrackerPlugin.collectContents(new Item[0], MITHRIL_PLATEBODY, id -> id));
	}

	/** Anything else in it and it is not the box we think it is: say nothing
	 *  rather than a wrong figure. */
	@Test
	public void aStrangerInTheBoxMeansItIsNotThisOffers()
	{
		Assert.assertNull(PocketGeTrackerPlugin.collectContents(
			new Item[]{new Item(995, 10), new Item(2359, 5)}, MITHRIL_PLATEBODY, id -> id));
	}

	/** A noted copy is the same item. */
	@Test
	public void notedCountsAsTheItem()
	{
		final long[] in = PocketGeTrackerPlugin.collectContents(
			new Item[]{new Item(1122, 40)}, MITHRIL_PLATEBODY, id -> id == 1122 ? MITHRIL_PLATEBODY : id);
		Assert.assertArrayEquals(new long[]{0, 40}, in);
	}

	private static GeSlotsPanel.SlotInfo sell(long coins, int items, int since)
	{
		final GeSlotsPanel.SlotInfo s = new GeSlotsPanel.SlotInfo();
		s.buy = false;
		s.collectCoins = coins;
		s.collectItems = items;
		s.sinceCollect = since;
		return s;
	}

	@Test
	public void theHoverSaysWhatIsWaiting()
	{
		Assert.assertEquals("To collect: 461,616 gp — 12 sold since you last collected",
			GeSlotsPanel.collectLine(sell(461_616, 0, 12)));
		Assert.assertEquals("To collect: 461,616 gp", GeSlotsPanel.collectLine(sell(461_616, 0, -1)));
		final GeSlotsPanel.SlotInfo buy = sell(1_200, 12_000, 12_000);
		buy.buy = true;
		Assert.assertEquals("To collect: 1,200 gp + 12,000 bought — 12,000 bought since you last collected",
			GeSlotsPanel.collectLine(buy));
	}

	@Test
	public void nothingWaitingOrNotSeenSaysNothing()
	{
		Assert.assertNull(GeSlotsPanel.collectLine(sell(0, 0, 0)));
		Assert.assertNull(GeSlotsPanel.collectLine(sell(-1, -1, -1)));
	}
}
