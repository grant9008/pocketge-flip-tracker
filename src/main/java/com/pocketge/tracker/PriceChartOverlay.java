package com.pocketge.tracker;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseWheelListener;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The pocketge.com price chart, over the chat while a Grand Exchange offer
 * is open — the place the price is being decided.
 *
 * Asked for after seeing another plugin's chart there: "make the chart
 * match the color theme + add the volume price bar + be dotted + match all
 * chart settings you use on pocketge". So the painting is PriceChart, a
 * port of the site's own; this class only decides when and where it shows,
 * draws the header, the range buttons and VP toggle, and takes clicks: the
 * buttons, the top edge (drag to resize), and the site's own zoom — the
 * wheel zooms time toward the newest prices, a drag pans, a double-click
 * goes back to everything.
 *
 * It stands aside whenever the chat is wanted: during the item search and
 * while a price or quantity prompt is open (any chat input layer), and
 * when the chat itself is hidden. Over a price or quantity box a small
 * "Chart" button above PocketGE's price chip brings it back, and the same
 * button, then reading "Price", puts the box back — to flick between the
 * two while deciding.
 */
@Singleton
class PriceChartOverlay extends Overlay
{
	/** What the chart needs from the plugin. Called on the client thread,
	 *  every frame: lookups only, never a request. */
	interface Feed
	{
		/** The series to draw for an item and range, or null while it
		 *  loads (asking for it is the plugin's business). */
		TradeEngine.Series series(int itemId, PriceChart.Range range);

		/** Whether prices may be fetched at all — the advisor switch. */
		boolean pricesOn();

		Advisor.Quote quote(int itemId);

		/** The target the offer screen quotes for that side, or 0. */
		long target(int itemId, boolean buy);

		String name(int itemId);

		/** Where PocketGE's price or quantity chip sits in the chat while a
		 *  number box is open, or null — the toggle button goes above it. */
		Rectangle promptChip();
	}

	/** Header height above the plot. */
	static final int HEADER = 20;
	/** The shortest the panel can be dragged: the header and a plot still
	 *  worth reading (or the chat's own height, if that is less). */
	static final int MIN_HEIGHT = 110;
	/** Kept clear at the top of the game when the panel is dragged tall. */
	static final int TOP_MARGIN = 4;
	/** The grab strip on the panel's top edge reaches this far above it,
	 *  and this far into it — short of the header's buttons. The grip's
	 *  two bars are drawn inside it. */
	private static final int GRIP_ABOVE = 4;
	private static final int GRIP_BELOW = 4;
	/** The Chart / Price toggle over a number box. */
	private static final int TOGGLE_W = 50;
	private static final int TOGGLE_H = 15;
	/** The chat's height when it has never been seen open this session. */
	private static final int CHAT_HEIGHT = 142;
	private static final Color BG = new Color(0x13, 0x11, 0x10, 0xF5);
	private static final Color EDGE = new Color(0x2B, 0x26, 0x21);
	private static final Color BUTTON_ON = new Color(0x3C, 0x35, 0x2B);

	private final Client client;
	private final PocketGeTrackerConfig config;
	private volatile Feed feed;

	/** Set during render, read by the mouse listeners: the panel, each
	 *  button's box by its action ("D1".."Y1", "vp", "reset"), the strip
	 *  along the top edge that resizes it, the chat area it stands on, and
	 *  the Chart / Price toggle over a number box. */
	private volatile Rectangle panel;
	private volatile Map<String, Rectangle> buttons = Map.of();
	private volatile Rectangle grip;
	private volatile Rectangle chatArea;
	private volatile Rectangle toggle;
	/** The chat area as last seen open, for its height while it is shut. */
	private Rectangle lastOpenChat;

	/** A drag of the top edge in progress: the panel's height while it is
	 *  held (drawn live, saved on release), or -1. */
	private volatile int dragHeight = -1;
	private int dragStartY;
	private int dragStartHeight;

