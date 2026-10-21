package com.pocketge.tracker;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import org.junit.Assert;
import org.junit.Test;

/**
 * PAID @ is what you paid for the stack you HOLD.
 *
 * Reported: 13,418 Aether catalysts bought at 277 showed "PAID @ 330". The
 * tracker also had about 24,500 left over from an earlier stack at 359,
 * sold where it did not see — and the card averaged the two.
 */
public class HeldCostTest
{
	private static final int AETHER = 30771;

	/** An earlier stack at 359 the plugin never saw sold, then this one at 277. */
	private static FlipTracker withLeftovers()
	{
		final FlipTracker t = new FlipTracker();
		t.onOffer(1L, 0, AETHER, "Aether catalyst", true, 0, 0L, 359, 25_000, false);
		t.onOffer(2L, 0, AETHER, "Aether catalyst", true, 24_522, 24_522L * 359, 359, 25_000, false);
		t.onOffer(3L, 0, 0, "", false, 0, 0L, 0, 0, true);
		t.onOffer(4L, 1, AETHER, "Aether catalyst", true, 0, 0L, 277, 23_000, false);
		t.onOffer(5L, 1, AETHER, "Aether catalyst", true, 13_418, 13_418L * 277, 277, 23_000, false);
		return t;
	}

	private static Map<Integer, Long> holding(long n)
	{
		final Map<Integer, Long> m = new HashMap<>();
		m.put(AETHER, n);
		return m;
	}

	@Test
	public void theStackYouHoldIsPricedFromYourNewestBuys()
	{
		final long[] c = withLeftovers().getHeldCost(holding(13_418)).get(AETHER);
		Assert.assertArrayEquals(new long[]{13_418, 13_418L * 277, 24_522, 24_522L * 359}, c);
		Assert.assertEquals("PAID @ 277, not 330", 277, Math.round(c[1] / (double) c[0]));
	}

	@Test
	public void aStackReachingIntoAnOlderLotTakesPartOfIt()
	{
		final long[] c = withLeftovers().getHeldCost(holding(14_418)).get(AETHER);
		Assert.assertEquals(14_418, c[0]);
		Assert.assertEquals(13_418L * 277 + 1_000L * 359, c[1]);
		Assert.assertEquals(23_522, c[2]);
	}

	@Test
	public void holdingAllOfItOrNoneChangesNothing()
	{
		final FlipTracker t = withLeftovers();
		Assert.assertArrayEquals(new long[]{37_940, 13_418L * 277 + 24_522L * 359, 0, 0},
			t.getHeldCost(holding(50_000)).get(AETHER));
		Assert.assertArrayEquals("nothing held: plain totals",
			new long[]{37_940, 13_418L * 277 + 24_522L * 359, 0, 0}, t.getHeldCost(new HashMap<>()).get(AETHER));
	}

	/** Forgetting the older ones keeps the stack's cost, and the next sale
	 *  is matched against it — a profit, not a loss against 359. */
	@Test
	public void forgettingTheOlderBuysLeavesTheStackAndMatchesSalesToIt()
	{
		final FlipTracker t = withLeftovers();
		Assert.assertArrayEquals(new long[]{24_522, 24_522L * 359}, t.forgetOlderThan(AETHER, 13_418));
		Assert.assertArrayEquals(new long[]{13_418, 13_418L * 277}, t.getOpenBuyTotal(AETHER));
		Assert.assertNull("nothing older left", t.forgetOlderThan(AETHER, 13_418));
		t.onOffer(6L, 2, AETHER, "Aether catalyst", false, 0, 0L, 299, 13_418, false);
		t.onOffer(7L, 2, AETHER, "Aether catalyst", false, 13_418, 13_418L * 299, 299, 13_418, false);
		final Flip f = t.getFlips().get(t.getFlips().size() - 1);
		Assert.assertTrue("a profit at 299 on a 277 stack: " + f.profit, f.profit > 0);
	}

	@Test
	public void forgettingTrimsTheOldestLotAtTheBoundary()
	{
		final FlipTracker t = withLeftovers();
		Assert.assertArrayEquals(new long[]{23_522, 23_522L * 359}, t.forgetOlderThan(AETHER, 14_418));
		Assert.assertArrayEquals(new long[]{14_418, 13_418L * 277 + 1_000L * 359}, t.getOpenBuyTotal(AETHER));
	}

	@SuppressWarnings("unchecked")
	private static <T> T stub(Class<T> type)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) ->
		{
			final Class<?> r = m.getReturnType();
			return r == boolean.class ? (Object) false : r.isPrimitive() ? (r == long.class ? (Object) 0L : (Object) 0) : null;
		});
	}

	private static Object get(Object o, String field) throws Exception
	{
		final Field f = o.getClass().getDeclaredField(field);
		f.setAccessible(true);
		return f.get(o);
	}

	/** The card says the older ones are left out, and offers to forget them. */
	@Test
	public void theCardSaysSoAndOffersToForgetThem() throws Exception
	{
		final AdvisorPanel panel = new AdvisorPanel(null, stub(AdvisorPanel.Actions.class));
		final Map<Integer, long[]> older = new HashMap<>();
		older.put(AETHER, new long[]{24_522, 24_522L * 359});
		panel.setOlderBuys(older);
		final AdvisorPanel.Rec r = new AdvisorPanel.Rec();
		r.sell = true;
		r.itemId = AETHER;
		r.name = "Aether catalyst";
		r.quantity = 13_418;
		r.unitPrice = 299;
		r.unitCost = 277;
		r.hasTrackedCost = true;
		r.profit = 13_418L * (299 - 5 - 277);
		final Method cardFor = AdvisorPanel.class.getDeclaredMethod("cardFor", AdvisorPanel.Rec.class);
		cardFor.setAccessible(true);
		final Object card = cardFor.invoke(panel, r);
		Assert.assertEquals("24,522 older buys not counted", get(card, "footnote"));
		final JPopupMenu menu = (JPopupMenu) get(card, "contextMenu");
		boolean offered = false;
		for (java.awt.Component c : menu.getComponents())
		{
			offered |= c instanceof JMenuItem && "Forget the 24,522 older buys".equals(((JMenuItem) c).getText());
		}
		Assert.assertTrue("the right-click offers to forget them", offered);
	}

	@Test
	public void onlyItemsWithOlderBuysAreReported()
	{
		final Map<Integer, long[]> cost = new HashMap<>();
		cost.put(AETHER, new long[]{13_418, 13_418L * 277, 24_522, 24_522L * 359});
		cost.put(1621, new long[]{100, 31_700, 0, 0});
		final Map<Integer, long[]> older = PocketGeTrackerPlugin.olderBuys(cost);
		Assert.assertArrayEquals(new long[]{24_522, 24_522L * 359}, older.get(AETHER));
		Assert.assertFalse(older.containsKey(1621));
	}
}
