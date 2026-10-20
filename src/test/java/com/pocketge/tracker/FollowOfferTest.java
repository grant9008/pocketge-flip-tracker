package com.pocketge.tracker;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * "every time i list a new item in the ge, if i already have a pocket ge
 * window open ... can it automatically change the window from sapphire
 * necklaces to ruby necklaces when i switch to my next ge listing".
 */
public class FollowOfferTest
{
	private PocketGeTrackerPlugin plugin;
	private LocalBridgeServer bridge;
	private boolean follow = true;

	private Object get(String field) throws Exception
	{
		final Field f = PocketGeTrackerPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		return f.get(plugin);
	}

	private void set(String field, Object v) throws Exception
	{
		final Field f = PocketGeTrackerPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(plugin, v);
	}

	@Before
	public void start() throws Exception
	{
		plugin = new PocketGeTrackerPlugin();
		bridge = new LocalBridgeServer(new Gson());
		bridge.start(0, HashMap::new, HashMap::new, null, null);
		set("bridge", bridge);
		set("config", Proxy.newProxyInstance(PocketGeTrackerConfig.class.getClassLoader(),
			new Class<?>[]{PocketGeTrackerConfig.class}, (p, m, a) ->
			{
				if (m.getName().equals("followGeOffer"))
				{
					return follow;
				}
				final Class<?> r = m.getReturnType();
				return r == boolean.class ? (Object) false : r.isPrimitive() ? (Object) 0 : null;
			}));
	}

	@After
	public void stop()
	{
		bridge.stop();
	}

	/** A tab that asked /nav a moment ago — the linked tab. */
	private void tabOpen() throws Exception
	{
		final Field f = LocalBridgeServer.class.getDeclaredField("lastNavPollAt");
		f.setAccessible(true);
		f.setLong(bridge, System.currentTimeMillis());
	}

	private LocalBridgeServer.NavRequest sent() throws Exception
	{
		return (LocalBridgeServer.NavRequest) get("pendingNav");
	}

	@Test
	public void theOpenTabFollowsToTheNextListing() throws Exception
	{
		tabOpen();
		plugin.followOfferScreen(1660, "Sapphire necklace");
		Assert.assertEquals("Sapphire necklace", sent().query);
		Assert.assertFalse("no ack wait: it opens nothing if the tab does not answer", sent().ack);
		final long first = sent().seq;

		plugin.followOfferScreen(1660, "Sapphire necklace");
		Assert.assertEquals("once per item, not on every screen update", first, sent().seq);

		plugin.followOfferScreen(1662, "Ruby necklace");
		Assert.assertEquals("Ruby necklace", sent().query);
	}

	@Test
	public void noTabNoMove() throws Exception
	{
		plugin.followOfferScreen(1660, "Sapphire necklace");
		Assert.assertNull("never opens one", sent());
	}

	@Test
	public void theSettingTurnsItOff() throws Exception
	{
		follow = false;
		tabOpen();
		plugin.followOfferScreen(1660, "Sapphire necklace");
		Assert.assertNull(sent());
	}
}
