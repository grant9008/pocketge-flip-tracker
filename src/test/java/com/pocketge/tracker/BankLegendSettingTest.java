package com.pocketge.tracker;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.Client;
import org.junit.Assert;
import org.junit.Test;

/**
 * The key beside the bank has a switch, and the switch works.
 *
 * "get rid of this your current suggestion / also worth selling. maybe hide
 * that in settings or off the ge bank menu. it covers the bank note" — after
 * it had already been moved once for covering the bank's tool row. The hover
 * tooltip on a marked slot says the same words, so the key became a setting.
 * Once its placement was fixed it went back on by default: "have it on by
 * default, the new location is fine".
 */
public class BankLegendSettingTest
{
	private static void set(Object o, String field, Object value) throws Exception
	{
		final Field f = o.getClass().getDeclaredField(field);
		f.setAccessible(true);
		f.set(o, value);
	}

	private static <T> T build(Class<T> cls) throws Exception
	{
		final Constructor<T> ctor = cls.getDeclaredConstructor();
		ctor.setAccessible(true);
		return ctor.newInstance();
	}

	/** A client that counts how often the legend went looking for the bank. */
	private static Client counting(AtomicInteger lookups)
	{
		return (Client) Proxy.newProxyInstance(
			Client.class.getClassLoader(),
			new Class<?>[]{Client.class},
			(proxy, method, args) ->
			{
				if ("getWidget".equals(method.getName()))
				{
					lookups.incrementAndGet();
				}
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

	@Test
	public void drawsNothingUntilTurnedOn() throws Exception
	{
		final BankHighlightOverlay marks = build(BankHighlightOverlay.class);
		marks.setEnabled(true); // the marks themselves stay on by default
		final BankLegendOverlay legend = build(BankLegendOverlay.class);
		final AtomicInteger lookups = new AtomicInteger();
		set(legend, "client", counting(lookups));
		set(legend, "marks", marks);
		final Graphics2D g = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB).createGraphics();

		legend.render(g);
		Assert.assertEquals("off: the bank is not even looked for", 0, lookups.get());

		legend.setEnabled(true);
		legend.render(g);
		Assert.assertTrue("on: it goes looking for the bank to sit beside", lookups.get() > 0);
	}

	/** The setting's default, read from the source because the interface's
	 *  default method cannot be called without a running config manager. */
	@Test
	public void theSettingDefaultsOn() throws Exception
	{
		final String src = Files.readString(Path.of("src/main/java/com/pocketge/tracker/PocketGeTrackerConfig.java"));
		final int at = src.indexOf("default boolean bankLegend()");
		Assert.assertTrue("the setting exists", at > 0);
		final String body = src.substring(at, src.indexOf('}', at));
		Assert.assertTrue(body, body.contains("return true;"));
	}
}
