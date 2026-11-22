package com.pocketge.tracker;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/**
 * "thoughts on hover over also showing high alch profit ea alch?" and
 * "putting high alch ea profit on the flip trade idea cards too in this
 * area": what one cast on a stack makes, after a nature rune, against what
 * one cost — on the stack's hover and beside VALUE on its card.
 */
public class HighAlchTest
{
	private static final int KITESHIELD = 1201;
	private static final int BATTLEAXE = 1373;

	@Test
	public void oneCast()
	{
		Assert.assertEquals(797, HighAlch.perCast(32_400, 103, 31_500));
		Assert.assertEquals(-203, HighAlch.perCast(400, 103, 500));
		Assert.assertTrue(HighAlch.known(32_400, 103, 31_500));
		Assert.assertFalse("does not alch", HighAlch.known(0, 103, 31_500));
		Assert.assertFalse("no rune price", HighAlch.known(32_400, 0, 31_500));
		Assert.assertFalse("no cost", HighAlch.known(32_400, 103, 0));
	}

	@Test
	public void figures()
	{
		Assert.assertEquals("+797", HighAlch.signed(797));
		Assert.assertEquals("-24,247", HighAlch.signed(-24_247));
		Assert.assertEquals("+0", HighAlch.signed(0));
		Assert.assertEquals("+15", HighAlch.compact(15));
		Assert.assertEquals("-999", HighAlch.compact(-999));
		Assert.assertEquals("+1K", HighAlch.compact(1_000));
		Assert.assertEquals("+24.2K", HighAlch.compact(24_247));
		Assert.assertEquals("-12.3K", HighAlch.compact(-12_300));
		Assert.assertEquals("+999.9K", HighAlch.compact(999_949));
		Assert.assertEquals("+1M", HighAlch.compact(999_950));
		Assert.assertEquals("+1.5M", HighAlch.compact(1_500_000));
	}

	/* ── The hover ─────────────────────────────────────────────────────── */

	/** Bought at 31,500, alchs for 32,400, a rune at 103: +797 a cast, in
	 *  green, with the sum; and alching beats selling at 32,000 (31,360
	 *  after tax) by 937. */
	@Test
	public void theHoverOnAStackYouBought()
	{
		final String t = BankHighlightOverlay.alchText(KITESHIELD, new long[]{100, 100L * 31_500, 32_000}, 32_400, 103);
		Assert.assertTrue(t, t.startsWith(TipStyle.BREAK + TipStyle.state(TipStyle.GOOD, "+797 gp ea to high alch")));
		Assert.assertTrue(t, t.contains(TipStyle.muted(" (32,400 - 103 rune - 31,500 paid)")));
		Assert.assertTrue(t, t.endsWith(TipStyle.BREAK + TipStyle.state(TipStyle.GOOD, "Alching beats selling by 937 gp ea")));
	}

	/** No buy on record: against the price it sells at, the sale alching
	 *  gives up. */
	@Test
	public void theHoverWithNoBuyOnRecord()
	{
		final String t = BankHighlightOverlay.alchText(BATTLEAXE, new long[]{0, 0, 24_000}, 24_960, 103);
		Assert.assertTrue(t, t.startsWith(TipStyle.BREAK + TipStyle.state(TipStyle.GOOD, "+857 gp ea to high alch")
			+ TipStyle.muted(" (24,960 - 103 rune - 24,000 price)")));
	}

	/** "have it only show a high alch profit if there is one, dont have it
	 *  say negative": a cast that loses, or only breaks even, says nothing. */
	@Test
	public void nothingWhenItDoesNotPay()
	{
		Assert.assertEquals("", BankHighlightOverlay.alchText(BATTLEAXE, new long[]{0, 0, 25_600}, 24_960, 103));
		Assert.assertEquals("even", "", BankHighlightOverlay.alchText(BATTLEAXE, new long[]{0, 0, 24_857}, 24_960, 103));
		Assert.assertEquals("a loss on what you paid, though it beats selling", "",
			BankHighlightOverlay.alchText(BATTLEAXE, new long[]{10, 10L * 30_000, 20_000}, 24_960, 103));
	}

