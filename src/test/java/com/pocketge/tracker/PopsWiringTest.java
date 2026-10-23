package com.pocketge.tracker;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.EnumSet;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.WorldType;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * What the plugin tells the overlay is waiting to be shown: the tracker's
 * unshown sale for the slot, for the character logged in, on the main
 * game's Exchange, for the sell the slot is showing.
 */
public class PopsWiringTest
{
	private static final int DART = 811;
	private static final long MAIN = 1111L;
	private static final long ALT = 2222L;

	private long account = MAIN;
	private EnumSet<WorldType> world = EnumSet.noneOf(WorldType.class);
	private boolean popsOn = true;
	private GrandExchangeOfferState slot2 = GrandExchangeOfferState.SOLD;

	private PocketGeTrackerPlugin plugin;
	private FlipTracker tracker;

	@Before
	public void setUp() throws Exception
	{
		final GrandExchangeOffer offer = (GrandExchangeOffer) Proxy.newProxyInstance(
			GrandExchangeOffer.class.getClassLoader(), new Class<?>[]{GrandExchangeOffer.class},
			(proxy, m, args) ->
			{
				switch (m.getName())
				{
					case "getItemId":
						return DART;
					case "getState":
						return slot2;
					default:
						return defaults(m.getReturnType());
				}
			});
		final Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
			new Class<?>[]{Client.class}, (proxy, m, args) ->
			{
				switch (m.getName())
				{
					case "getAccountHash":
						return account;
					case "getWorldType":
						return world;
					case "getGameState":
						return GameState.LOGGED_IN;
					case "getTickCount":
						return 100;
					case "getGrandExchangeOffers":
					{
						final GrandExchangeOffer[] all = new GrandExchangeOffer[8];
						all[2] = offer;
						return all;
					}
					default:
						return defaults(m.getReturnType());
				}
			});
		final PocketGeTrackerConfig config = (PocketGeTrackerConfig) Proxy.newProxyInstance(
			PocketGeTrackerConfig.class.getClassLoader(), new Class<?>[]{PocketGeTrackerConfig.class},
			(proxy, m, args) -> "profitPops".equals(m.getName()) ? popsOn : defaults(m.getReturnType()));
		plugin = new PocketGeTrackerPlugin();
		set("client", client);
		set("config", config);
		set("geSettledTick", 10);
		final Field f = PocketGeTrackerPlugin.class.getDeclaredField("tracker");
		f.setAccessible(true);
		tracker = (FlipTracker) f.get(plugin);

		/* 7,132 Rune darts bought at 190, then sold in slot 2 at 200. */
		tracker.setAccountHash(MAIN);
		tracker.onOffer(1_000, 0, DART, "Rune dart", true, 0, 0, 190, 7_132, false);
		tracker.onOffer(2_000, 0, DART, "Rune dart", true, 7_132, 7_132L * 190, 190, 7_132, false);
		tracker.onOffer(3_000, 0, 0, "", false, 0, 0, 0, 0, true);
		tracker.onOffer(4_000, 2, DART, "Rune dart", false, 0, 0, 200, 7_132, false);
	}

	private void set(String field, Object value) throws Exception
	{
		final Field f = PocketGeTrackerPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(plugin, value);
	}

	private static Object defaults(Class<?> t)
	{
		if (t == boolean.class)
		{
			return false;
		}
		if (t == int.class)
		{
			return 0;
		}
		if (t == long.class)
		{
			return 0L;
		}
		return null;
	}

	/** The sale, booked as the plugin books one: onOffer between two reads
	 *  of the slot's total, then noteSale. */
	private void sold(int units) throws Exception
	{
		final long[] before = tracker.bookedBy(2);
		tracker.onOffer(5_000, 2, DART, "Rune dart", false, units, units * 200L, 200, 7_132, false);
		final Method m = PocketGeTrackerPlugin.class.getDeclaredMethod("noteSale", int.class, long[].class, long[].class);
		m.setAccessible(true);
		m.invoke(plugin, 2, before, tracker.bookedBy(2));
	}

	@Test
	public void theWholeSaleIsWaitingUntilShown() throws Exception
	{
		sold(7_132);
		Assert.assertArrayEquals(new long[]{7_132, 7_132L * 6}, plugin.unseenSale(2));
		plugin.saleShown(2);
		Assert.assertNull(plugin.unseenSale(2));
	}

	@Test
	public void notForAnotherCharacter() throws Exception
	{
		sold(7_132);
		account = ALT;
		Assert.assertNull(plugin.unseenSale(2));
		account = MAIN;
		Assert.assertNotNull("still waiting for its own", plugin.unseenSale(2));
	}

	@Test
	public void notOnAWorldWithItsOwnExchange() throws Exception
	{
		sold(7_132);
		world = EnumSet.of(WorldType.SEASONAL);
		Assert.assertNull(plugin.unseenSale(2));
		world = EnumSet.noneOf(WorldType.class);
		Assert.assertNotNull(plugin.unseenSale(2));
	}

	/** The slot shows a buy of the same item now: not that sale's square. */
	@Test
	public void notOverABuy() throws Exception
	{
		sold(7_132);
		slot2 = GrandExchangeOfferState.BUYING;
		Assert.assertNull(plugin.unseenSale(2));
	}

	@Test
	public void notWhileTheLoginReplayIsSettling() throws Exception
	{
		sold(7_132);
		set("geSettledTick", -1);
		Assert.assertNull(plugin.unseenSale(2));
	}

	/** Sold with the figures switched off: shown as far as anyone cares, so
	 *  switching them on does not pop a backlog. */
	@Test
	public void switchedOffASaleDoesNotWait() throws Exception
	{
		popsOn = false;
		sold(7_132);
		Assert.assertNull(plugin.unseenSale(2));
		popsOn = true;
		Assert.assertNull(plugin.unseenSale(2));
	}
}
