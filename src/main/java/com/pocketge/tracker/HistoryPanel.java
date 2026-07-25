package com.pocketge.tracker;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.List;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.QuantityFormatter;

/**
 * Completed flip history, paginated like Flipping Copilot's "Page N of M" —
 * a flat list gets unwieldy once you've made a few hundred flips. Each row
 * links out to the live PocketGE chart for that item, and its star toggles
 * the Favorites watchlist (same as a suggestion card's star).
 */
public class HistoryPanel extends JPanel
{
	private static final Color POSITIVE = new Color(0x1F, 0xB8, 0x5C);
	private static final Color NEGATIVE = new Color(0xEF, 0x53, 0x50);
	private static final Color HOVER_BG = new Color(0x3A, 0x33, 0x28);
	private static final int PAGE_SIZE = 10;
	private static final int ICON_SIZE = 26;

	public interface Actions
	{
		void toggleFavorite(int itemId, String name);
	}

	private final ItemManager itemManager;
	private final Actions actions;
	private final JPanel rows = new JPanel();
	private final JLabel pageLabel = new JLabel("Page 1 of 1", SwingConstants.CENTER);
	private final JButton prevBtn = new JButton("‹");
	private final JButton nextBtn = new JButton("›");

	private List<Flip> flips = List.of();
	private Set<Integer> favoriteIds = Set.of();
	private int page = 0; // 0-indexed, page 0 = most recent flips

	public HistoryPanel(ItemManager itemManager, Actions actions)
	{
		this.itemManager = itemManager;
		this.actions = actions;
		setLayout(new BorderLayout(0, 6));
		setOpaque(false);
		setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

		JLabel title = new JLabel("Flip history");
		title.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		title.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
		add(title, BorderLayout.NORTH);

		rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
		rows.setOpaque(false);
		add(rows, BorderLayout.CENTER);

		JPanel pager = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
		pager.setOpaque(false);
		prevBtn.addActionListener(e -> { page = Math.max(0, page - 1); render(); });
		nextBtn.addActionListener(e -> { page = Math.min(maxPage(), page + 1); render(); });
		pageLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		pager.add(prevBtn);
		pager.add(pageLabel);
		pager.add(nextBtn);
		add(pager, BorderLayout.SOUTH);
	}

	/** Rebuild from the latest flip list. Call on the Swing EDT. Newest-first;
	 *  jumps back to page 0 only if the flip count changed (a new flip just
	 *  landed), so paging through history isn't reset by an unrelated
	 *  background refresh. */
	public void update(List<Flip> allFlips, Set<Integer> favoriteIds)
	{
		boolean changed = allFlips.size() != flips.size();
		this.flips = allFlips;
		this.favoriteIds = favoriteIds != null ? favoriteIds : Set.of();
		if (changed)
		{
			page = 0;
		}
		page = Math.min(page, maxPage());
		render();
	}

	private int maxPage()
	{
		return Math.max(0, (flips.size() - 1) / PAGE_SIZE);
	}

	private void render()
	{
		rows.removeAll();
		if (flips.isEmpty())
		{
			JLabel empty = new JLabel("<html><center>No completed flips yet.<br>Buy low, sell high — fills are tracked automatically.</center></html>", SwingConstants.CENTER);
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			empty.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
			rows.add(empty);
		}
		else
		{
			int from = flips.size() - 1 - page * PAGE_SIZE;
			int to = Math.max(-1, from - PAGE_SIZE);
			for (int i = from; i > to && i >= 0; i--)
			{
				rows.add(row(flips.get(i)));
				rows.add(Box.createVerticalStrut(6));
			}
		}
		pageLabel.setText("Page " + (page + 1) + " of " + (maxPage() + 1));
		prevBtn.setEnabled(page > 0);
		nextBtn.setEnabled(page < maxPage());
		revalidate();
		repaint();
	}

	private JPanel row(Flip f)
	{
		JPanel p = new JPanel(new BorderLayout(6, 0));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 6));
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 64));

		JLabel icon = iconLabel(f.itemId);

		JPanel text = new JPanel(new GridLayout(0, 1, 0, 2));
		text.setOpaque(false);
		JLabel name = new JLabel(f.itemName + "  ×" + NumberFormat.getIntegerInstance().format(f.quantity));
		name.setForeground(Color.WHITE);
		JLabel detail = new JLabel(QuantityFormatter.quantityToStackSize(f.avgBuy()) + " → "
			+ QuantityFormatter.quantityToStackSize(f.avgSell()) + "   "
			+ (f.profit >= 0 ? "+" : "") + QuantityFormatter.quantityToStackSize(f.profit) + " gp");
		detail.setForeground(f.profit >= 0 ? POSITIVE : NEGATIVE);
		text.add(name);
		text.add(detail);

		JPanel left = new JPanel(new BorderLayout(6, 0));
		left.setOpaque(false);
		left.add(icon, BorderLayout.WEST);
		left.add(text, BorderLayout.CENTER);
		p.add(left, BorderLayout.CENTER);

		boolean fav = favoriteIds.contains(f.itemId);
		JButton star = new JButton(fav ? "★" : "☆");
		star.setToolTipText(fav ? "Remove " + f.itemName + " from favorites" : "Add " + f.itemName + " to favorites");
		star.setFocusPainted(false);
		star.setMargin(new java.awt.Insets(2, 4, 2, 4));
		star.setFont(star.getFont().deriveFont(11f));
		star.addActionListener(e -> actions.toggleFavorite(f.itemId, f.itemName));
		p.add(star, BorderLayout.EAST);

		wireOpenChart(p, ColorScheme.DARKER_GRAY_COLOR, f.itemName);
		return p;
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
