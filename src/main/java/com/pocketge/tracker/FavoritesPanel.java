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
import javax.swing.Box;
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
import net.runelite.client.ui.ColorScheme;
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
	/** The panel's body text, reused for the open collapse handle. */
	private static final Color ROW_TEXT = new Color(0xD9, 0xD3, 0xC7);
	/** The shortest the grip will drag the list. One row, not zero — zero is
	 *  what the collapse chevron above is for, and a drag that ended in an
	 *  empty box with a handle under it would look broken rather than
	 *  collapsed. */
	private static final int MIN_VISIBLE_ROWS = 1;
	/* Same colors as the website's .hl-badge.high5d / .low5d. */
	static final Color HIGH5D = new Color(0x00, 0xFF, 0x7A);
	static final Color LOW5D = new Color(0xFF, 0xB3, 0x00);
	/* The day tier, deliberately PALE — the website's .hl-badge.high / .low.
	   An item brushes its own daily high or low constantly, so this has to
	   read as "noted" rather than "act now", or it drowns out the multi-day
	   tiers that actually matter. */
	private static final Color HIGH1D = new Color(0x6F, 0xE8, 0xA0);
	private static final Color LOW1D = new Color(0xE5, 0xB8, 0x42);
	private static final int PULSE_PERIOD_MS = 2200; // matches the site's 2.2s rs-pulse-*-bright animation
	private static final int ICON_SIZE = 20;
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
		/** Live MID against the 24h typical mid, as a percent; 0 if unknown.
		 *  Deliberately not a change on {@link #price} (which is the
		 *  insta-sell) — mid-vs-mid is what FinderEngine, AnalystRating and
		 *  the website's mover1dPct all mean by "change", and this drives the
		 *  ±15% spike badge, so it has to be the same number the site shows. */
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
		public long potentialProfit;    // for a full GE-limit buy/sell cycle, after tax; 0 if unknown
		public int limit;               // GE buy limit, 0 if unknown
		public long dailyVolume;        // combined 24h trade volume, 0 if unknown — the site's own VOL column
		/* What you're actually sitting on right now. The watchlist is where
		   you look to decide what to do next, so "you already own this and
		   it's up" belongs here rather than only inside a card you have to
		   click into. All four are filled in together — see
		   PocketGeTrackerPlugin.refreshStatsAndFavorites. */
		public int heldQty;             // units in bank + inventory, 0 if you hold none
		public long sellValue;          // after-tax proceeds for heldQty at the current insta-buy, 0 if unpriced
		/** Only meaningful when {@link #hasCostBasis}: sellValue minus what
		 *  those exact units cost you. */
		public long heldProfit;
		/** True only when the plugin actually watched you buy the units you
		 *  hold. A stack you had before the plugin ever ran has no cost, and
		 *  calling its sale value "profit" would be inventing a win. */
		public boolean hasCostBasis;
		/** How many of {@link #heldQty} the cost basis actually covers. Less
		 *  than heldQty when you already owned some of the stack before the
		 *  tracked purchases — {@link #heldProfit} then describes only this
		 *  many units, and deliberately understates the position. */
		public int pricedQty;
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
	private static final String SEARCH_PLACEHOLDER = "Search items to add…";
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
		/** Three 2px ribs on a 6px pitch. */
		private static final int DOTS_W = 14;
		private static final int DOTS_GAP = 6;
		private int dragStartY;
		private int dragStartRows;

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
			g2.setColor(ColorScheme.LIGHT_GRAY_COLOR);
			g2.drawString(text, x + DOTS_W + DOTS_GAP, mid + g2.getFontMetrics().getAscent() / 2 - 1);
			ribs(g2, x + DOTS_W + DOTS_GAP + textW + DOTS_GAP, mid);
		}

		/** Three 2px ribs on a 6px pitch, starting at {@code x}. */
		private void ribs(Graphics2D g2, int x, int mid)
		{
			g2.setColor(ColorScheme.MEDIUM_GRAY_COLOR);
			for (int i = 0; i < 3; i++)
			{
				g2.fillRect(x + i * 6, mid - 1, 2, 2);
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

	/** The right-hand end of the list bar: collapse, then the new-list "+".
	 *  Rebuilt whenever the bar is, since updateLists clears it. */
	private JPanel listBarRight()
	{
		final JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		right.setOpaque(false);
		right.add(listToggle);
		right.add(addListChip());
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
		listToggle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
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
		searchField.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		searchField.setToolTipText("Search any tradeable item to add it to this list");

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
					searchField.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
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
				actions.addFavorite(r.id, r.name);
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
		ListMeta active = null;
		for (ListMeta l : this.lists)
		{
			if (l.id.equals(activeListId))
			{
				active = l;
				break;
			}
		}
		if (active == null && !this.lists.isEmpty())
		{
			active = this.lists.get(0);
		}
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
	 *  list, current one checked); right-click still opens rename/recolor/
	 *  delete for the list currently shown. Text stays left-aligned since it
	 *  stretches to fill the row, so it reads as a header rather than a
	 *  mis-centered button. */
	private JButton listDropdown(ListMeta l)
	{
		JButton chip = new JButton("\u25CF " + l.name + "  \u25BE");
		chip.setToolTipText("Switch list — right-click to rename, recolor, or delete \"" + l.name + "\"");
		chip.setFocusPainted(false);
		chip.setOpaque(true);
		chip.setContentAreaFilled(true);
		chip.setBorderPainted(true);
		chip.setHorizontalAlignment(SwingConstants.LEFT);
		chip.setFont(chip.getFont().deriveFont(Font.BOLD, 12f));
		chip.setMargin(new java.awt.Insets(3, 7, 3, 7));
		chip.setForeground(Color.decode(l.color));
		chip.setBackground(HOVER_BG);
		chip.setBorder(BorderFactory.createLineBorder(Color.decode(l.color), 1));
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
				if (!e.isPopupTrigger())
				{
					return;
				}
				JPopupMenu menu = new JPopupMenu();
				JMenuItem rename = new JMenuItem("Rename list");
				rename.addActionListener(a ->
				{
					String name = javax.swing.JOptionPane.showInputDialog(chip, "List name:", l.name);
					if (name != null && !name.trim().isEmpty())
					{
						actions.renameList(l.id, name.trim());
					}
				});
				menu.add(rename);
				JMenu colorMenu = new JMenu("Color");
				for (String hex : FavoriteLists.PALETTE)
				{
					JMenuItem swatch = new JMenuItem("\u25CF " + hex);
					swatch.setForeground(Color.decode(hex));
					swatch.addActionListener(a -> actions.recolorList(l.id, hex));
					colorMenu.add(swatch);
				}
				menu.add(colorMenu);
				JMenuItem delete = new JMenuItem("Delete list");
				delete.setEnabled(lists.size() > 1);
				delete.addActionListener(a -> actions.deleteList(l.id));
				menu.add(delete);
				menu.show(chip, e.getX(), e.getY());
			}
		});
		return chip;
	}

	/** Every list, current one marked — plus a "New list…" tail so creating
	 *  one is reachable from the same place you switch, not only from the
	 *  separate + button. */
	private void showListSwitcher(JButton anchor)
	{
		JPopupMenu menu = new JPopupMenu();
		for (ListMeta l : lists)
		{
			final boolean active = l.id.equals(activeListId);
			JMenuItem item = new JMenuItem((active ? "\u2713 " : "\u2003") + l.name);
			item.setForeground(Color.decode(l.color));
			if (active)
			{
				item.setFont(item.getFont().deriveFont(Font.BOLD));
			}
			item.addActionListener(a -> actions.selectList(l.id));
			menu.add(item);
		}
		menu.addSeparator();
		JMenuItem create = new JMenuItem("New list\u2026");
		create.addActionListener(a -> promptNewList(anchor));
		menu.add(create);
		menu.show(anchor, 0, anchor.getHeight());
	}

	private void promptNewList(java.awt.Component parent)
	{
		String name = javax.swing.JOptionPane.showInputDialog(parent, "New list name:", "Watchlist");
		if (name != null && !name.trim().isEmpty())
		{
			actions.createList(name.trim());
		}
	}

	private JButton addListChip()
	{
		JButton add = new JButton("+");
		add.setToolTipText("New favorites list");
		add.setFocusPainted(false);
		add.setFont(add.getFont().deriveFont(Font.BOLD, 13f));
		add.setMargin(new java.awt.Insets(3, 8, 3, 8));
		add.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		add.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		add.addActionListener(e -> promptNewList(add));
		return add;
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
		update(lastRows);
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
		update(lastRows);
	}

	/** Rebuild from resolved rows. Call on the Swing EDT. */
	public void update(List<Row> favoriteRows)
	{
		lastRows = favoriteRows != null ? favoriteRows : new ArrayList<>();
		favoriteRows = lastRows;
		stopPulseTimers();
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
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
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
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
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

		wireSelect(p, ColorScheme.DARKER_GRAY_COLOR, r, right, actionsPanel);
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
			wireSpikeGlow(p, r.changePct >= 0, badge);
		}
		else if (r.tier != PriceExtremes.Tier.NONE
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
	 *   <li>spike — the live price is 15%+ away from its own 24h typical.
	 *       Rare, and the only badge that animates: it cycles the same
	 *       palette as the row's border glow, green/gold climbing for a move
	 *       up and red/magenta for one down, so the colour says the
	 *       direction even before the number is read.
	 *   <li>5-day — bright text, within 8% of the 5-day high or low.
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
	 * run from its own 24-hour typical, animated.
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
			up ? "Spiking \u2014 up %.1f%% on its 24-hour typical price"
				: "Spiking \u2014 down %.1f%% on its 24-hour typical price", Math.abs(pct)));
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
				? "Trading within 8% of its 5-day high"
				: "Trading within 8% of its 5-day low";
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
			sb.append("<br>That is <b>").append(QuantityFormatter.quantityToStackSize(r.heldProfit))
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
		final Timer timer = new Timer(60, null);
		timer.addActionListener(e ->
		{
			final double phase = (System.currentTimeMillis() % PULSE_PERIOD_MS) / (double) PULSE_PERIOD_MS;
			final double eased = (1 - Math.cos(2 * Math.PI * phase)) / 2; // 0..1..0
			row.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 2, 0, 0, blend(dim, color, eased)),
				BorderFactory.createEmptyBorder(4, 5, 6, 6)));
		});
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
	private void wireSpikeGlow(JPanel row, boolean up, JLabel badge)
	{
		final Color[] palette = up ? SPIKE_UP_PALETTE : SPIKE_DOWN_PALETTE;
		/* Paint frame zero now rather than waiting 40ms for the first tick.
		   Without this the badge renders in Swing's default foreground for one
		   frame, a dark grey all but invisible on this row — a badge that
		   flashes unreadable every time the list rebuilds. */
		if (badge != null)
		{
			badge.setForeground(palette[0]);
		}
		final Timer timer = new Timer(40, null);
		timer.addActionListener(e ->
		{
			final double phase = (System.currentTimeMillis() % SPIKE_PERIOD_MS) / (double) SPIKE_PERIOD_MS;
			final double pos = phase * (palette.length - 1);
			final int i = Math.min(palette.length - 2, (int) pos);
			final Color c = blend(palette[i], palette[i + 1], pos - i);
			row.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(c, 2),
				BorderFactory.createEmptyBorder(2, 5, 4, 4)));
			if (badge != null)
			{
				badge.setForeground(c);
			}
		});
		timer.start();
		pulseTimers.add(timer);
	}

	/** Stops every glow Timer from the previous {@link #update} — otherwise
	 *  each refresh would leave the old ones ticking in the background
	 *  forever, still holding their (now-discarded) row panels alive. Also
	 *  called from the plugin's shutDown() so disabling the plugin doesn't
	 *  leave Timers running against a panel nobody can see anymore. */
	public void stopPulseTimers()
	{
		for (Timer t : pulseTimers)
		{
			t.stop();
		}
		pulseTimers.clear();
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
		MouseAdapter interaction = interactionAdapter(row, r);
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
				row.setBackground(normalBg);
				right.remove(actionsPanel);
				right.revalidate();
				right.repaint();
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
	private MouseAdapter interactionAdapter(JPanel row, Row r)
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
				JPopupMenu menu = new JPopupMenu();
				/* First, because it is the one you reach for mid-trade — and
				   because the other three all rearrange the list, which is a
				   different kind of intent. */
				JMenuItem chart = new JMenuItem("Open PocketGE chart");
				chart.addActionListener(a -> actions.openChart(r.name));
				menu.add(chart);
				menu.addSeparator();
				JMenuItem remove = new JMenuItem("Remove from favorites");
				remove.addActionListener(a -> actions.remove(r.id));
				menu.add(remove);
				JMenuItem up = new JMenuItem("Move up");
				up.addActionListener(a -> actions.reorder(r.id, -1));
				menu.add(up);
				JMenuItem down = new JMenuItem("Move down");
				down.addActionListener(a -> actions.reorder(r.id, 1));
				menu.add(down);
				// Show relative to whichever component (row or a descendant)
				// actually caught the event — e.getX()/getY() are already in
				// that component's own coordinate space, so this positions
				// correctly either way.
				menu.show(e.getComponent(), e.getX(), e.getY());
			}
		};
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
