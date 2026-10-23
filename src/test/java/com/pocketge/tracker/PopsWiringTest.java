package com.pocketge.tracker;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.EnumSet;
import net.runelite.api.Client;
import net.runelite.api.WorldType;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * What the plugin hands the overlay: every sale it books, including the ones
 * booked by the login replay — "the next time i load into this ge screen, it
 * should have said +64kgp over rune darts" — and only the character's own.
 */
public class PopsWiringTest
{
	private static final int DART = 811;
	private static final long MAIN = 1111L;
	private static final long ALT = 2222L;

	private long account = MAIN;
	private EnumSet<WorldType> world = EnumSet.noneOf(WorldType.class);
	private boolean popsOn = true;

	private PocketGeTrackerPlugin plugin;
	private GeOfferGridOverlay overlay;

	@Before
	public void setUp() throws Exception
	{
		final Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
			new Class<?>[]{Client.class}, (proxy, m, args) ->
			{
				switch (m.getName())
				{
					case "getAccountHash":
						return account;
					case "getWorldType":
						return world;
					default:
						return defaults(m.getReturnType());
				}
			});
		final PocketGeTrackerConfig config = (PocketGeTrackerConfig) Proxy.newProxyInstance(
			PocketGeTrackerConfig.class.getClassLoader(), new Class<?>[]{PocketGeTrackerConfig.class},
			(proxy, m, args) -> "profitPops".equals(m.getName()) ? popsOn : defaults(m.getReturnType()));
		final Constructor<GeOfferGridOverlay> k =
			GeOfferGridOverlay.class.getDeclaredConstructor(Client.class, TooltipManager.class);
		k.setAccessible(true);
		overlay = k.newInstance(client, new TooltipManager());
		plugin = new PocketGeTrackerPlugin();
		set("client", client);
		set("config", config);
		set("geGridOverlay", overlay);
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

	private void sold(long[] before, long[] after) throws Exception
	{
		final Method m = PocketGeTrackerPlugin.class.getDeclaredMethod("noteSale",
			int.class, int.class, long[].class, long[].class);
		m.setAccessible(true);
		m.invoke(plugin, 2, DART, before, after);
	}

	private void loggedIn() throws Exception
	{
		final Method m = PocketGeTrackerPlugin.class.getDeclaredMethod("syncPopsAccount");
		m.setAccessible(true);
		m.invoke(plugin);
	}

	private int held() throws Exception
	{
		final Field f = GeOfferGridOverlay.class.getDeclaredField("pops");
		f.setAccessible(true);
		return ((ProfitPops) f.get(overlay)).held();
	}

	private boolean paused() throws Exception
	{
		final Field f = GeOfferGridOverlay.class.getDeclaredField("popsPaused");
		f.setAccessible(true);
		return f.getBoolean(overlay);
	}

	/** Booked while the login replay is still settling — what sold while
	 *  logged out — and it is handed over to wait for its square. */
	@Test
	public void aSaleBookedAtLoginWaitsForTheScreen() throws Exception
	{
		final Field settled = PocketGeTrackerPlugin.class.getDeclaredField("geSettledTick");
		settled.setAccessible(true);
		Assert.assertEquals("the login burst", -1, settled.getInt(plugin));
		sold(null, new long[]{7_132, 64_000});
		Assert.assertEquals(1, held());
	}

	@Test
	public void nothingBookedNothingHandedOver() throws Exception
	{
		sold(new long[]{100, 2_000}, new long[]{100, 2_000});
		sold(null, null);
		Assert.assertEquals(0, held());
	}

	@Test
	public void switchedOffNothingHandedOver() throws Exception
	{
		popsOn = false;
		sold(null, new long[]{7_132, 64_000});
		Assert.assertEquals(0, held());
	}

	/** The same character back after a logout keeps what it has waiting;
	 *  another character does not get it over its own slots. */
	@Test
	public void anotherCharacterDoesNotSeeTheseFigures() throws Exception
	{
		loggedIn();
		sold(null, new long[]{7_132, 64_000});
		loggedIn();
		Assert.assertEquals("same character: still waiting", 1, held());
		account = ALT;
		loggedIn();
		Assert.assertEquals("another character: gone", 0, held());
	}

	/** Leagues and the like have their own Exchange: the figures pause there
	 *  rather than landing on its slots, and play back on the main game. */
	@Test
	public void pausedOnAWorldWithItsOwnExchange() throws Exception
	{
		loggedIn();
		Assert.assertFalse(paused());
		world = EnumSet.of(WorldType.SEASONAL);
		loggedIn();
		Assert.assertTrue(paused());
		world = EnumSet.noneOf(WorldType.class);
		loggedIn();
		Assert.assertFalse(paused());
	}
}
