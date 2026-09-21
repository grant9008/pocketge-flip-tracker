package com.pocketge.tracker;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes the recommended price directly onto the Grand Exchange "Set up
 * offer" screen, the way Flipping Copilot and Flipping Utilities do.
 *
 * The price already existed in the sidebar, but reading a number over
 * there and typing it in over here is exactly the moment a flip goes
 * wrong — you look away, come back, and the prompt has already been
 * confirmed. Putting the number on the screen you're actually looking at
 * removes that round trip. The wiki's own instant price is shown next to
 * ours so it's obvious what the recommendation is departing from, and by
 * how much.
 *
 * Draws only while that screen is open with an item chosen; every other
 * GE screen is left alone.
 */
@Singleton
public class GeOfferPriceOverlay extends Overlay
{
	/* Explicit LoggerFactory rather than lombok's @Slf4j, matching
	   PocketGeTrackerPlugin. @Slf4j does compile here, but only incidentally:
	   this project declares no lombok anywhere, and the annotation resolves
	   solely because net.runelite:client drags lombok onto the compile
	   classpath, where javac auto-discovers it as an annotation processor.
	   Not a guarantee worth leaning on in a build=standard hub plugin — the
	   hub swaps build.gradle for its own template, so the classpath it
	   compiles against is not the one this repo describes. */
	private static final Logger log = LoggerFactory.getLogger(GeOfferPriceOverlay.class);
	private static final Color GOLD = new Color(0xE5, 0xC1, 0x58);
	/**
	 * The ring that says "click this next", in the plugin's one colour for
	 * that — LEAD_RIM, the same white the card puts round the price box you
	 * are about to type into, the bank puts round the stack it is suggesting,
	 * and the slot grid puts round the button to press.
	 *
	 * It was gold, which is a HUE, and a hue in this plugin means buy or sell.
	 * A gold ring on the Confirm button of a SELL offer says the wrong word
	 * quietly, and on a buy it says the right one for the wrong reason. White
	 * carries no direction, so it is free to mean only "here".
	 *
	 * It is also simply easier to see. Sampled off a real capture, the
	 * Exchange's chrome is about #494033, against which this reads 9.1:1 and
	 * the gold it replaces 5.9:1.
	 *
	 * Always an OUTLINE, never a fill. The gold chip this plugin draws beside
	 * the ring is its own button — a surface you press — and the two appear
	 * together; painting both white would leave neither pointing at anything.
	 */
	private static final Color ACT_HERE = new Color(0xF2, 0xF2, 0xF2);
	private static final Color TEXT_MAIN = new Color(0xD9, 0xD3, 0xC7);
	private static final Color PANEL_BG = new Color(0x1B, 0x18, 0x15, 0xE8);
	/** The fill button, unlit and lit, with dark text on it. */
	private static final Color GOLD_HOVER = new Color(0xFF, 0xDA, 0x7A);
	private static final Color BUTTON_RIM = new Color(0x6B, 0x55, 0x1E);
	private static final Color BUTTON_TEXT = new Color(0x1B, 0x18, 0x15);
	/* Was 0x4A3C18, which is 3.1:1 on the gold fill — under AA for small
	   text, and this is the smallest text the plugin draws anywhere. At
	   0x2A2208 it is 6.4:1 and still reads as the quieter of the two lines
	   because it is smaller and not the price. */
	private static final Color BUTTON_SUBTEXT = new Color(0x2A, 0x22, 0x08);
	private static final int PAD = 8;
	private static final int LINE_GAP = 3;

	private final Client client;
	private final net.runelite.client.game.ItemManager itemManager;

	/** Everything the overlay needs, published as one immutable snapshot so
	 *  a render can never catch half of an update from the client thread. */
	private static class Context
	{
		final String name;
		final boolean buy;
		final long target;
		final long wiki;
		final long margin;
		/** How many to trade — the 4-hour limit for a buy, the stack you hold
		 *  for a sell. 0 when unknown, which hides the quantity step. */
		final long quantity;

