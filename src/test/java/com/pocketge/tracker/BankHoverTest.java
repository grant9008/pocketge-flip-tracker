package com.pocketge.tracker;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import org.junit.Assert;
import org.junit.Test;

/**
 * What hovering a stack says, with the pointer actually on it.
 *
 * Three reports, from one sitting at the Exchange: 7,537 redwood logs just
 * bought hovered nothing at all; 11,000 bolt tips bought at 427 and fetching
 * 425 hovered "Also worth selling"; and that hover came up twice.
 */
public class BankHoverTest
{
	private static final int REDWOOD = 19669;
	private static final int REDWOOD_NOTE = 19670;
	private static final int BOLT_TIPS = 9193;
	private static final int DIAMOND = 1601;
	private static final Rectangle SLOT = new Rectangle(10, 20, 36, 32);

	private static String words(String markup)
	{
		return markup.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
	}

	private static BankHighlightOverlay overlay(TooltipManager tips) throws Exception
	{
		final java.lang.reflect.Constructor<BankHighlightOverlay> ctor =
			BankHighlightOverlay.class.getDeclaredConstructor();
		ctor.setAccessible(true);
		final BankHighlightOverlay o = ctor.newInstance();
		set(o, "client", pointerOnTheSlot());
		set(o, "tooltipManager", tips);
		o.setEnabled(true);
		return o;
	}

	/** A client whose mouse sits in the middle of {@link #SLOT}. A proxy, as
	 *  BankHighlightOverlayTest explains, so it fits both Client versions. */
	private static net.runelite.api.Client pointerOnTheSlot()
	{
		return (net.runelite.api.Client) java.lang.reflect.Proxy.newProxyInstance(
			net.runelite.api.Client.class.getClassLoader(),
			new Class<?>[]{net.runelite.api.Client.class},
			(proxy, method, args) ->
			{
				if ("getMouseCanvasPosition".equals(method.getName()))
				{
					return new net.runelite.api.Point(SLOT.x + SLOT.width / 2, SLOT.y + SLOT.height / 2);
				}
				final Class<?> r = method.getReturnType();
				if (r == boolean.class)
				{
					return false;
				}
				if (r.isPrimitive())
				{
					return r == long.class ? 0L : r == double.class ? 0d : r == float.class ? 0f : 0;
				}
				return null;
			});
	}

	private static void set(Object target, String field, Object value) throws Exception
	{
		final Field f = BankHighlightOverlay.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(target, value);
	}

