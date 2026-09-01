package com.pocketge.tracker;

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
	private static final Color GOLD = new Color(0xE5, 0xC1, 0x58);
	private static final Color TEXT_MAIN = new Color(0xD9, 0xD3, 0xC7);
	private static final Color MUTED = new Color(0x8A, 0x82, 0x74);
	private static final Color POSITIVE = new Color(0x1F, 0xB8, 0x5C);
	private static final Color PANEL_BG = new Color(0x1B, 0x18, 0x15, 0xE8);
	private static final int PAD = 8;
	private static final int LINE_GAP = 3;

	private final Client client;

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

	@Inject
	private GeOfferPriceOverlay(Client client)
	{
		this.client = client;
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

	@Override
	public Dimension render(Graphics2D g)
	{
		/* Cleared before anything can return early. These are hit-tested by
		   the plugin's mouse handler, which consumes the click it matches —
		   so a rectangle left over from a frame we no longer paint silently
		   eats real game clicks (confirming an offer, most damagingly). Only
		   a frame that genuinely draws may re-establish it. */
		panelHitbox = null;
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
		final String clickLine = fillable ? "click to fill this price" : "set a price to fill it";

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

		/* Below the offer window by preference — that keeps it clear of the
		   quantity/price buttons you're about to click. If there isn't room
		   underneath (a short client, or the window sitting low), flip it
		   above rather than letting it run off the canvas. */
		int x = bounds.x;
		int y = bounds.y + bounds.height + 4;
		if (y + h > client.getCanvasHeight())
		{
			y = Math.max(0, bounds.y - h - 4);
		}
		if (x + w > client.getCanvasWidth())
		{
			x = Math.max(0, client.getCanvasWidth() - w);
		}

		g.setColor(PANEL_BG);
		g.fillRect(x, y, w, h);
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
