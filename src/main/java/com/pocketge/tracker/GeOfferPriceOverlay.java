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
import net.runelite.client.util.QuantityFormatter;
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
	private static final Color TEXT_MAIN = new Color(0xD9, 0xD3, 0xC7);
	private static final Color MUTED = new Color(0x8A, 0x82, 0x74);
	private static final Color POSITIVE = new Color(0x1F, 0xB8, 0x5C);
	private static final Color PANEL_BG = new Color(0x1B, 0x18, 0x15, 0xE8);
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

		Context(String name, boolean buy, long target, long wiki, long margin)
		{
			this.name = name;
			this.buy = buy;
			this.target = target;
			this.wiki = wiki;
			this.margin = margin;
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
	public void setContext(String name, boolean buy, long target, long wiki, long margin)
	{
		this.context = name == null || target <= 0 ? null : new Context(name, buy, target, wiki, margin);
	}

	public void clear()
	{
		this.context = null;
		this.panelHitbox = null;
		this.searchHitbox = null;
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
		final Widget mes = client.getWidget(InterfaceID.Chatbox.MES_TEXT);
		final String t = mes != null && mes.getText() != null ? mes.getText().toLowerCase() : "";
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

	/** True while the chatbox is genuinely asking for a price — the only
	 *  state in which fillGePrice does anything. */
	private boolean pricePromptOpen()
	{
		final Widget mes = client.getWidget(InterfaceID.Chatbox.MES_TEXT);
		final Widget mes2 = client.getWidget(InterfaceID.Chatbox.MES_TEXT2);
		return ((mes != null ? mes.getText() : "")
			+ " " + (mes2 != null ? mes2.getText() : "")).toLowerCase().contains("price");
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
	private Widget findPriceEntryControl(Widget setup)
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
				if (!lower.contains("price") || lower.contains("%"))
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
			log.debug("PocketGE: no price-entry control matched on the GE setup screen. Actions seen: {}", seen);
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
		final Font f = g.getFont().deriveFont(Font.BOLD, 14f);
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

		/* Ring the control that opens the price box, while it is still shut.
		   Copilot does this in blue; ours is the same gold as the rest of the
		   plugin so it reads as us. Drawn before the panel so the panel can
		   never be obscured by it. Once the box is open this disappears and
		   the panel itself becomes the thing to click. */
		if (!pricePromptOpen())
		{
			final Widget priceBtn = findPriceEntryControl(setup);
			if (priceBtn != null)
			{
				final Rectangle b = priceBtn.getBounds();
				if (b != null && !b.isEmpty())
				{
					g.setStroke(new BasicStroke(2f));
					g.setColor(GOLD);
					g.drawRect(b.x - 1, b.y - 1, b.width + 1, b.height + 1);
				}
			}
		}

		final String title = (ctx.buy ? "Buy " : "Sell ") + ctx.name;
		final String priceLine = QuantityFormatter.quantityToStackSize(ctx.target) + " gp each";
		/* The reference price is the side of the book you are TRADING AGAINST,
		   not the side you are on: buying means paying what sellers accept
		   (the wiki's insta-sell), selling means taking what buyers offer
		   (insta-buy). These two labels were the wrong way round, so anyone
		   cross-checking against the wiki was comparing our number to the
		   opposite end of the spread. */
		final String wikiLine = ctx.wiki > 0 && ctx.wiki != ctx.target
			? "wiki " + (ctx.buy ? "insta-sell" : "insta-buy") + " " + QuantityFormatter.quantityToStackSize(ctx.wiki)
			: null;
		final String marginLine = ctx.margin > 0
			? "+" + QuantityFormatter.quantityToStackSize(ctx.margin) + " gp each after tax" : null;
		final boolean fillable = pricePromptOpen();
		/* When the price box isn't open yet there is nothing to fill, and
		   "set a price to fill it" left the player to work out which of the
		   six buttons on the price row opens it. Name the step instead. */
		final String clickLine = fillable
			? "click here to fill this price"
			: "click \u2039 \u2026 \u203A on the price row first";

		final Font titleFont = g.getFont().deriveFont(Font.BOLD, 13f);
		final Font priceFont = g.getFont().deriveFont(Font.BOLD, 17f);
		final Font smallFont = g.getFont().deriveFont(11f);

		final FontMetrics tm = g.getFontMetrics(titleFont);
		final FontMetrics pm = g.getFontMetrics(priceFont);
		final FontMetrics sm = g.getFontMetrics(smallFont);

		int w = Math.max(tm.stringWidth(title), pm.stringWidth(priceLine));
		if (wikiLine != null)
		{
			w = Math.max(w, sm.stringWidth(wikiLine));
		}
		if (marginLine != null)
		{
			w = Math.max(w, sm.stringWidth(marginLine));
		}
		w = Math.max(w, sm.stringWidth(clickLine));
		w += PAD * 2;

		int h = PAD + tm.getHeight() + LINE_GAP + pm.getHeight();
		if (wikiLine != null)
		{
			h += LINE_GAP + sm.getHeight();
		}
		if (marginLine != null)
		{
			h += LINE_GAP + sm.getHeight();
		}
		h += LINE_GAP + sm.getHeight(); // click affordance
		h += PAD;

		/* Sits just above the chat area, left-aligned to it — the corner
		   Flipping Copilot puts its own price hint in, and where a flipper is
		   already looking when the game asks for a number, since the prompt
		   itself is right below. It used to hang off the bottom of the offer
		   window, which put it in the middle of the screen and well away from
		   the box being typed into.

		   CHATAREA is the anchor rather than a hardcoded offset because the
		   chatbox moves: fixed and resizable layouts put it in different
		   places, and it can be scrolled taller. If it is missing (some
		   resizable setups hide it entirely) fall back to the old
		   below-the-offer-window spot rather than drawing nothing. */
		int x;
		int y;
		final Widget chat = client.getWidget(InterfaceID.Chatbox.CHATAREA);
		final Rectangle chatBounds = chat != null && !chat.isHidden() ? chat.getBounds() : null;
		if (chatBounds != null && !chatBounds.isEmpty())
		{
			x = chatBounds.x + 4;
			y = chatBounds.y - h - 4;
		}
		else
		{
			x = bounds.x;
			y = bounds.y + bounds.height + 4;
		}
		if (y + h > client.getCanvasHeight())
		{
			y = Math.max(0, bounds.y - h - 4);
		}
		y = Math.max(0, y);
		if (x + w > client.getCanvasWidth())
		{
			x = Math.max(0, client.getCanvasWidth() - w);
		}

		g.setColor(PANEL_BG);
		g.fillRect(x, y, w, h);
		/* A full gold surround while the price box is still closed: that is
		   the moment the panel is asking for an action rather than just
		   reporting a number, and the left accent alone did not read as a
		   prompt. It drops back to the accent once the box is open and the
		   panel becomes clickable. */
		if (!fillable)
		{
			g.setStroke(new BasicStroke(2f));
			g.setColor(GOLD);
			g.drawRect(x + 1, y + 1, w - 2, h - 2);
		}
		g.setColor(GOLD);
		g.fillRect(x, y, 2, h); // same left accent the sidebar cards use

		int textY = y + PAD + tm.getAscent();
		g.setFont(titleFont);
		g.setColor(TEXT_MAIN);
		g.drawString(title, x + PAD, textY);

		textY += LINE_GAP + pm.getAscent();
		g.setFont(priceFont);
		g.setColor(GOLD);
		g.drawString(priceLine, x + PAD, textY);

		g.setFont(smallFont);
		if (wikiLine != null)
		{
			textY += LINE_GAP + sm.getAscent();
			g.setColor(MUTED);
			g.drawString(wikiLine, x + PAD, textY);
		}
		if (marginLine != null)
		{
			textY += LINE_GAP + sm.getAscent();
			g.setColor(POSITIVE);
			g.drawString(marginLine, x + PAD, textY);
		}
		textY += LINE_GAP + sm.getAscent();
		g.setColor(GOLD);
		g.drawString(clickLine, x + PAD, textY);
		if (fillable)
		{
			panelHitbox = new Rectangle(x, y, w, h);
		}
		return null;
	}
}