		Context(String name, boolean buy, long target, long wiki, long margin, long quantity)
		{
			this.name = name;
			this.buy = buy;
			this.target = target;
			this.wiki = wiki;
			this.margin = margin;
			this.quantity = quantity;
		}
	}

	private volatile Context context;
	/** Where the clickable price panel ended up last frame, so the mouse
	 *  handler can hit-test it. Written on the client thread during render,
	 *  read on the same thread from the mouse callback. */
	private volatile Rectangle panelHitbox;
	/** One debug line per session, not one per frame. */
	private boolean loggedPriceControlMiss;
	/** The item to offer as a one-click search while the GE "What would you
	 *  like to buy?" prompt is open, and where its chip landed last frame. */
	private volatile int searchItemId;
	private volatile String searchItemName;
	private volatile Rectangle searchHitbox;
	/** Where the quantity chip landed last frame, for the same hit-testing. */
	private volatile Rectangle quantityHitbox;

	@Inject
	private GeOfferPriceOverlay(Client client, net.runelite.client.game.ItemManager itemManager)
	{
		this.client = client;
		this.itemManager = itemManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	/** Called from the plugin whenever the offer screen's item/price changes.
	 *  Null clears it. {@code wiki} is the raw live quote we'd otherwise have
	 *  used; passing it equal to {@code target} just hides that line. */
	public void setContext(String name, boolean buy, long target, long wiki, long margin, long quantity)
	{
		this.context = name == null || target <= 0 ? null
			: new Context(name, buy, target, wiki, margin, quantity);
	}

	public void clear()
	{
		this.context = null;
		this.panelHitbox = null;
		this.searchHitbox = null;
		this.quantityHitbox = null;
	}

	/** The item the plugin currently recommends buying, offered as a chip on
	 *  the item-search prompt. 0 clears it. */
	public void setSearchSuggestion(int itemId, String name)
	{
		this.searchItemId = itemId;
		this.searchItemName = name;
	}

	/** True while the chatbox is asking which ITEM to trade, as opposed to
	 *  the price/quantity prompts. */
	private boolean itemSearchOpen()
	{
		return isItemSearchPrompt(client);
	}

	/**
	 * Whether the chatbox is asking WHICH item to trade.
	 *
	 * Reads MES_TEXT and MES_TEXT2 together, which pricePromptOpen has
	 * always done and this had not. The item search lays its chatbox out
	 * differently from the price prompt — a title line, then the hint — so
	 * reading only the first widget meant the question could be on screen
	 * with this returning false, and then neither the chip nor the click
	 * that fills from it did anything.
	 *
	 * Static and public so the plugin's own fill path applies the identical
	 * test. They were two copies of the same check and only one of them was
	 * ever fixed at a time.
	 */
	static boolean isItemSearchPrompt(net.runelite.api.Client client)
	{
		final Widget mes = client.getWidget(InterfaceID.Chatbox.MES_TEXT);
		final Widget mes2 = client.getWidget(InterfaceID.Chatbox.MES_TEXT2);
		/* Visible, not merely once-set — see pricePromptOpen. */
		final String t = ((mes != null && !mes.isHidden() && mes.getText() != null ? mes.getText() : "")
			+ " " + (mes2 != null && !mes2.isHidden() && mes2.getText() != null ? mes2.getText() : ""))
			.toLowerCase();
		return t.contains("what would you like to");
	}

	/** True when {@code point} is over the search chip. */
	public boolean isOverSearchChip(java.awt.Point point)
	{
		final Rectangle r = searchHitbox;
		return point != null && r != null && r.contains(point);
	}

	public String searchToFill()
	{
		return searchItemName;
	}

	/** True when {@code point} is over the price panel — the plugin's mouse
	 *  handler uses this to turn a click into a fill. Nothing is clickable
	 *  when we aren't drawing. */
	public boolean isOverPrice(java.awt.Point point)
	{
		final Rectangle panel = panelHitbox;
		return point != null && panel != null && panel.contains(point);
	}

	/** The price a click should fill, or 0 when there's nothing to fill. */
	public long priceToFill()
	{
		final Context ctx = context;
		return ctx != null ? ctx.target : 0;
	}

	/** True when {@code point} is over the QUANTITY chip. Same contract as
	 *  isOverPrice: only true while that chip is actually drawn. */
	public boolean isOverQuantity(java.awt.Point point)
	{
		final Rectangle panel = quantityHitbox;
		return point != null && panel != null && panel.contains(point);
	}

	/** The quantity a click should fill, or 0 when there's nothing to fill. */
	public long quantityToFill()
	{
		final Context ctx = context;
		return ctx != null ? ctx.quantity : 0;
	}

	/** True while the chatbox is genuinely asking for a price — the only
	 *  state in which fillGePrice does anything. */
	/**
	 * Whether the game is asking for a price RIGHT NOW.
	 *
	 * The isHidden checks are the whole method. A chatbox prompt widget keeps
	 * its last text after it closes, so reading the text alone answered "has
	 * the game ever asked for a price this session" — which stays true forever
	 * once it has. The fill panel therefore never went away after filling, and
	 * because the panel returns before the ring logic, the ring never advanced
	 * to the quantity control either: set the price, and the plugin sat there
	 * offering to set the price.
	 *
	 * quantityPromptOpen had the checks; this one and itemSearchOpen did not.
	 */
	private boolean pricePromptOpen()
	{
		final Widget mes = client.getWidget(InterfaceID.Chatbox.MES_TEXT);
		final Widget mes2 = client.getWidget(InterfaceID.Chatbox.MES_TEXT2);
		return ((mes != null && !mes.isHidden() ? mes.getText() : "")
			+ " " + (mes2 != null && !mes2.isHidden() ? mes2.getText() : "")).toLowerCase().contains("price");
	}

	/**
	 * Finds the control that opens the price box, by asking the interface
	 * rather than hardcoding where it lives.
	 *
	 * The obvious implementation is a widget id, and there isn't one:
	 * InterfaceID.GeOffers names SETUP and its labels but not the six
	 * buttons on the price row, which are unnamed children addressed by
	 * index. An index guessed from a screenshot would draw a gold box over
	 * whichever control happened to sit there, on every client layout, and
	 * be wrong silently.
	 *
	 * So this searches SETUP's descendants for a widget whose own right-click
	 * action mentions a price without being one of the percentage nudges.
	 * That is the button's self-description, so it survives the row being
	 * reordered or re-indexed, and — the point — it FAILS CLOSED: no match
	 * means no highlight, never a highlight of the wrong thing.
	 *
	 * When it finds nothing it logs the actions it did see, once per offer
	 * screen, so the strings needed to fix the match can be read out of a
	 * log rather than guessed at again.
	 */
	/** The control that opens the free-text PRICE box. */
	/**
	 * The quantity half of the same affordance: once the price is in, the
	 * game asks how many, and this offers the number the card already
	 * decided — the 4-hour limit for a buy, the stack you hold for a sell.
	 *
	 * Same panel, same place, same click as the price step, because it is the
	 * same job one field later. Drawn only while the quantity prompt is
	 * actually open, for the reason the price panel is: a box that appears
	 * early and says "go and click that other thing" is worse than the ring
	 * on the button itself.
	 */
	/**
	 * The one clickable thing the overlay draws, painted like a control.
	 *
	 * Both chips were a dark box with a gold outline and three lines of text —
	 * the shape this plugin uses for things you READ. The words said "click
	 * here" and nothing else did: no hover, no button, and it sat above the
	 * parchment rather than in it. Reported, twice, as not looking clickable.
	 *
	 * A filled gold button with dark text instead — the same treatment the
	 * sidebar gives an active segment button — that lightens under the cursor.
	 * Returns its own bounds so the caller can register the hitbox.
	 */
	private Rectangle drawFillButton(Graphics2D g, String bigLine, String hint)
	{
		/* Both up a size. These are drawn over the game canvas at whatever
		   scaling the client is running, next to RuneScape's own chunky
		   parchment type — 11pt for the line that tells you the button is
		   clickable came out as grey mush, and the price above it was only
		   just holding together. The button grows with them; there is room,
		   it sits in the clear right end of the chat strip. */
		final Font bigFont = g.getFont().deriveFont(Font.BOLD, 21f);
		final Font hintFont = g.getFont().deriveFont(Font.BOLD, 13f);
		final FontMetrics bm = g.getFontMetrics(bigFont);
		final FontMetrics hm = g.getFontMetrics(hintFont);

		final int w = Math.max(bm.stringWidth(bigLine), hm.stringWidth(hint)) + PAD * 3;
		final int h = PAD + bm.getHeight() + LINE_GAP + hm.getHeight() + PAD;

		/* Inside the parchment, hard right.
		   Above it — where these used to sit — is outside the box the player
		   is reading and on top of whatever another plugin writes on that
		   strip. The game's own prompt and button are centred, so the right of
		   the chat area is the one reliably clear part of it, and it puts the
		   thing to click a short move from the button you would otherwise
		   have used. */
		final Widget chat = client.getWidget(InterfaceID.Chatbox.CHATAREA);
		final Rectangle cb = chat != null && !chat.isHidden() ? chat.getBounds() : null;
		if (cb == null || cb.isEmpty())
		{
			return null; // nowhere sensible to put it
		}
		int x = Math.max(cb.x + 2, cb.x + cb.width - w - PAD);
		int y = cb.y + (cb.height - h) / 2;
		x = Math.max(0, Math.min(x, client.getCanvasWidth() - w));
		y = Math.max(0, Math.min(y, client.getCanvasHeight() - h));

		final net.runelite.api.Point mouse = client.getMouseCanvasPosition();
		final boolean hover = mouse != null
			&& mouse.getX() >= x && mouse.getX() < x + w
			&& mouse.getY() >= y && mouse.getY() < y + h;

		g.setColor(hover ? GOLD_HOVER : GOLD);
		g.fillRect(x, y, w, h);
		/* A darker rim, not a brighter one: on a filled button the edge reads
		   as a shadow, and a second bright outline only blurs where the button
		   stops. It thickens on hover so the lift is felt as well as seen. */
		g.setStroke(new BasicStroke(hover ? 2f : 1f));
		g.setColor(BUTTON_RIM);
		g.drawRect(x, y, w - 1, h - 1);

		int ty = y + PAD + bm.getAscent();
		g.setFont(bigFont);
		g.setColor(BUTTON_TEXT);
		g.drawString(bigLine, x + (w - bm.stringWidth(bigLine)) / 2, ty);
		ty += LINE_GAP + hm.getAscent();
		g.setFont(hintFont);
		g.setColor(BUTTON_SUBTEXT);
		g.drawString(hint, x + (w - hm.stringWidth(hint)) / 2, ty);
		return new Rectangle(x, y, w, h);
	}

	private void drawQuantityChip(Graphics2D g, Context ctx)
	{
		final Rectangle r = drawFillButton(g,
			String.format("%,d", ctx.quantity), "click to fill quantity");
		quantityHitbox = r;
	}

	/** True while the chatbox is asking HOW MANY, as opposed to the price. */
	private boolean quantityPromptOpen()
	{
		final Widget mes = client.getWidget(InterfaceID.Chatbox.MES_TEXT);
		final Widget mes2 = client.getWidget(InterfaceID.Chatbox.MES_TEXT2);
		final String text = ((mes != null && !mes.isHidden() ? mes.getText() : "")
			+ " " + (mes2 != null && !mes2.isHidden() ? mes2.getText() : "")).toLowerCase();
		return text.contains("how many") || text.contains("quantity");
	}


	private Widget findPriceEntryControl(Widget setup)
	{
		return findEntryControl(setup, "price");
	}

	/** ...and the QUANTITY one. Same walk, different word: the game labels
	 *  these buttons with their own action text, so one finder serves both. */
	private Widget findQuantityEntryControl(Widget setup)
	{
		return findEntryControl(setup, "quantity");
	}

	/** The Confirm button — the third and last thing to point at. Found the
	 *  same self-describing way as the other two, so it fails closed rather
	 *  than ringing whatever sits at a guessed index. */
	private Widget findConfirmControl(Widget setup)
	{
		return findEntryControl(setup, "confirm");
	}

	/**
	 * Whether any "N coins" readout on the setup screen shows exactly
	 * {@code value}.
	 *
	 * Deliberately "any", not "the first one". The screen carries at least two
	 * — the price per item and the running total — and which the widget walk
	 * reaches first is an accident of the interface tree. Asking whether the
	 * number we want is on the screen at all is the question that actually
	 * matters and does not depend on that order.
	 */
	private boolean setupShowsCoins(Widget setup, long value)
	{
		if (value <= 0)
		{
			return false;
		}
		final java.util.List<Widget> queue = new java.util.ArrayList<>();
		queue.add(setup);
		for (int i = 0; i < queue.size() && i < 512; i++)
		{
			final Widget w = queue.get(i);
			if (w == null || w.isHidden())
			{
				continue;
			}
			for (Widget[] kids : new Widget[][]{ w.getStaticChildren(), w.getDynamicChildren(), w.getNestedChildren() })
			{
				if (kids != null)
				{
					for (Widget k : kids)
					{
						if (k != null)
						{
							queue.add(k);
						}
					}
				}
			}
			final String t = w.getText();
			if (t == null || !t.toLowerCase().contains("coins"))
			{
				continue;
			}
			if (textShowsValue(t, value))
			{
				return true;
			}
		}
		return false;
	}

	/** Every run of digits in a readout, with the thousands separators taken
	 *  out first. */
	private static final java.util.regex.Pattern NUMBER =
		java.util.regex.Pattern.compile("\\d+");

	/**
	 * Whether {@code text} states {@code value} as one of its numbers.
	 *
	 * Each number separately, which is the whole point. This used to strip
	 * every non-digit from the line and parse what was left as a single
	 * number, and that works right up until a readout carries more than one:
	 *
	 * <pre>
	 *   a buy    "8,658,000 coins"                    -&gt; 8658000        ok
	 *   a sell   "8,502,000 coins (8,658,000 - 2%)"   -&gt; 850200086580002
	 * </pre>
	 *
	 * The Exchange prints a sell's total NET of the 2% tax and puts the gross
	 * and the rate in brackets after it, so the old rule silently failed on
	 * every sell — which is why the ring reached Confirm on a buy and stuck on
	 * the quantity control on a sell however much of the offer was already
	 * filled in. Reported as "it should be highlighting the confirm now in
	 * this example. i already selected price and quantity".
	 *
	 * Package-private so it can be tested on the strings the game actually
	 * prints, without a client.
	 */
	static boolean textShowsValue(String text, long value)
	{
		if (text == null || value <= 0)
		{
			return false;
		}
		final java.util.regex.Matcher m = NUMBER.matcher(text.replace(",", ""));
		while (m.find())
		{
			try
			{
				if (Long.parseLong(m.group()) == value)
				{
					return true;
				}
			}
			catch (NumberFormatException ignore)
			{
				// a run of digits too big to be one of ours; keep looking
			}
		}
		return false;
	}

	private Widget findEntryControl(Widget setup, String keyword)
	{
		final java.util.List<Widget> queue = new java.util.ArrayList<>();
		queue.add(setup);
		Widget best = null;
		final java.util.List<String> seen = new java.util.ArrayList<>();
		for (int i = 0; i < queue.size() && i < 512; i++)
		{
			final Widget w = queue.get(i);
			if (w == null || w.isHidden())
			{
				continue;
			}
			for (Widget[] kids : new Widget[][]{ w.getStaticChildren(), w.getDynamicChildren(), w.getNestedChildren() })
			{
				if (kids != null)
				{
					for (Widget k : kids)
					{
						if (k != null)
						{
							queue.add(k);
						}
					}
				}
			}
			final String[] actions = w.getActions();
			if (actions == null)
			{
				continue;
			}
			for (String a : actions)
			{
				if (a == null || a.trim().isEmpty())
				{
					continue;
				}
				final String lower = a.toLowerCase();
				seen.add(a);
				if (!lower.contains(keyword) || lower.contains("%"))
				{
					continue;
				}
				/* Prefer the one that opens a free-text box over any other
				   price-mentioning control, but take a plain "price" match
				   rather than nothing. */
				if (lower.contains("enter") || lower.contains("set") || lower.contains("custom"))
				{
					return w;
				}
				if (best == null)
				{
					best = w;
				}
			}
		}
		if (best == null && !loggedPriceControlMiss)
		{
			loggedPriceControlMiss = true;
			log.debug("PocketGE: no {}-entry control matched on the GE setup screen. Actions seen: {}", keyword, seen);
		}
		return best;
	}

	/**
	 * A one-click chip on the "What would you like to buy?" prompt: the
	 * recommended item's own sprite and name, clicked instead of typed.
	 *
	 * This is the affordance Flipping Copilot users are trained to look for,
	 * and it needs no scripting to draw — the click is what does the work,
	 * by setting the same MESLAYERINPUT the price and quantity fills already
	 * drive (RuneLite's own GrandExchangePlugin reads that var inside its
	 * GE_ITEM_SEARCH handler, which is how we know it is the search field).
	 *
	 * Drawn ABOVE the results area rather than over it, so it never covers a
	 * row you were about to click, and only while the prompt is genuinely
	 * the item search — the price and quantity prompts share this chatbox.
	 */
	private void drawSearchChip(Graphics2D g)
	{
		searchHitbox = null;
		final int id = searchItemId;
		final String name = searchItemName;
		if (id <= 0 || name == null || name.isEmpty() || !itemSearchOpen())
		{
			return;
		}
		final Widget results = client.getWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
		final Widget anchorW = results != null && !results.isHidden()
			? results : client.getWidget(InterfaceID.Chatbox.MES_TEXT);
		if (anchorW == null || anchorW.isHidden())
		{
			return;
		}
		final Rectangle ab = anchorW.getBounds();
		if (ab == null || ab.isEmpty())
		{
			return;
		}

		final String label = "Click: " + name;
		final Font f = g.getFont().deriveFont(Font.BOLD, 16f);
		final FontMetrics fm = g.getFontMetrics(f);
		final int icon = 28;
		final int w = icon + 6 + fm.stringWidth(label) + PAD * 2;
		final int h = Math.max(icon, fm.getHeight()) + PAD;
		int x = ab.x;
		int y = Math.max(0, ab.y - h - 3);
		x = Math.max(0, Math.min(x, client.getCanvasWidth() - w));

		g.setColor(PANEL_BG);
		g.fillRect(x, y, w, h);
		g.setStroke(new BasicStroke(2f));
		g.setColor(GOLD);
		g.drawRect(x + 1, y + 1, w - 2, h - 2);

		final java.awt.image.BufferedImage sprite = itemManager.getImage(id);
		if (sprite != null)
		{
			g.drawImage(sprite, x + PAD, y + (h - icon) / 2, icon, icon, null);
		}
		g.setFont(f);
		g.setColor(TEXT_MAIN);
		g.drawString(label, x + PAD + icon + 6, y + (h + fm.getAscent()) / 2 - 2);
		searchHitbox = new Rectangle(x, y, w, h);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		/* Cleared before anything can return early. These are hit-tested by
		   the plugin's mouse handler, which consumes the click it matches —
		   so a rectangle left over from a frame we no longer paint silently
		   eats real game clicks (confirming an offer, most damagingly). Only
		   a frame that genuinely draws may re-establish it. */
		panelHitbox = null;
		quantityHitbox = null;
		/* Before the early returns below: the item-search chip is shown while
		   choosing WHAT to trade, which is a moment when there is no offer
		   context yet by definition. */
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		drawSearchChip(g);
		final Context ctx = context;
		if (ctx == null)
		{
			return null;
		}
		final Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
		if (setup == null || setup.isHidden())
		{
			return null;
		}
		final Rectangle bounds = setup.getBounds();
		if (bounds == null || bounds.isEmpty())
		{
			return null;
		}

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		/* The quantity prompt gets its own chip and nothing else. Checked
		   first, and returning here, so the two prompts can never both draw:
		   they are the same chatbox one step apart, and a price panel left
		   over the "how many?" box would offer the wrong number to click. */
		if (quantityPromptOpen())
		{
			if (ctx.quantity > 0)
			{
				drawQuantityChip(g, ctx);
			}
			return null;
		}

		/* One lookup, used by everything below: whether the game is currently
		   asking for a price decides where this panel goes, how much it says,
		   and whether it is the thing to click. Read once so two halves of
		   the same frame cannot disagree. */
		final boolean fillable = pricePromptOpen();

		/* Ring the control that opens the price box, while it is still shut.
		   Copilot does this in blue; ours is the same gold as the rest of the
		   plugin so it reads as us. Drawn before the panel so the panel can
		   never be obscured by it. Once the box is open this disappears and
		   the panel itself becomes the thing to click. */
		if (!fillable)
		{
			/* Price first, then quantity — the order the screen has to be
			   filled in, and the order the ring now follows.
			
			   It used to sit on the price control forever, including after the
			   price was already right, which left the second half of the job
			   unmarked: you set the price, and the plugin kept pointing at the
			   thing you had just done. Once the entered price matches the
			   target the ring moves to the quantity control, and clicking it
			   opens the prompt the chip above answers. */
			/*
			 * Price, then quantity, then Confirm — the three things the screen
			 * needs, ringed one at a time in the order it needs them.
			 *
			 * Both tests read the RUNNING TOTAL rather than the quantity box.
			 * The total is price x quantity, so a total matching target x
			 * quantity means both halves are in, and it cannot be confused
			 * with anything else on the screen. Reading the quantity field
			 * instead would have to tell 11,000 the quantity apart from
			 * "Buy limit: 11,000" printed a few pixels above it, and it
			 * cannot — which would ring Confirm on an offer for one item.
			 *
			 * When the quantity is 1 the total IS the price, so both become
			 * true together and the ring goes straight to Confirm. Correct:
			 * there is no quantity left to set.
			 */
			final long wholeOffer = ctx.target * (long) ctx.quantity;
			final boolean allDone = ctx.quantity > 0 && setupShowsCoins(setup, wholeOffer);
			final boolean priceDone = allDone
				|| (ctx.target > 0 && setupShowsCoins(setup, ctx.target));
			final Widget ring = !priceDone
				? findPriceEntryControl(setup)
				: !allDone
					? findQuantityEntryControl(setup)
					: findConfirmControl(setup);
			if (ring != null)
			{
				final Rectangle b = ring.getBounds();
				if (b != null && !b.isEmpty())
				{
					g.setStroke(new BasicStroke(2f));
					g.setColor(ACT_HERE);
					g.drawRect(b.x - 1, b.y - 1, b.width + 1, b.height + 1);
				}
			}
			/* And nothing else. The panel used to appear the moment the offer
			   screen opened, saying "click the price row first" — a box that
			   sits over the game telling you to go and click something else.
			   The gold ring already says that, on the button itself, without
			   covering anything. One cue for one step. */
			return null;
		}

		/* Thousands separators, never the abbreviating formatter. This is the
		   number about to be typed into the game, and "6.48M" is not a thing
		   you can type — the price behind one such was 6,480,851, and the two
		   differ by 851 gp an item. The button sizes itself from the string,
		   so a longer number simply makes a wider button.

		   The item name went with the redesign: the sidebar names it, and the
		   game has just asked "set a price for each item", so the only things
		   left to say are which number and that you may click for it. */
		panelHitbox = drawFillButton(g,
			String.format("%,d", ctx.target) + " gp each", "click to fill price");
		return null;
	}
}
