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
 * the last two prints and no rating. The panel hands on the two cards after
 * the one on screen as it changes, so their series are asked for before
 * they are paged to.
 */
public class NextCardTest
{
	private static final int A = 811;
	private static final int B = 4694;
	private static final int C = 565;
	private static final int X = 1601;

	/** Every handful of cards ahead handed on, in order. */
	private final List<List<Integer>> aheads = new ArrayList<>();

	private AdvisorPanel panel()
	{
		final AdvisorPanel.Actions actions = (AdvisorPanel.Actions) Proxy.newProxyInstance(
			AdvisorPanel.Actions.class.getClassLoader(), new Class<?>[]{AdvisorPanel.Actions.class},
			(proxy, method, args) ->
			{
				if ("onCardsAhead".equals(method.getName()))
				{
					@SuppressWarnings("unchecked")
					final List<Integer> ids = (List<Integer>) args[0];
					aheads.add(ids);
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

	/** The two cards after the one on screen, as the card moves — round to
	 *  the first from the last, where Next goes. */
	@Test
	public void theCardsAheadAreHandedOnAsTheCardMoves()
	{
		final AdvisorPanel p = panel();
		p.setRecommendations(list(A, B, C, X));
		Assert.assertEquals(List.of(List.of(B, C)), aheads);
		next(p).doClick();
		next(p).doClick();
		next(p).doClick();
		Assert.assertEquals(List.of(List.of(B, C), List.of(C, X), List.of(X, A), List.of(A, B)), aheads);
	}

	/** As they change, with the card or under it: a round that re-ranks
	 *  what follows the card, or an idea ahead turned down, hands on the new
	 *  ones; a rebuild that changes nothing says nothing. */
	@Test
	public void wheneverTheyChange()
	{
		final AdvisorPanel p = panel();
		p.setRecommendations(list(A, B, C, X));
		p.setRecommendations(list(A, B, C, X));
		Assert.assertEquals(List.of(List.of(B, C)), aheads);
		p.setRecommendations(list(A, C, X));
		Assert.assertEquals(List.of(List.of(B, C), List.of(C, X)), aheads);
	}

	/** Fewer cards, fewer ahead; none after the only one; the same item
	 *  twice (a buy and a sell) once. */
	@Test
	public void asManyAsThereAre()
	{
		Assert.assertEquals(List.of(B), AdvisorPanel.cardsAhead(list(A, B), 0));
		Assert.assertEquals(List.of(), AdvisorPanel.cardsAhead(list(A), 0));
		final List<AdvisorPanel.Rec> both = list(A, B, C);
		final AdvisorPanel.Rec sellB = buy(B);
		sellB.sell = true;
		both.add(2, sellB);
		Assert.assertEquals(List.of(B, C), AdvisorPanel.cardsAhead(both, 0));
		Assert.assertEquals("not the card itself", List.of(C, A), AdvisorPanel.cardsAhead(both, 1));
	}
}
