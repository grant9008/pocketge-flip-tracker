package com.pocketge.tracker;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import org.junit.Assert;
import org.junit.Test;

/**
 * "thoughts on adding little green +$200gp or whatever profits / lossses as
 * well on these on side bar any time you dont have the grand exchange window
 * open so it doesnt double repeat".
 */
public class SidebarPopsTest
{
	private static final int DART = 811;
	private static final long T = 1_000_000L;

	@Test
	public void theSquaresFigureIsShort()
	{
		Assert.assertEquals("+200", ProfitPops.shortText(200));
		Assert.assertEquals("+2K", ProfitPops.shortText(2_000));
		Assert.assertEquals("+1.2K", ProfitPops.shortText(1_250));
		Assert.assertEquals("+64K", ProfitPops.shortText(64_000));
		Assert.assertEquals("+999K", ProfitPops.shortText(999_999));
		Assert.assertEquals("-1.1M", ProfitPops.shortText(-1_120_000));
		Assert.assertEquals("+0", ProfitPops.shortText(0));
		Assert.assertTrue(ProfitPops.shortText(Long.MIN_VALUE).startsWith("-"));
	}

	/* ── The square ─────────────────────────────────────────────────── */

	private static List<JComponent> cells(Container root)
	{
		final List<JComponent> out = new ArrayList<>();
		for (Component c : root.getComponents())
		{
			if (c.getClass().getSimpleName().equals("Cell"))
			{
				out.add((JComponent) c);
			}
			else if (c instanceof Container)
			{
				out.addAll(cells((Container) c));
			}
		}
		return out;
	}

	private static int green(JComponent cell)
	{
		cell.setSize(49, 40);
		final BufferedImage img = new BufferedImage(49, 40, BufferedImage.TYPE_INT_ARGB);
		final Graphics g = img.createGraphics();
		cell.paint(g);
		g.dispose();
		int n = 0;
		for (int y = 0; y < img.getHeight(); y++)
		{
			for (int x = 0; x < img.getWidth(); x++)
			{
				final int rgb = img.getRGB(x, y);
				if ((rgb >>> 24) > 200 && Math.abs(((rgb >> 16) & 0xFF) - ProfitPops.GAIN.getRed()) < 12
					&& Math.abs(((rgb >> 8) & 0xFF) - ProfitPops.GAIN.getGreen()) < 12
					&& Math.abs((rgb & 0xFF) - ProfitPops.GAIN.getBlue()) < 12)
				{
					n++;
				}
			}
		}
		return n;
	}

	@Test
	public void itRisesOffItsOwnSquareAndGoes()
	{
		final GeSlotsPanel p = new GeSlotsPanel(null, null);
		final long[] now = {T};
		p.clock = () -> now[0];
		p.update(new GeSlotsPanel.SlotInfo[8]);
		final List<JComponent> cells = cells(p);
		Assert.assertEquals(8, cells.size());
		Assert.assertEquals(0, green(cells.get(2)));

		p.pop(2, 2_000);
		Assert.assertTrue("+2K on the square", green(cells.get(2)) > 0);
		Assert.assertEquals("and only there", 0, green(cells.get(3)));
		now[0] += ProfitPops.LIFE_MS;
		Assert.assertEquals("gone after its time", 0, green(cells.get(2)));
	}

	/** Switched off: the figure on the square goes with it. */
	@Test
	public void clearTakesItOff()
	{
		final GeSlotsPanel p = new GeSlotsPanel(null, null);
		p.clock = () -> T;
		p.update(new GeSlotsPanel.SlotInfo[8]);
		p.pop(2, 2_000);
		p.clearPops();
		Assert.assertEquals(0, green(cells(p).get(2)));
	}

	/**
	 * What counts as seen. A figure played on the sidebar is used up, so the
	 * squares have to be really in front of you: not scrolled away, not in a
	 * minimised client or one behind another window, not folded.
	 */
	@Test
	public void onlyWhatIsReallyInViewCountsAsSeen()
	{
		Assert.assertTrue(GeSlotsPanel.squaresSeen(true, true, true, false, 80, 80));
		Assert.assertTrue("half in view is enough", GeSlotsPanel.squaresSeen(true, true, true, false, 40, 80));
		Assert.assertFalse("scrolled mostly away", GeSlotsPanel.squaresSeen(true, true, true, false, 39, 80));
		Assert.assertFalse("scrolled right away", GeSlotsPanel.squaresSeen(true, true, true, false, 0, 80));
		Assert.assertFalse("folded", GeSlotsPanel.squaresSeen(false, true, true, false, 80, 80));
		Assert.assertFalse("another tab", GeSlotsPanel.squaresSeen(true, false, true, false, 80, 80));
		Assert.assertFalse("another window in front", GeSlotsPanel.squaresSeen(true, true, false, false, 80, 80));
		Assert.assertFalse("minimised", GeSlotsPanel.squaresSeen(true, true, true, true, 80, 80));
		Assert.assertFalse("not laid out", GeSlotsPanel.squaresSeen(true, true, true, false, 0, 0));
	}

	/** Out of the box, before anything has shown it, the squares are not
	 *  counted as seen. */
	@Test
	public void aPanelNeverShownIsNotSeen()
	{
		Assert.assertFalse(new GeSlotsPanel(null, null).squaresShowing());
	}

	/* ── Which place plays it ──────────────────────────────────────── */

	private boolean geOpen;
	/** The Exchange open on an offer's own screen: squares loaded, hidden. */
	private boolean offerScreen;
	private boolean popsOn = true;
	private PocketGeTrackerPlugin plugin;
	private FlipTracker tracker;
	private MainPanel panel;

