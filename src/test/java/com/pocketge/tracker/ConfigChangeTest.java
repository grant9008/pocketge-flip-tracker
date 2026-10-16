package com.pocketge.tracker;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import net.runelite.client.events.ConfigChanged;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * A write to the plugin's config group is not always a setting.
 *
 * The plugin keeps its own data in the same group — the trade state on every
 * offer update, the bank snapshot whenever the bank changes, the favourite
 * lists whenever the website stars an item. onConfigChanged used to restart
 * the advisor and the bridge on every one of them: an immediate round of
 * price requests to the wiki for a bank deposit, and the bridge's HTTP
 * server torn down from inside its own request handler.
 */
public class ConfigChangeTest
{
	private PocketGeTrackerPlugin plugin;
	private int bridgeStops;
	private int advisorCancels;

	private static void set(Object o, String field, Object value) throws Exception
	{
		final Field f = PocketGeTrackerPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(o, value);
	}

	/** Every setting off, every number zero: enough for the sync methods to
	 *  run to completion without starting anything. */
	private static PocketGeTrackerConfig quietConfig()
	{
		return (PocketGeTrackerConfig) Proxy.newProxyInstance(
			PocketGeTrackerConfig.class.getClassLoader(),
			new Class<?>[]{PocketGeTrackerConfig.class},
			(proxy, method, args) ->
			{
				final Class<?> r = method.getReturnType();
				if (r == boolean.class)
				{
					return false;
				}
				if (r.isPrimitive())
				{
					return r == long.class ? 0L : 0;
				}
				return null;
			});
	}

	@Before
	public void wire() throws Exception
	{
		plugin = new PocketGeTrackerPlugin();
		set(plugin, "config", quietConfig());
		set(plugin, "bridge", new LocalBridgeServer(new Gson())
		{
			@Override
			public void stop()
			{
				bridgeStops++;
			}
		});
		armAdvisor();
	}

	/** syncAdvisor starts by cancelling the running schedule, so a counted
	 *  cancel is a restart. Re-armed after each, since it nulls the field. */
	private void armAdvisor() throws Exception
	{
		set(plugin, "advisorTask", new ScheduledFuture<Object>()
		{
			@Override
			public boolean cancel(boolean interrupt)
			{
				advisorCancels++;
				return true;
			}

			@Override
			public long getDelay(TimeUnit unit)
			{
				return 0;
			}

			@Override
			public int compareTo(Delayed o)
			{
				return 0;
			}

			@Override
			public boolean isCancelled()
			{
				return false;
			}

			@Override
			public boolean isDone()
			{
				return false;
			}

			@Override
			public Object get()
			{
				return null;
			}

			@Override
			public Object get(long timeout, TimeUnit unit)
			{
				return null;
			}
		});
	}

	private void write(String key)
	{
		final ConfigChanged e = new ConfigChanged();
		e.setGroup(PocketGeTrackerConfig.GROUP);
		e.setKey(key);
		plugin.onConfigChanged(e);
	}

	@Test
	public void theirOwnDataRestartsNothing()
	{
		for (String key : new String[]{"state", "bankMemory", "watchlistRows",
			"favoriteLists", "favorites", "activeFavoriteList",
			"priceAlerts", "reuseBrowserTab", "confirmBlock"})
		{
			write(key);
			Assert.assertEquals(key + " restarted the bridge", 0, bridgeStops);
			Assert.assertEquals(key + " restarted the advisor", 0, advisorCancels);
		}
	}

	@Test
	public void theBridgeSettingsRestartTheBridgeOnly()
	{
		write("localBridge");
		write("bridgePort");
		Assert.assertEquals(2, bridgeStops);
		Assert.assertEquals(0, advisorCancels);
	}

	@Test
	public void theAdvisorSettingsRestartTheAdvisorOnly() throws Exception
	{
		write("advisor");
		Assert.assertEquals(1, advisorCancels);
		armAdvisor();
		write("adjustInterval");
		Assert.assertEquals(2, advisorCancels);
		Assert.assertEquals(0, bridgeStops);
	}

	@Test
	public void anotherPluginsGroupIsIgnored()
	{
		final ConfigChanged e = new ConfigChanged();
		e.setGroup("someotherplugin");
		e.setKey("localBridge");
		plugin.onConfigChanged(e);
		Assert.assertEquals(0, bridgeStops);
		Assert.assertEquals(0, advisorCancels);
	}
}
