package com.pocketge.tracker;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.LinkBrowser;

/**
 * The plugin-side "Find Opportunities" section — same idea as
 * pocketge.com's sidebar scanner, collapsed under one header by default so
 * it doesn't compete with Favorites for space. Only the three categories
 * FinderEngine actually computes (High Vol Margins, Low Vol Margins,
 * Biggest Losers 24H) — see FinderEngine's own doc comment for why
 * Reliable 14D Margins and At 5D Highs/Lows aren't here.
 */
public class FinderPanel extends JPanel
{
	public interface Actions
	{
		void addFavorite(int itemId, String name);
	}

	/** One resolved row — id/name already looked up, metric already
	 *  formatted (margin gp or a % string) so this panel doesn't need to
	 *  know which category it's rendering. */
	public static class Row
	{
		public int id;
		public String name;
		public String metricText;
		public Color metricColor;
		public long vol;
	}

	private static final Color POSITIVE = new Color(0x1F, 0xB8, 0x5C);
	private static final Color NEGATIVE = new Color(0xEF, 0x53, 0x50);
	private static final int ICON_SIZE = 18;

	private final ItemManager itemManager;
	private final Actions actions;
	private final JPanel body = new JPanel();
	private final Group highVol = new Group("High Vol Margins");
	private final Group lowVol = new Group("Low Vol Margins");
	private final Group losers = new Group("Biggest Losers (24H)");
	private boolean open = false;

	public FinderPanel(ItemManager itemManager, Actions actions)
	{
		this.itemManager = itemManager;
		this.actions = actions;
		setLayout(new BorderLayout());
		setOpaque(false);

		// Plain text, not an emoji glyph — font fallback for emoji is
		// inconsistent across the JREs RuneLite runs on (same reasoning as
		// AdvisorPanel's chart/share icons, which are drawn instead).
		final JLabel headerLabel = new JLabel("Find Opportunities");
		headerLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD, 12f));
		final JPanel header = new JPanel(new BorderLayout());
		header.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));
		header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		header.add(headerLabel, BorderLayout.WEST);
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setOpaque(false);
		body.setVisible(open);
		body.add(highVol);
		body.add(lowVol);
		body.add(losers);

		header.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				open = !open;
				body.setVisible(open);
				revalidate();
				repaint();
			}
		});

		add(header, BorderLayout.NORTH);
		add(body, BorderLayout.CENTER);
	}

	/** Rebuild all three groups. Call on the EDT. Each list should already
	 *  be capped (top 10ish) and resolved to display rows by the caller. */
	public void update(List<Row> highVolRows, List<Row> lowVolRows, List<Row> loserRows)
	{
		highVol.setRows(highVolRows);
		lowVol.setRows(lowVolRows);
		losers.setRows(loserRows);
		revalidate();
		repaint();
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

	private static String truncateName(String name)
	{
		final int max = 18;
		return name.length() > max ? name.substring(0, max - 1) + "…" : name;
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

	/** One nested collapsible sub-section — its own header (name only, no
	 *  icon/chevron needed at this depth) + a stack of rows, or a small
	 *  "nothing right now" line when empty. Collapsed by default, same as
	 *  the outer Find Opportunities wrapper. */
	private class Group extends JPanel
	{
		private final JPanel rows = new JPanel();
		private boolean groupOpen = false;

		Group(String title)
		{
			setLayout(new BorderLayout());
			setOpaque(false);
			setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 0));

			final JLabel titleLabel = new JLabel(title);
			titleLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			titleLabel.setFont(titleLabel.getFont().deriveFont(11f));
			titleLabel.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
			titleLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

			rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
			rows.setOpaque(false);
			rows.setVisible(groupOpen);

			titleLabel.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					groupOpen = !groupOpen;
					rows.setVisible(groupOpen);
					FinderPanel.this.revalidate();
					FinderPanel.this.repaint();
				}
			});

			add(titleLabel, BorderLayout.NORTH);
			add(rows, BorderLayout.CENTER);
		}

		void setRows(List<Row> data)
		{
			rows.removeAll();
			if (data == null || data.isEmpty())
			{
				JLabel empty = new JLabel("Nothing qualifying right now");
				empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				empty.setFont(empty.getFont().deriveFont(10.5f));
				empty.setBorder(BorderFactory.createEmptyBorder(2, 4, 4, 0));
				rows.add(empty);
				return;
			}
			for (Row r : data)
			{
				rows.add(row(r));
			}
		}

		private JPanel row(Row r)
		{
			JPanel p = new JPanel(new BorderLayout(6, 0));
			p.setOpaque(false);
			p.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 4));
			p.add(iconLabel(r.id), BorderLayout.WEST);
			JLabel name = new JLabel(truncateName(r.name));
			name.setToolTipText(r.name);
			name.setForeground(Color.WHITE);
			name.setFont(name.getFont().deriveFont(11.5f));
			p.add(name, BorderLayout.CENTER);
			JLabel metric = new JLabel(r.metricText);
			metric.setForeground(r.metricColor != null ? r.metricColor : ColorScheme.LIGHT_GRAY_COLOR);
			metric.setFont(metric.getFont().deriveFont(Font.BOLD, 11f));
			p.add(metric, BorderLayout.EAST);
			p.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			p.setToolTipText("Open the live " + r.name + " chart on PocketGE — right-click to favorite it");
			p.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					if (javax.swing.SwingUtilities.isRightMouseButton(e))
					{
						return;
					}
					LinkBrowser.browse("https://pocketge.com/?q=" + urlEncode(r.name));
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
					JMenuItem fav = new JMenuItem("Add to favorites");
					fav.addActionListener(a -> actions.addFavorite(r.id, r.name));
					menu.add(fav);
					menu.show(p, e.getX(), e.getY());
				}
			});
			return p;
		}
	}
}
