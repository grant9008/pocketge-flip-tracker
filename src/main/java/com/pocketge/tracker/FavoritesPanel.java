package com.pocketge.tracker;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.QuantityFormatter;

/**
 * A plugin-local watchlist, mirroring the site's Favorites — live price for
 * each item, one tap to its chart. This is independent of the website's own
 * favorites (no account, nothing synced) — a local list for the client.
 * Items are added with the star toggle on a suggestion or history row (see
 * {@link AdvisorPanel} / {@link HistoryPanel}) — id+name are already known
 * there, so there's never a need to search for an item by typed name.
 */
public class FavoritesPanel extends JPanel
{
	private static final Color HOVER_BG = new Color(0x3A, 0x33, 0x28);
	/** The shortest the grip will drag the list. One row, not zero — zero is
	 *  what the collapse chevron above is for, and a drag that ended in an
	 *  empty box with a handle under it would look broken rather than
	 *  collapsed. */
	private static final int MIN_VISIBLE_ROWS = 1;
	/* Same colors as the website's .hl-badge.high5d / .low5d. */
	/*
	 * The 5-day badges, and they follow the colour theme.
	 *
	 * They were a fixed #00FF7A green and #FFB300 amber, so a watchlist of
	 * green and amber badges sat under a panel painted whatever pair the
	 * player had chosen — the same drift the bank squares had. The mapping is
	 * not arbitrary either: an item at a 5-day HIGH is something to sell, and
	 * one at a 5-day LOW is something to buy, so they take the theme's sell
	 * and buy colours respectively and the whole plugin ends up speaking one
	 * two-colour language.
	 *
	 * Volatile and static for the same reason the bank's are: several panels
	 * read them while painting, and one setter keeps them from disagreeing.
	 */
	static volatile Color HIGH5D = new Color(0x29, 0xB6, 0xF6);
	static volatile Color LOW5D = new Color(0xFF, 0xC1, 0x07);

	/** Point the 5-day badges at the current theme — see
	 *  PocketGeTrackerPlugin.pushBankTheme, which drives both surfaces. */
	static void setTheme(Color buy, Color sell)
	{
		if (buy != null && sell != null)
		{
			HIGH5D = sell;
			LOW5D = buy;
			HIGH1D = sell;
			LOW1D = buy;
		}
	}
	/* The day tier: a bare arrow, where the 5-day tier names itself — an
	   item brushes its own daily high or low constantly, so it has to read
	   as "noted" rather than "act now". In the theme's sell and buy colours,
	   as the website's .hl-badge.high / .low are; it was a fixed pale green
	   and gold, the one arrow in the list that ignored the theme. */
	static volatile Color HIGH1D = new Color(0x29, 0xB6, 0xF6);
	static volatile Color LOW1D = new Color(0xFF, 0xC1, 0x07);
	private static final int PULSE_PERIOD_MS = 2200; // matches the site's 2.2s rs-pulse-*-bright animation
	private static final int ICON_SIZE = 20;
	/** The list dropdown's arrow, and how far in from its right edge. */
	private static final String DROPDOWN_ARROW = "\u25BE";
	private static final int DROPDOWN_ARROW_INSET = 8;
	/** A move this big (either direction) gets the "spike" treatment
	 *  instead of the plain 5D pulse — genuinely rare, worth standing out
	 *  from routine breakout flags. */
	private static final double SPIKE_THRESHOLD_PCT = 15.0;
	private static final int SPIKE_PERIOD_MS = 1400; // faster than the routine pulse — reads as urgent
	// OSRS's own rare-item shimmer (party hats, 3rd age) cycles hue while
	// staying recognizably "that item" — same idea here, just biased toward
	// green/gold for a spike UP and red/magenta for a spike DOWN, so the
	// color itself still tells you which way it moved at a glance instead
	// of being a neutral rainbow.
	private static final Color[] SPIKE_UP_PALETTE = {
		new Color(0x1F, 0xB8, 0x5C), new Color(0x4F, 0xFF, 0x8E), new Color(0xFF, 0xD2, 0x4D), new Color(0x1F, 0xB8, 0x5C)
	};
	private static final Color[] SPIKE_DOWN_PALETTE = {
		new Color(0xEF, 0x53, 0x50), new Color(0xFF, 0x6B, 0x35), new Color(0xE0, 0x4F, 0xC4), new Color(0xEF, 0x53, 0x50)
	};

	/** One favorites list's identity (id/name/color) — separate from {@link
	 *  Row} since a list's metadata doesn't change per-item. */
	public static class ListMeta
	{
		public String id;
		public String name;
		public String color;
	}

	/** Resolved display row — the plugin looks up the live price, the panel
	 *  just renders it. */
	public static class Row
	{
		public int id;
		public String name;
		public long price;       // current insta-sell (low), 0 if unknown
		/** The day's change: live MID against the mid 24 hours ago, as a
		 *  percent — the website's 1D figure (PriceExtremes.dayChangePct); 0
		 *  if unknown. Deliberately not a change on {@link #price} (which is
		 *  the insta-sell). Drives the ±15% spike badge and the price alert,
		 *  so it has to be the number the site shows beside its 1D button. */
		public double changePct;
		/** Where this price sits in its own recent range: at a 5-day edge, at
		 *  a daily one, or nowhere in particular. Never null; see
		 *  PriceExtremes.tier for the rules and PocketGeTrackerPlugin for
		 *  where it is filled in. A big intraday move outranks all of it —
		 *  see badgeFor. */
		public PriceExtremes.Tier tier = PriceExtremes.Tier.NONE;
		// Detail-view fields (see PocketGeTrackerPlugin.refreshStatsAndFavorites):
		public long targetBuy;          // 0 if unknown
		public long targetSell;         // 0 if unknown
		/** What the stack you hold sells at — Advisor.heldSellPrice, the
		 *  price the sell card, hovers and marks name; 0 if unknown. Apart
		 *  from targetSell, which is the flip pair's ask and needs a margin. */
		public long heldSell;
		public long potentialProfit;    // for a full GE-limit buy/sell cycle, after tax; 0 if unknown
		public int limit;               // GE buy limit, 0 if unknown
		/** Units bought in this character's open 4h buy-limit window, and
		 *  when the limit is whole again (0 when it already is). */
		public int limitUsed;
		public long limitResetsAt;
		public long dailyVolume;        // combined 24h trade volume, 0 if unknown — the site's own VOL column
		/* What you're actually sitting on right now. The watchlist is where
		   you look to decide what to do next, so "you already own this and
		   it's up" belongs here rather than only inside a card you have to
		   click into. All four are filled in together — see
		   PocketGeTrackerPlugin.refreshStatsAndFavorites. */
		public int heldQty;             // units you own: bank + inventory + still listed for sale; 0 if none
		/** Where they are, for the card's subtitle — "listed in the Exchange",
		 *  "from your bank", … — or null. */
		public String heldWhere;
		/* Your live offers for this item. A stack sitting in a sell offer is
		   still yours, and it is the one you most want to see: "i have 25k of
		   them in ge waiting to sell at 374 ... thats way more important than
		   capped by the 4h buy limit". */
		public int listedQty;           // units still to sell in active sell offers
		public int listedSold;          // already sold out of those offers
		public int listedTotal;         // what those offers were placed for
		public long listedPrice;        // the ask on the biggest of them, 0 if none
		public int bidQty;              // what active buy offers were placed for
		public int bidFilled;           // bought so far on them
		public long bidPrice;           // the bid on the biggest of them, 0 if none
		public long sellValue;          // after-tax proceeds for heldQty at the card's SELL @, 0 if unpriced
		/** Only meaningful when {@link #hasCostBasis}: sellValue minus what
		 *  those exact units cost you. */
		public long heldProfit;
		/** The held stack against what the item actually trades, for the
		 *  inspect card's clearance line. Null when nothing is held or there
		 *  are too few prints to say — see {@link Clearance}. */
		public Clearance clearance;
		/** True only when the plugin actually watched you buy the units you
		 *  hold. A stack you had before the plugin ever ran has no cost, and
		 *  calling its sale value "profit" would be inventing a win. */
		public boolean hasCostBasis;
		/** How many of {@link #heldQty} the cost basis actually covers. Less
		 *  than heldQty when you already owned some of the stack before the
		 *  tracked purchases — {@link #heldProfit} then describes only this
		 *  many units, and deliberately understates the position. */
		public int pricedQty;
		/** What those pricedQty units cost in total — see
		 *  PortfolioValuer.HeldPosition.costOfPriced. */
		public long pricedCost;
	}

	public interface Actions
	{
		void remove(int itemId);
		void reorder(int itemId, int delta);
		/** Drag-to-reorder: itemId's new absolute index in the active list,
		 *  wherever the drag was dropped. */
		void reorderTo(int itemId, int newIndex);
		/** Clicking a row — the detail view it opens lives above the Top
		 *  Suggestion card (see AdvisorPanel.setSelectedItem), not here, so
		 *  the Favorites list itself always stays visible. */
		void selectItem(Row r);
		/** Right-click on a row. Left-click inspects the item in the card
		 *  above; this is the way OUT to the full chart on the website, the
		 *  same pairing the Find Opportunities rows use. */
		void openChart(String itemName);
		/** Open the chart, unconditionally.
		 *
		 *  {@link #openChart} is the LEFT-CLICK behaviour and is deliberately
		 *  gated: while the Exchange is asking which item to trade it types
		 *  the name instead, and with reuseBrowserTab on it tries to move a
		 *  tab you already have open. Neither is right for a menu entry whose
		 *  own text is "Open PocketGE chart" — that is an explicit request
		 *  for a chart and must never quietly do something else. */
		default void openChartTab(String itemName)
		{
			openChart(itemName);
		}
		/** The same item on the pocketge.com page you already have open —
		 *  see AdvisorPanel.Actions.sendChartToOpenTab. */
		default void sendChartToOpenTab(String itemName)
		{
			openChartTab(itemName);
		}
		/** The rows' glow borders on or off, for every row — see
		 *  {@link #setGlowEnabled}. */
		default void setRowGlow(boolean on)
		{
		}
		/** TradingView-style multiple watchlists: switch which list the star
		 *  button on suggestions/flips adds to, and manage the lists
		 *  themselves (create/rename/recolor/delete). */
		void selectList(String listId);
		void createList(String name);
		void renameList(String listId, String name);
		void recolorList(String listId, String color);
		void deleteList(String listId);
		/** Live item search (any tradeable item, not just whatever's already
		 *  surfaced as a suggestion) — matches the website's search box.
		 *  Runs on the client thread on the other end, so results come back
		 *  through a callback rather than a return value; the callback is
		 *  guaranteed to fire on the EDT. */
		void searchItems(String query, Consumer<List<SearchResult>> callback);
		/** See GeSlotsPanel.Actions.setSlotAdviceSkipped — routed through the
		 *  plugin, which owns the set of slots you have opted out of. */
		void setSlotAdviceSkipped(int slot, boolean skipped);
		/** See GeSlotsPanel.Actions.inspectItem. Clicking one of the eight
		 *  squares opens the same card a finder row does. Unlike
		 *  {@link #selectItem}, which carries a whole priced Row, this has
		 *  only an id and a name — a GE slot knows what is in it, not what
		 *  the market is doing with it. */
		void inspectItem(int itemId, String name);
		/** Adds (never toggles/removes) an item to the active list — a
		 *  search hit the player already has favorited is just a no-op. */
		void addFavorite(int itemId, String name);
		/** Remember how many watchlist rows to show, 0 meaning all of them.
		 *  Written when the grip under the list is dragged. */
		void setWatchlistRows(int rows);
	}