	/** Nothing to say: nothing. */
	@Test
	public void theHoverSaysNothingItCannotWorkOut()
	{
		Assert.assertEquals("", BankHighlightOverlay.alchText(BATTLEAXE, new long[]{0, 0, 25_600}, 0, 103));
		Assert.assertEquals("", BankHighlightOverlay.alchText(BATTLEAXE, new long[]{0, 0, 25_600}, 24_960, 0));
		Assert.assertEquals("", BankHighlightOverlay.alchText(BATTLEAXE, new long[]{0, 0, 0}, 24_960, 103));
	}

	/** Both hovers carry it — the plain one and a marked stack's — behind
	 *  its own switch. */
	@Test
	public void bothHoversCarryIt() throws Exception
	{
		final String src = java.nio.file.Files.readString(
			java.nio.file.Path.of("src/main/java/com/pocketge/tracker/BankHighlightOverlay.java"))
			.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
		Assert.assertTrue(src.contains("hoverText(itemId, widgetItem.getQuantity(), h)\n\t\t\t\t+ alchFor(itemId, h) + batchesText"));
		Assert.assertTrue(src.contains("text += alchFor(itemId, h);"));
		Assert.assertTrue(src.contains("return alchOn && h != null && value != null ? alchText(itemId, h, value, naturePrice) : \"\";"));
	}

	/* ── The card ──────────────────────────────────────────────────────── */

	private static AdvisorPanel panel()
	{
		final AdvisorPanel.Actions actions = (AdvisorPanel.Actions) Proxy.newProxyInstance(
			AdvisorPanel.Actions.class.getClassLoader(), new Class<?>[]{AdvisorPanel.Actions.class},
			(proxy, method, args) ->
			{
				final Class<?> t = method.getReturnType();
				return t == boolean.class ? false : t.isPrimitive() ? (t == long.class ? (Object) 0L : (Object) 0) : null;
			});
		final AdvisorPanel p = new AdvisorPanel(null, actions);
		p.setAlch(Map.of(KITESHIELD, 32_400, BATTLEAXE, 24_960), 103);
		return p;
	}

	private static AdvisorPanel.Rec sell(int id, long price, long paid)
	{
		final AdvisorPanel.Rec r = new AdvisorPanel.Rec();
		r.sell = true;
		r.itemId = id;
		r.quantity = 130;
		r.unitPrice = price;
		r.unitCost = paid;
		r.hasTrackedCost = paid > 0;
		return r;
	}

	@Test
	public void theCardsCell() throws Exception
	{
		final AdvisorPanel p = panel();
		final AdvisorPanel.Card.Stat bought = p.alchStat(sell(KITESHIELD, 32_000, 31_500));
		Assert.assertEquals("ALCH EA", bought.label);
		Assert.assertEquals("+797", bought.value);
		Assert.assertEquals(TipStyle.GOOD, bought.valueColor);
		Assert.assertTrue(bought.tip, bought.tip.contains("+797 gp a high alch") && bought.tip.contains("31,500 you paid"));
		final AdvisorPanel.Card.Stat untracked = p.alchStat(sell(BATTLEAXE, 24_000, 0));
		Assert.assertEquals("against its price", "+857", untracked.value);
		Assert.assertTrue(untracked.tip, untracked.tip.contains("24,000 it sells for"));
		Assert.assertNull("nothing when it does not pay", p.alchStat(sell(BATTLEAXE, 25_600, 0)));
		Assert.assertNull("or only breaks even", p.alchStat(sell(BATTLEAXE, 24_857, 0)));
		Assert.assertNull("an item with no alch value", p.alchStat(sell(4151, 1_500_000, 0)));
		final Field f = AdvisorPanel.class.getDeclaredField("settings");
		f.setAccessible(true);
		((AdvisorPanel.Settings) f.get(p)).alchProfit = false;
		Assert.assertNull("switched off", p.alchStat(sell(KITESHIELD, 32_000, 31_500)));
	}

