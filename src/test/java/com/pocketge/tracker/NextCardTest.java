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
 * "why sometimes flip rating and sometimes not": only the first few flip
 * ideas got a price series from the round, so the card after them showed
 * the last two prints and no rating. The panel hands on the card Next would
 * show, so its series is asked for before it is paged to.
 */
public class NextCardTest
{
	private static final int A = 811;
	private static final int B = 4694;
	private static final int C = 565;
	private static final int X = 1601;

	/** Every card announced as next, in order. */
	private final List<Integer> nexts = new ArrayList<>();

	private AdvisorPanel panel()
	{
		final AdvisorPanel.Actions actions = (AdvisorPanel.Actions) Proxy.newProxyInstance(
			AdvisorPanel.Actions.class.getClassLoader(), new Class<?>[]{AdvisorPanel.Actions.class},
			(proxy, method, args) ->
			{
				if ("onNextRecommendation".equals(method.getName()))
				{
					nexts.add((Integer) args[0]);
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

	/** The card after the one on screen, as the card moves — round to the
	 *  first from the last, where Next goes. */
	@Test
	public void theNextCardIsHandedOnAsTheCardMoves()
	{
		final AdvisorPanel p = panel();
		p.setRecommendations(list(A, B, C));
		Assert.assertEquals(List.of(B), nexts);
		next(p).doClick();
		next(p).doClick();
		Assert.assertEquals(List.of(B, C, A), nexts);
	}

	/** Once per change: a rebuild that leaves the next card where it was
	 *  says nothing; one that changes it — the idea after this one turned
	 *  down — hands on the new one. */
	@Test
	public void onlyWhenItChanges()
	{
		final AdvisorPanel p = panel();
		p.setRecommendations(list(A, B, C));
		p.setRecommendations(list(A, B, C));
		Assert.assertEquals(List.of(B), nexts);
		p.setRecommendations(list(A, C));
		Assert.assertEquals(List.of(B, C), nexts);
	}

	/** Nothing after the card: none. */
	@Test
	public void aListOfOneHasNoNext()
	{
		final AdvisorPanel p = panel();
		p.setRecommendations(list(A, X));
		p.setRecommendations(list(A));
		Assert.assertEquals(Arrays.asList(X, null), nexts);
	}
}
