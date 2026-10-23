package com.pocketge.tracker;

import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JButton;
import org.junit.Assert;
import org.junit.Test;

/**
 * "why it show 2/20 but i cant click to whatever 1/20 is" — the card had
 * moved on by itself, so nothing had been paged past, and Back stayed grey.
 */
public class CardBackTest
{
	private static final int A = 811;
	private static final int B = 4694;
	private static final int C = 565;
	private static final int X = 1601;

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

	private static AdvisorPanel.Rec buy(int id)
	{
		final AdvisorPanel.Rec r = new AdvisorPanel.Rec();
		r.itemId = id;
		r.name = "Item " + id;
		r.sell = false;
		r.quantity = 1_000;
		r.unitPrice = 100;
		r.profit = 50_000;
		return r;
	}

	private static List<AdvisorPanel.Rec> list(int... ids)
	{
		final List<AdvisorPanel.Rec> out = new ArrayList<>();
		Arrays.stream(ids).forEach(id -> out.add(buy(id)));
		return out;
	}

	private Integer onScreen()
	{
		return shown.isEmpty() ? null : shown.get(shown.size() - 1);
	}

	/** The pager's Back square, as the card is drawn now. */
	private static JButton back(Container root)
	{
		for (Component c : root.getComponents())
		{
			if (c instanceof JButton)
			{
				final String tip = ((JButton) c).getToolTipText();
				if (tip != null && (tip.contains("previous suggestion") || tip.contains("Nothing to go back to")))
				{
					return (JButton) c;
				}
			}
			if (c instanceof Container)
			{
				final JButton found = back((Container) c);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

	private static JButton next(Container root)
	{
		for (Component c : root.getComponents())
		{
			if (c instanceof JButton)
			{
				final String tip = ((JButton) c).getToolTipText();
				if (tip != null && tip.contains("Next suggestion"))
				{
					return (JButton) c;
				}
			}
			if (c instanceof Container)
			{
				final JButton found = next((Container) c);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

	/** The card stays on its idea as a new one lands ahead of it: 2 of 4,
	 *  nothing paged past, and Back goes to the 1. */
	@Test
	public void backGoesToTheCardBeforeEvenWithNothingPagedPast()
	{
		final AdvisorPanel p = panel();
		p.setRecommendations(list(A, B, C));
		Assert.assertEquals(Integer.valueOf(A), onScreen());
		Assert.assertFalse("1 of 3: nothing before it", back(p).isEnabled());

		p.setRecommendations(list(X, A, B, C));
		Assert.assertEquals("still on its idea, now 2 of 4", Integer.valueOf(A), onScreen());
		final JButton back = back(p);
		Assert.assertTrue("there is a 1 to go back to", back.isEnabled());
		back.doClick();
		Assert.assertEquals(Integer.valueOf(X), onScreen());
		Assert.assertFalse("and at 1, nothing before", back(p).isEnabled());
	}

	/** A card you paged past is still where Back goes first, wherever the
	 *  re-ranking has put it. */
	@Test
	public void aCardPagedPastIsStillWhereBackGoes()
	{
		final AdvisorPanel p = panel();
		p.setRecommendations(list(A, B, C));
		next(p).doClick();
		Assert.assertEquals(Integer.valueOf(B), onScreen());
		p.setRecommendations(list(C, B, A));
		back(p).doClick();
		Assert.assertEquals("the one paged past, not whatever is first now", Integer.valueOf(A), onScreen());
	}
}