	/** Shown over an open price or quantity box, by the toggle. Cleared
	 *  when the box closes or is swapped for the other, so the next one
	 *  opens on the box. */
	private volatile boolean overPrompt;
	/** The number box open last frame. Client thread. */
	private GeOfferPriceOverlay.NumberBox lastBox = GeOfferPriceOverlay.NumberBox.NONE;

	/**
	 * The time zoom, as the site's: 1 to {@link PriceChart#ZOOM_MAX}, and
	 * the first point shown, -1 while it is pinned to the newest. Kept for
	 * one item and range; another of either starts at everything again.
	 */
	private volatile double zoom = 1;
	private volatile int zoomOffset = -1;
	private volatile String zoomFor = "";
	/** What the last frame drew, for a drag to pan by: the series' length,
	 *  the window and where it started, and px per point. */
	private volatile int viewN;
	private volatile int viewWin;
	private volatile int viewStart;
	private volatile double viewStepX;
	private int panStartX = Integer.MIN_VALUE;
	private int panStartOffset;

	@Inject
	private PriceChartOverlay(Client client, PocketGeTrackerConfig config)
	{
		this.client = client;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	void setFeed(Feed feed)
	{
		this.feed = feed;
	}

	/**
	 * Whether the chart is up over an open price or quantity box, covering
	 * PocketGE's chip — read by GeOfferPriceOverlay, which renders first.
	 * Only while the chart really drew there last frame (not when it has
	 * been switched off, or the chat is gone), and only over the box it
	 * went over: one swapped in place since is not covered.
	 */
	boolean overPrompt()
	{
		return overPrompt && panel != null && toggle != null && GeOfferPriceOverlay.openNumberBox(client) == lastBox;
	}

	/** Forget every click target: the game is leaving the screens this is
	 *  drawn on, and nothing is drawn to replace them until it is back. */
	void forget()
	{
		panel = null;
		grip = null;
		toggle = null;
		buttons = Map.of();
		dragHeight = -1;
		panStartX = Integer.MIN_VALUE;
	}

	/** The offer on screen: item, side and the price set in it. */
	static final class Shown
	{
		final int itemId;
		final boolean buy;
		final long offerPrice;

		Shown(int itemId, boolean buy, long offerPrice)
		{
			this.itemId = itemId;
			this.buy = buy;
			this.offerPrice = offerPrice;
		}
	}

	/**
	 * What offer the screen is showing, or null when there is none. The
	 * game shows one of two panels for a selected slot
	 * (ge_offers_switchpanel): "Set up offer" for an empty slot, with the
	 * item picked in the search, the Buy/Sell side and the price in the
	 * screen's own price box; or "Offer status" for a placed offer, whose
	 * own item, side and price are read from the slot. Whether the chat is
	 * free to cover is chatFree's business.
	 */
	Shown shown()
	{
		final boolean setup = visible(InterfaceID.GeOffers.SETUP);
		if (!setup && !visible(InterfaceID.GeOffers.DETAILS))
		{
			return null;
		}
		if (setup)
		{
			final int item = client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
			if (item <= 0)
			{
				return null;
			}
			/* The price box is a 64-bit varp since prices may pass max cash:
			   the int getter cannot read it. */
			return new Shown(item, client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE) == 0,
				Math.max(0L, client.getVarpLongValue(VarPlayerID.GE_NEWOFFER_PRICE)));
		}
		final int slot = client.getVarbitValue(VarbitID.GE_SELECTEDSLOT) - 1;
		final GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (slot < 0 || offers == null || slot >= offers.length || offers[slot] == null || offers[slot].getItemId() <= 0)
		{
			return null;
		}
		final GrandExchangeOffer o = offers[slot];
		final GrandExchangeOfferState st = o.getState();
		final boolean buy = st == GrandExchangeOfferState.BUYING || st == GrandExchangeOfferState.BOUGHT
			|| st == GrandExchangeOfferState.CANCELLED_BUY;
		return new Shown(o.getItemId(), buy, Math.max(0L, o.getPrice()));
	}

	private boolean visible(int component)
	{
		final Widget w = client.getWidget(component);
		return w != null && !w.isHidden();
	}

