package com.pocketge.tracker;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
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

	/** Resolved display row — the plugin looks up the live price, the panel
	 *  just renders it. */
	public static class Row
	{
		public int id;
		public String name;
		public long price;       // current insta-sell (low), 0 if unknown
		public double changePct; // vs today's typical (24h average), 0 if unknown
	}

	public interface Actions
	{
		void remove(int itemId);
	}

	private final Actions actions;
	private final JPanel rows = new JPanel();

	public FavoritesPanel(Actions actions)
	{
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

		JLabel name = new JLabel(r.name);
		name.setForeground(Color.WHITE);
		name.setFont(name.getFont().deriveFont(11.5f));
		p.add(name, BorderLayout.WEST);

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

		p.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		p.setToolTipText("Open the live " + r.name + " chart on PocketGE");
		p.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				// Swing mouse listeners are component-local (no DOM-style
				// bubbling), so a click on the remove button below never
				// reaches this listener — safe to always open the chart here.
				LinkBrowser.browse("https://pocketge.com/?q=" + urlEncode(r.name));
			}
		});
		return p;
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