	private void setUp() throws Exception
	{
		final Widget square = (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(),
			new Class<?>[]{Widget.class}, (proxy, m, args) -> defaults(m.getReturnType()));
		final Widget hiddenSquare = (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(),
			new Class<?>[]{Widget.class}, (proxy, m, args) ->
				"isHidden".equals(m.getName()) ? true : defaults(m.getReturnType()));
		final GrandExchangeOffer offer = (GrandExchangeOffer) Proxy.newProxyInstance(
			GrandExchangeOffer.class.getClassLoader(), new Class<?>[]{GrandExchangeOffer.class},
			(proxy, m, args) -> "getItemId".equals(m.getName()) ? DART
				: "getState".equals(m.getName()) ? GrandExchangeOfferState.SOLD : defaults(m.getReturnType()));
		final Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
			new Class<?>[]{Client.class}, (proxy, m, args) ->
			{
				switch (m.getName())
				{
					case "getAccountHash":
						return 1111L;
					case "getGameState":
						return GameState.LOGGED_IN;
					case "getTickCount":
						return 100;
					case "getWidget":
						if (args.length != 1 || (Integer) args[0] != InterfaceID.GeOffers.INDEX_2)
						{
							return null;
						}
						return geOpen ? square : offerScreen ? hiddenSquare : null;
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
		final MainPanel.Actions actions = (MainPanel.Actions) Proxy.newProxyInstance(
			MainPanel.Actions.class.getClassLoader(), new Class<?>[]{MainPanel.Actions.class},
			(proxy, m, args) -> defaults(m.getReturnType()));
		panel = new MainPanel(null, actions);
		plugin = new PocketGeTrackerPlugin();
		set(plugin, PocketGeTrackerPlugin.class, "client", client);
		set(plugin, PocketGeTrackerPlugin.class, "config", config);
		set(plugin, PocketGeTrackerPlugin.class, "geSettledTick", 10);
		set(plugin, PocketGeTrackerPlugin.class, "mainPanel", panel);
		final Field f = PocketGeTrackerPlugin.class.getDeclaredField("tracker");
		f.setAccessible(true);
		tracker = (FlipTracker) f.get(plugin);
		tracker.setAccountHash(1111L);
		tracker.onOffer(1_000, 0, DART, "Rune dart", true, 0, 0, 190, 7_132, false);
		tracker.onOffer(2_000, 0, DART, "Rune dart", true, 7_132, 7_132L * 190, 190, 7_132, false);
		tracker.onOffer(3_000, 0, 0, "", false, 0, 0, 0, 0, true);
		tracker.onOffer(4_000, 2, DART, "Rune dart", false, 0, 0, 200, 7_132, false);
		tracker.onOffer(5_000, 2, DART, "Rune dart", false, 7_132, 7_132L * 200, 200, 7_132, false);
	}

	private static void set(Object target, Class<?> cls, String field, Object value) throws Exception
	{
		final Field f = cls.getDeclaredField(field);
		f.setAccessible(true);
		f.set(target, value);
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

	/** The sidebar's squares, as if the PocketGE tab were open. */
	private GeSlotsPanel squares(boolean showing) throws Exception
	{
		final Field fav = MainPanel.class.getDeclaredField("favoritesPanel");
		fav.setAccessible(true);
		final Field slots = FavoritesPanel.class.getDeclaredField("geSlots");
		slots.setAccessible(true);
		final GeSlotsPanel g = (GeSlotsPanel) slots.get(fav.get(panel));
		set(g, GeSlotsPanel.class, "squaresShowing", showing);
		return g;
	}

	private int running(GeSlotsPanel g) throws Exception
	{
		SwingUtilities.invokeAndWait(() -> { });
		final Field f = GeSlotsPanel.class.getDeclaredField("pops");
		f.setAccessible(true);
		return ((ProfitPops) f.get(g)).running(System.currentTimeMillis(), s -> true).size();
	}

	private void tick() throws Exception
	{
		final Method m = PocketGeTrackerPlugin.class.getDeclaredMethod("popInSidebar");
		m.setAccessible(true);
		m.invoke(plugin);
	}

	@Test
	public void theExchangeShutTheSidebarPlaysItOnce() throws Exception
	{
		setUp();
		final GeSlotsPanel g = squares(true);
		tick();
		Assert.assertEquals("+42.7K on the sidebar square", 1, running(g));
		Assert.assertNull("and shown, so the Exchange will not play it again", plugin.unseenSale(2));
	}

	@Test
	public void theExchangeOpenItIsLeftToTheExchange() throws Exception
	{
		setUp();
		final GeSlotsPanel g = squares(true);
		geOpen = true;
		tick();
		Assert.assertEquals(0, running(g));
		Assert.assertNotNull("still waiting for the Exchange's own square", plugin.unseenSale(2));
	}

	/** The Exchange open on an offer's own screen: its squares are hidden,
	 *  but it is open — the figure waits for the slot screen, not the
	 *  sidebar: "any time you dont have the grand exchange window open". */
	@Test
	public void theExchangeOnAnOffersScreenStillCountsAsOpen() throws Exception
	{
		setUp();
		final GeSlotsPanel g = squares(true);
		offerScreen = true;
		tick();
		Assert.assertEquals(0, running(g));
		Assert.assertNotNull(plugin.unseenSale(2));
	}

	@Test
	public void neitherOnScreenItWaits() throws Exception
	{
		setUp();
		final GeSlotsPanel g = squares(false);
		tick();
		Assert.assertEquals(0, running(g));
		Assert.assertNotNull(plugin.unseenSale(2));
	}

	@Test
	public void switchedOffNothing() throws Exception
	{
		setUp();
		final GeSlotsPanel g = squares(true);
		popsOn = false;
		tick();
		Assert.assertEquals(0, running(g));
	}
}
