package com.pocketge.tracker;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.QuantityFormatter;

/**
 * The last few closed flips, and a link to all of them on pocketge.com.
 *
 * The two halves do different jobs, which is why both exist. The rows answer
 * "did that actually book, and for how much?" — a question you ask in the
 * seconds after a sell fills, standing at the Exchange, where opening a
 * browser is not a reasonable answer. The link answers "how am I doing?" —
 * sorting, charting, gp per slot-hour, a ledger thousands of rows deep, none
 * of which fits in a 225px column.
 *
 * Deliberately NOT paginated. Page arrows and a "Page 1 of 1" counter are a
 * lot of furniture for a column this narrow, and paging is browsing, which is
 * the thing the link is for. This is the tail of the ledger, not a view onto
 * it, so it has a fixed small length and no controls.
 */
public class HistoryPanel extends JPanel
{
	/** How many recent flips get a row. Enough to cover a burst of fills
	 *  without the section growing past the watchlist beneath it. */
	private static final int RECENT = 5;

	private static final Color POSITIVE = new Color(0x1F, 0xB8, 0x5C);
	private static final Color NEGATIVE = new Color(0xEF, 0x53, 0x50);
	private static final Color ROW_TEXT = new Color(0xD9, 0xD3, 0xC7);

	public interface Actions
	{
		/** Routed through the plugin rather than opening a URL here, so every
		 *  chart in the sidebar reuses the same tab — see
		 *  AdvisorPanel.Actions.openChart. */
		void openChart(String itemName);
	}

	private final JLabel countLabel = new JLabel();
	private final JPanel rows = new JPanel();
	private final Actions actions;
	/** False between logout and the next login. Every flip here belongs to the
	 *  character that made it, and logged out there is no character — the same
	 *  reason the stats header blanks and the watchlist hides itself. A list of
	 *  somebody's trades sitting under "Log in to the game" is the panel
	 *  claiming to know whose they are when it does not. */
	private boolean loggedIn = true;
	/** The last list handed to {@link #update}, so logging back in can redraw
	 *  without waiting for the next refresh to come round. */
	private List<Flip> lastFlips = List.of();

