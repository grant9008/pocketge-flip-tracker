package com.pocketge.tracker;

import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 * Which cards you page through first.
 *
 * Reported with 2,910 Mithril bars in the bank (2.66M, kept for use) and
 * gold free to flip with: twenty "sell from your bank" cards came before any
 * flip — "i keep finding myself clicking 15 pages back just to find 5
 * recommended flips".
 */
public class RecommendationOrderTest
{
	private static final long FLOOR = 2_000;
	private static final int MAX = 20;

	private static AdvisorPanel.Rec buy(String name, long profit, long capital)
	{
		final AdvisorPanel.Rec r = new AdvisorPanel.Rec();
		r.name = name;
		r.sell = false;
		r.profit = profit;
		r.capital = capital;
		return r;
	}

	private static AdvisorPanel.Rec sell(String name, long profit)
	{
		final AdvisorPanel.Rec r = new AdvisorPanel.Rec();
		r.name = name;
		r.sell = true;
		r.profit = profit;
		return r;
	}

	private static List<AdvisorPanel.Rec> buys(int n, long profit)
	{
		final List<AdvisorPanel.Rec> out = new ArrayList<>();
		for (int i = 0; i < n; i++)
		{
			out.add(buy("flip " + i, profit, 700_000));
		}
		return out;
	}

	private static List<AdvisorPanel.Rec> sells(int n)
	{
		final List<AdvisorPanel.Rec> out = new ArrayList<>();
		out.add(sell("Mithril bar", 100_000));
		for (int i = 1; i < n; i++)
		{
			out.add(sell("stack " + i, 50_000));
		}
		return out;
	}

	private static List<AdvisorPanel.Rec> order(List<AdvisorPanel.Rec> buys, List<AdvisorPanel.Rec> sells)
	{
		final List<AdvisorPanel.Rec> out = new ArrayList<>();
		PocketGeTrackerPlugin.orderRecommendations(out, buys, sells, FLOOR, MAX);
		return out;
	}

	/** The reported bank: the flips come first, and the stacks still follow. */
	@Test
	public void flipsLeadABankWorthMoreThanTheCash()
	{
		final List<AdvisorPanel.Rec> out = order(buys(20, 20_000), sells(20));
		Assert.assertEquals(MAX, out.size());
		for (int i = 0; i < MAX - PocketGeTrackerPlugin.SELLS_KEPT_BEHIND_FLIPS; i++)
		{
			Assert.assertFalse("card " + (i + 1) + " is a flip", out.get(i).sell);
		}
		Assert.assertEquals("the best sell is still on the list", "Mithril bar",
			out.get(MAX - PocketGeTrackerPlugin.SELLS_KEPT_BEHIND_FLIPS).name);
	}

	/** Sells no longer use up the places before the buys are counted: the
	 *  five flips that used to be on pages 16-20 are pages 1-5. */
	@Test
	public void aFewFlipsAreNotPushedToTheEnd()
	{
		final List<AdvisorPanel.Rec> out = order(buys(5, 20_000), sells(30));
		for (int i = 0; i < 5; i++)
		{
			Assert.assertFalse(out.get(i).sell);
		}
		Assert.assertEquals(MAX, out.size());
		Assert.assertTrue(out.get(5).sell);
	}

	/** "Buy 40 Lobster pot for 1 gp ea" at a 344 gp purse: no real flip, so
	 *  the bank leads, as before. */
	@Test
	public void withNoRealFlipTheSellsLead()
	{
		final List<AdvisorPanel.Rec> out = order(List.of(buy("Lobster pot", 40, 40)), sells(3));
		Assert.assertTrue(out.get(0).sell);
		Assert.assertEquals("Lobster pot", out.get(out.size() - 1).name);
	}

	/** The adjust nudges a full board puts first stay first. */
	@Test
	public void whatIsAlreadyThereStaysFirst()
	{
		final List<AdvisorPanel.Rec> out = new ArrayList<>();
		out.add(sell("re-list your Rune bar", 5_000));
		PocketGeTrackerPlugin.orderRecommendations(out, buys(20, 20_000), sells(20), FLOOR, MAX);
		Assert.assertEquals("re-list your Rune bar", out.get(0).name);
		Assert.assertFalse(out.get(1).sell);
		Assert.assertEquals(MAX, out.size());
	}

	/** Few sells: the flips take every place the sells do not need. */
	@Test
	public void flipsFillWhatTheSellsDoNotNeed()
	{
		final List<AdvisorPanel.Rec> out = order(buys(25, 20_000), sells(2));
		Assert.assertEquals(MAX, out.size());
		Assert.assertEquals(18, out.stream().filter(r -> !r.sell).count());
	}

	/** The sell that finishes a flip you are in keeps its place behind the
	 *  flips, however many bigger stacks you held from before. */
	@Test
	public void aTrackedFlipsSellIsNotPushedOutByOldStacks()
	{
		final List<AdvisorPanel.Rec> sells = new ArrayList<>();
		for (String name : new String[]{"Mithril bar", "Coal", "Law rune", "Iron ore", "Nature rune", "Feather"})
		{
			final AdvisorPanel.Rec r = sell(name, 700_000);
			r.hasTrackedCost = false; // held from before: ranked on its whole value
			sells.add(r);
		}
		sells.add(sell("Yew logs", 34_000)); // bought through the plugin, ranked on profit
		final List<AdvisorPanel.Rec> out = order(buys(20, 5_000), sells);
		final int first = MAX - PocketGeTrackerPlugin.SELLS_KEPT_BEHIND_FLIPS;
		Assert.assertEquals("Yew logs", out.get(first).name);
		Assert.assertEquals("then the biggest of the rest, in rank order", "Mithril bar", out.get(first + 1).name);
	}

	/** A player's own Min profit raises the bar a flip has to clear to lead. */
	@Test
	public void theFloorIsThePlayersOwn()
	{
		final List<AdvisorPanel.Rec> out = new ArrayList<>();
		PocketGeTrackerPlugin.orderRecommendations(out, buys(3, 20_000), sells(3), 50_000, MAX);
		Assert.assertTrue(out.get(0).sell);
	}
}
