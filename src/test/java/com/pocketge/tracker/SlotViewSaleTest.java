package com.pocketge.tracker;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/**
 * The slot hover on a sell: what it has made so far, and what the rest will
 * make, by the rule the sale books with.
 */
public class SlotViewSaleTest
{
	private static final int ITEM = 30771;

	private static FlipTracker tracker(PocketGeTrackerPlugin plugin) throws Exception
	{
		final Field f = PocketGeTrackerPlugin.class.getDeclaredField("tracker");
		f.setAccessible(true);
		return (FlipTracker) f.get(plugin);
	}

	@SuppressWarnings("unchecked")
	private static GeOfferGridOverlay.SlotView view(PocketGeTrackerPlugin plugin, Advisor.OfferView o,
		Map<Integer, long[]> openBuys) throws Exception
	{
		final Method build = PocketGeTrackerPlugin.class.getDeclaredMethod("buildSlotViews",
			List.class, Map.class, Map.class, Map.class, Map.class);
		build.setAccessible(true);
		return ((Map<Integer, GeOfferGridOverlay.SlotView>) build.invoke(plugin, List.of(o), new HashMap<>(), openBuys,
			new HashMap<>(), new HashMap<>())).get(o.slot);
	}

	private static Advisor.OfferView sell(int sold)
	{
		final Advisor.OfferView o = new Advisor.OfferView();
		o.slot = 1;
		o.itemId = ITEM;
		o.itemName = "Aether catalyst";
		o.buy = false;
		o.price = 300;
		o.totalQuantity = 2_000;
		o.quantitySold = sold;
		o.active = true;
		return o;
	}

	/** Bought 2,000 at 277, listed at 300, 1,000 sold: "Made +17K so far,
	 *  +17K to come". The first 0.7.6 dropped the "so far". */
	@Test
	public void aPartSoldSellKeepsWhatItHasMadeSoFar() throws Exception
	{
		final PocketGeTrackerPlugin plugin = new PocketGeTrackerPlugin();
		final FlipTracker t = tracker(plugin);
		t.setAccountHash(42L);
		t.onOffer(1, 0, ITEM, "Aether catalyst", true, 0, 0, 277, 2_000, false);
		t.onOffer(2, 0, ITEM, "Aether catalyst", true, 2_000, 2_000L * 277, 277, 2_000, false);
		t.onOffer(3, 0, 0, "", false, 0, 0, 0, 0, true);
		t.onOffer(4, 1, ITEM, "Aether catalyst", false, 0, 0, 300, 2_000, false);
		t.onOffer(5, 1, ITEM, "Aether catalyst", false, 1_000, 300_000, 300, 2_000, false);

		final GeOfferGridOverlay.SlotView v = view(plugin, sell(1_000), new HashMap<>());
		final long each = 300 - FlipTracker.taxPerItem(300, ITEM) - 277;
		Assert.assertEquals("made so far", Long.valueOf(1_000 * each), v.bookedProfit);
		Assert.assertEquals(1_000, v.bookedUnits);
		Assert.assertEquals("to come, by the booking's own rule", Long.valueOf(1_000 * each), v.projectedProfit);
	}

	/** A record that nothing on file can be charged against: no profit
	 *  projected — not one priced from lots the sale will never touch. */
	@Test
	public void nothingTheSaleCanBeChargedMeansNoProjection() throws Exception
	{
		final PocketGeTrackerPlugin plugin = new PocketGeTrackerPlugin();
		final FlipTracker t = tracker(plugin);
		t.setAccountHash(42L);
		/* Listed from stock the plugin never saw bought, then a buy of the
		   same item after the listing: not in this offer. */
		t.onOffer(1, 1, ITEM, "Aether catalyst", false, 0, 0, 300, 2_000, false);
		t.onOffer(2, 0, ITEM, "Aether catalyst", true, 0, 0, 277, 1_000, false);
		t.onOffer(3, 0, ITEM, "Aether catalyst", true, 1_000, 277_000, 277, 1_000, false);
		t.onOffer(4, 0, 0, "", false, 0, 0, 0, 0, true);
		final Map<Integer, long[]> openBuys = new HashMap<>();
		openBuys.put(ITEM, new long[]{1_000, 277_000});

		final GeOfferGridOverlay.SlotView v = view(plugin, sell(0), openBuys);
		Assert.assertNull(v.projectedProfit);
	}
}