	/** Beside QUANTITY and VALUE when it fits, a tighter gap with three
	 *  across; a quantity in the millions crowds it out. */
	@Test
	public void itGoesWhereItFits()
	{
		assumeTextMeasuresAsItWillOnScreen();
		final List<AdvisorPanel.Card.Stat> row = new ArrayList<>();
		row.add(new AdvisorPanel.Card.Stat("QUANTITY", "130", null));
		row.add(new AdvisorPanel.Card.Stat("VALUE", "81.6K gp", null));
		Assert.assertEquals(16, AdvisorPanel.statGap(row));
		row.add(new AdvisorPanel.Card.Stat("ALCH EA", "+24.2K", null));
		Assert.assertEquals(12, AdvisorPanel.statGap(row));
		Assert.assertTrue(AdvisorPanel.statsFit(row));
		row.set(0, new AdvisorPanel.Card.Stat("QUANTITY", "12,345,678", null));
		row.set(1, new AdvisorPanel.Card.Stat("VALUE", "467.8M gp", null));
		Assert.assertFalse(AdvisorPanel.statsFit(row));
	}

	/**
	 * Skip where measured text cannot mean what these assertions assume.
	 *
	 * The fit checks below measure a JLabel's preferred width against a fixed
	 * pixel budget, which only answers the real question where the measuring
	 * font is the one the client draws with. A headless runner has no display
	 * and falls back to whatever fontconfig offers, which is wider: the ALCH
	 * EA cell stops fitting, statsFit drops it, and the card assertions fail
	 * over a layout that is correct in the client. They were calibrated on a
	 * desktop JVM, so they run there and are skipped where the measurement is
	 * of a different font.
	 *
	 * This is a narrower guarantee than it looks on CI, and worth saying out
	 * loud: on a runner these four tests assert nothing. Making them mean
	 * something everywhere needs a known font bundled with the tests and
	 * measured against, rather than the look-and-feel's default.
	 */
	private static void assumeTextMeasuresAsItWillOnScreen()
	{
		org.junit.Assume.assumeFalse("headless: font metrics are not the client's",
			java.awt.GraphicsEnvironment.isHeadless());
	}

	private static boolean showsLabel(java.awt.Container root, String text)
	{
		for (java.awt.Component c : root.getComponents())
		{
			if (c instanceof javax.swing.JLabel && text.equals(((javax.swing.JLabel) c).getText()))
			{
				return true;
			}
			if (c instanceof java.awt.Container && showsLabel((java.awt.Container) c, text))
			{
				return true;
			}
		}
		return false;
	}

	/** On the card as drawn: there for 130, crowded out by a stack in the
	 *  millions rather than cut off at the edge. */
	@Test
	public void onTheCardAsDrawn()
	{
		assumeTextMeasuresAsItWillOnScreen();
		final AdvisorPanel p = panel();
		p.setLoggedIn(true);
		final AdvisorPanel.Rec small = sell(KITESHIELD, 32_000, 31_500);
		p.setRecommendations(new ArrayList<>(List.of(small)));
		Assert.assertTrue(showsLabel(p, "ALCH EA"));
		Assert.assertTrue(showsLabel(p, "+797"));
		final AdvisorPanel.Rec huge = sell(KITESHIELD, 32_000, 31_500);
		huge.quantity = 123_456_789;
		p.setRecommendations(new ArrayList<>(List.of(huge)));
		Assert.assertTrue("the card is drawn", showsLabel(p, "VALUE"));
		Assert.assertFalse(showsLabel(p, "ALCH EA"));
	}

	private static AdvisorPanel.Rec buy(int qty, long capital)
	{
		final AdvisorPanel.Rec r = new AdvisorPanel.Rec();
		r.itemId = KITESHIELD;
		r.quantity = qty;
		r.unitPrice = 31_730;
		r.exitPrice = 32_699;
		r.capital = capital;
		r.profit = 22_100;
		return r;
	}