	/**
	 * RuneLite draws an ABOVE_WIDGETS overlay only after these toplevels
	 * (OverlayManager.rebuildOverlayLayers). Under any other — the welcome
	 * screen's TOPLEVEL_DISPLAY (165), the full-screen world map — render
	 * is not called, and the last frame's click targets are stale: pressed
	 * there, they ate the welcome screen's "Click here to play".
	 */
	static boolean drawnUnder(int toplevel)
	{
		return toplevel == InterfaceID.TOPLEVEL || toplevel == InterfaceID.TOPLEVEL_OSRS_STRETCH
			|| toplevel == InterfaceID.TOPLEVEL_PRE_EOC;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		/* The click targets are built here and published once, at the end:
		   the mouse listener runs on another thread, and a press landing
		   mid-frame must still find this frame's buttons, not none. */
		Rectangle drawn = null;
		Rectangle tog = null;
		Map<String, Rectangle> hit = Map.of();
		try
		{
			final Map<String, Rectangle> boxes = new LinkedHashMap<>();
			final Rectangle[] togOut = new Rectangle[1];
			drawn = draw(g, boxes, togOut);
			tog = togOut[0];
			if (drawn != null)
			{
				hit = boxes;
			}
		}
		finally
		{
			buttons = hit;
			grip = drawn != null ? gripOf(drawn) : null;
			toggle = tog;
			panel = drawn;
		}
		return null;
	}

	/**
	 * The panel's box: the chat area's width, standing on its bottom edge,
	 * {@code height} tall — 0 for the chat's own height — and kept between
	 * MIN_HEIGHT (or the chat's height, if less) and the top of the game.
	 * It grows upward over the game, never down over the chat's tabs.
	 */
	static Rectangle panelBox(Rectangle chat, int height)
	{
		final int bottom = chat.y + chat.height;
		final int min = Math.min(MIN_HEIGHT, chat.height);
		final int max = Math.max(min, bottom - TOP_MARGIN);
		final int h = Math.max(min, Math.min(max, height > 0 ? height : chat.height));
		return new Rectangle(chat.x, bottom - h, chat.width, h);
	}

	/** The strip along the panel's top edge that drags it taller or shorter. */
	static Rectangle gripOf(Rectangle box)
	{
		return new Rectangle(box.x, box.y - GRIP_ABOVE, box.width, GRIP_ABOVE + GRIP_BELOW);
	}

	/**
	 * The Chart / Price toggle: in the top-right corner of whatever is
	 * showing — the chat, above the price chip, or the chart's header,
	 * where the range buttons make room for it. It sat just over the chip,
	 * which over the chart put it on the price axis and the offer's badge
	 * ("price label in the way").
	 */
	static Rectangle toggleOf(Rectangle area)
	{
		return new Rectangle(area.x + area.width - 6 - TOGGLE_W, area.y + 5, TOGGLE_W, TOGGLE_H);
	}

	/**
	 * Where the toggle goes: the chat's top-right corner, above the price
	 * chip, in both states — so flicking to the chart and back is two clicks
	 * in one place — except over a chart dragged taller than the chat.
	 *
	 * With the chart down it is always the chat's corner. It once followed
	 * where a taller chart's header WOULD be, which with no chart drawn put a
	 * button over the game view and the Exchange window that ate the clicks
	 * meant for them.
	 *
	 * A chart taller than the chat carries it in its own header instead:
	 * there the chat's corner is part way down the price axis, and a button
	 * on the axis is "price label in the way" again. At the chat's height or
	 * shorter the chat's corner is in or above the chart's header, where the
	 * range buttons make room for it (toggleInHeader) and the drag edge
	 * stands aside (overGrip).
	 */
	static Rectangle toggleFor(Rectangle chat, Rectangle box, boolean chartUp)
	{
		return toggleOf(chartUp && box.y < chat.y ? box : chat);
	}

	/** Whether the toggle reaches into the chart's header row, where the
	 *  range buttons must leave it room. A chart a few pixels shorter than the
	 *  chat has its header just under the toggle's top, not around it. */
	static boolean toggleInHeader(Rectangle tog, Rectangle box)
	{
		return tog != null && tog.y < box.y + HEADER && tog.y + tog.height > box.y;
	}

