package com.pocketge.tracker;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 * The chip on "What would you like to buy?". That search only ever buys —
 * a sell starts from the inventory — so it offers a buy: the card's own item
 * on a buy card, the next buy idea on a sell card, never the stack a sell
 * card is telling you to sell.
 */
public class SearchChipTest
{
	private static final int RUNE_DART = 811;
	private static final int STEAM_RUNE = 4694;
	private static final int BOLT_TIPS = 9193;
	private static final int BLOOD_RUNE = 565;

	/** Every item the chip was handed, in order. */
	private final List<Integer> chip = new ArrayList<>();

	private AdvisorPanel panel()
	{
		final AdvisorPanel.Actions actions = (AdvisorPanel.Actions) Proxy.newProxyInstance(
			AdvisorPanel.Actions.class.getClassLoader(), new Class<?>[]{AdvisorPanel.Actions.class},
			(proxy, method, args) ->
			{
				if ("onSearchItemShown".equals(method.getName()))
				{
					chip.add((Integer) args[0]);
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

	private static AdvisorPanel.Rec rec(int id, String name, boolean sell)
	{
		final AdvisorPanel.Rec r = new AdvisorPanel.Rec();
		r.itemId = id;
		r.name = name;
		r.sell = sell;
		r.quantity = 1_000;
		r.unitPrice = 100;
		r.profit = 50_000;
		return r;
	}

	private Integer chipNow()
	{
		return chip.isEmpty() ? null : chip.get(chip.size() - 1);
	}

	@Test
	public void aBuyCardOffersItsOwnItem()
	{
		final AdvisorPanel p = panel();
		p.setRecommendations(new ArrayList<>(Arrays.asList(rec(RUNE_DART, "Rune dart", false), rec(STEAM_RUNE, "Steam rune", false))));
		Assert.assertEquals(Integer.valueOf(RUNE_DART), chipNow());
	}

	/** A sell card offers the next buy idea after it, not its own stack. */
	@Test
	public void aSellCardOffersTheNextBuyNotItsOwnStack() throws Exception
	{
		final AdvisorPanel p = panel();
		p.setRecommendations(new ArrayList<>(Arrays.asList(rec(BLOOD_RUNE, "Blood rune", false),
			rec(BOLT_TIPS, "Dragonstone bolt tips", true), rec(RUNE_DART, "Rune dart", false))));
		final Field f = AdvisorPanel.class.getDeclaredField("recIndex");
		f.setAccessible(true);
		f.setInt(p, 1);
		p.setRecommendations(new ArrayList<>(Arrays.asList(rec(BLOOD_RUNE, "Blood rune", false),
			rec(BOLT_TIPS, "Dragonstone bolt tips", true), rec(RUNE_DART, "Rune dart", false))));
		Assert.assertEquals("the buy after it", Integer.valueOf(RUNE_DART), chipNow());
		Assert.assertFalse("never the stack it says to sell", chip.contains(BOLT_TIPS));
	}

	/** Only sells to show: nothing to offer, so nothing is offered. */
	@Test
	public void noBuyIdeaMeansNoChip()
	{
		final AdvisorPanel p = panel();
		p.setRecommendations(new ArrayList<>(Arrays.asList(rec(BOLT_TIPS, "Dragonstone bolt tips", true))));
		Assert.assertNull(chipNow());
		Assert.assertNull(p.searchItemFor(rec(BOLT_TIPS, "Dragonstone bolt tips", true)));
	}
}
