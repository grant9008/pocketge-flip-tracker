package com.pocketge.tracker;

import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/**
 * "Filling, so priced fine" only for fills the plugin watched happen.
 *
 * A 317 bid that filled overnight, under a market that had since moved to
 * 341, read "Priced fine — leave it" for the first ten minutes after login:
 * units found already bought were taken as "filling now".
 */
public class FillGraceTest
{
	private static final long MIN = 60_000L;

	@Test
	public void unitsFoundAlreadyBoughtAreNotAFillNow()
	{
		final Map<Integer, long[]> progress = new HashMap<>();
		Assert.assertFalse("first look, 7,950 already bought",
			PocketGeTrackerPlugin.filledRecently(progress, 0, 7_950, 1_000 * MIN));
		Assert.assertFalse("and still nothing new a minute later",
			PocketGeTrackerPlugin.filledRecently(progress, 0, 7_950, 1_001 * MIN));
	}

	@Test
	public void aFillWatchedHappeningQuietsTheNagForTenMinutes()
	{
		final Map<Integer, long[]> progress = new HashMap<>();
		PocketGeTrackerPlugin.filledRecently(progress, 0, 7_950, 1_000 * MIN);
		Assert.assertTrue(PocketGeTrackerPlugin.filledRecently(progress, 0, 8_100, 1_002 * MIN));
		Assert.assertTrue(PocketGeTrackerPlugin.filledRecently(progress, 0, 8_100, 1_011 * MIN));
		Assert.assertFalse("stalled since", PocketGeTrackerPlugin.filledRecently(progress, 0, 8_100, 1_013 * MIN));
	}

	/** Logging out forgets the clock, so the next login is a first look. */
	@Test
	public void aLogoutForgetsTheClock() throws Exception
	{
		final PocketGeTrackerPlugin plugin = new PocketGeTrackerPlugin();
		final java.lang.reflect.Field c = PocketGeTrackerPlugin.class.getDeclaredField("client");
		c.setAccessible(true);
		c.set(plugin, java.lang.reflect.Proxy.newProxyInstance(net.runelite.api.Client.class.getClassLoader(),
			new Class<?>[]{net.runelite.api.Client.class}, (p, m, a) ->
			{
				final Class<?> r = m.getReturnType();
				return m.getName().equals("getGameState") ? net.runelite.api.GameState.LOGIN_SCREEN
					: r == boolean.class ? (Object) false : r.isPrimitive() ? (r == long.class ? (Object) 0L : (Object) 0) : null;
			}));
		final java.lang.reflect.Field f = PocketGeTrackerPlugin.class.getDeclaredField("slotProgress");
		f.setAccessible(true);
		@SuppressWarnings("unchecked")
		final Map<Integer, long[]> progress = (Map<Integer, long[]>) f.get(plugin);
		progress.put(0, new long[]{100, System.currentTimeMillis()});
		final net.runelite.api.events.GameStateChanged logout = new net.runelite.api.events.GameStateChanged();
		logout.setGameState(net.runelite.api.GameState.LOGIN_SCREEN);
		plugin.onGameStateChanged(logout);
		Assert.assertTrue(progress.isEmpty());
	}
}