	/** One item-search hit — id + name are all the row needs to add it. */
	public static class SearchResult
	{
		public int id;
		public String name;
	}

	private final ItemManager itemManager;
	private final Actions actions;
	private final JTextField searchField = new JTextField();
	private static final String SEARCH_PLACEHOLDER = "Search items…";
	private final JPopupMenu searchResults = new JPopupMenu();
	private Timer searchDebounce;
	private final JPanel listBar = new JPanel(new BorderLayout(4, 0));
	private final JPanel rows = new JPanel();
	/** The collapse handle over the watchlist, and whether it is open. */
	private final JLabel listToggle = new JLabel();
	private boolean rowsOpen = true;
	/**
	 * How many watchlist rows to show, or 0 for all of them. Dragged with the
	 * grip below the list.
	 *
	 * Rows past the limit are HIDDEN, never removed: drag-to-reorder maps
	 * rows.getComponents() one-to-one onto the row data by index, and
	 * dropping children on the floor here would silently reorder the wrong
	 * item the moment the list was clipped.
	 *
	 * <h2>Why there is no scrollbar in here</h2>
	 * The obvious port of the website's resizable watchlist gives the list
	 * its own scroll region. On the website that is right, because its
	 * sidebar is a fixed column in a large window. This sidebar is itself one
	 * tall scrolling strip with the watchlist in the middle of it, and a
	 * scroll region nested inside a scroll region is a trap: rolling the
	 * wheel from the stats at the top down to the finder at the bottom would
	 * be caught by the watchlist and made to grind through every row before
	 * the sidebar resumed. Dozens of times an hour, for a list you were only
	 * scrolling past.
	 *
	 * So the list really does get shorter rather than gaining a scrollbar,
	 * and the rest is one drag away. It is the same idea as the collapse
	 * chevron above — that is 0 rows or all of them; this is the continuum
	 * between.
	 */
	private int visibleRows;
	/** Grab handle under the list. Hidden when the list is collapsed or when
	 *  there is nothing to size. */
	private final JPanel resizeGrip = new ResizeGrip();
	/** Timers driving the 5-day-extreme glow on rows currently shown — every
	 *  {@link #update} throws away the old row panels, so their timers must
	 *  be stopped too or they'd keep ticking (and holding those panels alive)
	 *  forever in the background. */
	private final List<Timer> pulseTimers = new ArrayList<>();
	/** The rows currently on screen, so a settings toggle can rebuild them
	 *  without waiting for the next advisor cycle. */
	private List<Row> lastRows = new ArrayList<>();
	private List<ListMeta> lists = new ArrayList<>();
	private String activeListId;
	/** Whether rows may wear range/spike badges and pulse. Off strips both —
	 *  see {@link #setBadgesEnabled}. */
	private boolean badgesEnabled = true;
	/** Whether a badged row also glows: its border pulsing at a 5-day edge,
	 *  or outlined in a big swing. Off keeps the badges, colours and all. */
	private boolean glowEnabled = true;
	/** Assume logged in until told otherwise, matching AdvisorPanel — a missed
	 *  state event should never leave the watchlist wedged behind a login
	 *  message while the game is running. */
	private boolean loggedIn = true;
	/** The whole interactive half of the section (GE slots, search, list
	 *  chips), hidden as one unit while logged out. */
	private JPanel north;
	private final GeSlotsPanel geSlots;

