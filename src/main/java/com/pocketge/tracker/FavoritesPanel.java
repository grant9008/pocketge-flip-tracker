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
	private static final Color GOLD = new Color(0xE5, 0xC1, 0x58);
	private static final Color HOVER_BG = new Color(0x3A, 0x33, 0x28);
	/* Same colors as the website's .hl-badge.high5d / .low5d. */
	private static final Color HIGH5D = new Color(0x00, 0xFF, 0x7A);
	private static final Color LOW5D = new Color(0xFF, 0xB3, 0x00);
	private static final int PULSE_PERIOD_MS = 2200; // matches the site's 2.2s rs-pulse-*-bright animation
	private static final int ICON_SIZE = 20;

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
	}

	public interface Actions
	{
		void remove(int itemId);
		void reorder(int itemId, int delta);
		/** Clicking a row — the detail view it opens lives above the
		 *  Recommended Flip card (see AdvisorPanel.setSelectedItem), not
		 *  here, so the Favorites list itself always stays visible. */
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
	private final JPanel listBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
	private final JLabel title = new JLabel("★ Favorites");
	private final JPanel rows = new JPanel();
	/** Timers driving the 5-day-extreme glow on rows currently shown — every
	 *  {@link #update} throws away the old row panels, so their timers must
	 *  be stopped too or they'd keep ticking (and holding those panels alive)
	 *  forever in the background. */
	private final List<Timer> pulseTimers = new ArrayList<>();
	private List<ListMeta> lists = new ArrayList<>();
	private String activeListId;

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

		north.add(searchWrap());

		listBar.setOpaque(false);
		listBar.setBorder(BorderFactory.createEmptyBorder(0, 0, 2, 0));
		north.add(listBar);

		title.setForeground(GOLD);
		title.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
		north.add(title);
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

	/** Rebuild the list-switcher chip row. Call on the Swing EDT whenever the
	 *  set of lists, the active one, or any name/color changes. */
	public void updateLists(List<ListMeta> lists, String activeListId)
	{
		this.lists = lists != null ? lists : new ArrayList<>();
		this.activeListId = activeListId;
		listBar.removeAll();
		for (ListMeta l : this.lists)
		{
			listBar.add(listChip(l));
		}
		listBar.add(addListChip());

		ListMeta active = null;
		for (ListMeta l : this.lists)
		{
			if (l.id.equals(activeListId))
			{
				active = l;
				break;
			}
		}
		title.setText(active != null ? "★ " + active.name : "★ Favorites");
		title.setForeground(active != null ? Color.decode(active.color) : GOLD);
		listBar.revalidate();
		listBar.repaint();
	}

	/** A small colored-dot + name chip, TradingView-watchlist-tab style —
	 *  click to switch lists, right-click to rename/recolor/delete. */
	private JButton listChip(ListMeta l)
	{
		boolean active = l.id.equals(activeListId);
		JButton chip = new JButton("● " + l.name);
		chip.setToolTipText("Switch to \"" + l.name + "\" — right-click to rename, recolor, or delete");
		chip.setFocusPainted(false);
		chip.setOpaque(true);
		chip.setContentAreaFilled(true);
		chip.setBorderPainted(true);
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
		for (int i = 0; i < favoriteRows.size(); i++)
		{
			rows.add(row(favoriteRows.get(i), i > 0, i < favoriteRows.size() - 1));
			rows.add(Box.createVerticalStrut(2));
		}
		revalidate();
		repaint();
	}

	/** Matches the website's own .wl-item: name + price always visible, kept
	 *  lean by only revealing reorder/remove on hover (its .wl-fav-remove is
	 *  opacity:0 until :hover the same way) instead of permanently eating
	 *  row width — that's what was crushing names down to 4-5 characters. */
	private JPanel row(Row r, boolean canMoveUp, boolean canMoveDown)
	{
		JPanel p = new JPanel(new BorderLayout(6, 0));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(BorderFactory.createEmptyBorder(3, 7, 3, 5));

		JLabel icon = iconLabel(r.id);

		JPanel nameRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
		nameRow.setOpaque(false);
		JLabel name = new JLabel(truncateName(r.name));
		name.setToolTipText(r.name);
		name.setForeground(Color.WHITE);
		name.setFont(name.getFont().deriveFont(12f));
		nameRow.add(name);
		if (r.atHigh5d)
		{
			nameRow.add(hlBadge("▲ 5D", HIGH5D));
		}
		else if (r.atLow5d)
		{
			nameRow.add(hlBadge("▼ 5D", LOW5D));
		}

		JPanel left = new JPanel(new BorderLayout(6, 0));
		left.setOpaque(false);
		left.add(icon, BorderLayout.WEST);
		left.add(nameRow, BorderLayout.CENTER);
		p.add(left, BorderLayout.CENTER);

		final JPanel right = new JPanel(new BorderLayout(6, 0));
		right.setOpaque(false);
		if (r.price > 0)
		{
			JPanel priceBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
			priceBox.setOpaque(false);
			JLabel price = new JLabel(QuantityFormatter.quantityToStackSize(r.price));
			price.setForeground(Color.WHITE);
			price.setFont(price.getFont().deriveFont(12f));
			priceBox.add(price);
			if (r.changePct != 0)
			{
				JLabel chg = new JLabel(String.format("%s%.1f%%", r.changePct >= 0 ? "+" : "", r.changePct));
				chg.setForeground(r.changePct >= 0 ? POSITIVE : NEGATIVE);
				chg.setFont(chg.getFont().deriveFont(11f));
				priceBox.add(chg);
			}
			right.add(priceBox, BorderLayout.CENTER);
		}
		p.add(right, BorderLayout.EAST);

		final JPanel actionsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
		actionsPanel.setOpaque(false);
		JPanel reorderBtns = new JPanel();
		reorderBtns.setLayout(new BoxLayout(reorderBtns, BoxLayout.Y_AXIS));
		reorderBtns.setOpaque(false);
		reorderBtns.add(reorderBtn("▲", "Move up", canMoveUp, e -> actions.reorder(r.id, -1)));
		reorderBtns.add(reorderBtn("▼", "Move down", canMoveDown, e -> actions.reorder(r.id, 1)));
		actionsPanel.add(reorderBtns);
		JButton remove = new JButton("×");
		remove.setToolTipText("Remove " + r.name + " from favorites");
		remove.setMargin(new java.awt.Insets(0, 4, 0, 4));
		remove.addActionListener(e -> actions.remove(r.id));
		actionsPanel.add(remove);

		wireSelect(p, ColorScheme.DARKER_GRAY_COLOR, r, right, actionsPanel, canMoveUp, canMoveDown);
		if (r.atHigh5d || r.atLow5d)
		{
			wirePulse(p, r.atHigh5d ? HIGH5D : LOW5D);
		}
		return p;
	}

	/** Same 16-char cutoff the Recommended Flip card uses — the full name
	 *  is always still reachable via the tooltip. */
	private static String truncateName(String name)
	{
		final int max = 16;
		return name.length() > max ? name.substring(0, max - 1) + "…" : name;
	}

	private JButton reorderBtn(String glyph, String tip, boolean enabled, java.awt.event.ActionListener a)
	{
		JButton b = new JButton(glyph);
		b.setToolTipText(tip);
		b.setEnabled(enabled);
		b.setMargin(new java.awt.Insets(0, 2, 0, 2));
		b.setFont(b.getFont().deriveFont(8f));
		b.setFocusPainted(false);
		b.addActionListener(a);
		return b;
	}

	private JLabel hlBadge(String text, Color color)
	{
		JLabel badge = new JLabel(text);
		badge.setOpaque(true);
		badge.setBackground(color);
		badge.setForeground(Color.BLACK);
		badge.setFont(badge.getFont().deriveFont(Font.BOLD, 9.5f));
		badge.setBorder(BorderFactory.createEmptyBorder(1, 4, 1, 4));
		return badge;
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
				BorderFactory.createMatteBorder(0, 3, 0, 0, blend(dim, color, eased)),
				BorderFactory.createEmptyBorder(3, 4, 3, 5)));
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
	 *  Flip card (see AdvisorPanel.setSelectedItem) — the list itself never
	 *  changes, and this no longer jumps straight to the browser either;
	 *  opening the full chart is now an explicit button in that view.
	 *  Reorder/remove only get ADDED to {@code right} on hover (mirroring
	 *  the site's opacity:0-until-:hover .wl-fav-remove) — reclaiming that
	 *  width the rest of the time is what actually fixed names getting cut
	 *  to 4-5 characters. Right-click always works too, hover or not — the
	 *  quickest way to remove a favorite without having to land the mouse
	 *  exactly on the tiny × button. */
	private void wireSelect(JPanel row, Color normalBg, Row r, JPanel right, JPanel actionsPanel,
		boolean canMoveUp, boolean canMoveDown)
	{
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.setToolTipText("Click for details, right-click to remove or reorder");
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (SwingUtilities.isRightMouseButton(e))
				{
					return;
				}
				// Swing mouse listeners are component-local (no DOM-style
				// bubbling), so a click on the remove/reorder buttons below
				// never reaches this listener — safe to always select here.
				actions.selectItem(r);
			}

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
				JMenuItem remove = new JMenuItem("Remove from favorites");
				remove.addActionListener(a -> actions.remove(r.id));
				menu.add(remove);
				JMenuItem up = new JMenuItem("Move up");
				up.setEnabled(canMoveUp);
				up.addActionListener(a -> actions.reorder(r.id, -1));
				menu.add(up);
				JMenuItem down = new JMenuItem("Move down");
				down.setEnabled(canMoveDown);
				down.addActionListener(a -> actions.reorder(r.id, 1));
				menu.add(down);
				menu.show(row, e.getX(), e.getY());
			}

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
		});
	}

}
