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
	private static final Color TEAL = new Color(0x26, 0xA6, 0x9A);
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
	}

	private final ItemManager itemManager;
	private final Actions actions;
	private final JPanel rows = new JPanel();
	/** Timers driving the 5-day-extreme glow on rows currently shown — every
	 *  {@link #update} throws away the old row panels, so their timers must
	 *  be stopped too or they'd keep ticking (and holding those panels alive)
	 *  forever in the background. */
	private final List<Timer> pulseTimers = new ArrayList<>();
	/** Which favorite (if any) is currently expanded into the detail view —
	 *  survives across {@link #update} calls (every price refresh) so the
	 *  detail view doesn't snap back to the list mid-read; cleared by the
	 *  back button or if the item drops out of favorites entirely. */
	private Integer selectedItemId = null;
	/** The most recent full row list from the plugin — kept so the "Back"
	 *  button can re-render the list view without waiting for the next
	 *  scheduled update(). */
	private List<Row> lastRows = List.of();

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

	/** Rebuild from resolved rows. Call on the Swing EDT. If a favorite is
	 *  currently expanded ({@link #selectedItemId}), the detail view is
	 *  refreshed in place instead of snapping back to the list — a price
	 *  refresh mid-read shouldn't kick the player out of what they clicked. */
	public void update(List<Row> favoriteRows)
	{
		lastRows = favoriteRows;
		stopPulseTimers();
		rows.removeAll();
		if (selectedItemId != null)
		{
			Row match = null;
			for (Row r : favoriteRows)
			{
				if (r.id == selectedItemId)
				{
					match = r;
					break;
				}
			}
			if (match != null)
			{
				rows.add(renderDetail(match));
				revalidate();
				repaint();
				return;
			}
			selectedItemId = null; // no longer a favorite — fall through to the list
		}
		if (favoriteRows.isEmpty())
		{
			JLabel empty = new JLabel("<html><center>No favorites yet.<br>Tap the star on a suggestion or flip to add one.</center></html>");
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			empty.setFont(empty.getFont().deriveFont(11f));
			rows.add(empty);
		}
		for (int i = 0; i < favoriteRows.size(); i++)
		{
			rows.add(row(favoriteRows.get(i), i > 0, i < favoriteRows.size() - 1));
			rows.add(Box.createVerticalStrut(4));
		}
		revalidate();
		repaint();
	}

	private JPanel row(Row r, boolean canMoveUp, boolean canMoveDown)
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

		JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		right.setOpaque(false);
		if (r.price > 0)
		{
			JPanel priceBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
			priceBox.setOpaque(false);
			JLabel price = new JLabel(QuantityFormatter.quantityToStackSize(r.price));
			price.setForeground(Color.WHITE);
			price.setFont(price.getFont().deriveFont(11f));
			priceBox.add(price);
			if (r.changePct != 0)
			{
				JLabel chg = new JLabel(String.format("%s%.1f%%", r.changePct >= 0 ? "+" : "", r.changePct));
				chg.setForeground(r.changePct >= 0 ? POSITIVE : NEGATIVE);
				chg.setFont(chg.getFont().deriveFont(10f));
				priceBox.add(chg);
			}
			right.add(priceBox);
		}
		JPanel reorderBtns = new JPanel();
		reorderBtns.setLayout(new BoxLayout(reorderBtns, BoxLayout.Y_AXIS));
		reorderBtns.setOpaque(false);
		reorderBtns.add(reorderBtn("▲", "Move up", canMoveUp, e -> actions.reorder(r.id, -1)));
		reorderBtns.add(reorderBtn("▼", "Move down", canMoveDown, e -> actions.reorder(r.id, 1)));
		right.add(reorderBtns);
		JButton remove = new JButton("×");
		remove.setToolTipText("Remove " + r.name + " from favorites");
		remove.setMargin(new java.awt.Insets(0, 4, 0, 4));
		remove.addActionListener(e -> actions.remove(r.id));
		right.add(remove);
		p.add(right, BorderLayout.EAST);

		wireSelect(p, ColorScheme.DARKER_GRAY_COLOR, r);
		if (r.atHigh5d || r.atLow5d)
		{
			wirePulse(p, r.atHigh5d ? HIGH5D : LOW5D);
		}
		return p;
	}

	private JButton reorderBtn(String glyph, String tip, boolean enabled, java.awt.event.ActionListener a)
	{
		JButton b = new JButton(glyph);
		b.setToolTipText(tip);
		b.setEnabled(enabled);
		b.setMargin(new java.awt.Insets(0, 2, 0, 2));
		b.setFont(b.getFont().deriveFont(7f));
		b.setFocusPainted(false);
		b.addActionListener(a);
		return b;
	}

	/** The expanded view for one favorite — icon/name/price/change, TARGET
	 *  BUY / TARGET SELL boxes, POTENTIAL PROFIT, and ANALYST RATING, same
	 *  fields as the website's ticker header + rating gauge. Opening the
	 *  full chart is an explicit button here, not a side effect of the click
	 *  that got you into this view. */
	private JPanel renderDetail(Row r)
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setOpaque(false);
		p.setBorder(BorderFactory.createEmptyBorder(2, 0, 4, 0));

		JButton back = new JButton("← Back to Favorites");
		back.setFocusPainted(false);
		back.setFont(back.getFont().deriveFont(10.5f));
		back.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		back.setBorder(BorderFactory.createEmptyBorder(2, 0, 8, 0));
		back.setContentAreaFilled(false);
		back.setBorderPainted(false);
		back.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		back.setAlignmentX(0f);
		back.addActionListener(e -> { selectedItemId = null; update(lastRows); });
		p.add(back);

		JPanel head = new JPanel(new BorderLayout(8, 0));
		head.setOpaque(false);
		head.setAlignmentX(0f);
		JLabel icon = iconLabel(r.id);
		icon.setPreferredSize(new Dimension(ICON_SIZE + 8, ICON_SIZE + 8));
		icon.setMinimumSize(icon.getPreferredSize());
		head.add(icon, BorderLayout.WEST);
		JPanel headText = new JPanel();
		headText.setLayout(new BoxLayout(headText, BoxLayout.Y_AXIS));
		headText.setOpaque(false);
		JLabel name = new JLabel(r.name);
		name.setForeground(Color.WHITE);
		name.setFont(name.getFont().deriveFont(Font.BOLD, 13f));
		name.setAlignmentX(0f);
		headText.add(name);
		JPanel priceRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		priceRow.setOpaque(false);
		priceRow.setAlignmentX(0f);
		JLabel price = new JLabel(r.price > 0 ? QuantityFormatter.quantityToStackSize(r.price) + " gp" : "—");
		price.setForeground(GOLD);
		price.setFont(price.getFont().deriveFont(Font.BOLD, 13f));
		priceRow.add(price);
		if (r.changePct != 0)
		{
			JLabel chg = new JLabel(String.format("%s%.1f%%", r.changePct >= 0 ? "+" : "", r.changePct));
			chg.setForeground(r.changePct >= 0 ? POSITIVE : NEGATIVE);
			chg.setFont(chg.getFont().deriveFont(11f));
			priceRow.add(chg);
		}
		headText.add(priceRow);
		head.add(headText, BorderLayout.CENTER);
		p.add(head);
		p.add(Box.createVerticalStrut(8));

		JPanel targets = new JPanel(new java.awt.GridLayout(1, 2, 6, 0));
		targets.setOpaque(false);
		targets.setAlignmentX(0f);
		targets.add(targetBox("TARGET BUY", r.targetBuy, GOLD));
		targets.add(targetBox("TARGET SELL", r.targetSell, TEAL));
		p.add(targets);
		p.add(Box.createVerticalStrut(6));

		if (r.limit > 0 && r.potentialProfit != 0)
		{
			JPanel profitRow = new JPanel(new BorderLayout());
			profitRow.setOpaque(false);
			profitRow.setAlignmentX(0f);
			profitRow.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));
			JLabel profitLbl = new JLabel("POTENTIAL PROFIT");
			profitLbl.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			profitLbl.setFont(profitLbl.getFont().deriveFont(Font.BOLD, 9f));
			profitRow.add(profitLbl, BorderLayout.WEST);
			JLabel profitVal = new JLabel((r.potentialProfit >= 0 ? "+" : "") + QuantityFormatter.quantityToStackSize(r.potentialProfit) + " gp");
			profitVal.setForeground(r.potentialProfit >= 0 ? POSITIVE : NEGATIVE);
			profitVal.setFont(profitVal.getFont().deriveFont(Font.BOLD, 12f));
			profitRow.add(profitVal, BorderLayout.EAST);
			p.add(profitRow);
			JLabel limitLbl = new JLabel(QuantityFormatter.quantityToStackSize(r.limit) + " units @ 4h limit");
			limitLbl.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			limitLbl.setFont(limitLbl.getFont().deriveFont(9.5f));
			limitLbl.setAlignmentX(0f);
			limitLbl.setBorder(BorderFactory.createEmptyBorder(0, 2, 6, 0));
			p.add(limitLbl);
		}

		if (r.rating != null)
		{
			JPanel ratingWrap = new JPanel();
			ratingWrap.setLayout(new BoxLayout(ratingWrap, BoxLayout.Y_AXIS));
			ratingWrap.setOpaque(false);
			ratingWrap.setAlignmentX(0f);
			ratingWrap.setBorder(BorderFactory.createEmptyBorder(2, 2, 6, 2));
			JLabel ratingLbl = new JLabel("ANALYST RATING");
			ratingLbl.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			ratingLbl.setFont(ratingLbl.getFont().deriveFont(Font.BOLD, 9f));
			ratingLbl.setAlignmentX(0f);
			ratingWrap.add(ratingLbl);
			JLabel ratingVal = new JLabel(r.rating.label.text + " · " + r.rating.score);
			ratingVal.setForeground(ratingColor(r.rating.label));
			ratingVal.setFont(ratingVal.getFont().deriveFont(Font.BOLD, 13f));
			ratingVal.setAlignmentX(0f);
			ratingWrap.add(ratingVal);
			ratingWrap.add(ratingBar(r.rating.score));
			p.add(ratingWrap);
		}

		JButton openChart = new JButton("Open full chart ↗");
		openChart.setAlignmentX(0f);
		openChart.setFocusPainted(false);
		openChart.setBackground(GOLD);
		openChart.setForeground(Color.BLACK);
		openChart.setFont(openChart.getFont().deriveFont(Font.BOLD, 11f));
		openChart.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		openChart.addActionListener(e -> LinkBrowser.browse("https://pocketge.com/?q=" + urlEncode(r.name)));
		p.add(openChart);
		return p;
	}

	private JPanel targetBox(String label, long price, Color accent)
	{
		JPanel box = new JPanel();
		box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
		box.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		box.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(accent, 1),
			BorderFactory.createEmptyBorder(5, 7, 5, 7)));
		JLabel lbl = new JLabel(label);
		lbl.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		lbl.setFont(lbl.getFont().deriveFont(Font.BOLD, 8.5f));
		lbl.setAlignmentX(0f);
		box.add(lbl);
		JLabel val = new JLabel(price > 0 ? QuantityFormatter.quantityToStackSize(price) : "—");
		val.setForeground(Color.WHITE);
		val.setFont(val.getFont().deriveFont(Font.BOLD, 13f));
		val.setAlignmentX(0f);
		box.add(val);
		return box;
	}

	/** A simple 5-segment strip (Strong Sell..Strong Buy) with the current
	 *  score's segment lit — a lightweight stand-in for the website's
	 *  gradient gauge bar. */
	private JPanel ratingBar(int score)
	{
		JPanel bar = new JPanel(new java.awt.GridLayout(1, 5, 2, 0));
		bar.setOpaque(false);
		bar.setAlignmentX(0f);
		bar.setPreferredSize(new Dimension(0, 5));
		bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 5));
		Color[] segColors = { TEAL, TEAL.brighter(), ColorScheme.MEDIUM_GRAY_COLOR, GOLD.darker(), GOLD };
		int lit = Math.min(4, score / 20);
		for (int i = 0; i < 5; i++)
		{
			JPanel seg = new JPanel();
			seg.setBackground(i == lit ? segColors[i] : ColorScheme.DARK_GRAY_COLOR);
			bar.add(seg);
		}
		return bar;
	}

	private static Color ratingColor(AnalystRating.Label label)
	{
		switch (label)
		{
			case STRONG_BUY: return GOLD;
			case BUY: return new Color(0xE8, 0xD9, 0xA8);
			case SELL: return new Color(0xB8, 0xE0, 0xDA);
			case STRONG_SELL: return TEAL;
			default: return ColorScheme.LIGHT_GRAY_COLOR;
		}
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

	/** Clicking a row expands it into the detail view in place — it no
	 *  longer jumps straight to the browser; that's now an explicit button
	 *  inside the detail view instead. */
	private void wireSelect(JPanel row, Color normalBg, Row r)
	{
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.setToolTipText("Show " + r.name + "'s target buy/sell, potential profit, and Analyst Rating");
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				// Swing mouse listeners are component-local (no DOM-style
				// bubbling), so a click on the remove/reorder buttons below
				// never reaches this listener — safe to always select here.
				selectedItemId = r.id;
				update(lastRows);
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