	public FavoritesPanel(ItemManager itemManager, Actions actions)
	{
		this.itemManager = itemManager;
		this.actions = actions;
		this.geSlots = new GeSlotsPanel(itemManager, new GeSlotsPanel.Actions()
		{
			@Override public void setSlotAdviceSkipped(int slot, boolean skipped) { actions.setSlotAdviceSkipped(slot, skipped); }
			@Override public void inspectItem(int itemId, String name) { actions.inspectItem(itemId, name); }
		});
		setLayout(new BorderLayout(0, 6));
		setOpaque(false);
		/* 2 on top, not 6. The section divider directly above already draws
		   the separation this pad was adding to, so the six pixels were a gap
		   under a line under a gap — and they came straight off the sidebar's
		   scarcest axis. */
		setBorder(BorderFactory.createEmptyBorder(2, 0, 8, 0));

		north = new JPanel();
		north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
		north.setOpaque(false);

		north.add(geSlots);
		/* No strut. GeSlotsPanel carries its own BOTTOM_PAD, so this was a
		   second gap stacked on the first — the search box sat further off
		   the slot grid than the slot rows sit off each other. */
		north.add(searchWrap());

		listBar.setOpaque(false);
		listBar.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
		listBar.add(listBarRight(), BorderLayout.EAST);
		north.add(listBar);
		add(north, BorderLayout.NORTH);

		/*
		 * The collapse control, which rides in the list bar beside the active
		 * list's own dropdown.
		 *
		 * It used to be a line of its own — "▾ Watchlist · 3 of 16" — sitting
		 * between the list bar and the rows. That line was a header for a
		 * section whose name was already written on the chip directly above it
		 * and whose contents were directly below it, so it spent a whole row
		 * of a 225px sidebar restating its neighbours. The count moved to the
		 * drag handle, where it is about something the rows cannot show you;
		 * the chevron moved here, next to the control it belongs with.
		 */
		listToggle.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
		listToggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		listToggle.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				rowsOpen = !rowsOpen;
				rows.setVisible(rowsOpen);
				/* The grip goes with the list. A resize handle under a
				   collapsed list is a control for something that is not
				   there. */
				applyRowLimit();
			}
		});

		rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
		rows.setOpaque(false);
		rows.setVisible(rowsOpen);
		add(rows, BorderLayout.CENTER);
		add(resizeGrip, BorderLayout.SOUTH);
		paintListToggle();
	}

	/* The handle's dots. Main text colour at rest and brand gold under the
	   pointer: "dots at bottom of watchlist are hard to see", then, at 4px
	   in the structural grey, "still too subtle". */
	private static final Color DOTS = Brand.TEXT_MAIN;
	private static final Color DOTS_HOT = Brand.ACCENT_BRIGHT;

	/**
	 * The drag handle under the watchlist: a few pixels tall, with the three
	 * ribs every resizable edge in every toolkit uses, so it reads as one
	 * without needing a caption.
	 *
	 * Dragging is measured in ROWS, not pixels — the thing being resized is
	 * a list of fixed-height items, and a pixel-accurate height that left
	 * half a row showing at the bottom would be worse than useless. It looks
	 * continuous because a row is only about eighteen pixels tall.
	 */
	private class ResizeGrip extends JPanel
	{
		/** Tall enough for a 9px count beside the ribs, and a friendlier drag
		 *  target than the 7px it started at. Fixed regardless of whether the
		 *  count is showing: a handle that changed height the moment the first
		 *  row was hidden would move under the cursor mid-drag. */
		private static final int H = 13;
		/** Three 4px dots on an 8px pitch. Size went 4, 6, 5 and back to 4
		 *  ("make these smaller"): what was too subtle was the old grey, not
		 *  the size, and in the main text colour 4px reads fine. */
		private static final int DOT = 4;
		private static final int PITCH = 8;
		private static final int DOTS_W = 2 * PITCH + DOT;
		private static final int DOTS_GAP = 6;
		private int dragStartY;
		private int dragStartRows;
		/** Under the mouse or being dragged: the dots light up. */
		private boolean hot;
		/** Pressed on the grip and not yet released. The grip moves a whole
		 *  row at a time while the pointer moves smoothly, so mid-drag the
		 *  pointer is off it more often than on it, and the exits that brings
		 *  must not dim the dots in your hand. */
		private boolean pressed;

		ResizeGrip()
		{
			setOpaque(false);
			setCursor(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR));
			setPreferredSize(new Dimension(0, H));
			setMaximumSize(new Dimension(Short.MAX_VALUE, H));
			// Text set per update, since it names the counts — see gripTooltip.
			final java.awt.event.MouseAdapter drag = new java.awt.event.MouseAdapter()
			{
				@Override
				public void mousePressed(java.awt.event.MouseEvent e)
				{
					pressed = true;
					hot = true;
					dragStartY = e.getYOnScreen();
					/* Resolve "all" to a real count at the moment the drag
					   starts. Dragging up from 0 would otherwise jump to
					   whatever -1 clamps to instead of shortening by one. */
					dragStartRows = effectiveRowCount();
				}

				@Override
				public void mouseDragged(java.awt.event.MouseEvent e)
				{
					final int step = rowHeight();
					if (step <= 0)
					{
						return;
					}
					setVisibleRows(dragStartRows + Math.round((e.getYOnScreen() - dragStartY) / (float) step));
				}

				@Override
				public void mouseEntered(java.awt.event.MouseEvent e)
				{
					hot = true;
					repaint();
				}

				@Override
				public void mouseExited(java.awt.event.MouseEvent e)
				{
					if (!pressed)
					{
						hot = false;
						repaint();
					}
				}

				@Override
				public void mouseReleased(java.awt.event.MouseEvent e)
				{
					pressed = false;
					hot = contains(e.getPoint());
					repaint();
				}

				@Override
				public void mouseClicked(java.awt.event.MouseEvent e)
				{
					/* An escape hatch that does not require dragging to the
					   bottom of a long list. */
					if (e.getClickCount() == 2)
					{
						setVisibleRows(0);
					}
				}
			};
			addMouseListener(drag);
			addMouseMotionListener(drag);
		}

		/** The count, with the ribs either side of it.
		 *
		 *  The count belongs HERE rather than on a header, because it is the
		 *  one number the list itself cannot show: the rows you can see say
		 *  nothing about the rows you dragged out of view, and a watchlist
		 *  silently 8 items short is a watchlist you stop trusting.
		 *
		 *  It sat to the right of the dots, 9pt plain, and read as a caption
		 *  that had drifted off something. Ribs on BOTH sides make the three
		 *  pieces one object — a divider with a label in it, which is a shape
		 *  people already know — and the count comes up to 10pt bold so it
		 *  can be read without leaning in. With nothing hidden the ribs close
		 *  up into the plain handle they always were. */
		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			final Graphics2D g2 = (Graphics2D) g;
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			final int hidden = hiddenRowCount();
			final String text = hidden > 0 ? hidden + " more" : null;
			final int mid = getHeight() / 2;

			if (text == null)
			{
				ribs(g2, Math.max(0, (getWidth() - DOTS_W) / 2), mid);
				return;
			}
			g2.setFont(getFont().deriveFont(Font.BOLD, 10f));
			final int textW = g2.getFontMetrics().stringWidth(text);
			final int width = DOTS_W + DOTS_GAP + textW + DOTS_GAP + DOTS_W;
			final int x = Math.max(0, (getWidth() - width) / 2);
			ribs(g2, x, mid);
			g2.setColor(hot ? DOTS_HOT : DOTS);
			g2.drawString(text, x + DOTS_W + DOTS_GAP, mid + g2.getFontMetrics().getAscent() / 2 - 1);
			ribs(g2, x + DOTS_W + DOTS_GAP + textW + DOTS_GAP, mid);
		}

		/** Three 4px dots on an 8px pitch, starting at {@code x}. They were
		 *  2px squares in the border colour, which on this background all but
		 *  disappeared ("dots at bottom of watchlist are hard to see"); now
		 *  the muted text colour, lighter under the mouse. */
		private void ribs(Graphics2D g2, int x, int mid)
		{
			g2.setColor(hot ? DOTS_HOT : DOTS);
			for (int i = 0; i < 3; i++)
			{
				g2.fillOval(x + i * PITCH, mid - DOT / 2, DOT, DOT);
			}
		}
	}

	/** How many rows the drag has taken out of view. 0 when all of them are
	 *  showing, which is also the case the grip prints nothing for. */
	private int hiddenRowCount()
	{
		return visibleRows > 0 ? Math.max(0, lastRows.size() - visibleRows) : 0;
	}

	/** One row's height in pixels, or 0 before the list has been laid out. */
	private int rowHeight()
	{
		for (Component c : rows.getComponents())
		{
			if (c.getHeight() > 0)
			{
				return c.getHeight();
			}
		}
		return 0;
	}

	/** How many rows are on screen right now, resolving 0 ("all") to the real
	 *  number so arithmetic on it behaves. */
	private int effectiveRowCount()
	{
		return visibleRows > 0 ? Math.min(visibleRows, lastRows.size()) : lastRows.size();
	}

	/**
	 * Set the row limit, clamp it, apply it, and tell the plugin to remember
	 * it. Anything at or past the full length stores 0 — "all of them" has to
	 * survive starring a new item, and a limit frozen at today's length would
	 * silently start hiding things tomorrow.
	 */
	private void setVisibleRows(int next)
	{
		final int clamped = next >= lastRows.size() ? 0 : Math.max(MIN_VISIBLE_ROWS, next);
		if (clamped == visibleRows)
		{
			return;
		}
		visibleRows = clamped;
		applyRowLimit();
		if (actions != null)
		{
			actions.setWatchlistRows(clamped);
		}
	}

	/** Restore a remembered limit. Does NOT write back — this is the plugin
	 *  telling the panel, not the panel telling the plugin. */
	public void setWatchlistRows(int n)
	{
		visibleRows = Math.max(0, n);
		applyRowLimit();
	}

	/** What the handle says on hover, which is also the only place the
	 *  numbers appear in full — the painted "8 more" beside the ribs has to
	 *  stay short enough not to crowd them. */
	private String gripTooltip()
	{
		final int hidden = hiddenRowCount();
		return hidden > 0
			? "Showing " + visibleRows + " of " + lastRows.size() + " — drag to change how many,"
				+ " or double-click to show all " + lastRows.size()
			: "Drag to change how many watchlist rows are shown";
	}

	/** Show the first {@link #visibleRows} rows and hide the rest, then hide
	 *  the grip itself when there is nothing left to size. */
	private void applyRowLimit()
	{
		final Component[] all = rows.getComponents();
		/* The "no favorites yet" placeholder is the sole child in the empty
		   case and is not a row; clipping it would leave a blank panel. */
		final boolean real = !lastRows.isEmpty();
		for (int i = 0; i < all.length; i++)
		{
			all[i].setVisible(!real || visibleRows <= 0 || i < visibleRows);
		}
		resizeGrip.setVisible(rowsOpen && real);
		resizeGrip.setToolTipText(gripTooltip());
		paintListToggle();
		revalidate();
		repaint();
	}

	/** The right-hand end of the list bar: the collapse chevron. Rebuilt
	 *  whenever the bar is, since updateLists clears it.
	 *
	 *  There was a "+" here for a new list too: "get rid of the + button on
	 *  plugin for favorites". The dropdown's own "New list…" does the same,
	 *  in the place you go to switch lists anyway. */
	private JPanel listBarRight()
	{
		final JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		right.setOpaque(false);
		right.add(listToggle);
		return right;
	}

	/**
	 * The collapse chevron, sitting immediately right of the list dropdown.
	 *
	 * Open it is a bare chevron: the rows are right there, so a count beside
	 * them would be counting what you can already see. Closed it carries the
	 * total, because then it is the only thing left that knows how much is in
	 * the list — the one job the old "Watchlist \u00b7 3 of 16" line did that
	 * nothing else does.
	 *
	 * Grey and plain against the chip's own coloured, bold chevron, so two
	 * disclosure triangles a few pixels apart still read as two controls
	 * rather than one repeated.
	 */
	private void paintListToggle()
	{
		final int total = lastRows.size();
		listToggle.setText(rowsOpen ? "\u25BE" : ("\u25B8 " + (total > 0 ? String.valueOf(total) : "")));
		listToggle.setForeground(Brand.TEXT_STRUCTURAL);
		listToggle.setFont(listToggle.getFont().deriveFont(Font.PLAIN, 11f));
		listToggle.setToolTipText(rowsOpen
			? "Hide the watchlist" + (total > 0 ? " (" + total + " items)" : "")
			: "Show the watchlist");
	}

	/** Search-to-add box — matches the website's own search bar: type an item
	 *  name, get live matches, click one to add it to the active list without
	 *  ever having to find it as a suggestion first. A plain JTextField has
	 *  no native placeholder, so the classic focus-listener swap stands in
	 *  for one. */
	private JPanel searchWrap()
	{
		JPanel wrap = new JPanel(new BorderLayout());
		wrap.setOpaque(false);
		wrap.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));

		searchField.setText(SEARCH_PLACEHOLDER);
		/* The last neutral rectangle over a warm list. Its foreground is set
		   below and swapped on focus, so control() is told to leave it. */
		Brand.control(searchField, null);
		searchField.setForeground(Brand.TEXT_STRUCTURAL);
		searchField.setToolTipText("Search any tradeable item: click a result to add it to this list and open it in the card");

		/*
		 * The results list must never take the keyboard.
		 *
		 * A JPopupMenu is focusable by default, and one long enough to fall
		 * outside its parent window is realised as a heavyweight window of
		 * its own — which takes focus the moment it is shown. So typing
		 * "swo" produced a list and then swallowed every key after it: the
		 * field no longer had focus, and the keys were going to the menu's
		 * own type-to-select navigation. Reported as "I can't finish typing
		 * once the results have filled".
		 *
		 * Non-focusable is the whole fix — Swing creates a non-focusable
		 * popup window for it, and focus simply stays where it was. The
		 * re-request after show() below is belt and braces for the
		 * lightweight path.
		 */
		searchResults.setFocusable(false);
		searchField.addKeyListener(new java.awt.event.KeyAdapter()
		{
			@Override
			public void keyPressed(java.awt.event.KeyEvent e)
			{
				if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ESCAPE)
				{
					searchResults.setVisible(false);
					return;
				}
				/* Enter takes the top match, which is what the list is
				   ordered for. Without it the only way to finish a search is
				   to stop typing and reach for the mouse — and the keyboard
				   is where you already are. */
				if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ENTER
					&& searchResults.isVisible() && searchResults.getComponentCount() > 0
					&& searchResults.getComponent(0) instanceof JMenuItem)
				{
					((JMenuItem) searchResults.getComponent(0)).doClick();
					e.consume();
				}
			}
		});
		searchField.addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusGained(FocusEvent e)
			{
				if (searchField.getText().equals(SEARCH_PLACEHOLDER))
				{
					searchField.setText("");
					searchField.setForeground(Color.WHITE);
				}
			}

			@Override
			public void focusLost(FocusEvent e)
			{
				if (searchField.getText().trim().isEmpty())
				{
					searchField.setText(SEARCH_PLACEHOLDER);
					searchField.setForeground(Brand.TEXT_STRUCTURAL);
				}
			}
		});
		searchField.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override public void insertUpdate(DocumentEvent e) { scheduleSearch(); }
			@Override public void removeUpdate(DocumentEvent e) { scheduleSearch(); }
			@Override public void changedUpdate(DocumentEvent e) { scheduleSearch(); }
		});
		wrap.add(searchField, BorderLayout.CENTER);
		return wrap;
	}

	/** Debounces keystrokes so every character typed doesn't fire its own
	 *  client-thread search — 200ms of quiet before actually searching. */
	private void scheduleSearch()
	{
		if (searchDebounce != null)
		{
			searchDebounce.stop();
		}
		final String query = searchField.getText();
		searchDebounce = new Timer(200, e -> runSearch(query));
		searchDebounce.setRepeats(false);
		searchDebounce.start();
	}

	private void runSearch(String query)
	{
		if (query == null || query.trim().length() < 2)
		{
			searchResults.setVisible(false);
			return;
		}
		actions.searchItems(query.trim(), this::showSearchResults);
	}

	/** actions.searchItems() guarantees this runs on the EDT. */
	private void showSearchResults(List<SearchResult> results)
	{
		searchResults.removeAll();
		if (results == null || results.isEmpty())
		{
			searchResults.setVisible(false);
			return;
		}
		for (SearchResult r : results)
		{
			JMenuItem item = new JMenuItem(r.name);
			item.addActionListener(e ->
			{
				/* Add it to the list, and open it in the card at once: a
				   search that only added an item looked like it did nothing
				   at all when the item was already on the list, and what you
				   searched for is what you want to look at. */
				actions.addFavorite(r.id, r.name);
				actions.inspectItem(r.id, r.name);
				searchField.setText("");
				searchResults.setVisible(false);
				searchField.requestFocusInWindow();
			});
			searchResults.add(item);
		}
		/* Built above either way, DISPLAYED only when the field is on screen.
		   The two used to be one check, which meant the list could not be
		   built at all without a real window — and the only thing the check
		   was ever guarding was popping a menu open over a panel nobody is
		   looking at. Enter still has a top match to take. */
		if (!searchField.isShowing())
		{
			return;
		}
		if (searchResults.isVisible())
		{
			/* Already up: resize it in place rather than show() it again.
			   Re-showing on every keystroke tears the popup window down and
			   builds a new one, which flickers and hands focus around at
			   exactly the rate someone is typing. */
			searchResults.pack();
			searchResults.revalidate();
			searchResults.repaint();
		}
		else
		{
			searchResults.show(searchField, 0, searchField.getHeight());
		}
		/* The caret belongs in the field, always — the list is something you
		   read while you keep typing, not something you move into. */
		searchField.requestFocusInWindow();
	}

	/** The 8-square GE offer-slot status strip above the search box. Call on
	 *  the EDT whenever the plugin recomputes advice/offer state. */
	public void updateGeSlots(GeSlotsPanel.SlotInfo[] slots)
	{
		geSlots.update(slots);
	}

	/** See GeSlotsPanel.pop. EDT. */
	public void popSlot(int slot, long gp, boolean coins)
	{
		geSlots.pop(slot, gp, coins);
	}

	/** See GeSlotsPanel.clearPops. EDT. */
	public void clearSlotPops()
	{
		geSlots.clearPops();
	}

	/** See GeSlotsPanel.squaresShowing. Any thread. */
	public boolean slotsShowing()
	{
		return geSlots.squaresShowing();
	}

	/** Rebuild the list-switcher chip row. Call on the Swing EDT whenever the
	 *  set of lists, the active one, or any name/color changes. */
	public void updateLists(List<ListMeta> lists, String activeListId)
	{
		this.lists = lists != null ? lists : new ArrayList<>();
		this.activeListId = activeListId;
		listBar.removeAll();
		// One dropdown showing the ACTIVE list, not a chip per list. Laying
		// every list out inline spent the sidebar's whole width on lists you
		// aren't looking at, and got worse with each one created — the point
		// of multiple watchlists is that only one is on screen at a time.
		final ListMeta active = activeList();
		if (active != null)
		{
			listBar.add(listDropdown(active), BorderLayout.CENTER);
		}
		/* No LINKED chip here.
		   It mirrored the website's own badge, which made sense while the
		   link had no other presence in the plugin — but the top strip now
		   carries a link BUTTON that shows the same state and is the thing
		   you press to change it. Two indicators for one boolean, and this
		   was the one you could not act on, sitting in the row that names
		   your watchlist. */
		listBar.add(listBarRight(), BorderLayout.EAST);
		/* The chevron is a live component that moved into a panel this method
		   rebuilds, so its text has to be re-applied after every rebuild or a
		   list switch would blank it. */
		paintListToggle();
		listBar.revalidate();
		listBar.repaint();
	}

	/** The active list, as a dropdown. Left-click opens the switcher (every
	 *  list, current one checked); right-click opens rename, recolour and
	 *  delete for the list currently shown. Text stays left-aligned since it
	 *  stretches to fill the row, so it reads as a header rather than a
	 *  mis-centered button.
	 *
	 *  Its arrow at the right-hand end, where a dropdown's arrow goes and
	 *  where every other chevron in the panel sits: "move the chevron to the
	 *  right like every other chevron". It sat straight after the name.
	 *  Painted rather than typed, so it stays at the edge however wide the
	 *  bar is; the border keeps the room for it, so a long name stops short
	 *  of it instead of running under it. */
	private JButton listDropdown(ListMeta l)
	{
		JButton chip = new JButton("\u25CF " + l.name)
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				super.paintComponent(g);
				final Graphics2D g2 = (Graphics2D) g.create();
				try
				{
					g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
					g2.setFont(getFont());
					g2.setColor(getForeground());
					final java.awt.FontMetrics fm = g2.getFontMetrics();
					g2.drawString(DROPDOWN_ARROW, getWidth() - DROPDOWN_ARROW_INSET - fm.stringWidth(DROPDOWN_ARROW),
						(getHeight() - fm.getHeight()) / 2 + fm.getAscent());
				}
				finally
				{
					g2.dispose();
				}
			}
		};
		chip.setToolTipText("Switch list or change its colour — right-click to rename, recolour or delete \"" + l.name + "\"");
		chip.setFocusPainted(false);
		chip.setOpaque(true);
		chip.setContentAreaFilled(true);
		chip.setBorderPainted(true);
		chip.setHorizontalAlignment(SwingConstants.LEFT);
		chip.setFont(chip.getFont().deriveFont(Font.BOLD, 12f));
		chip.setMargin(new java.awt.Insets(3, 7, 3, 7));
		chip.setForeground(Color.decode(l.color));
		chip.setBackground(HOVER_BG);
		chip.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Color.decode(l.color), 1),
			BorderFactory.createEmptyBorder(0, 0, 0, DROPDOWN_ARROW_INSET
				+ chip.getFontMetrics(chip.getFont()).stringWidth(DROPDOWN_ARROW) + 4)));
		chip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		chip.addActionListener(e -> showListSwitcher(chip));
		chip.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e) { maybeShowMenu(e); }

			@Override
			public void mouseReleased(MouseEvent e) { maybeShowMenu(e); }

			private void maybeShowMenu(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					listMenu(l).show(chip, e.getX(), e.getY());
				}
			}
		});
		return chip;
	}

	/**
	 * One list's own menu: rename it, recolour it, delete it. The dropdown's
	 * right-click opens it for the list shown, and a right-click on any list
	 * in the switcher opens it for that one.
	 *
	 * Its dialogs belong to this panel, not to the dropdown: the dropdown is
	 * rebuilt on every refresh (an inventory change, a price poll), and one
	 * thrown away while the menu was open left the rename box and the delete
	 * confirmation with no window to sit over.
	 */
	JPopupMenu listMenu(ListMeta l)
	{
		final Component parent = this;
		final JPopupMenu menu = new JPopupMenu();
		final JMenuItem rename = new JMenuItem("Rename list…");
		rename.addActionListener(a ->
		{
			String name = javax.swing.JOptionPane.showInputDialog(parent, "List name:", l.name);
			if (name != null && !name.trim().isEmpty())
			{
				actions.renameList(l.id, name.trim());
			}
		});
		menu.add(rename);
		/* Named dots, not hex codes: a dot and "#E5C158" told you the colour
		   only by the glyph's tint, and the text beside it was a number
		   nobody picks a colour by. */
		final JMenu colorMenu = new JMenu("Colour");
		for (int i = 0; i < FavoriteLists.PALETTE.length; i++)
		{
			final String hex = FavoriteLists.PALETTE[i];
			final JMenuItem swatch = new JMenuItem(FavoriteLists.PALETTE_NAMES[i],
				new Swatch(Color.decode(hex), hex.equalsIgnoreCase(l.color)));
			swatch.addActionListener(a -> actions.recolorList(l.id, hex));
			colorMenu.add(swatch);
		}
		menu.add(colorMenu);
		menu.addSeparator();
		final JMenuItem delete = new JMenuItem("Delete list…");
		final boolean deletable = lists.size() > 1;
		delete.setEnabled(deletable);
		if (!deletable)
		{
			delete.setToolTipText("There has to be at least one list");
		}
		/* Asked first. A list is all of its items, and this used to go on
		   one click from a menu that opens on a right-click. */
		delete.addActionListener(a ->
		{
			final int ok = javax.swing.JOptionPane.showConfirmDialog(parent,
				"Delete the list \"" + l.name + "\" and the items in it?", "Delete list",
				javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE);
			if (ok == javax.swing.JOptionPane.OK_OPTION)
			{
				actions.deleteList(l.id);
			}
		});
		menu.add(delete);
		return menu;
	}

	/**
	 * One list in the switcher. Left-click switches to it; right-click opens
	 * that list's own menu — "make it so you can do it from here".
	 *
	 * Not a plain JMenuItem, because Swing fires a menu item on the release
	 * of ANY button: a right-click would switch lists as well as opening the
	 * menu. The right button never reaches the item's own handling here; its
	 * release closes the switcher and opens the list's menu where it was.
	 */
	final class ListItem extends JMenuItem
	{
		private final ListMeta list;
		private final JPopupMenu switcher;

		ListItem(ListMeta list, boolean active, JPopupMenu switcher)
		{
			super((active ? "✓ " : " ") + list.name);
			this.list = list;
			this.switcher = switcher;
			setForeground(Color.decode(list.color));
			if (active)
			{
				setFont(getFont().deriveFont(Font.BOLD));
			}
			setToolTipText("Click to switch — right-click to rename, recolour or delete");
			addActionListener(a -> actions.selectList(list.id));
		}

		@Override
		protected void processMouseEvent(MouseEvent e)
		{
			final int id = e.getID();
			final boolean button = id == MouseEvent.MOUSE_PRESSED || id == MouseEvent.MOUSE_RELEASED
				|| id == MouseEvent.MOUSE_CLICKED;
			if (button && (e.getButton() == MouseEvent.BUTTON3 || SwingUtilities.isRightMouseButton(e)))
			{
				if (id == MouseEvent.MOUSE_RELEASED && contains(e.getPoint()))
				{
					openListMenu();
				}
				return;
			}
			super.processMouseEvent(e);
		}

		/** The switcher shut, and the list's menu open where the item was.
		 *  Placed against this panel, not the dropdown the switcher opened
		 *  from: a refresh while the switcher is open replaces the dropdown,
		 *  and a menu placed against the old one never appeared. */
		void openListMenu()
		{
			final Component host = FavoritesPanel.this;
			final java.awt.Point at = isShowing() && host.isShowing()
				? SwingUtilities.convertPoint(this, 0, getHeight() / 2, host) : null;
			switcher.setVisible(false);
			if (at != null)
			{
				listMenu(list).show(host, at.x, at.y);
			}
		}
	}

	/** Every list, current one marked — plus a "New list…" tail, which is
	 *  where a new list is made: the place you go to switch lists anyway. */
	private void showListSwitcher(JButton anchor)
	{
		final JPopupMenu menu = new JPopupMenu();
		for (ListMeta l : lists)
		{
			menu.add(new ListItem(l, l.id.equals(activeListId), menu));
		}
		final ListMeta current = activeList();
		if (current != null)
		{
			menu.addSeparator();
			menu.add(colourRow(current, menu));
		}
		menu.addSeparator();
		JMenuItem create = new JMenuItem("New list…");
		create.addActionListener(a -> promptNewList(this));
		menu.add(create);
		/* Says so, because nothing else would: nobody tries a right-click on
		   a menu item ("make it show you can right click delete a list"). */
		final JLabel hint = new JLabel("Right-click a list: rename, colour, delete");
		hint.setForeground(Brand.TEXT_STRUCTURAL);
		hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 10.5f));
		hint.setBorder(BorderFactory.createEmptyBorder(4, 8, 3, 8));
		/* Centred like every other row of the menu, and free to fill it. A
		   label is left-aligned by default, and the menu's box layout, given
		   one row out of step, made itself wider by half its widest item and
		   pushed the hint to the right edge. */
		hint.setAlignmentX(Component.CENTER_ALIGNMENT);
		hint.setMaximumSize(new Dimension(Integer.MAX_VALUE, hint.getPreferredSize().height));
		menu.add(hint);
		menu.show(anchor, 0, anchor.getHeight());
	}

	/** The list the dropdown is showing: the active one, or the first when
	 *  the active id names no list. */
	private ListMeta activeList()
	{
		for (ListMeta l : lists)
		{
			if (l.id.equals(activeListId))
			{
				return l;
			}
		}
		return lists.isEmpty() ? null : lists.get(0);
	}

	/**
	 * The palette as dots, in the switcher, for the list it is showing.
	 *
	 * "make it so you can select the color of these lists". You always could,
	 * but only from the dropdown's RIGHT-click, a menu nothing points to, in
	 * a submenu of hex codes. The switcher is where the lists' colours are
	 * on show, so that is where choosing one goes. One click on a dot, and
	 * the menu closes on the new colour.
	 */
	JPanel colourRow(ListMeta l, JPopupMenu menu)
	{
		final JPanel wrap = new JPanel(new BorderLayout(0, 3));
		wrap.setOpaque(false);
		wrap.setBorder(BorderFactory.createEmptyBorder(3, 8, 4, 8));
		final String name = l.name.length() > 18 ? l.name.substring(0, 17) + "\u2026" : l.name;
		final JLabel caption = new JLabel("Colour of " + name);
		caption.setForeground(Brand.TEXT_STRUCTURAL);
		caption.setFont(caption.getFont().deriveFont(Font.PLAIN, 11f));
		wrap.add(caption, BorderLayout.NORTH);
		final int across = 6;
		final JPanel dots = new JPanel(new java.awt.GridLayout(
			(FavoriteLists.PALETTE.length + across - 1) / across, across, 3, 3));
		dots.setOpaque(false);
		for (int i = 0; i < FavoriteLists.PALETTE.length; i++)
		{
			final String hex = FavoriteLists.PALETTE[i];
			final JLabel dot = new JLabel(new Swatch(Color.decode(hex), hex.equalsIgnoreCase(l.color)));
			dot.setToolTipText(FavoriteLists.PALETTE_NAMES[i]);
			dot.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			/* On the release, and only if it lands on the dot: a click
			   proper is lost to the smallest wobble between press and
			   release, and these are small targets. */
			dot.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseReleased(MouseEvent e)
				{
					if (SwingUtilities.isLeftMouseButton(e) && dot.contains(e.getPoint()))
					{
						menu.setVisible(false);
						actions.recolorList(l.id, hex);
					}
				}
			});
			dots.add(dot);
		}
		/* WEST, so the dots keep their own size at the left rather than
		   spreading across however wide the longest list name made the menu. */
		wrap.add(dots, BorderLayout.WEST);
		return wrap;
	}

	/** A palette colour as a filled dot, ringed when it is the list's own.
	 *  Painted rather than a "\u25cf" glyph, so it is the same size whatever
	 *  font the menu falls back to. */
	static final class Swatch implements javax.swing.Icon
	{
		private static final int D = 12;
		private static final int PAD = 3;
		private final Color colour;
		private final boolean current;

		Swatch(Color colour, boolean current)
		{
			this.colour = colour;
			this.current = current;
		}

		boolean current()
		{
			return current;
		}

		@Override
		public int getIconWidth()
		{
			return D + 2 * PAD;
		}

		@Override
		public int getIconHeight()
		{
			return D + 2 * PAD;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y)
		{
			final Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(colour);
			g2.fillOval(x + PAD, y + PAD, D, D);
			if (current)
			{
				g2.setColor(Brand.TEXT_MAIN);
				g2.setStroke(new java.awt.BasicStroke(1.5f));
				g2.draw(new java.awt.geom.Ellipse2D.Float(x + 0.75f, y + 0.75f,
					D + 2 * PAD - 1.5f, D + 2 * PAD - 1.5f));
			}
			g2.dispose();
		}
	}

	private void promptNewList(java.awt.Component parent)
	{
		String name = javax.swing.JOptionPane.showInputDialog(parent, "New list name:", "Watchlist");
		if (name != null && !name.trim().isEmpty())
		{
			actions.createList(name.trim());
		}
	}

	/**
	 * Turn the range/spike badges and the row glow on or off.
	 *
	 * Both together, on purpose. The chip and the pulse are two renderings of
	 * one signal, and "quiet list" is what was asked for — a list that still
	 * breathes at you, just without saying why, is the worst of the two.
	 *
	 * Rebuilds immediately off the last rows rather than waiting for the next
	 * advisor cycle, which can be a minute away: a settings toggle that
	 * appears to do nothing for 60 seconds gets clicked again.
	 */
	public void setBadgesEnabled(boolean enabled)
	{
		if (this.badgesEnabled == enabled)
		{
			return;
		}
		this.badgesEnabled = enabled;
		rebuild(lastRows);
	}

	/** The rows' glow borders, on or off, rebuilt at once as the badges
	 *  switch is. The badges keep their colours either way. */
	public void setGlowEnabled(boolean enabled)
	{
		if (this.glowEnabled == enabled)
		{
			return;
		}
		this.glowEnabled = enabled;
		rebuild(lastRows);
	}

	/**
	 * Hide the watchlist behind a login message, the way the advisor box
	 * already is.
	 *
	 * A live-looking list under "Log in to the game" was incoherent, and it
	 * was not merely cosmetic: a Row carries what you HOLD of that item
	 * (fillHeldPosition — quantity, cost basis, what the stack would clear),
	 * and logged out there is no bank or inventory to read, so every one of
	 * those fields is silently empty. A row for a stack you own 11,000 of
	 * looked exactly like a row for something you have never touched. The
	 * prices and range badges were right; the holdings half was blank and
	 * said nothing about being blank.
	 *
	 * The GE slot strip, the search box and the list chips go with it: eight
	 * empty slots and an "add to this list" box above a list that is not shown
	 * are the same incoherence in smaller print.
	 */
	public void setLoggedIn(boolean loggedIn)
	{
		if (this.loggedIn == loggedIn)
		{
			return;
		}
		this.loggedIn = loggedIn;
		rebuild(lastRows);
	}

	/**
	 * Show these rows. Call on the Swing EDT.
	 *
	 * Rebuilt only when something on them has changed. Every inventory change
	 * lands here — each shark cooked, every few seconds — and a rebuild makes
	 * fresh rows whose glow and spike borders were bare until their timers'
	 * first tick, so the whole list flashed on every one: "every time i cook
	 * a shark, it flashes the colors on the side bar". Unchanged rows now
	 * leave the list as it is, glows and all.
	 */
	public void update(List<Row> favoriteRows)
	{
		final List<Row> next = favoriteRows != null ? favoriteRows : new ArrayList<>();
		if (built && sameRows(next, lastRows) && HIGH5D.equals(builtHigh) && LOW5D.equals(builtLow))
		{
			lastRows = next;
			return;
		}
		rebuild(next);
	}

	/** Whether the rows were built yet, and in which glow colours. */
	private boolean built;
	private Color builtHigh;
	private Color builtLow;

	/** Two lists of rows that would draw, and act, the same. */
	static boolean sameRows(List<Row> a, List<Row> b)
	{
		if (a == b)
		{
			return true;
		}
		if (a == null || b == null || a.size() != b.size())
		{
			return false;
		}
		for (int i = 0; i < a.size(); i++)
		{
			if (!sameRow(a.get(i), b.get(i)))
			{
				return false;
			}
		}
		return true;
	}

	/** Every field of a Row: the list draws some of them and a click hands
	 *  the rest to the inspection card. */
	static boolean sameRow(Row a, Row b)
	{
		if (a == b)
		{
			return true;
		}
		if (a == null || b == null)
		{
			return false;
		}
		return a.id == b.id && java.util.Objects.equals(a.name, b.name) && a.price == b.price
			&& Double.compare(a.changePct, b.changePct) == 0 && a.tier == b.tier
			&& a.targetBuy == b.targetBuy && a.targetSell == b.targetSell && a.heldSell == b.heldSell
			&& a.potentialProfit == b.potentialProfit
			&& a.limit == b.limit && a.limitUsed == b.limitUsed && a.limitResetsAt == b.limitResetsAt
			&& a.dailyVolume == b.dailyVolume && a.heldQty == b.heldQty
			&& java.util.Objects.equals(a.heldWhere, b.heldWhere) && a.listedQty == b.listedQty
			&& a.listedSold == b.listedSold && a.listedTotal == b.listedTotal && a.listedPrice == b.listedPrice
			&& a.bidQty == b.bidQty && a.bidFilled == b.bidFilled && a.bidPrice == b.bidPrice
			&& a.sellValue == b.sellValue && a.heldProfit == b.heldProfit && sameClearance(a.clearance, b.clearance)
			&& a.hasCostBasis == b.hasCostBasis && a.pricedQty == b.pricedQty && a.pricedCost == b.pricedCost;
	}

	private static boolean sameClearance(Clearance a, Clearance b)
	{
		if (a == null || b == null)
		{
			return a == b;
		}
		return a.qty == b.qty && a.flowPerDay == b.flowPerDay && Double.compare(a.days, b.days) == 0;
	}

	/** Build the list afresh from {@code favoriteRows}. */
	private void rebuild(List<Row> favoriteRows)
	{
		lastRows = favoriteRows;
		built = true;
		builtHigh = HIGH5D;
		builtLow = LOW5D;
		stopGlowTimers();
		rows.removeAll();
		north.setVisible(loggedIn);
		if (!loggedIn)
		{
			/* Silent, not captioned. The advisor's banner above already says
			   the one thing there is to say; repeating it here made the
			   logged-out sidebar a column of near-identical apologies. */
			resizeGrip.setVisible(false); // nothing to size; applyRowLimit is not reached
			revalidate();
			repaint();
			return;
		}
		if (favoriteRows.isEmpty())
		{
			JLabel empty = new JLabel("<html><center>No favorites yet.<br>Tap the star on a suggestion or flip to add one.</center></html>");
			empty.setForeground(Brand.TEXT_STRUCTURAL);
			empty.setFont(empty.getFont().deriveFont(12f));
			rows.add(empty);
		}
		// One child per row, no separate strut spacers — drag-to-reorder needs
		// rows.getComponents() to map 1:1 to favoriteRows' indices. The 2px
		// gap a strut used to provide is now baked into each row's own
		// bottom border instead (see row() / wirePulse()).
		for (int i = 0; i < favoriteRows.size(); i++)
		{
			rows.add(row(favoriteRows.get(i)));
		}
		for (java.awt.Component c : rows.getComponents())
		{
			final Object paint = c instanceof javax.swing.JComponent
				? ((javax.swing.JComponent) c).getClientProperty(PAINT_NOW) : null;
			if (paint instanceof Runnable)
			{
				((Runnable) paint).run();
			}
		}
		/* Re-apply after every rebuild, and it does the revalidate/repaint —
		   the rows are fresh components that know nothing about the limit. */
		applyRowLimit();
	}

	/** Matches the website's own .wl-item: name + price always visible, kept
	 *  lean by only revealing remove on hover (its .wl-fav-remove is
	 *  opacity:0 until :hover the same way) instead of permanently eating
	 *  row width — that's what was crushing names down to 4-5 characters. */
	private JPanel row(Row r)
	{
		// One line, readable: picture, name, and a 5-day extreme badge.
		// The LAST/EA/VOL stats line this briefly carried was 10px text in a
		// 225px sidebar — legible in a mockup, not in a running client. Those
		// numbers are one click away in the inspection card, which has the
		// room to show them at a readable size. Hover-only remove keeps the
		// name from being crushed to 4-5 characters the rest of the time.
		JPanel p = new JPanel(new BorderLayout(6, 0));
		p.setBackground(Brand.BG_PANEL);
		// Bottom padding carries the 2px gap a separate strut component used
		// to provide — dropped so rows.getComponents() maps 1:1 to
		// favoriteRows' indices, which drag-to-reorder depends on.
		p.setBorder(BorderFactory.createEmptyBorder(4, 7, 6, 6));

		p.add(iconLabel(r.id), BorderLayout.WEST);

		JPanel nameWrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		nameWrap.setOpaque(false);
		JLabel name = new JLabel(truncateName(r.name));
		/* Holdings live on the tooltip now, not on a second line. "8,917 ×
		   748 gp" under every stack you owned turned the watchlist into a
		   wall of digits, and the one thing this list is for — which items
		   are at an actionable price right now — got buried under arithmetic
		   you can do in the inspection card. The number is still worth
		   having, just on demand: hover the name. */
		name.setToolTipText(r.heldQty > 0 ? heldTooltip(r) : r.name);
		name.setForeground(Color.WHITE);
		name.setFont(name.getFont().deriveFont(13f));
		nameWrap.add(name);
		p.add(nameWrap, BorderLayout.CENTER);

		// Says in words what the pulsing border says in colour. The glow
		// alone can't be read at a glance once several rows are pulsing at
		// once, and it's invisible in a screenshot.
		final JPanel right = new JPanel(new BorderLayout(4, 0));
		right.setOpaque(false);
		/* The range badge owns this slot. The holdings tag used to take it and
		   lost on both counts: every held row read "\u2026 to sell", so the badges
		   stopped distinguishing anything, while the one signal that DOES vary
		   row to row \u2014 where the price sits in its own range \u2014 got crowded
		   out. Holdings are on the name's tooltip now. */
		final JLabel badge = badgeFor(r);
		if (badge != null)
		{
			right.add(badge, BorderLayout.CENTER);
		}
		p.add(right, BorderLayout.EAST);

		// Just the remove button now — reordering is drag-and-drop on the row
		// itself (see wireSelect), not a pair of ▲/▼ buttons. Those buttons
		// only ever got ADDED to `right` on hover, after the row's own
		// mouse listeners were already attached to its build-time children;
		// entering them (a component with no listener of its own) fired the
		// row's mouseExited — same background/click as leaving the row
		// entirely — which yanked the buttons back out from under the
		// cursor before a click could land. Drag sidesteps the whole class
		// of bug: no new component ever has to be entered mid-interaction.
		final JPanel actionsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
		actionsPanel.setOpaque(false);
		JButton remove = new JButton("×");
		remove.setToolTipText("Remove " + r.name + " from favorites");
		remove.setMargin(new java.awt.Insets(0, 4, 0, 4));
		remove.addActionListener(e -> actions.remove(r.id));
		actionsPanel.add(remove);

		wireSelect(p, Brand.BG_PANEL, r, right, actionsPanel);
		/* Only the multi-day tiers glow. The day tier deliberately does not:
		   an item touches its own daily edge constantly, and a row that
		   breathes all day long stops meaning "look at this". */
		if (!badgesEnabled)
		{
			/* Nothing to wire. Left as its own branch rather than folded into
			   the conditions below so it reads as the switch it is. */
		}
		else if (Math.abs(r.changePct) >= SPIKE_THRESHOLD_PCT)
		{
			/* One timer drives the border AND the badge. Two would not just
			   cost twice the ticks; they would be free to drift out of phase,
			   and the whole point of giving the badge the border's palette is
			   that the two are the same colour at the same instant. */
			wireSpikeGlow(p, r.changePct >= 0, badge, glowEnabled);
		}
		else if (glowEnabled && r.tier != PriceExtremes.Tier.NONE
			&& r.tier != PriceExtremes.Tier.HIGH_1D && r.tier != PriceExtremes.Tier.LOW_1D)
		{
			wirePulse(p, r.tier.isHigh() ? HIGH5D : LOW5D);
		}
		return p;
	}

	/**
	 * What you hold, what it would clear, and whether that beats what you
	 * paid — the whole holdings story, on the item name's tooltip.
	 *
	 * This used to be a visible second line reading "11 \u00D7 6.79M gp". It is
	 * a tooltip now because the watchlist is where you scan for something to
	 * act on, and a quantity \u00D7 price on every stack you happen to own
	 * drowned that out. Hover keeps the number reachable without it competing
	 * with the range badges for the eye.
	 *
	 * Sale value here is the after-tax figure, and says so. The old headline
	 * deliberately showed the pre-tax unit price instead, so it would agree
	 * with what pocketge.com prints for the same item; with no headline left
	 * to match, the honest number wins.
	 */
	/**
	 * The badge for a row, or null when nothing is worth saying.
	 *
	 * Three weights, so the signal is legible without reading the words:
	 * <ul>
	 *   <li>spike — the live price is 15%+ away from its price a day ago.
	 *       Rare, and the only badge that animates: it cycles the same
	 *       palette as the row's border glow, green/gold climbing for a move
	 *       up and red/magenta for one down, so the colour says the
	 *       direction even before the number is read.
	 *   <li>5-day — bright text, in the top or bottom 8% of the 5-day range.
	 *   <li>1-day — a bare ▲ or ▼ in a muted shade. No label at all:
	 *       the arrow is the whole message, and spelling out "1D HIGH" on a
	 *       signal this common costs more width than it is worth.
	 * </ul>
	 * There is no 30-day tier. There was, briefly, and it fired on almost
	 * every row — see PriceExtremes.Tier for the arithmetic reason a wider
	 * window makes a percentage band commoner rather than rarer.
	 *
	 * Green means high and gold means low at every tier, here and on the
	 * website, so the colour never has to be re-learned per badge.
	 */
	private JLabel badgeFor(Row r)
	{
		if (!badgesEnabled)
		{
			return null;
		}
		if (Math.abs(r.changePct) >= SPIKE_THRESHOLD_PCT)
		{
			return spikeBadge(r.changePct);
		}
		return tierBadge(r.tier);
	}

	/**
	 * The website's multicolour "big swing" chip: how far the live price has
	 * run from its price a day ago, animated.
	 *
	 * This outranks the range tiers outright, exactly as the site's own
	 * dayState does — a 20% move in a day is a different kind of event from
	 * sitting near a 5-day edge, and while both are true the move is the one
	 * worth looking at. The row was already glowing for this; the number was
	 * the missing half, and a glow alone cannot be read in a screenshot or
	 * told apart once several rows are pulsing at once.
	 */
	private JLabel spikeBadge(double pct)
	{
		final boolean up = pct >= 0;
		final JLabel badge = new JLabel((up ? "▲ +" : "▼ ") + String.format("%.0f%%", pct));
		badge.setFont(badge.getFont().deriveFont(Font.BOLD, 10.5f));
		badge.setToolTipText(String.format(
			up ? "Spiking \u2014 up %.1f%% on the day"
				: "Spiking \u2014 down %.1f%% on the day", Math.abs(pct)));
		/* Colour comes from wireSpikeGlow, which owns this row's one timer. */
		return badge;
	}

	private static JLabel tierBadge(PriceExtremes.Tier tier)
	{
		if (tier == null || tier == PriceExtremes.Tier.NONE)
		{
			return null;
		}
		final boolean high = tier.isHigh();
		final String text;
		final String why;
		if (tier == PriceExtremes.Tier.HIGH_5D || tier == PriceExtremes.Tier.LOW_5D)
		{
			text = high ? "▲ 5D HIGH" : "▼ 5D LOW";
			why = high
				? "In the top 8% of its 5-day price range"
				: "In the bottom 8% of its 5-day price range";
		}
		else
		{
			text = high ? "▲" : "▼";
			why = high
				? "At or above its 24-hour high \u2014 sell now to catch the peak"
				: "At or below its 24-hour low \u2014 buy now to catch the dip";
		}

		final JLabel badge = new JLabel(text);
		badge.setFont(badge.getFont().deriveFont(Font.BOLD, 10.5f));
		badge.setToolTipText(why);
		if (tier == PriceExtremes.Tier.HIGH_5D || tier == PriceExtremes.Tier.LOW_5D)
		{
			badge.setForeground(high ? HIGH5D : LOW5D);
		}
		else
		{
			badge.setForeground(high ? HIGH1D : LOW1D);
		}
		return badge;
	}

	private static String heldTooltip(Row r)
	{
		/* Names the item too, because this tooltip REPLACES the plain name
		   tooltip on a held row — and that tooltip is the only way to read a
		   name the row had to truncate. Escaped because Swing parses the
		   whole string as HTML: no live OSRS item name contains an ampersand
		   or an angle bracket today, but one that did would silently swallow
		   the rest of the tooltip. */
		final String safeName = r.name == null ? ""
			: r.name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
		final StringBuilder sb = new StringBuilder("<html><b>").append(safeName)
			.append("</b><br>You hold <b>")
			.append(QuantityFormatter.quantityToStackSize(r.heldQty)).append("</b>.");
		if (r.sellValue > 0)
		{
			sb.append("<br>Selling the lot clears <b>")
				.append(QuantityFormatter.quantityToStackSize(r.sellValue))
				.append(" gp</b> after the 2% tax.");
		}
		if (r.hasCostBasis && r.heldProfit != 0)
		{
			final boolean partial = r.pricedQty > 0 && r.pricedQty < r.heldQty;
			/* The size, then the direction in words — "-550K gp less" was a
			   double negative. */
			sb.append("<br>That is <b>").append(QuantityFormatter.quantityToStackSize(Math.abs(r.heldProfit)))
				.append(" gp</b> ").append(r.heldProfit >= 0 ? "more" : "less").append(" than you paid")
				.append(partial ? " for the " + QuantityFormatter.quantityToStackSize(r.pricedQty)
					+ " the plugin watched you buy." : ".");
		}
		else if (r.heldQty > 0)
		{
			sb.append("<br>No purchase on record, so there is no profit to measure.");
		}
		return sb.append("</html>").toString();
	}


	/** Same 16-char cutoff the Top Suggestion card uses — the full name
	 *  is always still reachable via the tooltip. */
	private static String truncateName(String name)
	{
		if (name == null)
		{
			return "";
		}
		final int max = 16;
		return name.length() > max ? name.substring(0, max - 1) + "…" : name;
	}

	/** Mirrors the site's rs-pulse-green-bright / rs-pulse-gold-bright 2.2s
	 *  ease-in-out CSS animation on Favorites at a 5-day high/low: a Timer
	 *  eases the row's left accent border between a dim and full-bright
	 *  variant of the highlight color on every tick. */
	private void wirePulse(JPanel row, Color color)
	{
		final Color dim = new Color(color.getRed() / 4, color.getGreen() / 4, color.getBlue() / 4);
		final Runnable paint = () ->
		{
			final double phase = (System.currentTimeMillis() % PULSE_PERIOD_MS) / (double) PULSE_PERIOD_MS;
			final double eased = (1 - Math.cos(2 * Math.PI * phase)) / 2; // 0..1..0
			row.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 2, 0, 0, blend(dim, color, eased)),
				BorderFactory.createEmptyBorder(4, 5, 6, 6)));
		};
		/* Frame zero now, not at the first tick 60ms on: a row is never
		   painted bare. The phase runs off the clock, so a rebuilt row picks
		   the glow up exactly where the old one left it. */
		paint.run();
		final Timer timer = new Timer(60, e -> paint.run());
		timer.start();
		pulseTimers.add(timer);
	}

	private static Color blend(Color a, Color b, double t)
	{
		final int r = (int) (a.getRed() + (b.getRed() - a.getRed()) * t);
		final int g = (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t);
		final int bl = (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t);
		return new Color(r, g, bl);
	}

	/** A genuine price spike (±15%+ — see SPIKE_THRESHOLD_PCT) gets a full
	 *  border, not just the routine pulse's left accent, cycling through a
	 *  small palette rather than one color — OSRS's own rare-item shimmer
	 *  (party hats, 3rd age gear), but biased green/gold for a spike UP and
	 *  red/magenta for a spike DOWN so the color still says which way it
	 *  moved. Takes priority over the plain 5D pulse when both would apply
	 *  — a move this size is the more urgent signal of the two. */
	private void wireSpikeGlow(JPanel row, boolean up, JLabel badge, boolean border)
	{
		final Color[] palette = up ? SPIKE_UP_PALETTE : SPIKE_DOWN_PALETTE;
		if (border)
		{
			row.putClientProperty(SPIKE_ROW, Boolean.TRUE);
		}
		final Runnable paint = () ->
		{
			final double phase = (System.currentTimeMillis() % SPIKE_PERIOD_MS) / (double) SPIKE_PERIOD_MS;
			final double pos = phase * (palette.length - 1);
			final int i = Math.min(palette.length - 2, (int) pos);
			final Color c = blend(palette[i], palette[i + 1], pos - i);
			/* Asked every tick rather than once at build time: rows are
			   dragged into new orders, and the neighbour above can change
			   under a running timer. */
			if (border)
			{
				row.setBorder(spikeBorder(c, outlinedAbove(rows, row)));
			}
			if (badge != null)
			{
				badge.setForeground(c);
			}
		};
		/* Frame zero now, border and badge, not at the first tick 40ms on: a
		   rebuilt row painted bare, its badge in Swing's grey, was half of
		   the list flashing on every rebuild. The row is not in the list
		   yet, so rebuild paints it once more when it is (PAINT_NOW), for
		   the edge it shares with an outlined row above. */
		paint.run();
		if (border)
		{
			row.putClientProperty(PAINT_NOW, paint);
		}
		final Timer timer = new Timer(40, e -> paint.run());
		timer.start();
		pulseTimers.add(timer);
	}

	/** Marks a row wearing the full spike border — see outlinedAbove. */
	static final String SPIKE_ROW = "pocketge.spikeRow";
	/** A spike row's paint, run again once the row is in the list. */
	private static final String PAINT_NOW = "pocketge.paintNow";

	/**
	 * A spike row's border. Two of them stacked drew their touching edges
	 * twice, a 4px bar between them against 2px everywhere else ("these
	 * bars when stacked get double thick borders"). The lower one leaves
	 * its top edge to the row above and takes the 2px as padding instead,
	 * so every line is 2px and no row changes height.
	 */
	static javax.swing.border.Border spikeBorder(Color c, boolean shareTop)
	{
		return BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(shareTop ? 0 : 2, 2, 2, 2, c),
			BorderFactory.createEmptyBorder(shareTop ? 4 : 2, 5, 4, 4));
	}

	/** Whether the row directly above this one in the list is a spike row. */
	static boolean outlinedAbove(java.awt.Container rows, javax.swing.JComponent row)
	{
		final java.awt.Component[] all = rows.getComponents();
		for (int i = 1; i < all.length; i++)
		{
			if (all[i] == row)
			{
				final java.awt.Component above = all[i - 1];
				return above instanceof javax.swing.JComponent && above.isVisible()
					&& Boolean.TRUE.equals(((javax.swing.JComponent) above).getClientProperty(SPIKE_ROW));
			}
		}
		return false;
	}

	/** The row glows only. A rebuild must not touch the search debounce:
	 *  it would cancel the query someone typed a moment before. */
	private void stopGlowTimers()
	{
		for (Timer t : pulseTimers)
		{
			t.stop();
		}
		pulseTimers.clear();
	}

	/** Stops every glow Timer from the previous {@link #update} — otherwise
	 *  each refresh would leave the old ones ticking in the background
	 *  forever, still holding their (now-discarded) row panels alive. Also
	 *  called from the plugin's shutDown() so disabling the plugin doesn't
	 *  leave Timers running against a panel nobody can see anymore. */
	public void stopPulseTimers()
	{
		stopGlowTimers();
		/* The search debounce is a Timer too. It fires once and stops itself,
		   so it is not a leak, but on shutdown it can still be armed — and
		   running a search into a panel the user has just closed is work
		   nobody asked for. */
		if (searchDebounce != null)
		{
			searchDebounce.stop();
		}
	}

	private JLabel iconLabel(int itemId)
	{
		JLabel label = new JLabel();
		label.setPreferredSize(new Dimension(ICON_SIZE, ICON_SIZE));
		label.setMinimumSize(new Dimension(ICON_SIZE, ICON_SIZE));
		label.setHorizontalAlignment(SwingConstants.CENTER);
		if (itemManager != null && itemId > 0)
		{
			/* Null-checked. ItemManager.getImage builds from the client's
			   item sprites, which are not loaded on the login screen — and
			   these panels render there. An NPE here does not just lose one
			   icon: it is thrown inside a row builder called from update(),
			   so it aborts the whole rebuild mid-list and takes the rest of
			   the panel with it. Same failure the getItemStats guard nearby
			   exists for. A missing icon is a blank square; a missing panel
			   reads as the plugin being broken. */
			final AsyncBufferedImage img = itemManager.getImage(itemId);
			if (img != null)
			{
				img.addTo(label);
			}
		}
		return label;
	}

	/** Clicking a row selects it as the item shown above the Recommended
	 *  Flip card (see AdvisorPanel.setSelectedItem); dragging it reorders the
	 *  list live. The list itself never changes on click, and this no longer
	 *  jumps straight to the browser either; opening the full chart is now
	 *  an explicit button in that view. Remove only gets ADDED to {@code
	 *  right} on hover (mirroring the site's opacity:0-until-:hover
	 *  .wl-fav-remove) — reclaiming that width the rest of the time is what
	 *  actually fixed names getting cut to 4-5 characters. Right-click
	 *  always works too, hover or not — the quickest way to remove a
	 *  favorite without having to land the mouse exactly on the tiny ×
	 *  button. */
	private void wireSelect(JPanel row, Color normalBg, Row r, JPanel right, JPanel actionsPanel)
	{
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.setToolTipText("Click for details, drag to reorder, right-click to remove");

		// Swing mouse events target only the deepest component under the
		// cursor and don't bubble to ancestor listeners — a click (or hover)
		// landing on the remove button never reaches this (it has its own
		// listener, so that's fine), but neither does one on the icon or
		// name label, which DON'T have listeners of their own. The row
		// leaves almost no "bare background" (icon+name+change% between them
		// cover nearly the whole row), so row-only hover/click/drag was
		// really only reachable via a thin sliver of padding — register
		// interaction AND hover on every child present when the row is
		// built, not just the row itself.
		final Runnable unhover = () ->
		{
			row.setBackground(normalBg);
			right.remove(actionsPanel);
			right.revalidate();
			right.repaint();
		};
		MouseAdapter interaction = interactionAdapter(row, r, unhover);
		MouseAdapter hover = new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				row.setBackground(HOVER_BG);
				right.add(actionsPanel, BorderLayout.EAST);
				right.revalidate();
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				/* Only when the pointer has really left the row. Moving from
				   the name onto the × button also "exits" the name, and acting
				   on that pulled the button out from under the cursor before a
				   click could land: the × showed, then vanished as you reached
				   it. Re-entering the button never got the chance to put it
				   back, because by then there was no button to enter. */
				java.awt.Point inRow = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), row);
				if (row.contains(inRow))
				{
					return;
				}
				unhover.run();
			}
		};

		row.addMouseListener(interaction);
		row.addMouseMotionListener(interaction);
		row.addMouseListener(hover);
		addMouseListenerToDescendants(row, interaction);
		addMouseMotionListenerToDescendants(row, interaction);
		addMouseListenerToDescendants(row, hover);
		// actionsPanel (the remove button) is added to `right` lazily on
		// hover rather than being a build-time child — attaching `hover` to
		// it too, ONCE here rather than every mouseEntered, is what actually
		// fixes "the × button appears but can't be clicked": without this,
		// moving the cursor onto the button (a component with no listener
		// of its own) registered as the mouse LEAVING the row — same
		// mouseExited that fires when you move off the row entirely — which
		// yanked the button back out from under the cursor before the click
		// could land. Re-entering it now is a no-op (already added, already
		// the hover background) instead of a removal.
		// addMouseListenerToDescendants only reaches actionsPanel's CHILDREN
		// (the button) — actionsPanel itself, i.e. the couple of pixels of
		// FlowLayout padding around the button, was still missing the
		// listener, so a click landing a hair off the button's exact bounds
		// hit that same bug again. Cover the panel itself too, not just what's
		// inside it.
		actionsPanel.addMouseListener(hover);
		addMouseListenerToDescendants(actionsPanel, hover);
	}

	/** Click selects the item, drag reorders it, right-click opens the
	 *  remove/reorder menu — one adapter so a drag can cleanly suppress the
	 *  click it would otherwise also fire on release. */
	private MouseAdapter interactionAdapter(JPanel row, Row r, Runnable unhover)
	{
		return new MouseAdapter()
		{
			private int pressY;
			private boolean dragging;

			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (dragging || SwingUtilities.isRightMouseButton(e))
				{
					return;
				}
				actions.selectItem(r);
			}

			@Override
			public void mousePressed(MouseEvent e)
			{
				pressY = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), rows).y;
				dragging = false;
				maybeShowMenu(e);
			}

			@Override
			public void mouseDragged(MouseEvent e)
			{
				if (!SwingUtilities.isLeftMouseButton(e))
				{
					return;
				}
				final int y = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), rows).y;
				if (!dragging)
				{
					// A few pixels of slack so an ordinary click doesn't
					// register as a drag from natural hand jitter.
					if (Math.abs(y - pressY) < 6)
					{
						return;
					}
					dragging = true;
					row.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
				}
				final int target = indexForY(y, row);
				final int current = indexOfComponent(rows, row);
				if (target >= 0 && target != current)
				{
					rows.remove(row);
					rows.add(row, target);
					rows.revalidate();
					rows.repaint();
				}
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
				if (dragging)
				{
					actions.reorderTo(r.id, indexOfComponent(rows, row));
				}
				maybeShowMenu(e);
			}

			private void maybeShowMenu(MouseEvent e)
			{
				if (!e.isPopupTrigger())
				{
					return;
				}
				final JPopupMenu menu = rowMenu(r);
				/* The pointer's move onto the menu "exits" the row at a point
				   still inside it, so mouseExited keeps the hover; if the menu
				   closes with the pointer off the row, nothing else clears it
				   now that an unchanged list is no longer rebuilt. */
				menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener()
				{
					@Override
					public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent ev)
					{
						final java.awt.PointerInfo pi = java.awt.MouseInfo.getPointerInfo();
						if (pi == null)
						{
							return;
						}
						final java.awt.Point p = pi.getLocation();
						SwingUtilities.convertPointFromScreen(p, row);
						if (!row.contains(p))
						{
							unhover.run();
						}
					}

					@Override
					public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent ev)
					{
					}

					@Override
					public void popupMenuCanceled(javax.swing.event.PopupMenuEvent ev)
					{
					}
				});
				// Show relative to whichever component (row or a descendant)
				// actually caught the event — e.getX()/getY() are already in
				// that component's own coordinate space, so this positions
				// correctly either way.
				menu.show(e.getComponent(), e.getX(), e.getY());
			}
		};
	}

	/** A watchlist row's right-click. */
	JPopupMenu rowMenu(Row r)
	{
		final JPopupMenu menu = new JPopupMenu();
		/* The charts first, because they are what you reach for mid-trade —
		   and because the other three all rearrange the list, which is a
		   different kind of intent. Both, as on the chart button: a new tab,
		   or the page you already have open ("lemme right click open pocket
		   ge chart here in favorites also"). */
		final JMenuItem chart = new JMenuItem("Open PocketGE chart");
		chart.addActionListener(a -> actions.openChartTab(r.name));
		menu.add(chart);
		final JMenuItem sendToTab = new JMenuItem(AdvisorPanel.SEND_TO_TAB);
		sendToTab.setToolTipText(AdvisorPanel.SEND_TO_TAB_TIP);
		sendToTab.addActionListener(a -> actions.sendChartToOpenTab(r.name));
		menu.add(sendToTab);
		menu.addSeparator();
		/* For every row, not this one: the borders are one setting, the
		   badges stay either way. */
		final JMenuItem glow = new JMenuItem(glowEnabled ? "Mute glow borders" : "Unmute glow borders");
		glow.setToolTipText(glowEnabled
			? "Stop every row's border glowing; the ▲/▼ and % badges stay, in colour"
			: "Let rows at a 5-day edge or in a big swing glow again");
		glow.addActionListener(a -> actions.setRowGlow(!glowEnabled));
		menu.add(glow);
		menu.addSeparator();
		final JMenuItem remove = new JMenuItem("Remove from favorites");
		remove.addActionListener(a -> actions.remove(r.id));
		menu.add(remove);
		final JMenuItem up = new JMenuItem("Move up");
		up.addActionListener(a -> actions.reorder(r.id, -1));
		menu.add(up);
		final JMenuItem down = new JMenuItem("Move down");
		down.addActionListener(a -> actions.reorder(r.id, 1));
		menu.add(down);
		return menu;
	}

	/** How many of rows' OTHER children (siblings, not the one being
	 *  dragged) have their vertical midpoint above y — i.e. the index a drop
	 *  at y should land at. */
	private int indexForY(int y, JPanel dragging)
	{
		int idx = 0;
		for (java.awt.Component c : rows.getComponents())
		{
			if (c == dragging)
			{
				continue;
			}
			if (y > c.getY() + c.getHeight() / 2)
			{
				idx++;
			}
			else
			{
				break;
			}
		}
		return idx;
	}

	private static int indexOfComponent(java.awt.Container container, java.awt.Component c)
	{
		java.awt.Component[] comps = container.getComponents();
		for (int i = 0; i < comps.length; i++)
		{
			if (comps[i] == c)
			{
				return i;
			}
		}
		return -1;
	}

	private static void addMouseListenerToDescendants(java.awt.Container container, MouseAdapter listener)
	{
		for (java.awt.Component child : container.getComponents())
		{
			child.addMouseListener(listener);
			if (child instanceof java.awt.Container)
			{
				addMouseListenerToDescendants((java.awt.Container) child, listener);
			}
		}
	}

	private static void addMouseMotionListenerToDescendants(java.awt.Container container, MouseAdapter listener)
	{
		for (java.awt.Component child : container.getComponents())
		{
			child.addMouseMotionListener(listener);
			if (child instanceof java.awt.Container)
			{
				addMouseMotionListenerToDescendants((java.awt.Container) child, listener);
			}
		}
	}

}