	public HistoryPanel(Actions actions)
	{
		this.actions = actions;
		setLayout(new BorderLayout(0, 4));
		setOpaque(false);
		setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

		JPanel header = new JPanel(new BorderLayout(0, 2));
		header.setOpaque(false);

		countLabel.setForeground(Brand.TEXT_STRUCTURAL);
		countLabel.setFont(countLabel.getFont().deriveFont(Font.BOLD, 11f));
		header.add(countLabel, BorderLayout.WEST);

		JLabel link = new JLabel("Flip history ↗", SwingConstants.RIGHT);
		link.setForeground(Brand.TEXT_STRUCTURAL);
		link.setFont(link.getFont().deriveFont(11f));
		link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		/* Says what it needs, because the page cannot read your ledger over
		   the public internet and never will — it asks the plugin for it on
		   127.0.0.1. With the bridge off the page opens and explains that;
		   better to say so here, before the click. */
		link.setToolTipText("<html>Every flip you have ever made, on pocketge.com."
			+ "<br>Needs <b>Local website bridge</b> switched on in settings — the page"
			+ "<br>reads the ledger from this computer, not from a server.</html>");
		link.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				LinkBrowser.browse(PocketGeLinks.flips("flip_history"));
			}
		});
		header.add(link, BorderLayout.EAST);
		add(header, BorderLayout.NORTH);

		rows.setOpaque(false);
		rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
		add(rows, BorderLayout.CENTER);

		update(List.of());
	}

	/** Call on the Swing EDT when the player logs in or out. */
	public void setLoggedIn(boolean loggedIn)
	{
		if (this.loggedIn == loggedIn)
		{
			return;
		}
		this.loggedIn = loggedIn;
		update(lastFlips);
	}

	/** Call on the Swing EDT whenever the flip list changes. */
	public void update(List<Flip> flips)
	{
		lastFlips = flips != null ? flips : List.of();
		if (!loggedIn)
		{
			/* Rows gone, and no caption: the advisor's banner at the top of
			   the sidebar is the single place that explains a logged-out
			   panel. The link stays live — pocketge.com is a website and does
			   not need you logged into the game to show you your history. */
			countLabel.setText("");
			rows.removeAll();
			rows.revalidate();
			rows.repaint();
			return;
		}
		updateRows(lastFlips);
	}

	private void updateRows(List<Flip> flips)
	{
		/* One row per TRADE, not per fill. A sell offer is filled in as many
		   chunks as the Exchange finds buyers for, so one sale of 8,218
		   adamantite bars arrived here as five rows — "2 x Adamantite bar,
		   +22 gp" among them — which reports the shape of the order book
		   rather than what you did. The fills are still recorded and still
		   priced individually; they are only shown as the trade they were. */
		final List<Flip> trades = Flip.byTrade(flips);
		final int n = trades.size();
		countLabel.setText(n + (n == 1 ? " flip recorded" : " flips recorded"));

		rows.removeAll();
		/* Newest first. The tracker appends as flips close, so the tail of the
		   list is the part you have not seen yet. */
		for (int i = n - 1; i >= 0 && n - i <= RECENT; i--)
		{
			rows.add(row(trades.get(i)));
		}
		rows.revalidate();
		rows.repaint();
	}

	private JPanel row(Flip f)
	{
		final JPanel p = new JPanel(new BorderLayout(10, 0))
		{
			@Override
			public Dimension getPreferredSize()
			{
				/* Width 0, real height.
				 *
				 * BoxLayout takes its container's preferred width from the
				 * widest child, so "18,000 × Sapphire necklace" next to
				 * "-666K gp" asked for 243px inside a 217px column — which
				 * does not clip, it widens the sidebar's scroll content and
				 * puts a horizontal scrollbar under the whole panel. The row
				 * is stretched to the full column width by getMaximumSize
				 * below regardless, so it never needs to ask for any. */
				return new Dimension(0, super.getPreferredSize().height);
			}
		};
		p.setOpaque(false);
		p.setBorder(BorderFactory.createEmptyBorder(3, 0, 0, 0));
		p.setAlignmentX(0f);

		/* Abbreviated, unlike the quantity on the offer chip — that one you
		   type, this one you read. "18K ×" buys back about 25px of item name
		   in a column that has none to spare, and the exact figure is a hover
		   away. */
		final JLabel name = new JLabel(QuantityFormatter.quantityToStackSize(f.quantity)
			+ " × " + f.itemName);
		name.setForeground(ROW_TEXT);
		name.setFont(name.getFont().deriveFont(11f));

		final JLabel amount = new JLabel(signed(f.profit), SwingConstants.RIGHT);
		amount.setForeground(f.profit >= 0 ? POSITIVE : NEGATIVE);
		amount.setFont(amount.getFont().deriveFont(Font.BOLD, 11f));

		/* Name in CENTER and money in EAST, so a long item name ellipsizes
		   and the figure beside it never does — same priority as the stat
		   rows. The tooltip carries the full name either way. */
		p.add(name, BorderLayout.CENTER);
		p.add(amount, BorderLayout.EAST);

		final String tip = tooltip(f);
		name.setToolTipText(tip);
		amount.setToolTipText(tip);
		p.setToolTipText(tip);

		p.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		p.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (actions != null)
				{
					actions.openChart(f.itemName);
				}
			}
		});
		/* BoxLayout hands a component its MAXIMUM height, and a BorderLayout
		   panel's is unbounded — without this one row would swallow the
		   section. Width stays unbounded so the row still fills the column. */
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
		return p;
	}

	private static String tooltip(Flip f)
	{
		final StringBuilder sb = new StringBuilder("<html>");
		sb.append(f.itemName).append("<br>")
			.append("Bought ").append(String.format("%,d", f.quantity))
			.append(" at ").append(QuantityFormatter.quantityToStackSize(f.avgBuy())).append(" gp<br>")
			.append("Sold at ").append(QuantityFormatter.quantityToStackSize(f.avgSell())).append(" gp<br>")
			.append("Tax ").append(QuantityFormatter.quantityToStackSize(f.tax)).append(" gp<br>")
			.append("Profit ").append(signed(f.profit)).append("<br>");
		final long held = f.holdMillis();
		if (held < 0)
		{
			/* Never "0:00:00". The plugin did not watch this stack being
			   bought, so it does not know — and an instant fill is the most
			   flattering thing a blank could be mistaken for. */
			sb.append("Held — (buy time not recorded)");
		}
		else
		{
			sb.append("Held ").append(StatsHeaderPanel.formatDuration(held));
			final long perHour = f.profitPerHour();
			if (perHour != 0)
			{
				sb.append(" · ").append(signed(perHour)).append("/hr");
			}
		}
		sb.append("<br><br>Click for the chart on pocketge.com");
		return sb.toString();
	}

	private static String signed(long v)
	{
		return (v >= 0 ? "+" : "") + QuantityFormatter.quantityToStackSize(v) + " gp";
	}
}
