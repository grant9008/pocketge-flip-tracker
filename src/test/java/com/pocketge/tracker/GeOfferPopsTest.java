package com.pocketge.tracker;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import org.junit.Assert;
import org.junit.Test;

/**
 * The profit figure through the real overlay: "even if i missed it live, the
 * next time i load into this ge screen, it should have said +64kgp over rune
 * darts in that square but it didnt".
 */
public class GeOfferPopsTest
{
	private static final int DART = 811;
	private static final Rectangle SQUARE = new Rectangle(240, 60, 115, 106);

	/** What the fake client shows: the Exchange open or shut, and what slot
	 *  2 holds. */
	private boolean open;
	private int inSlot2 = DART;

	private GeOfferGridOverlay overlay() throws Exception
	{
		final Widget square = (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(),
			new Class<?>[]{Widget.class}, (proxy, m, args) ->
				"getBounds".equals(m.getName()) ? new Rectangle(SQUARE) : defaults(m.getReturnType()));
		final GrandExchangeOffer offer = (GrandExchangeOffer) Proxy.newProxyInstance(
			GrandExchangeOffer.class.getClassLoader(), new Class<?>[]{GrandExchangeOffer.class},
			(proxy, m, args) -> "getItemId".equals(m.getName()) ? inSlot2 : defaults(m.getReturnType()));
		final Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
			new Class<?>[]{Client.class}, (proxy, m, args) ->
			{
				if ("getWidget".equals(m.getName()) && args.length == 1)
				{
					return open && (Integer) args[0] == InterfaceID.GeOffers.INDEX_2 ? square : null;
				}
				if ("getGrandExchangeOffers".equals(m.getName()))
				{
					final GrandExchangeOffer[] all = new GrandExchangeOffer[8];
					all[2] = offer;
					return all;
				}
				return defaults(m.getReturnType());
			});
		final Constructor<GeOfferGridOverlay> k =
			GeOfferGridOverlay.class.getDeclaredConstructor(Client.class, TooltipManager.class);
		k.setAccessible(true);
		return k.newInstance(client, new TooltipManager());
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

	/** Green pixels the overlay painted inside the square. */
	private static int greenIn(GeOfferGridOverlay o)
	{
		final BufferedImage img = new BufferedImage(400, 200, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = img.createGraphics();
		o.render(g);
		g.dispose();
		int n = 0;
		for (int y = 0; y < img.getHeight(); y++)
		{
			for (int x = 0; x < img.getWidth(); x++)
			{
				final int rgb = img.getRGB(x, y);
				final boolean green = (rgb >>> 24) > 200
					&& Math.abs(((rgb >> 16) & 0xFF) - ProfitPops.GAIN.getRed()) < 12
					&& Math.abs(((rgb >> 8) & 0xFF) - ProfitPops.GAIN.getGreen()) < 12
					&& Math.abs((rgb & 0xFF) - ProfitPops.GAIN.getBlue()) < 12;
				if (green)
				{
					Assert.assertTrue("only over its own square", SQUARE.contains(x, y));
					n++;
				}
			}
		}
		return n;
	}

	@Test
	public void soldWithTheExchangeShutPopsWhenItOpens() throws Exception
	{
		final GeOfferGridOverlay o = overlay();
		open = false;
		o.pop(2, DART, 64_000, System.currentTimeMillis());
		Assert.assertEquals("nothing while it is shut", 0, greenIn(o));
		Assert.assertEquals(0, greenIn(o));
		open = true;
		Assert.assertTrue("+64K gp over the Rune dart square", greenIn(o) > 0);
	}

	@Test
	public void soldWithTheExchangeOpenPopsAtOnce() throws Exception
	{
		final GeOfferGridOverlay o = overlay();
		open = true;
		o.pop(2, DART, 2_000, System.currentTimeMillis());
		Assert.assertTrue(greenIn(o) > 0);
	}

	/** Collected somewhere it could not see, and the slot holds something
	 *  else by the time the Exchange opens: no figure over the wrong offer. */
	@Test
	public void notOverAnOfferItWasNotFor() throws Exception
	{
		final GeOfferGridOverlay o = overlay();
		open = false;
		o.pop(2, DART, 64_000, System.currentTimeMillis());
		inSlot2 = 0;
		open = true;
		Assert.assertEquals(0, greenIn(o));
		inSlot2 = DART;
		Assert.assertEquals("dropped, not deferred", 0, greenIn(o));
	}

	/** On a world with its own Exchange the figures wait, and play once back. */
	@Test
	public void pausedOnAnotherExchange() throws Exception
	{
		final GeOfferGridOverlay o = overlay();
		open = true;
		o.setPopsPaused(true);
		o.pop(2, DART, 64_000, System.currentTimeMillis());
		Assert.assertEquals(0, greenIn(o));
		o.setPopsPaused(false);
		Assert.assertTrue(greenIn(o) > 0);
	}

	@Test
	public void clearedForAnotherCharacter() throws Exception
	{
		final GeOfferGridOverlay o = overlay();
		open = false;
		o.pop(2, DART, 64_000, System.currentTimeMillis());
		o.clearPops();
		open = true;
		Assert.assertEquals(0, greenIn(o));
	}
}
