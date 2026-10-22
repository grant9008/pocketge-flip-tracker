package com.pocketge.tracker;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 * "it flipped its focus off dragonstone bolt tips on our pocketge inspect
 * for some reason to uncut diamonds while i was making my trade".
 */
public class InspectHoldTest
{
	private static final int BOLT_TIPS = 9193;
	private static final int UNCUT_DIAMOND = 1617;

	private final List<Integer> selectionChanges = new ArrayList<>();

	private AdvisorPanel panel()
	{
		final AdvisorPanel.Actions actions = (AdvisorPanel.Actions) Proxy.newProxyInstance(
			AdvisorPanel.Actions.class.getClassLoader(), new Class<?>[]{AdvisorPanel.Actions.class},
			(proxy, method, args) ->
			{
				if ("onSelectedItemChanged".equals(method.getName()))
				{
					selectionChanges.add((Integer) args[0]);
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

	private static FavoritesPanel.Row row(int id, String name)
	{
		final FavoritesPanel.Row r = new FavoritesPanel.Row();
		r.id = id;
		r.name = name;
		return r;
	}

	/** A refresh built before the click — so without the inspected item —
	 *  arriving after it does not end the inspection. */
	@Test
	public void aRefreshFromBeforeTheClickLeavesTheCardUp()
	{
		final AdvisorPanel p = panel();
		p.setSelectedItem(row(BOLT_TIPS, "Dragonstone bolt tips"));
		p.refreshSelectedFrom(Collections.singletonList(row(UNCUT_DIAMOND, "Uncut diamond")));
		Assert.assertTrue("still inspecting the bolt tips", p.inspecting(BOLT_TIPS));
		Assert.assertFalse("the plugin was not told to stop", selectionChanges.contains(null));

		final FavoritesPanel.Row priced = row(BOLT_TIPS, "Dragonstone bolt tips");
		priced.heldQty = 11_000;
		p.refreshSelectedFrom(Collections.singletonList(priced));
		Assert.assertTrue(p.inspecting(BOLT_TIPS));
		Assert.assertEquals("and the next refresh fills it in", Boolean.TRUE, p.inspectSwapTarget(BOLT_TIPS));
	}

	/** The ✕ still ends it. */
	@Test
	public void theCloseButtonStillEndsIt()
	{
		final AdvisorPanel p = panel();
		p.setSelectedItem(row(BOLT_TIPS, "Dragonstone bolt tips"));
		p.setSelectedItem(null);
		Assert.assertFalse(p.inspecting(BOLT_TIPS));
	}
}
