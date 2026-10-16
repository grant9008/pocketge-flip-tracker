package com.pocketge.tracker;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * A disabled plugin sends nothing.
 *
 * The executor every background task runs on is RuneLite's, shared by the
 * whole client, and it is not shut down when a plugin is. 0.7.1's price
 * retry rescheduled itself while the Wiki was unreachable, so a plugin
 * disabled during an outage kept asking for prices — once a minute offline,
 * every half hour against a Wiki that was refusing it — for as long as the
 * client stayed open.
 */
public class ShutdownQuietTest
{
	private PocketGeTrackerPlugin plugin;
	private final AtomicInteger scheduled = new AtomicInteger();

	private void set(String field, Object value) throws Exception
	{
		final Field f = PocketGeTrackerPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(plugin, value);
	}

	private void retry() throws Exception
	{
		final Method m = PocketGeTrackerPlugin.class.getDeclaredMethod("scheduleBulkRetry");
		m.setAccessible(true);
		m.invoke(plugin);
	}

	@Before
	public void wire() throws Exception
	{
		plugin = new PocketGeTrackerPlugin();
		set("marketClient", new MarketClient(null, null));
		set("executor", Proxy.newProxyInstance(
			ScheduledExecutorService.class.getClassLoader(),
			new Class<?>[]{ScheduledExecutorService.class},
			(proxy, method, args) ->
			{
				if ("schedule".equals(method.getName()))
				{
					scheduled.incrementAndGet();
				}
				final Class<?> r = method.getReturnType();
				if (r == boolean.class)
				{
					return false;
				}
				return r.isPrimitive() ? 0 : null;
			}));
	}

	@Test
	public void aStoppedPluginSchedulesNoRetry() throws Exception
	{
		set("running", false);
		retry();
		Assert.assertEquals(0, scheduled.get());
	}

	@Test
	public void aRunningPluginSchedulesOneRetryAtATime() throws Exception
	{
		set("running", true);
		retry();
		retry();
		Assert.assertEquals("one pending retry, not a chain per caller", 1, scheduled.get());
	}

	/** shutDown has to flip the switch before anything else, so a task the
	 *  executor is about to run sees it. Read from the source: shutDown
	 *  needs a whole running client. */
	@Test
	public void shutDownStopsTheNetworkFirst() throws Exception
	{
		final String src = Files.readString(Path.of("src/main/java/com/pocketge/tracker/PocketGeTrackerPlugin.java"))
			.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
		final int at = src.indexOf("protected void shutDown()");
		Assert.assertTrue(at > 0);
		final String body = src.substring(src.indexOf('{', at) + 1).trim();
		Assert.assertTrue(body.substring(0, 40), body.startsWith("running = false;"));
		Assert.assertTrue("and cancels the pending retry",
			body.substring(0, 400).contains("retry.cancel(false)"));
	}
}