	/** On the chart's drag edge, and not on the toggle: where the two meet
	 *  a press is the toggle's (it is tested first), so the edge must not
	 *  light up there as if it would drag. */
	static boolean overGrip(Rectangle box, Rectangle tog, int x, int y)
	{
		return gripOf(box).contains(x, y) && (tog == null || !tog.contains(x, y));
	}

	/** What the header's buttons leave free on the right for the toggle. */
	static final int TOGGLE_ROOM = TOGGLE_W + 8;

	/** Paint this frame's chart, if one should be up, filling {@code hit};
	 *  the panel's box, or null for none. The toggle over a number box, if
	 *  drawn, goes in {@code togOut[0]}. */
	private Rectangle draw(Graphics2D g, Map<String, Rectangle> hit, Rectangle[] togOut)
	{
		final Feed f = feed;
		final GeOfferPriceOverlay.NumberBox open = GeOfferPriceOverlay.openNumberBox(client);
		final boolean numberBox = open != GeOfferPriceOverlay.NumberBox.NONE;
		if (open != lastBox)
		{
			/* The box closed, or the game swapped it for the other in place
			   (the price box straight to the quantity box, with no closed
			   frame between): the next one opens on the box. */
			lastBox = open;
			overPrompt = false;
		}
		if (f == null || !config.priceChart())
		{
			return null;
		}
		final Shown s = shown();
		if (s == null)
		{
			return null;
		}
		final net.runelite.api.Point mp = client.getMouseCanvasPosition();
		/* Over a price or quantity box: the toggle above PocketGE's chip,
		   and the chart only while it is flicked on. Any other chat input —
		   the item search — and the chart stands aside. */
		final int mode = client.getVarcIntValue(VarClientID.MESLAYERMODE);
		final Rectangle cb = chatBounds();
		if (cb == null || cb.width < 200 || cb.height < 80)
		{
			return null;
		}
		final boolean toggleUp = numberBox && f.promptChip() != null;
		final int dragging = dragHeight;
		final Rectangle box = panelBox(cb, dragging >= 0 ? dragging : config.chartHeight());
		if (mode != 0 && (!toggleUp || !overPrompt))
		{
			if (toggleUp)
			{
				final Rectangle tog = toggleFor(cb, box, false);
				drawToggle(g, tog, "Chart", mp != null && tog.contains(mp.getX(), mp.getY()));
				togOut[0] = tog;
			}
			return null;
		}
		chatArea = new Rectangle(cb);
		final PriceChart.Range range = PriceChart.Range.of(config.chartRange());
		final TradeEngine.Series series = f.series(s.itemId, range);
		final PriceChart.Data data = series != null ? PriceChart.prepare(series, range.periodSec) : null;

		/* The zoom belongs to one item and range. */
		final String key = s.itemId + "/" + range.name();
		if (!key.equals(zoomFor))
		{
			zoomFor = key;
			zoom = 1;
			zoomOffset = -1;
		}
		PriceChart.Data view = data;
		final boolean zoomed;
		if (data != null && data.size() > 0)
		{
			final int n = data.size();
			final int win = PriceChart.window(n, zoom);
			final int start = PriceChart.windowStart(n, win, zoomOffset);
			view = PriceChart.slice(data, start, start + win);
			viewN = n;
			viewWin = win;
			viewStart = start;
			zoomed = win < n;
		}
		else
		{
			viewN = 0;
			zoomed = false;
		}

		final Rectangle tog = toggleUp ? toggleFor(cb, box, true) : null;
		/* Over the toggle the pointer is on a button, not the chart: no
		   crosshair under it, and no lit drag edge. */
		final boolean onToggle = tog != null && mp != null && tog.contains(mp.getX(), mp.getY());
		final Point mouse = mp != null && !onToggle && box.contains(mp.getX(), mp.getY())
			? new Point(mp.getX(), mp.getY()) : null;
		final boolean gripHot = dragging >= 0 || (mp != null && overGrip(box, tog, mp.getX(), mp.getY()));

		final PriceChart.Style style = new PriceChart.Style();
		final PocketGeTrackerConfig.ColourTheme theme = config.colourTheme();
		if (theme != null)
		{
			style.buy = theme.buy();
			style.sell = theme.sell();
		}
		style.vp = config.chartVolumeProfile();
		final PriceChart.Lines lines = new PriceChart.Lines();
		lines.target = f.target(s.itemId, s.buy);
		lines.targetBuy = s.buy;
		lines.offer = s.offerPrice;

		final PriceChart.Layout lay = paint(g, box, f.name(s.itemId), f.quote(s.itemId), range, data, view, zoomed,
			series == null, f.pricesOn(), style, lines, mouse, gripHot,
			toggleInHeader(tog, box) ? TOGGLE_ROOM : 0, hit);
		viewStepX = lay != null ? lay.stepX : 0;
		if (tog != null)
		{
			/* Where Chart was, now putting the box back. */
			drawToggle(g, tog, "Price", onToggle);
			togOut[0] = tog;
		}
		return box;
	}

