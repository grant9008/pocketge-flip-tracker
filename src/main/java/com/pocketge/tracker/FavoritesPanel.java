package com.pocketge.tracker;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.LinkBrowser;
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
	private static final int ICON_SIZE = 26;

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
	}

	public interface Actions
	{
		void remove(int itemId);
	}

	private final ItemManager itemManager;
	private final Actions actions;
	private final JPanel rows = new JPanel();
	/** Timers driving the 5-day-extreme glow on rows currently shown — every
	 *  {@link #update} throws away the old row panels, so their timers must
	 *  be stopped too or they'd keep ticking (and holding those panels alive)
	 *  forever in the background. */
	private final List<Timer> pulseTimers = new ArrayList<>();

	public FavoritesPanel(ItemManager itemManager, Actions actions)
	{
		this.itemManager = itemManager;
		this.actions = actions;
		setLayout(new BorderLayout(0, 6));
		setOpaque(false);
		setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));

		JLabel title = new JLabel("★ Favorites");
		title.setForeground(GOLD);
		title.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
		add(title, BorderLayout.NORTH);

		rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
		rows.setOpaque(false);
		add(rows, BorderLayout.CENTER);
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
			empty.setFont(empty.getFont().deriveFont(11f));
			rows.add(empty);
		}
		for (Row r : favoriteRows)
		{
			rows.add(row(r));
			rows.add(Box.createVerticalStrut(4));
		}
		revalidate();
		repaint();
	}

	private JPanel row(Row r)
	{
		JPanel p = new JPanel(new BorderLayout(6, 0));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 4));

		JLabel icon = iconLabel(r.id);

		JPanel nameRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
		nameRow.setOpaque(false);
		JLabel name = new JLabel(r.name);
		name.setForeground(Color.WHITE);
		name.setFont(name.getFont().deriveFont(11.5f));
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

		JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		right.setOpaque(false);
		if (r.price > 0)
		{
			JLabel price = new JLabel(QuantityFormatter.quantityToStackSize(r.price));
			price.setForeground(Color.WHITE);
			price.setFont(price.getFont().deriveFont(11f));
			right.add(price);
			if (r.changePct != 0)
			{
				JLabel chg = new JLabel(String.format("%s%.1f%%", r.changePct >= 0 ? "+" : "", r.changePct));
				chg.setForeground(r.changePct >= 0 ? POSITIVE : NEGATIVE);
				chg.setFont(chg.getFont().deriveFont(10f));
				right.add(chg);
			}
		}
		JButton remove = new JButton("×");
		remove.setToolTipText("Remove " + r.name + " from favorites");
		remove.setMargin(new java.awt.Insets(0, 4, 0, 4));
		remove.addActionListener(e -> actions.remove(r.id));
		right.add(remove);
		p.add(right, BorderLayout.EAST);

		wireOpenChart(p, ColorScheme.DARKER_GRAY_COLOR, r.name);
		if (r.atHigh5d || r.atLow5d)
		{
			wirePulse(p, r.atHigh5d ? HIGH5D : LOW5D);
		}
		return p;
	}

	private JLabel hlBadge(String text, Color color)
	{
		JLabel badge = new JLabel(text);
		badge.setOpaque(true);
		badge.setBackground(color);
		badge.setForeground(Color.BLACK);
		badge.setFont(badge.getFont().deriveFont(Font.BOLD, 8.5f));
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
				BorderFactory.createEmptyBorder(5, 5, 5, 4)));
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

	private void wireOpenChart(JPanel row, Color normalBg, String itemName)
	{
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.setToolTipText("Open the live " + itemName + " chart on PocketGE");
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				// Swing mouse listeners are component-local (no DOM-style
				// bubbling), so a click on the remove button below never
				// reaches this listener — safe to always open the chart here.
				LinkBrowser.browse("https://pocketge.com/?q=" + urlEncode(itemName));
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				row.setBackground(HOVER_BG);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				row.setBackground(normalBg);
			}
		});
	}

	private static String urlEncode(String s)
	{
		try
		{
			return URLEncoder.encode(s, StandardCharsets.UTF_8.name()).replace("+", "%20");
		}
		catch (UnsupportedEncodingException e)
		{
			return s.replace(" ", "%20");
		}
	}
}