	/** Hover one slot; returns the image it painted into. */
	private static BufferedImage hover(BankHighlightOverlay o, int slotItemId, int qty)
	{
		final BufferedImage img = new BufferedImage(80, 80, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = img.createGraphics();
		o.renderItemOverlay(g, slotItemId, new WidgetItem(slotItemId, qty, SLOT, null, null));
		g.dispose();
		return img;
	}

	/** Each tooltip's text, read from its component: RuneLite's
	 *  TooltipComponent has no getter for it, here or upstream. */
	private static List<String> texts(TooltipManager tips) throws Exception
	{
		final List<String> out = new ArrayList<>();
		for (Tooltip t : tips.getTooltips())
		{
			final Object c = t.getComponent();
			final Field f = c.getClass().getDeclaredField("text");
			f.setAccessible(true);
			out.add(words((String) f.get(c)));
		}
		return out;
	}

	private static boolean painted(BufferedImage img)
	{
		for (int y = 0; y < img.getHeight(); y++)
		{
			for (int x = 0; x < img.getWidth(); x++)
			{
				if ((img.getRGB(x, y) >>> 24) != 0)
				{
					return true;
				}
			}
		}
		return false;
	}

	private static Map<Integer, long[]> paid(int itemId, long qty, long each, long sellsFor)
	{
		final Map<Integer, long[]> m = new HashMap<>();
		m.put(itemId, new long[]{qty, qty * each, sellsFor});
		return m;
	}

	/** The bolt tips as the ranked sell list has them: a tracked cost, and a
	 *  loss on it. */
	private static Advisor.Suggestion boltTipsAtALoss()
	{
		final Advisor.Suggestion s = new Advisor.Suggestion(
			Advisor.Suggestion.Type.SELL, BOLT_TIPS, "Dragonstone bolt tips", 425, 11_000, -110_000, "");
		s.hasTrackedCost = true;
		s.unitCost = 427;
		s.trackedQty = 11_000;
		s.grossValue = 4_587_000;
		s.whyNow = "down 2% on what you paid";
		return s;
	}

	/** The redwoods were noted — 7,537 logs in one inventory slot is a note
	 *  — and everything the overlay knows is kept under the log's own id. */
	/**
	 * "why no hover detail on diamonds?" — 9,017 diamonds held since before
	 * the plugin watched, so no cost, and no hover at all. Now: what they
	 * fetch, and why there is no profit.
	 */
	@Test
	public void aStackWithNoBuyOnRecordStillSaysWhatItSellsFor() throws Exception
	{
		final TooltipManager tips = new TooltipManager();
		final BankHighlightOverlay o = overlay(tips);
		final Map<Integer, long[]> m = new HashMap<>();
		m.put(DIAMOND, new long[]{0, 0, 1_013});
		o.setHoldings(m);

		hover(o, DIAMOND, 9_017);

		final List<String> t = texts(tips);
		Assert.assertEquals(t.toString(), 1, t.size());
		/* 9,017 at 993 after tax: the headline is the take, not the gross
		   1,013 each, and not nothing. Formatted by the formatter the code
		   uses, so it holds for the client's own as well as the stub's. */
		Assert.assertTrue(t.get(0), t.get(0).startsWith(
			net.runelite.client.util.QuantityFormatter.quantityToStackSize(993L * 9_017) + " gp after tax"));
		Assert.assertTrue(t.get(0), t.get(0).contains("Sells for 1,013 (993 after tax)"));
		Assert.assertTrue(t.get(0), t.get(0).contains("No buy on record, so no profit to show"));
		Assert.assertFalse("never a profit without a cost", t.get(0).contains("profit available"));
		Assert.assertFalse(t.get(0).contains("if you sell"));

		tips.clear();
		o.setHoldingTips(false);
		hover(o, DIAMOND, 9_017);
		Assert.assertTrue("the setting turns it off", texts(tips).isEmpty());
	}

	/** What the plugin hands the hover: the costed stacks as they are, and
	 *  every other held stack with a price at its sell price, no cost. */
	@Test
	public void everyHeldStackWithAPriceGetsAHover()
	{
		final Map<Integer, long[]> costed = paid(REDWOOD, 7_537, 644, 685);
		final Map<Integer, Integer> held = new HashMap<>();
		held.put(REDWOOD, 7_537);
		held.put(DIAMOND, 9_017);
		held.put(BOLT_TIPS, 11_000);
		held.put(995_000, 0);
		final Map<Integer, Advisor.Quote> quotes = new HashMap<>();
		for (int id : new int[]{REDWOOD, DIAMOND, 995_000})
		{
			final Advisor.Quote q = new Advisor.Quote();
			q.high = id == DIAMOND ? 1_013 : 700;
			q.low = q.high - 10;
			quotes.put(id, q);
		}
		final Map<Integer, long[]> hovers = PocketGeTrackerPlugin.stackHoversFor(costed, held, quotes, null);
		Assert.assertArrayEquals("costed as it was", costed.get(REDWOOD), hovers.get(REDWOOD));
		Assert.assertArrayEquals("no cost, its price", new long[]{0, 0, 1_013}, hovers.get(DIAMOND));
		Assert.assertNull("no price, no hover", hovers.get(BOLT_TIPS));
		Assert.assertNull("none held", hovers.get(995_000));
		Assert.assertEquals(2, hovers.size());
	}

	@Test
	public void aNotedStackHoversWhatYouPaidForTheItem() throws Exception
	{
		final TooltipManager tips = new TooltipManager();
		final BankHighlightOverlay o = overlay(tips);
		o.canonicalizer = id -> id == REDWOOD_NOTE ? REDWOOD : id;
		o.setHoldings(paid(REDWOOD, 7_537, 644, 685));

		hover(o, REDWOOD_NOTE, 7_537);

		final List<String> t = texts(tips);
		Assert.assertEquals(t.toString(), 1, t.size());
		Assert.assertTrue(t.get(0), t.get(0).startsWith("+211K gp profit available"));
		Assert.assertTrue(t.get(0), t.get(0).contains("Paid 644 gp each"));
	}

	/** And the one the card names gets its ring as a note too. */
	@Test
	public void aNotedStackTheCardNamesIsMarked() throws Exception
	{
		final TooltipManager tips = new TooltipManager();
		final BankHighlightOverlay o = overlay(tips);
		o.canonicalizer = id -> id == REDWOOD_NOTE ? REDWOOD : id;
		o.setRecommended(REDWOOD);

		Assert.assertTrue("ringed", painted(hover(o, REDWOOD_NOTE, 7_537)));
		Assert.assertTrue(texts(tips).toString(), texts(tips).get(0).startsWith("Your current suggestion"));
	}

	/** A losing sell is not "worth selling": no mark, and the hover over it
	 *  is the plain one, which leads with what selling now costs. */
	@Test
	public void aSellAtALossIsNotMarkedAndHoversTheLoss() throws Exception
	{
		final TooltipManager tips = new TooltipManager();
		final BankHighlightOverlay o = overlay(tips);
		final Map<Integer, Advisor.Suggestion> byItem = new HashMap<>();
		byItem.put(BOLT_TIPS, boltTipsAtALoss());
		o.setSuggestions(byItem);
		o.setHoldings(paid(BOLT_TIPS, 11_000, 427, 425));

		Assert.assertFalse("no mark at a loss", painted(hover(o, BOLT_TIPS, 11_000)));
		Assert.assertFalse("and so no key for one", o.hasSellable());
		final List<String> t = texts(tips);
		Assert.assertEquals(t.toString(), 1, t.size());
		Assert.assertTrue(t.get(0), t.get(0).startsWith("-110K gp if you sell"));
		Assert.assertFalse(t.get(0), t.get(0).contains("worth selling"));
		Assert.assertTrue(t.get(0), t.get(0).contains("Sells for 425 (417 after tax)"));
	}

	/** The stack the card names stays marked at a loss, since the card is
	 *  talking about it — but its hover leads with the loss, not with what
	 *  the stack fetches. */
	@Test
	public void theStackTheCardNamesLeadsWithItsLoss() throws Exception
	{
		final TooltipManager tips = new TooltipManager();
		final BankHighlightOverlay o = overlay(tips);
		final Map<Integer, Advisor.Suggestion> byItem = new HashMap<>();
		byItem.put(BOLT_TIPS, boltTipsAtALoss());
		o.setSuggestions(byItem);
		o.setRecommended(BOLT_TIPS);

		Assert.assertTrue("ringed", painted(hover(o, BOLT_TIPS, 11_000)));
		final String t = texts(tips).get(0);
		Assert.assertTrue(t, t.startsWith("Your current suggestion -110K gp if you sell"));
		Assert.assertFalse("not what it fetches: " + t, t.contains("gp after tax"));
		Assert.assertTrue(t, t.contains("Paid 427 gp each on all 11,000"));
	}

	/** The card can name a losing stack that the suggestion map has never
	 *  heard of — it holds the one best sell, and a loss is rarely that. Its
	 *  hover still says what selling comes to, from what you paid. */
	@Test
	public void theCardsStackWithNoSuggestionStillSaysItsLoss() throws Exception
	{
		final TooltipManager tips = new TooltipManager();
		final BankHighlightOverlay o = overlay(tips);
		o.setRecommended(BOLT_TIPS);
		o.setHoldings(paid(BOLT_TIPS, 11_000, 427, 425));

		Assert.assertTrue("ringed", painted(hover(o, BOLT_TIPS, 11_000)));
		final String t = texts(tips).get(0);
		Assert.assertTrue(t, t.startsWith("Your current suggestion -110K gp if you sell"));
		Assert.assertTrue(t, t.contains("Paid 427 gp each"));
	}

	/** An empty bank placeholder is the item, to canonicalize — and must not
	 *  be ringed beside the stack it stands in for. */
	@Test
	public void anEmptyPlaceholderGetsNothing() throws Exception
	{
		final TooltipManager tips = new TooltipManager();
		final BankHighlightOverlay o = overlay(tips);
		o.canonicalizer = id -> id == 20_000 ? REDWOOD : id;
		o.setRecommended(REDWOOD);
		o.setHoldings(paid(REDWOOD, 7_537, 644, 685));

		Assert.assertFalse(painted(hover(o, 20_000, 0)));
		Assert.assertTrue(tips.getTooltips().isEmpty());
	}

	/** The trade screen shows the other player's offer too, and your cost
	 *  says nothing about their stack. */
	@Test
	public void theTradeScreenIsLeftAlone() throws Exception
	{
		final TooltipManager tips = new TooltipManager();
		final BankHighlightOverlay o = overlay(tips);
		o.setRecommended(REDWOOD);
		o.setHoldings(paid(REDWOOD, 7_537, 644, 685));
		final net.runelite.api.widgets.Widget theirOffer = (net.runelite.api.widgets.Widget) java.lang.reflect.Proxy.newProxyInstance(
			net.runelite.api.widgets.Widget.class.getClassLoader(),
			new Class<?>[]{net.runelite.api.widgets.Widget.class},
			(proxy, method, args) -> "getId".equals(method.getName()) ? (335 << 16 | 28) : null);

		final BufferedImage img = new BufferedImage(80, 80, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = img.createGraphics();
		o.renderItemOverlay(g, REDWOOD, new WidgetItem(REDWOOD, 20_000, SLOT, theirOffer, null));
		g.dispose();
		Assert.assertFalse(painted(img));
		Assert.assertTrue(tips.getTooltips().isEmpty());
	}

	/** The rating mark itself, as the plugin builds it for the bolt tips:
	 *  rated a sell, worth millions, and still not marked at a loss. */
	@Test
	public void theRatingMarkIsNotBuiltAtALoss()
	{
		final Advisor.Quote q = new Advisor.Quote();
		q.high = 425;
		q.low = 420;
		final AnalystRating.Grade sell = new AnalystRating.Grade();
		sell.label = AnalystRating.Label.SELL;
		Assert.assertNull("at a loss", PocketGeTrackerPlugin.ratingSell(BOLT_TIPS, "Dragonstone bolt tips", 11_000, q, sell,
			new long[]{11_000, 11_000L * 427, 425}));
		final Advisor.Suggestion marked = PocketGeTrackerPlugin.ratingSell(BOLT_TIPS, "Dragonstone bolt tips", 11_000, q, sell,
			new long[]{11_000, 11_000L * 400, 425});
		Assert.assertNotNull("in profit", marked);
		Assert.assertTrue(BankHighlightOverlay.marks(marked));
		Assert.assertEquals("its hover says what you paid", 400, marked.unitCost);
		Assert.assertNotNull("never bought here: the rating alone", PocketGeTrackerPlugin.ratingSell(BOLT_TIPS, "x", 11_000, q, sell, null));
		final AnalystRating.Grade hold = new AnalystRating.Grade();
		hold.label = AnalystRating.Label.HOLD;
		Assert.assertNull("not rated a sell", PocketGeTrackerPlugin.ratingSell(BOLT_TIPS, "x", 11_000, q, hold, null));
	}

	/** And the card's stack with a BUY in the map — buildBuys never leaves
	 *  out what you hold — hovers as the stack it is, not as a purchase. */
	@Test
	public void aBuyInTheMapDoesNotDescribeTheCardsStack() throws Exception
	{
		final TooltipManager tips = new TooltipManager();
		final BankHighlightOverlay o = overlay(tips);
		final Map<Integer, Advisor.Suggestion> byItem = new HashMap<>();
		byItem.put(BOLT_TIPS, new Advisor.Suggestion(Advisor.Suggestion.Type.BUY, BOLT_TIPS, "Dragonstone bolt tips", 420, 5_000, 20_000, ""));
		o.setSuggestions(byItem);
		o.setRecommended(BOLT_TIPS);
		o.setHoldings(paid(BOLT_TIPS, 11_000, 427, 425));

		hover(o, BOLT_TIPS, 11_000);
		final String t = texts(tips).get(0);
		Assert.assertTrue(t, t.startsWith("Your current suggestion -110K gp if you sell"));
		Assert.assertFalse("not the buy's 5,000 at 420: " + t, t.contains("5,000 at 420"));
	}

	/** The rating mark's own test: "worth selling" never goes on a stack the
	 *  hover would call a loss. */
	@Test
	public void theRatingMarkSkipsALoss()
	{
		Assert.assertTrue(PocketGeTrackerPlugin.sellsAtALoss(BOLT_TIPS, 11_000, new long[]{11_000, 11_000L * 427, 425}));
		Assert.assertFalse(PocketGeTrackerPlugin.sellsAtALoss(BOLT_TIPS, 11_000, new long[]{11_000, 11_000L * 400, 425}));
		Assert.assertFalse("no cost seen, no loss to claim", PocketGeTrackerPlugin.sellsAtALoss(BOLT_TIPS, 11_000, null));
	}

	/** In profit, a sell is marked and says so, as before. */
	@Test
	public void aSellInProfitIsStillWorthSelling() throws Exception
	{
		final TooltipManager tips = new TooltipManager();
		final BankHighlightOverlay o = overlay(tips);
		final Advisor.Suggestion s = boltTipsAtALoss();
		s.expectedProfit = 88_000;
		s.whyNow = null;
		final Map<Integer, Advisor.Suggestion> byItem = new HashMap<>();
		byItem.put(BOLT_TIPS, s);
		o.setSuggestions(byItem);

		Assert.assertTrue("marked", painted(hover(o, BOLT_TIPS, 11_000)));
		Assert.assertTrue(o.hasSellable());
		final String t = texts(tips).get(0);
		Assert.assertTrue(t, t.startsWith("Also worth selling"));
		Assert.assertTrue(t, t.contains("gp after tax"));
		Assert.assertFalse(t, t.contains("if you sell"));
	}

	/** The Exchange's inventory panel was registered twice — once by
	 *  showOnInventory, once by hand — and RuneLite draws an overlay once per
	 *  registration, so every hover there came up twice. */
	@Test
	public void eachInterfaceIsRegisteredOnce() throws Exception
	{
		final List<Integer> hooks = overlay(new TooltipManager()).getDrawHooks();
		Assert.assertEquals("registered twice: " + hooks, new HashSet<>(hooks).size(), hooks.size());
		Assert.assertTrue("the Exchange's inventory is still covered", hooks.contains(467 << 16 | 0xffff));
	}

	/** The rating mark's test of "at a loss" and the hover's headline are
	 *  the same number, so the two cannot disagree. */
	@Test
	public void theLossTestIsTheHoversHeadline()
	{
		Assert.assertEquals(-110_000, BankHighlightOverlay.holdingProfit(BOLT_TIPS, 11_000, 11_000, 11_000L * 427, 425));
		Assert.assertEquals("only the tracked part is costed", 11_000L * 417 - 11_000L * 400,
			BankHighlightOverlay.holdingProfit(BOLT_TIPS, 20_000, 11_000, 11_000L * 400, 425));
	}
}