	/** "where the high alch on this?" — a buy too: against the bid, what a
	 *  cast makes if the flip never sells. Rune kiteshield bid at 31,730
	 *  alchs for 32,400: +567 after a nature rune at 103. */
	@Test
	public void aBuyAgainstItsBid()
	{
		final AdvisorPanel p = panel();
		final AdvisorPanel.Card.Stat s = p.alchStat(buy(70, 2_221_100));
		Assert.assertEquals("+567", s.value);
		Assert.assertTrue(s.tip, s.tip.contains("31,730 you would bid"));
		final AdvisorPanel.Rec heldToo = buy(70, 2_221_100);
		heldToo.unitCost = 30_000; // a cost carried over: a buy is measured against its bid all the same
		Assert.assertEquals("+567", p.alchStat(heldToo).value);
		final AdvisorPanel.Rec dear = buy(70, 2_300_000);
		dear.unitPrice = 32_400;
		Assert.assertNull("a bid at or over the alch: nothing", p.alchStat(dear));
	}

	/** On the buy card as drawn: beside QUANTITY with the capital as CAPITAL
	 *  and its figure without " gp" — in full; capital in the tens of
	 *  millions crowds it out and the row is as it was. */
	@Test
	public void onTheBuyCardAsDrawn()
	{
		assumeTextMeasuresAsItWillOnScreen();
		final AdvisorPanel p = panel();
		p.setLoggedIn(true);
		p.setRecommendations(new ArrayList<>(List.of(buy(70, 2_221_100))));
		Assert.assertTrue(showsLabel(p, "ALCH EA"));
		Assert.assertTrue(showsLabel(p, "CAPITAL"));
		Assert.assertTrue(showsLabel(p, "2,221,100"));
		Assert.assertFalse(showsLabel(p, "CAPITAL NEEDED"));
		p.setRecommendations(new ArrayList<>(List.of(buy(640, 20_307_200))));
		Assert.assertFalse(showsLabel(p, "ALCH EA"));
		Assert.assertTrue(showsLabel(p, "CAPITAL NEEDED"));
		Assert.assertTrue(showsLabel(p, "20,307,200 gp"));
	}

	/** The plugin reads alch values for the cards, the watchlist and the
	 *  offer screen's item, not only the stacks held. */
	@Test
	public void valuesForEveryCardThatCanCarryIt() throws Exception
	{
		final String plugin = java.nio.file.Files.readString(
			java.nio.file.Path.of("src/main/java/com/pocketge/tracker/PocketGeTrackerPlugin.java"));
		final int at = plugin.indexOf("final Set<Integer> alchIds = new HashSet<>(heldOrWorn.keySet());");
		Assert.assertTrue(at > 0);
		final String block = plugin.substring(at, plugin.indexOf("lookUpAlch(alchIds);", at));
		Assert.assertTrue(block.contains("for (AdvisorPanel.Rec r : recommendations)"));
		Assert.assertTrue(block.contains("alchIds.addAll(favoriteIdSet());"));
		Assert.assertTrue(block.contains("selectedFavoriteItemId, geContextItemId"));
	}

	/* Overrides the value look-up rather than supplying an ItemManager. The
	   previous version subclassed ItemManager and set it by reflection, which
	   never compiled: that class has a single private constructor, so it
	   cannot be instantiated or subclassed. It went in with 0.7.41 and took
	   the build red with it -- :compileJava stayed green, so the jar still
	   built and every release since went out over a failing :compileTestJava.
	   See PocketGeTrackerPlugin.alchValueOf for the seam this uses. */
	private static PocketGeTrackerPlugin pluginWithAlch(int itemId, int alchValue)
	{
		return new PocketGeTrackerPlugin()
		{
			@Override
			Integer alchValueOf(int id)
			{
				return id == itemId ? alchValue : 0;
			}
		};
	}

