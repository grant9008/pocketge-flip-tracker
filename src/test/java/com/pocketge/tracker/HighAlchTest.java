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