	/**
	 * Where the chat area is: its own box while it is open, or — when the
	 * player has shut the chat with a tab — the same box standing on the tab
	 * bar, at the height it last had open. The chart is wanted all the same
	 * ("as soon as i click uncut diamond, the chart should appear").
	 */
	private Rectangle chatBounds()
	{
		final Widget chat = client.getWidget(InterfaceID.Chatbox.CHATAREA);
		if (chat != null && !chat.isHidden())
		{
			final Rectangle cb = chat.getBounds();
			if (cb != null && cb.width >= 200 && cb.height >= 80)
			{
				lastOpenChat = new Rectangle(cb);
				return cb;
			}
		}
		final Widget tabs = client.getWidget(InterfaceID.Chatbox.CONTROLS);
		if (tabs == null || tabs.isHidden())
		{
			return null;
		}
		final Rectangle tb = tabs.getBounds();
		if (tb == null || tb.width < 200)
		{
			return null;
		}
		final int h = lastOpenChat != null ? lastOpenChat.height : CHAT_HEIGHT;
		final int w = lastOpenChat != null ? lastOpenChat.width : tb.width;
		return new Rectangle(tb.x, tb.y - h, w, h);
	}

	/** The small Chart / Price toggle above the chip, in the chip's gold. */
	static void drawToggle(Graphics2D g0, Rectangle r, String label, boolean hover)
	{
		final Graphics2D g = (Graphics2D) g0.create();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setColor(hover ? new Color(0xF2, 0xD3, 0x75) : new Color(0xE5, 0xC1, 0x58));
			g.fillRect(r.x, r.y, r.width, r.height);
			g.setColor(new Color(0x6B, 0x55, 0x1C));
			g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
			g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
			final FontMetrics fm = g.getFontMetrics();
			g.setColor(new Color(0x1A, 0x14, 0x05));
			g.drawString(label, r.x + (r.width - fm.stringWidth(label)) / 2, r.y + (r.height + fm.getAscent()) / 2 - 2);
		}
		finally
		{
			g.dispose();
		}
	}

	/**
	 * The whole panel, for the overlay and for rendering off the client:
	 * background, header, then the chart or a line saying why there is
	 * none. {@code data} is the range's series, which the header's change
	 * is worked out from; {@code view} the window of it the zoom shows,
	 * which is what is drawn. Fills {@code hit} with the buttons' boxes.
	 * {@code gripHot}: the mouse is on the top edge, or dragging it. The
	 * plot's layout, or null when there was nothing to plot.
	 */
	static PriceChart.Layout paint(Graphics2D g0, Rectangle box, String name, Advisor.Quote q, PriceChart.Range range,
		PriceChart.Data data, PriceChart.Data view, boolean zoomed, boolean loading, boolean pricesOn,
		PriceChart.Style style, PriceChart.Lines lines, Point mouse, boolean gripHot, int rightReserve,
		Map<String, Rectangle> hit)
	{
		final Graphics2D g = (Graphics2D) g0.create();
		try
		{
			g.clip(box); // nothing past the chat it covers
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setColor(BG);
			g.fillRect(box.x, box.y, box.width, box.height);
			g.setColor(EDGE);
			g.drawRect(box.x, box.y, box.width - 1, box.height - 1);
			/* The top edge drags: a small grip in the middle says so, inside
			   the strip that takes the press and above the header's text, and
			   the edge lights while it is under the mouse or held. */
			if (gripHot)
			{
				g.setColor(style.buy);
				g.fillRect(box.x, box.y, box.width, 1);
			}
			g.setColor(gripHot ? style.buy : PriceChart.TEXT_MUTED);
			final int gx = box.x + box.width / 2 - 14;
			g.fillRect(gx, box.y + 1, 28, 1);
			g.fillRect(gx + 4, box.y + 3, 20, 1);

			/* buttons, right-aligned: Reset while zoomed, the ranges, a gap,
			   then VP */
			final Font bf = new Font(Font.SANS_SERIF, Font.BOLD, 10);
			g.setFont(bf);
			final FontMetrics fm = g.getFontMetrics();
			final int ranges = PriceChart.Range.values().length;
			final String[][] specs = new String[ranges + (zoomed ? 2 : 1)][];
			int k = 0;
			if (zoomed)
			{
				specs[k++] = new String[]{"reset", "Reset zoom", "off"};
			}
			final int firstRange = k;
			for (PriceChart.Range r : PriceChart.Range.values())
			{
				specs[k++] = new String[]{r.name(), r.label, r == range ? "on" : "off"};
			}
			specs[k] = new String[]{"vp", "VP", style.vp ? "on" : "off"};
			int bx = box.x + box.width - 6 - Math.max(0, rightReserve);
			for (int i = specs.length - 1; i >= 0; i--)
			{
				final int bw = fm.stringWidth(specs[i][1]) + 10;
				bx -= bw;
				if (i == firstRange + ranges - 1)
				{
					bx -= 8; // a gap between the ranges and the toggle
				}
				if (zoomed && i == 0)
				{
					bx -= 8; // and between Reset and the ranges
				}
				final Rectangle r = new Rectangle(bx, box.y + 5, bw, 14);
				hit.put(specs[i][0], r);
				bx -= 2;
			}
			for (String[] spec : specs)
			{
				final Rectangle r = hit.get(spec[0]);
				final boolean on = "on".equals(spec[2]);
				final boolean outlined = "vp".equals(spec[0]) || "reset".equals(spec[0]);
				final boolean hover = mouse != null && r.contains(mouse);
				if (on && !outlined)
				{
					g.setColor(BUTTON_ON);
					g.fillRoundRect(r.x, r.y, r.width, r.height, 4, 4);
				}
				if (outlined)
				{
					g.setColor(EDGE);
					g.drawRoundRect(r.x, r.y, r.width - 1, r.height - 1, 4, 4);
				}
				/* The toggle lights in the buy colour when on, as the site's
				   aria-pressed buttons do; the active range goes white. */
				g.setColor("vp".equals(spec[0]) ? (on ? style.buy : PriceChart.TEXT_MUTED)
					: on ? Color.WHITE : hover ? PriceChart.TEXT_MAIN : PriceChart.TEXT_MUTED);
				g.drawString(spec[1], r.x + (r.width - fm.stringWidth(spec[1])) / 2, r.y + 11);
			}

			/* header: name, the live price and the range's change, after the
			   buttons and kept left of them — a long name with a 10-digit
			   price ran under Reset zoom. The name gives way first, with an
			   ellipsis; the price and the change are what the header is for. */
			int leftmost = box.x + box.width;
			for (Rectangle r : hit.values())
			{
				leftmost = Math.min(leftmost, r.x);
			}
			final int limit = leftmost - 6;
			final int baseY = box.y + 15;
			final Font nameFont = new Font(Font.SANS_SERIF, Font.BOLD, 11);
			final Font valueFont = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
			final FontMetrics vfm = g.getFontMetrics(valueFont);
			final long last = q == null ? 0 : q.high > 0 ? q.high : q.low;
			final String price = last > 0 ? String.format(Locale.US, "%,d gp", last) : null;
			final double pct = data != null && data.size() > 1 ? PriceChart.pctChange(data) : Double.NaN;
			final String change = Double.isNaN(pct) ? null : String.format(Locale.US, "%+.2f%%", pct);
			int x = box.x + 8;
			final int tail = (price != null ? vfm.stringWidth(price) + 6 : 0) + (change != null ? vfm.stringWidth(change) : 0);
			final String title = fit(name != null ? name : "", g.getFontMetrics(nameFont), limit - x - 8 - tail);
			final Graphics2D hg = (Graphics2D) g.create();
			try
			{
				hg.clipRect(box.x, box.y, Math.max(0, limit - box.x), HEADER); // a narrow chat cuts the tail, never a button
				hg.setFont(nameFont);
				hg.setColor(PriceChart.TEXT_MAIN);
				hg.drawString(title, x, baseY);
				x += hg.getFontMetrics().stringWidth(title) + 8;
				hg.setFont(valueFont);
				if (price != null)
				{
					hg.drawString(price, x, baseY);
					x += vfm.stringWidth(price) + 6;
				}
				if (change != null)
				{
					hg.setColor(pct > 0.004 ? PriceChart.POSITIVE : pct < -0.004 ? PriceChart.NEGATIVE : PriceChart.TEXT_MUTED);
					hg.drawString(change, x, baseY);
				}
			}
			finally
			{
				hg.dispose();
			}

			final Rectangle plot = new Rectangle(box.x, box.y + HEADER, box.width, box.height - HEADER);
			if (view != null && view.size() > 0)
			{
				return PriceChart.paint(g, plot, view, style, lines, mouse);
			}
			PriceChart.drawCentred(g, plot, !pricesOn && loading
				? "Prices are off — the Flip advisor switch turns them on."
				: loading ? "Loading " + range.label + " prices…"
				: "No trade data for this timeframe — try a longer one.", PriceChart.TEXT_MUTED, 12);
			return null;
		}
		finally
		{
			g.setStroke(new BasicStroke(1f));
			g.dispose();
		}
	}

	/** {@code s}, or as much of it as fits in {@code w} px with an ellipsis. */
	static String fit(String s, FontMetrics fm, int w)
	{
		if (fm.stringWidth(s) <= w)
		{
			return s;
		}
		for (int n = s.length() - 1; n > 0; n--)
		{
			final String t = s.substring(0, n).trim() + "…";
			if (fm.stringWidth(t) <= w)
			{
				return t;
			}
		}
		return "";
	}

	/** Whether the mouse may act on what the last frame drew: logged in,
	 *  under a toplevel the overlay is drawn on, and no right-click menu
	 *  open — its entries, drawn over the chart, take the press. */
	private boolean live()
	{
		return client.getGameState() == GameState.LOGGED_IN && drawnUnder(client.getTopLevelInterfaceId())
			&& !client.isMenuOpen();
	}

	/** Left presses on the chart: a button acts, the top edge drags the
	 *  panel taller or shorter (a double-click puts it back to the chat's
	 *  height), a press on the plot starts a pan (a double-click shows
	 *  everything again), and every press inside it is kept from the chat
	 *  underneath. The toggle above a price chip flicks the chart over the
	 *  box and back. */
	final MouseAdapter mouse = new MouseAdapter()
	{
		@Override
		public MouseEvent mousePressed(MouseEvent e)
		{
			if (dragHeight >= 0)
			{
				endDrag(); // its release never came (let go outside the window)
			}
			panStartX = Integer.MIN_VALUE;
			if (!SwingUtilities.isLeftMouseButton(e) || !live())
			{
				return e;
			}
			final Rectangle tog = toggle;
			if (tog != null && tog.contains(e.getPoint()))
			{
				overPrompt = !overPrompt;
				e.consume();
				return e;
			}
			final Rectangle p = panel;
			if (p == null)
			{
				return e;
			}
			final Rectangle edge = grip;
			if (edge != null && edge.contains(e.getPoint()))
			{
				if (e.getClickCount() >= 2)
				{
					dragHeight = -1;
					config.setChartHeight(0);
				}
				else
				{
					dragStartY = e.getY();
					dragStartHeight = p.height;
					dragHeight = p.height;
				}
				e.consume();
				return e;
			}
			if (!p.contains(e.getPoint()))
			{
				return e;
			}
			final Map<String, Rectangle> bs = buttons;
			for (Map.Entry<String, Rectangle> b : bs.entrySet())
			{
				if (b.getValue().contains(e.getPoint()))
				{
					press(b.getKey());
					e.consume();
					return e;
				}
			}
			if (e.getY() >= p.y + HEADER)
			{
				if (e.getClickCount() >= 2)
				{
					resetZoom();
				}
				else
				{
					panStartX = e.getX();
					panStartOffset = viewStart;
				}
			}
			e.consume();
			return e;
		}

		@Override
		public MouseEvent mouseDragged(MouseEvent e)
		{
			if (dragHeight >= 0)
			{
				final Rectangle cb = chatArea;
				if (cb != null)
				{
					/* Never 0 or less, which panelBox reads as "the chat's
					   own height": dragged down past the chat, it stays at
					   its shortest. */
					dragHeight = panelBox(cb, Math.max(1, dragStartHeight + (dragStartY - e.getY()))).height;
				}
				e.consume();
				return e;
			}
			if (panStartX != Integer.MIN_VALUE)
			{
				pan(e.getX());
				e.consume();
			}
			return e;
		}

		@Override
		public MouseEvent mouseReleased(MouseEvent e)
		{
			if (panStartX != Integer.MIN_VALUE)
			{
				panStartX = Integer.MIN_VALUE;
				e.consume();
				return e;
			}
			if (dragHeight < 0)
			{
				return e;
			}
			endDrag();
			e.consume();
			return e;
		}
	};

	/**
	 * The wheel over the chart zooms time as the site's does: each notch in
	 * by 1.2, out by 0.8, between everything and a twentieth of it, staying
	 * on the newest prices (app.js:9708-9712, anchorZoomToLatest). Taken
	 * from the game, so it neither scrolls the chat nor moves the camera.
	 */
	final MouseWheelListener wheel = new MouseWheelListener()
	{
		@Override
		public MouseWheelEvent mouseWheelMoved(MouseWheelEvent e)
		{
			final Rectangle p = panel;
			if (p == null || !p.contains(e.getPoint()) || !live())
			{
				return e;
			}
			/* Nothing to zoom while it says Loading or No trade data, but
			   the turn is still not the chat's (nor the camera's). */
			if (viewN > 0)
			{
				final double factor = e.getWheelRotation() < 0 ? 1.2 : e.getWheelRotation() > 0 ? 0.8 : 1;
				zoom = zoomAfter(zoom, factor);
				zoomOffset = -1;
			}
			e.consume();
			return e;
		}
	};

	/** A zoom times {@code factor}, kept between 1 and the most. */
	static double zoomAfter(double zoom, double factor)
	{
		return Math.max(1, Math.min(PriceChart.ZOOM_MAX, zoom * factor));
	}

	/** Pan to where a drag that started at panStartX now is: the window
	 *  follows the mouse, a point per point's width. */
	private void pan(int x)
	{
		final double step = viewStepX;
		final int n = viewN;
		final int win = viewWin;
		if (step <= 0 || n <= 0 || win >= n)
		{
			return;
		}
		final int moved = (int) Math.round((x - panStartX) / step);
		zoomOffset = Math.max(0, Math.min(n - win, panStartOffset - moved));
	}

	private void resetZoom()
	{
		zoom = 1;
		zoomOffset = -1;
	}

	/** Keep the height the edge was let go at. */
	private void endDrag()
	{
		final int h = dragHeight;
		if (h >= 0 && h != dragStartHeight)
		{
			config.setChartHeight(h);
		}
		dragHeight = -1;
	}

	private void press(String action)
	{
		switch (action)
		{
			case "vp":
				config.setChartVolumeProfile(!config.chartVolumeProfile());
				break;
			case "reset":
				resetZoom();
				break;
			default:
				config.setChartRange(PriceChart.Range.of(action).name());
		}
	}
}
