package com.pocketge.tracker;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
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
	private static final Rectangle SQUARE = new Rectangle(240, 60, 115, 106);
	private static final long T = 1_000_000L;

	/** What the fake client shows: the Exchange's slot screen open or not. */
	private boolean open;
	/** The fake plugin: unshown {units, profit} per slot, and what it was
	 *  told has been shown. */
	private final Map<Integer, long[]> unseen = new HashMap<>();
	private final List<Integer> shown = new ArrayList<>();
	private long now = T;

	private GeOfferGridOverlay overlay() throws Exception
	{
		final Widget square = (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(),
			new Class<?>[]{Widget.class}, (proxy, m, args) ->
				"getBounds".equals(m.getName()) ? new Rectangle(SQUARE) : defaults(m.getReturnType()));
		final Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
			new Class<?>[]{Client.class}, (proxy, m, args) ->
			{
				if ("getWidget".equals(m.getName()) && args.length == 1)
				{
					return open && (Integer) args[0] == InterfaceID.GeOffers.INDEX_2 ? square : null;
				}
				return defaults(m.getReturnType());
			});
		final Constructor<GeOfferGridOverlay> k =
			GeOfferGridOverlay.class.getDeclaredConstructor(Client.class, TooltipManager.class);
		k.setAccessible(true);
		final GeOfferGridOverlay o = k.newInstance(client, new TooltipManager());
		o.clock = () -> now;
		o.setPopSource(new GeOfferGridOverlay.PopSource()
		{
			@Override
			public long[] unseen(int slot)
			{
				return unseen.get(slot);
			}

			@Override
			public void shown(int slot)
			{
				shown.add(slot);
				unseen.remove(slot);
			}
		});
		return o;
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

	/** Green pixels the overlay painted — all of them inside the square. */
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

	/** Over the Exchange: done boxed three pixels deep in green, a reprice
	 *  two in a softened white. */
	@Test
	public void doneIsBoxedHeavierThanAReprice() throws Exception
	{
		final GeOfferGridOverlay o = overlay();
		open = true;
		final GeOfferGridOverlay.SlotView v = new GeOfferGridOverlay.SlotView();
		v.itemName = "Shark";
		v.ready = true;
		o.setSlots(Map.of(2, v));
		BufferedImage img = new BufferedImage(400, 200, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		o.render(g);
		g.dispose();
		final int mid = SQUARE.x + SQUARE.width / 2;
		for (int i = 0; i < 3; i++)
		{
			Assert.assertEquals("green, row " + i, GeSlotsPanel.COLLECT_COLOR.getRGB(), img.getRGB(mid, SQUARE.y + i));
		}
		Assert.assertEquals("three deep, no more", 0, img.getRGB(mid, SQUARE.y + 3) >>> 24);

		v.ready = false;
		v.needsAdjust = true;
		img = new BufferedImage(400, 200, BufferedImage.TYPE_INT_ARGB);
		g = img.createGraphics();
		o.render(g);
		g.dispose();
		for (int i = 0; i < 2; i++)
		{
			final int rgb = img.getRGB(mid, SQUARE.y + i);
			Assert.assertEquals("white, row " + i, GeSlotsPanel.REPRICE_BOX.getRGB() & 0xFFFFFF, rgb & 0xFFFFFF);
			Assert.assertTrue("softened", (rgb >>> 24) < 0xFF);
		}
		Assert.assertEquals("two deep", 0, img.getRGB(mid, SQUARE.y + 2) >>> 24);
	}

	/** Cooked sharks, no buy on record: the coins they brought in, in gold
	 *  over their square, and nothing green — it is not profit. */
	@Test
	public void aSaleWithNoBuyOnRecordPopsGold() throws Exception
	{
		final GeOfferGridOverlay o = overlay();
		unseen.put(2, new long[]{500, 441_000, FlipTracker.UNSEEN_COINS});
		open = true;
		final BufferedImage img = new BufferedImage(400, 200, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = img.createGraphics();
		o.render(g);
		g.dispose();
		Assert.assertTrue("gold over the square", CoinPopsTest.count(img, ProfitPops.COINS) > 0);
		Assert.assertEquals("not green", 0, CoinPopsTest.count(img, ProfitPops.GAIN));
		Assert.assertEquals(List.of(2), shown);
	}

	/** Sold with the Exchange shut; opened ten minutes later — far past a
	 *  figure's three seconds — and there it is, once. */
	@Test
	public void soldWithTheExchangeShutPopsWhenItOpens() throws Exception
	{
		final GeOfferGridOverlay o = overlay();
		unseen.put(2, new long[]{7_132, 64_000});
		open = false;
		Assert.assertEquals("nothing while it is shut", 0, greenIn(o));
		now = T + 10 * 60_000;
		Assert.assertEquals(0, greenIn(o));
		Assert.assertTrue("and not counted as shown", shown.isEmpty());

		open = true;
		Assert.assertTrue("+64K gp over the Rune dart square", greenIn(o) > 0);
		Assert.assertEquals("shown, so it will not come back", List.of(2), shown);

		now += ProfitPops.LIFE_MS;
		Assert.assertEquals("over after its three seconds", 0, greenIn(o));
		open = false;
		open = true;
		Assert.assertEquals("not again on reopening", 0, greenIn(o));
	}

	@Test
	public void soldWithTheExchangeOpenPopsAtOnce() throws Exception
	{
		final GeOfferGridOverlay o = overlay();
		open = true;
		Assert.assertEquals(0, greenIn(o));
		unseen.put(2, new long[]{100, 2_000});
		Assert.assertTrue(greenIn(o) > 0);
	}

	/** Only a slot whose square is up is asked, so nothing is counted as
	 *  shown that was not. */
	@Test
	public void nothingIsShownThatWasNotOnScreen() throws Exception
	{
		final GeOfferGridOverlay o = overlay();
		unseen.put(5, new long[]{100, 2_000});
		open = true;
		Assert.assertEquals(0, greenIn(o));
		Assert.assertTrue(shown.isEmpty());
	}

	/** Switched off: the figure on screen goes too. */
	@Test
	public void clearTakesTheFigureOff() throws Exception
	{
		final GeOfferGridOverlay o = overlay();
		open = true;
		unseen.put(2, new long[]{100, 2_000});
		Assert.assertTrue(greenIn(o) > 0);
		o.clearPops();
		Assert.assertEquals(0, greenIn(o));
	}
}
