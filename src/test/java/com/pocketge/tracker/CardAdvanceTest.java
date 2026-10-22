package com.pocketge.tracker;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 * "after i have a rune dart trade in, the rune dart reccomendation trade
 * idea should automatically go to the next trade idea" — paused or not.
 */
public class CardAdvanceTest
{
	private static final int RUNE_DART = 811;
	private static final int STEAM_RUNE = 4694;
	private static final int BLOOD_RUNE = 565;

	/** What the card announced last: the item on screen. */
	private final List<Integer> shown = new ArrayList<>();

	private AdvisorPanel panel()
	{
		final AdvisorPanel.Actions actions = (AdvisorPanel.Actions) Proxy.newProxyInstance(
			AdvisorPanel.Actions.class.getClassLoader(), new Class<?>[]{AdvisorPanel.Actions.class},
			(proxy, method, args) ->
			{
				if ("onRecommendationShown".equals(method.getName()))
				{
					shown.add((Integer) args[0]);
				}
				final Class<?> t = method.getReturnType();
				if (t == boolean.class)
				{
					return false;
				}
				if (t == int.class || t == long.class || t == double.class || t == float.class)
				{
					return 0;
				}
				return null;
			});
		return new AdvisorPanel(null, actions);
	}

	private static AdvisorPanel.Rec buy(int id, String name)
	{
		final AdvisorPanel.Rec r = new AdvisorPanel.Rec();
		r.itemId = id;
		r.name = name;
		r.sell = false;
		r.quantity = 1_000;
		r.unitPrice = 100;
		r.profit = 50_000;
		return r;
	}

	private static List<AdvisorPanel.Rec> list(AdvisorPanel.Rec... recs)
	{
		return new ArrayList<>(java.util.Arrays.asList(recs));
	}

	private Integer onScreen()
	{
		return shown.isEmpty() ? null : shown.get(shown.size() - 1);
	}

	private static void setIndex(AdvisorPanel p, int i) throws Exception
	{
		final Field f = AdvisorPanel.class.getDeclaredField("recIndex");
		f.setAccessible(true);
		f.setInt(p, i);
	}

	/** Paused on Rune dart, the rune darts go in: the pause lifts, and the
	 *  card moves to the next idea. */
	@Test
	public void placingThePausedCardsOfferMovesOn()
	{
		final AdvisorPanel p = panel();
		p.setRecommendations(list(buy(RUNE_DART, "Rune dart"), buy(STEAM_RUNE, "Steam rune"), buy(BLOOD_RUNE, "Blood rune")));
		Assert.assertEquals(Integer.valueOf(RUNE_DART), onScreen());
		p.pauseButton().doClick();
		p.setRecommendations(list(buy(BLOOD_RUNE, "Blood rune"), buy(RUNE_DART, "Rune dart")));
		Assert.assertEquals("held while paused", Integer.valueOf(RUNE_DART), onScreen());

		p.offerPlaced(STEAM_RUNE, true);
		Assert.assertEquals("another item's offer leaves the hold alone", Integer.valueOf(RUNE_DART), onScreen());
		p.offerPlaced(RUNE_DART, false);
		Assert.assertEquals("a SELL of it is not the card's buy", Integer.valueOf(RUNE_DART), onScreen());

		p.offerPlaced(RUNE_DART, true);
		Assert.assertNotEquals("moved off the placed idea", Integer.valueOf(RUNE_DART), onScreen());
		Assert.assertTrue("and no longer paused", p.pauseButton().getToolTipText().contains("Pause the suggestions"));
	}

	/** Not paused: the refresh after the offer drops the idea, and the card
	 *  goes to the one after it rather than back to the first. */
	@Test
	public void anIdeaThatLeavesTheListGivesWayToTheNextOne() throws Exception
	{
		final AdvisorPanel p = panel();
		p.setRecommendations(list(buy(BLOOD_RUNE, "Blood rune"), buy(RUNE_DART, "Rune dart"), buy(STEAM_RUNE, "Steam rune")));
		setIndex(p, 1);
		p.setRecommendations(list(buy(BLOOD_RUNE, "Blood rune"), buy(STEAM_RUNE, "Steam rune")));
		Assert.assertEquals(Integer.valueOf(STEAM_RUNE), onScreen());
		Assert.assertEquals("the same idea stays put",
			1, AdvisorPanel.placeOf(list(buy(1, "a"), buy(2, "b")), 1, list(buy(3, "c"), buy(2, "b"))));
		Assert.assertEquals("nothing left from there on: the first",
			0, AdvisorPanel.placeOf(list(buy(1, "a"), buy(2, "b")), 1, list(buy(3, "c"))));
	}

	/** Ending a pause by hand shows the newest list, not the one from
	 *  before it. */
	@Test
	public void unpausingShowsTheNewestList()
	{
		final AdvisorPanel p = panel();
		p.setRecommendations(list(buy(RUNE_DART, "Rune dart")));
		p.pauseButton().doClick();
		p.setRecommendations(list(buy(BLOOD_RUNE, "Blood rune")));
		Assert.assertEquals(Integer.valueOf(RUNE_DART), onScreen());
		p.pauseButton().doClick();
		Assert.assertEquals(Integer.valueOf(BLOOD_RUNE), onScreen());
	}
}
