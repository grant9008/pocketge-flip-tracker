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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.input.MouseAdapter;
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
 * draws the header and the range and Fill/VP buttons, and takes clicks.
 *
 * It stands aside whenever the chat is wanted: during the item search and
 * while a price or quantity prompt is open (any chat input layer), and
 * when the chat itself is hidden.
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
	}

	/** Header height above the plot. */
	static final int HEADER = 20;
	private static final Color BG = new Color(0x13, 0x11, 0x10, 0xF5);
	private static final Color EDGE = new Color(0x2B, 0x26, 0x21);
	private static final Color BUTTON_ON = new Color(0x3C, 0x35, 0x2B);

	private final Client client;
	private final PocketGeTrackerConfig config;
	private volatile Feed feed;

	/** Set during render, read by the mouse listener: the panel, and each
	 *  button's box by its action ("D1".."Y1", "fill", "vp"). */
	private volatile Rectangle panel;
	private volatile Map<String, Rectangle> buttons = Map.of();

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
	 * What offer the screen is showing, or null when the chart should not
	 * be up. A placed offer's own item, side and price when one is selected
	 * (GE_SELECTEDSLOT); otherwise the offer being set up — the item picked
	 * in the search, the Buy/Sell side, and the price varp the screen's own
	 * price box shows.
	 */
	Shown shown()
	{
		final Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
		if (setup == null || setup.isHidden())
		{
			return null;
		}
		/* Any chat input open — the item search, a price or quantity
		   prompt — and the chat is in use: stand aside. */
		if (client.getVarcIntValue(VarClientID.MESLAYERMODE) != 0)
		{
			return null;
		}
		final int slot = client.getVarbitValue(VarbitID.GE_SELECTEDSLOT) - 1;
		final GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (slot >= 0 && offers != null && slot < offers.length && offers[slot] != null && offers[slot].getItemId() > 0)
		{
			final GrandExchangeOffer o = offers[slot];
			final GrandExchangeOfferState st = o.getState();
			final boolean buy = st == GrandExchangeOfferState.BUYING || st == GrandExchangeOfferState.BOUGHT
				|| st == GrandExchangeOfferState.CANCELLED_BUY;
			return new Shown(o.getItemId(), buy, o.getPrice());
		}
		final int item = client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
		if (item <= 0)
		{
			return null;
		}
		return new Shown(item, client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE) == 0,
			Math.max(0, client.getVarpValue(VarPlayerID.GE_NEWOFFER_PRICE)));
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		panel = null;
		buttons = Map.of();
		final Feed f = feed;
		if (f == null || !config.priceChart())
		{
			return null;
		}
		final Shown s = shown();
		if (s == null)
		{
			return null;
		}
		final Widget chat = client.getWidget(InterfaceID.Chatbox.CHATAREA);
		if (chat == null || chat.isHidden())
		{
			return null;
		}
		final Rectangle cb = chat.getBounds();
		if (cb == null || cb.width < 200 || cb.height < 80)
		{
			return null;
		}
		final Rectangle box = new Rectangle(cb.x, cb.y, cb.width, cb.height);
		final PriceChart.Range range = PriceChart.Range.of(config.chartRange());
		final TradeEngine.Series series = f.series(s.itemId, range);
		final PriceChart.Data data = series != null ? PriceChart.prepare(series, range.periodSec) : null;
		final net.runelite.api.Point mp = client.getMouseCanvasPosition();
		final Point mouse = mp != null && box.contains(mp.getX(), mp.getY()) ? new Point(mp.getX(), mp.getY()) : null;

		final PriceChart.Style style = new PriceChart.Style();
		final PocketGeTrackerConfig.ColourTheme theme = config.colourTheme();
		if (theme != null)
		{
			style.buy = theme.buy();
			style.sell = theme.sell();
		}
		style.fill = config.chartFill();
		style.vp = config.chartVolumeProfile();
		final PriceChart.Lines lines = new PriceChart.Lines();
		lines.target = f.target(s.itemId, s.buy);
		lines.targetBuy = s.buy;
		lines.offer = s.offerPrice;

		final Map<String, Rectangle> hit = new LinkedHashMap<>();
		paint(g, box, f.name(s.itemId), f.quote(s.itemId), range, data, series == null, f.pricesOn(),
			style, lines, mouse, hit);
		panel = box;
		buttons = hit;
		return null;
	}

	/**
	 * The whole panel, for the overlay and for rendering off the client:
	 * background, header, then the chart or a line saying why there is
	 * none. Fills {@code hit} with the buttons' boxes.
	 */
	static void paint(Graphics2D g0, Rectangle box, String name, Advisor.Quote q, PriceChart.Range range,
		PriceChart.Data data, boolean loading, boolean pricesOn, PriceChart.Style style, PriceChart.Lines lines,
		Point mouse, Map<String, Rectangle> hit)
	{
		final Graphics2D g = (Graphics2D) g0.create();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setColor(BG);
			g.fillRect(box.x, box.y, box.width, box.height);
			g.setColor(EDGE);
			g.drawRect(box.x, box.y, box.width - 1, box.height - 1);

			// header: name, the live price and the range's change, then the buttons
			final int baseY = box.y + 14;
			int x = box.x + 8;
			g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
			FontMetrics fm = g.getFontMetrics();
			final String title = name != null ? name : "";
			g.setColor(PriceChart.TEXT_MAIN);
			g.drawString(title, x, baseY);
			x += fm.stringWidth(title) + 8;
			final long last = q == null ? 0 : q.high > 0 ? q.high : q.low;
			g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
			fm = g.getFontMetrics();
			if (last > 0)
			{
				final String p = String.format(Locale.US, "%,d gp", last);
				g.setColor(PriceChart.TEXT_MAIN);
				g.drawString(p, x, baseY);
				x += fm.stringWidth(p) + 6;
			}
			if (data != null && data.size() > 1)
			{
				final double pct = PriceChart.pctChange(data);
				final String c = String.format(Locale.US, "%+.2f%%", pct);
				g.setColor(pct > 0.004 ? PriceChart.POSITIVE : pct < -0.004 ? PriceChart.NEGATIVE : PriceChart.TEXT_MUTED);
				g.drawString(c, x, baseY);
			}

			// buttons, right-aligned: the ranges, a gap, then Fill and VP
			final Font bf = new Font(Font.SANS_SERIF, Font.BOLD, 10);
			g.setFont(bf);
			fm = g.getFontMetrics();
			final String[][] specs = new String[PriceChart.Range.values().length + 2][];
			int k = 0;
			for (PriceChart.Range r : PriceChart.Range.values())
			{
				specs[k++] = new String[]{r.name(), r.label, r == range ? "on" : "off"};
			}
			specs[k++] = new String[]{"fill", "Fill", style.fill ? "on" : "off"};
			specs[k] = new String[]{"vp", "VP", style.vp ? "on" : "off"};
			int bx = box.x + box.width - 6;
			for (int i = specs.length - 1; i >= 0; i--)
			{
				final int bw = fm.stringWidth(specs[i][1]) + 10;
				bx -= bw;
				if (i == PriceChart.Range.values().length - 1)
				{
					bx -= 8; // a gap between the ranges and the toggles
				}
				final Rectangle r = new Rectangle(bx, box.y + 4, bw, 14);
				hit.put(specs[i][0], r);
				bx -= 2;
			}
			for (String[] spec : specs)
			{
				final Rectangle r = hit.get(spec[0]);
				final boolean on = "on".equals(spec[2]);
				final boolean toggle = "fill".equals(spec[0]) || "vp".equals(spec[0]);
				final boolean hover = mouse != null && r.contains(mouse);
				if (on && !toggle)
				{
					g.setColor(BUTTON_ON);
					g.fillRoundRect(r.x, r.y, r.width, r.height, 4, 4);
				}
				if (toggle)
				{
					g.setColor(EDGE);
					g.drawRoundRect(r.x, r.y, r.width - 1, r.height - 1, 4, 4);
				}
				/* Toggles light in the buy colour when on, as the site's
				   aria-pressed buttons do; the active range goes white. */
				g.setColor(toggle ? (on ? style.buy : PriceChart.TEXT_MUTED)
					: on ? Color.WHITE : hover ? PriceChart.TEXT_MAIN : PriceChart.TEXT_MUTED);
				g.drawString(spec[1], r.x + (r.width - fm.stringWidth(spec[1])) / 2, r.y + 11);
			}

			final Rectangle plot = new Rectangle(box.x, box.y + HEADER, box.width, box.height - HEADER);
			if (data != null && data.size() > 0)
			{
				PriceChart.paint(g, plot, data, style, lines, mouse);
			}
			else
			{
				PriceChart.drawCentred(g, plot, !pricesOn && loading
					? "Prices are off — the Flip advisor switch turns them on."
					: loading ? "Loading " + range.label + " prices…"
					: "No trade data for this timeframe — try a longer one.", PriceChart.TEXT_MUTED, 12);
			}
			g.setStroke(new BasicStroke(1f));
		}
		finally
		{
			g.dispose();
		}
	}

	/** Left presses on the chart: a button acts, and every press inside
	 *  it is kept from the chat underneath. */
	final MouseAdapter mouse = new MouseAdapter()
	{
		@Override
		public MouseEvent mousePressed(MouseEvent e)
		{
			final Rectangle p = panel;
			if (p == null || !SwingUtilities.isLeftMouseButton(e) || !p.contains(e.getPoint()))
			{
				return e;
			}
			for (Map.Entry<String, Rectangle> b : buttons.entrySet())
			{
				if (b.getValue().contains(e.getPoint()))
				{
					press(b.getKey());
					break;
				}
			}
			e.consume();
			return e;
		}
	};

	private void press(String action)
	{
		switch (action)
		{
			case "fill":
				config.setChartFill(!config.chartFill());
				break;
			case "vp":
				config.setChartVolumeProfile(!config.chartVolumeProfile());
				break;
			default:
				config.setChartRange(PriceChart.Range.of(action).name());
		}
	}
}
