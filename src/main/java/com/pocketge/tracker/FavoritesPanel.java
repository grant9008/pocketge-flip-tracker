package com.pocketge.tracker;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
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
	private static final Color POSITIVE = new Color(0x1F, 0xB8, 0x5C);
	private static final Color NEGATIVE = new Color(0xEF, 0x53, 0x50);
	private static final Color HOVER_BG = new Color(0x3A, 0x33, 0x28);
	/* Same colors as the website's .hl-badge.high5d / .low5d. */
	private static final Color HIGH5D = new Color(0x00, 0xFF, 0x7A);
	private static final Color LOW5D = new Color(0xFF, 0xB3, 0x00);
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
		public double changePct; // vs today's typical (24h average), 0 if unknown
		public boolean atHigh5d; // within 8% of the 5-day high (see PocketGeTrackerPlugin.refreshStatsAndFavorites)
		public boolean atLow5d;  // within 8% of the 5-day low
		// Detail-view fields (see PocketGeTrackerPlugin.refreshStatsAndFavorites):
		public long targetBuy;          // 0 if unknown
		public long targetSell;         // 0 if unknown
		public long potentialProfit;    // for a full GE-limit buy/sell cycle, after tax; 0 if unknown
		public int limit;               // GE buy limit, 0 if unknown
		public AnalystRating.Grade rating; // never null (grade() itself defaults to HOLD/50)
		public long dailyVolume;        // combined 24h trade volume, 0 if unknown — the site's own VOL column
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
		/** Adds (never toggles/removes) an item to the active list — a
		 *  search hit the player already has favorited is just a no-op. */
		void addFavorite(int itemId, String name);
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
	/** Timers driving the 5-day-extreme glow on rows currently shown — every
	 *  {@link #update} throws away the old row panels, so their timers must
	 *  be stopped too or they'd keep ticking (and holding those panels alive)
	 *  forever in the background. */
	private final List<Timer> pulseTimers = new ArrayList<>();
	private List<ListMeta> lists = new ArrayList<>();
	private String activeListId;
	private final GeSlotsPanel geSlots = new GeSlotsPanel();

	public FavoritesPanel(ItemManager itemManager, Actions actions)
	{
		this.itemManager = itemManager;
		this.actions = actions;
		setLayout(new BorderLayout(0, 6));
		setOpaque(false);
		setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));

		JPanel north = new JPanel();
		north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
		north.setOpaque(false);

		north.add(geSlots);
		north.add(Box.createVerticalStrut(4));
		north.add(searchWrap());

		listBar.setOpaque(false);
		listBar.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
		north.add(listBar);
		add(north, BorderLayout.NORTH);

		rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
		rows.setOpaque(false);
		add(rows, BorderLayout.CENTER);
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
		if (results == null || results.isEmpty() || !searchField.isShowing())
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
				searchField.requestFocusInWindow();
			});
			searchResults.add(item);
		}
		searchResults.show(searchField, 0, searchField.getHeight());
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
		if (this.lists.size() == 1)
		{
			// The common case: just the one default list — let its chip
			// fill the row like a header instead of hugging its own text
			// as a small pill floating on an otherwise-bare row.
			listBar.add(listChip(this.lists.get(0)), BorderLayout.CENTER);
		}
		else
		{
			JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
			chips.setOpaque(false);
			for (ListMeta l : this.lists)
			{
				chips.add(listChip(l));
			}
			listBar.add(chips, BorderLayout.CENTER);
		}
		listBar.add(addListChip(), BorderLayout.EAST);
		listBar.revalidate();
		listBar.repaint();
	}

	/** A small colored-dot + name chip, TradingView-watchlist-tab style —
	 *  click to switch lists, right-click to rename/recolor/delete. Stretches
	 *  to fill its row when it's the sole list (see updateLists) — text
	 *  stays left-aligned either way so that doesn't look like a mis-centered
	 *  button. */
	private JButton listChip(ListMeta l)
	{
		boolean active = l.id.equals(activeListId);
		JButton chip = new JButton("● " + l.name);
		chip.setToolTipText("Switch to \"" + l.name + "\" — right-click to rename, recolor, or delete");
		chip.setFocusPainted(false);
		chip.setOpaque(true);
		chip.setContentAreaFilled(true);
		chip.setBorderPainted(true);
		chip.setHorizontalAlignment(SwingConstants.LEFT);
		chip.setFont(chip.getFont().deriveFont(active ? Font.BOLD : Font.PLAIN, 11f));
		chip.setMargin(new java.awt.Insets(2, 6, 2, 6));
		chip.setForeground(Color.decode(l.color));
		chip.setBackground(active ? HOVER_BG : ColorScheme.DARKER_GRAY_COLOR);
		chip.setBorder(BorderFactory.createLineBorder(active ? Color.decode(l.color) : ColorScheme.MEDIUM_GRAY_COLOR, 1));
		chip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		chip.addActionListener(e -> actions.selectList(l.id));
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
					JMenuItem swatch = new JMenuItem("● " + hex);
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

	private JButton addListChip()
	{
		JButton add = new JButton("+");
		add.setToolTipText("New favorites list");
		add.setFocusPainted(false);
		add.setFont(add.getFont().deriveFont(Font.BOLD, 11f));
		add.setMargin(new java.awt.Insets(2, 6, 2, 6));
		add.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		add.addActionListener(e ->
		{
			String name = javax.swing.JOptionPane.showInputDialog(add, "New list name:", "Watchlist");
			if (name != null && !name.trim().isEmpty())
			{
				actions.createList(name.trim());
			}
		});
		return add;
	}

	/** Rebuild from resolved rows. Call on the Swing EDT. */
	public void update(List<Row> favoriteRows)
	{
		stopPulseTimers();
		rows.removeAll();
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
		revalidate();
		repaint();
	}

	/** Matches the website's own .wl-item: name + price always visible, kept
	 *  lean by only revealing remove on hover (its .wl-fav-remove is
	 *  opacity:0 until :hover the same way) instead of permanently eating
	 *  row width — that's what was crushing names down to 4-5 characters. */
	private JPanel row(Row r)
	{
		// Back to one line, not the three-line stack this briefly became.
		// That split was chasing an overflow bug caused by cramming in a
		// badge pill AND a raw price AND a 5D range on top of name+change% —
		// now that content is down to just name and % change, one line is
		// short enough to fit without clipping, and a permanently-reserved
		// blank row for the hover-only reorder/remove buttons was pure
		// wasted height. Back to the original shape: icon + name, %change
		// pinned to the right, actions overlapping that same right side only
		// while hovered.
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		// Bottom padding carries the 2px gap a separate strut component used
		// to provide — dropped so rows.getComponents() maps 1:1 to
		// favoriteRows' indices, which drag-to-reorder depends on.
		p.setBorder(BorderFactory.createEmptyBorder(3, 7, 5, 6));

		// Two lines now, not one — matching the website's ITEM/LAST/CHG%/
		// EA/VOL table columns needed more room than a single FlowLayout row
		// could give without repeating the exact overflow bug (badge + price
		// + 5D range + name all fighting for one line) this row's history
		// already ran into once. A second, smaller stats line underneath
		// keeps line 1 exactly as lean as before (icon, name, %change).
		JPanel line1 = new JPanel(new BorderLayout(6, 0));
		line1.setOpaque(false);

		JLabel icon = iconLabel(r.id);
		line1.add(icon, BorderLayout.WEST);

		// Just the name — no badge pill here. Whether it's at a 5-day
		// extreme is already the pulsing left-border's job (below); a
		// second, louder, solid-color indicator saying the same thing right
		// next to the name was redundant weight, not new information.
		JPanel nameWrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		nameWrap.setOpaque(false);
		JLabel name = new JLabel(truncateName(r.name));
		name.setToolTipText(r.name);
		name.setForeground(Color.WHITE);
		name.setFont(name.getFont().deriveFont(12f));
		nameWrap.add(name);
		line1.add(nameWrap, BorderLayout.CENTER);

		final JPanel right = new JPanel(new BorderLayout(4, 0));
		right.setOpaque(false);
		if (r.changePct != 0)
		{
			JLabel chg = new JLabel(String.format("%s%.1f%%", r.changePct >= 0 ? "+" : "", r.changePct));
			chg.setForeground(r.changePct >= 0 ? POSITIVE : NEGATIVE);
			chg.setFont(chg.getFont().deriveFont(Font.BOLD, 12f));
			right.add(chg, BorderLayout.CENTER);
		}
		line1.add(right, BorderLayout.EAST);
		p.add(line1);

		p.add(Box.createVerticalStrut(1));
		p.add(statsLine(r));

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
		if (Math.abs(r.changePct) >= SPIKE_THRESHOLD_PCT)
		{
			wireSpikeGlow(p, r.changePct >= 0);
		}
		else if (r.atHigh5d || r.atLow5d)
		{
			wirePulse(p, r.atHigh5d ? HIGH5D : LOW5D);
		}
		return p;
	}

	/** Second line: LAST price, EA (per-unit edge — targetSell minus
	 *  targetBuy minus tax, same convention as the inspection card's own
	 *  fallback profit line) and VOL, matching the website's LAST/EA/VOL
	 *  columns. Indented to align under the name rather than the icon —
	 *  the icon has nothing to say here. */
	private JPanel statsLine(Row r)
	{
		JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
		line.setOpaque(false);
		line.setBorder(BorderFactory.createEmptyBorder(0, ICON_SIZE + 6, 0, 0));

		line.add(statLabel(r.price > 0 ? QuantityFormatter.quantityToStackSize(r.price) : "—"));

		Long ea = r.targetBuy > 0 && r.targetSell > 0
			? r.targetSell - r.targetBuy - FlipTracker.taxPerItem(r.targetSell, r.id) : null;
		JLabel eaLabel = statLabel("EA " + (ea != null ? (ea >= 0 ? "+" : "") + QuantityFormatter.quantityToStackSize(ea) : "—"));
		if (ea != null)
		{
			eaLabel.setForeground(ea >= 0 ? POSITIVE : NEGATIVE);
		}
		line.add(eaLabel);

		line.add(statLabel(r.dailyVolume > 0 ? QuantityFormatter.quantityToStackSize(r.dailyVolume) : "—"));
		return line;
	}

	private JLabel statLabel(String text)
	{
		JLabel l = new JLabel(text);
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		l.setFont(l.getFont().deriveFont(10f));
		return l;
	}

	/** Same 16-char cutoff the Top Suggestion card uses — the full name
	 *  is always still reachable via the tooltip. */
	private static String truncateName(String name)
	{
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
				BorderFactory.createEmptyBorder(3, 5, 5, 6)));
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
	private void wireSpikeGlow(JPanel row, boolean up)
	{
		final Color[] palette = up ? SPIKE_UP_PALETTE : SPIKE_DOWN_PALETTE;
		final Timer timer = new Timer(40, null);
		timer.addActionListener(e ->
		{
			final double phase = (System.currentTimeMillis() % SPIKE_PERIOD_MS) / (double) SPIKE_PERIOD_MS;
			final double pos = phase * (palette.length - 1);
			final int i = Math.min(palette.length - 2, (int) pos);
			final double t = pos - i;
			final Color c = blend(palette[i], palette[i + 1], t);
			row.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(c, 2),
				BorderFactory.createEmptyBorder(1, 3, 3, 4)));
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
	}

	private JLabel iconLabel(int itemId)
	{
		JLabel label = new JLabel();
		label.setPreferredSize(new Dimension(ICON_SIZE, ICON_SIZE));
		label.setMinimumSize(new Dimension(ICON_SIZE, ICON_SIZE));
		label.setHorizontalAlignment(SwingConstants.CENTER);
		if (itemManager != null && itemId > 0)
		{
			AsyncBufferedImage img = itemManager.getImage(itemId);
			img.addTo(label);
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