	/** Every value seen, not this cycle's: paused on a Rune kiteshield BUY
	 *  card, a refresh that no longer ranked it took the cell away. And an
	 *  item looked up as it comes on screen — inspected, or on the offer
	 *  screen — without waiting for a round. Things that do not alch are
	 *  left out. */
	@Test
	public void valuesSeenAreKept() throws Exception
	{
		final PocketGeTrackerPlugin plugin = pluginWithAlch(KITESHIELD, 32_400);
		plugin.showAlchFor(KITESHIELD);
		plugin.showAlchFor(4151);
		plugin.showAlchFor(null);
		Assert.assertEquals(Map.of(KITESHIELD, 32_400), plugin.alchSnapshot());
		final String src = java.nio.file.Files.readString(
			java.nio.file.Path.of("src/main/java/com/pocketge/tracker/PocketGeTrackerPlugin.java"));
		Assert.assertTrue("handed over whole", src.contains("lookUpAlch(alchIds);\n\t\t\tfinal Map<Integer, Integer> alchValues = alchSnapshot();"));
		Assert.assertTrue("the offer screen's item", src.contains("syncCardsTo(itemId);\n\t\tshowAlchFor(itemId);"));
		Assert.assertTrue("the item picked", src.contains("syncCardsTo(itemId);\n\t\t\t\t\tclientThread.invokeLater(() -> showAlchFor(itemId));"));
		Assert.assertTrue("and the card redrawn", src.contains("panel.alchArrived(values, nature)"));
	}

	/** A value that could not be read is not remembered as zero.
	 *
	 *  lookUpAlch never asks twice about an id already in the map, so caching
	 *  a 0 for "no ItemManager yet" would make that item alch for nothing for
	 *  the rest of the session. Before the advisor's first round there is no
	 *  item manager, and the ids on the first cards go through here. */
	@Test
	public void aValueThatCannotBeReadIsNotCached()
	{
		final int[] asked = {0};
		final PocketGeTrackerPlugin plugin = new PocketGeTrackerPlugin()
		{
			@Override
			Integer alchValueOf(int id)
			{
				/* Unavailable the first time, readable afterwards -- exactly
				   the order the real one goes through at start-up. */
				return asked[0]++ == 0 ? null : 32_400;
			}
		};
		plugin.showAlchFor(KITESHIELD);
		Assert.assertEquals("nothing is remembered from a failed read",
			Map.of(), plugin.alchSnapshot());
		plugin.showAlchFor(KITESHIELD);
		Assert.assertEquals("and the item is asked about again once it can be read",
			Map.of(KITESHIELD, 32_400), plugin.alchSnapshot());
	}

	/* ── Wiring ────────────────────────────────────────────────────────── */

	@Test
	public void theNatureRunesInstaBuy()
	{
		final Advisor.Quote q = new Advisor.Quote();
		q.low = 98;
		q.high = 103;
		Assert.assertEquals(103, PocketGeTrackerPlugin.naturePrice(Map.of(HighAlch.NATURE_RUNE, q)));
		Assert.assertEquals(0, PocketGeTrackerPlugin.naturePrice(Map.of()));
		Assert.assertEquals(0, PocketGeTrackerPlugin.naturePrice(null));
	}

	/** One switch, on by default, in both settings screens; it reaches the
	 *  overlay at start and on change. */
	@Test
	public void oneSwitchOnByDefault() throws Exception
	{
		final String cfg = java.nio.file.Files.readString(
			java.nio.file.Path.of("src/main/java/com/pocketge/tracker/PocketGeTrackerConfig.java"));
		Assert.assertTrue(cfg.matches("(?s).*keyName = \"alchProfit\",.*?default boolean alchProfit\\(\\)\\s*\\{\\s*return true;.*"));
		Assert.assertTrue(new AdvisorPanel.Settings().alchProfit);
		final String plugin = java.nio.file.Files.readString(
			java.nio.file.Path.of("src/main/java/com/pocketge/tracker/PocketGeTrackerPlugin.java"));
		Assert.assertTrue(plugin.contains("bankOverlay.setAlchOn(config.alchProfit());\n"));
		Assert.assertTrue(plugin.contains("case \"alchProfit\":\n\t\t\t\tbankOverlay.setAlchOn(config.alchProfit());"));
		Assert.assertTrue(plugin.contains("\"holdingTips\", \"alchProfit\","));
		Assert.assertTrue(plugin.contains("s.alchProfit = config.alchProfit();"));
		Assert.assertTrue(plugin.contains("bankOverlay.setAlch(alchValues, naturePrice);"));
		Assert.assertTrue(plugin.contains("mainPanel.setAlch(alchValues, naturePrice);"));
	}
}
