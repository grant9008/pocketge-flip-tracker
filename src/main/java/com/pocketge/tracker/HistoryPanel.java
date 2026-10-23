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
 * the thing the link is for. This is the tail of the ledger for the range
 * picked above it: the latest five, and the rest of the range on request.
 */
public class HistoryPanel extends JPanel
{
	/** How many recent flips get a row. Enough to cover a burst of fills
	 *  without the section growing past the watchlist beneath it. */
	private static final int RECENT = 5;
	/** "Show all" stops here: a sidebar row per trade is fine for a day's
	 *  work and silly for years of it, which the Flip history page is for. */
	static final int SHOW_ALL_CAP = 200;

	private static final Color POSITIVE = new Color(0x1F, 0xB8, 0x5C);
	private static final Color NEGATIVE = new Color(0xEF, 0x53, 0x50);
	private static final Color ROW_TEXT = new Color(0xD9, 0xD3, 0xC7);
	/** The stats rows' caption colour, so the list's heading reads as one
	 *  of them rather than as a footnote under them. */
	private static final Color HEADING = new Color(0xB8, 0xB0, 0xA2);
	/** Every line here is the size the stats rows above it are. At 11 this
	 *  list was the smallest text in the sidebar, under the biggest numbers:
	 *  "can this area be clearer to read". */
	private static final float TEXT_SIZE = 12f;
	private static final javax.swing.border.Border LIST_RULE = BorderFactory.createCompoundBorder(
		BorderFactory.createMatteBorder(1, 0, 0, 0, Brand.BORDER_LIGHT),
		BorderFactory.createEmptyBorder(1, 0, 0, 0));

	public interface Actions
	{
		/** Routed through the plugin rather than opening a URL here, so every
		 *  chart in the sidebar reuses the same tab — see
		 *  AdvisorPanel.Actions.openChart. */
		void openChart(String itemName);

		/** The player says this trade's buys cost {@code unitCost} each —
		 *  see PocketGeTrackerPlugin.correctPaid. */
		default void correctPaid(Flip trade, long unitCost)
		{
		}
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
		/* The same side padding as the stats above it, so the flip rows
		   line up with them and stop at the same margin. */
		setBorder(BorderFactory.createEmptyBorder(8, StatsHeaderPanel.SIDE_PAD, 0, StatsHeaderPanel.SIDE_PAD));

		JPanel header = new JPanel(new BorderLayout(0, 2));
		header.setOpaque(false);

		countLabel.setForeground(HEADING);
		countLabel.setFont(countLabel.getFont().deriveFont(Font.BOLD, TEXT_SIZE));
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

		/* The rest of the range on request. The list keeps to the latest
		   five so the section stays short; "if you have all time, then youd
		   see all the flips youve ever made" is one click from here. */
		moreLink.setForeground(Brand.TEXT_STRUCTURAL);
		moreLink.setFont(moreLink.getFont().deriveFont(11f));
		moreLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		moreLink.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		moreLink.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				showAll = !showAll;
				update(lastFlips, range);
			}
		});
		add(moreLink, BorderLayout.SOUTH);

		update(List.of());
	}

	/** The range the list is showing — the one picked above the stats. */
	private FlipStats.Range range = FlipStats.Range.ALL;
	/** Whether every trade in the range is listed, not just the latest few. */
	private boolean showAll;
	private final JLabel moreLink = new JLabel();

	/** How many rows to draw of {@code n} trades. */
	static int shown(int n, boolean showAll)
	{
		return Math.min(n, showAll ? SHOW_ALL_CAP : RECENT);
	}

	/** The toggle under the rows, or "" when everything already fits. */
	static String moreText(int n, boolean showAll)
	{
		if (n <= RECENT)
		{
			return "";
		}
		if (showAll)
		{
			return n > SHOW_ALL_CAP ? "Latest " + SHOW_ALL_CAP + " shown · show fewer" : "Show fewer";
		}
		return "Show all " + Math.min(n, SHOW_ALL_CAP) + (n > SHOW_ALL_CAP ? " latest" : "");
	}

	/** "3 flips in the last hour", "No flips this session". */
	static String countText(int n, FlipStats.Range range)
	{
		final String when;
		switch (range != null ? range : FlipStats.Range.ALL)
		{
			case SESSION:
				when = "this session";
				break;
			case H1:
				when = "in the last hour";
				break;
			case H4:
				when = "in the last 4 hours";
				break;
			case H12:
				when = "in the last 12 hours";
				break;
			case D1:
				when = "in the last day";
				break;
			case W1:
				when = "in the last week";
				break;
			case M1:
				when = "in the last month";
				break;
			default:
				when = "recorded";
		}
		if (n == 0)
		{
			return range == FlipStats.Range.ALL || range == null ? "No flips recorded yet" : "No flips " + when;
		}
		return n + (n == 1 ? " flip " : " flips ") + when;
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
		update(flips, range);
	}

	/** The flips in {@code range}, which the count says. EDT. */
	public void update(List<Flip> flips, FlipStats.Range range)
	{
		this.range = range != null ? range : FlipStats.Range.ALL;
		lastFlips = flips != null ? flips : List.of();
		if (!loggedIn)
		{
			/* Rows gone, and no caption: the advisor's banner at the top of
			   the sidebar is the single place that explains a logged-out
			   panel. The link stays live — pocketge.com is a website and does
			   not need you logged into the game to show you your history. */
			countLabel.setText("");
			moreLink.setText("");
			rows.removeAll();
			rows.setBorder(null);
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
		countLabel.setText(countText(n, range));

		rows.removeAll();
		/* A hairline under the heading, so the rows read as its list — and
		   only over rows: with none it was a rule with nothing under it. */
		rows.setBorder(n > 0 ? LIST_RULE : null);
		/* Newest first. The tracker appends as flips close, so the tail of the
		   list is the part you have not seen yet. */
		final int show = shown(n, showAll);
		for (int i = n - 1; i >= 0 && n - i <= show; i--)
		{
			rows.add(row(trades.get(i)));
		}
		moreLink.setText(moreText(n, showAll));
		moreLink.setVisible(!moreLink.getText().isEmpty());
		rows.revalidate();
		rows.repaint();
	}

	/**
	 * Right-click a trade: "Correct what I paid…". For a sale booked against
	 * buys the player no longer had — "it thinks i paid 359 each for these,
	 * i only paid 280 each. i lost money but i didnt lose that much money".
	 * Only on a trade with an offer token, which names its fills exactly;
	 * older rows are grouped by inference, and re-pricing the wrong set of
	 * fills would be worse than none. Null for a row without one.
	 */
	private MouseAdapter correctMenu(Flip f, JPanel row)
	{
		if (f.offerId == 0 || f.quantity <= 0 || actions == null)
		{
			return null;
		}
		return new MouseAdapter()
		{
			@Override public void mousePressed(MouseEvent e) { maybeShow(e); }
			@Override public void mouseReleased(MouseEvent e) { maybeShow(e); }

			private void maybeShow(MouseEvent e)
			{
				if (!e.isPopupTrigger())
				{
					return;
				}
				final javax.swing.JPopupMenu m = new javax.swing.JPopupMenu();
				final javax.swing.JMenuItem fix = new javax.swing.JMenuItem("Correct what I paid\u2026");
				fix.addActionListener(a -> askPaid(f, row));
				m.add(fix);
				m.show(e.getComponent(), e.getX(), e.getY());
			}
		};
	}

	private void askPaid(Flip f, java.awt.Component near)
	{
		final long paidEach = Math.round(f.buySpent / (double) f.quantity);
		String note = "";
		Object prefill = String.valueOf(paidEach);
		while (true)
		{
			final String ask = "<html><div style='width:220px'>What did you pay each for these <b>"
				+ String.format("%,d", f.quantity) + " " + f.itemName + "</b>?<br><br>PocketGE has <b>"
				+ String.format("%,d", paidEach) + "</b> gp each, so this sale shows <b>" + signed(f.profit) + "</b>."
				+ "<br><br><span style='color:#a5a5a5'>Re-prices this sale in your history and totals. What it sold"
				+ " for and the tax stay as they were.</span>" + note + "</div></html>";
			final Object answer = javax.swing.JOptionPane.showInputDialog(near, ask, "Correct what I paid",
				javax.swing.JOptionPane.PLAIN_MESSAGE, null, null, prefill);
			if (answer == null)
			{
				return; // cancelled
			}
			final long unitCost = parsePaid(answer);
			if (unitCost > 0)
			{
				if (unitCost != paidEach)
				{
					actions.correctPaid(f, unitCost);
				}
				return;
			}
			/* Not left to vanish: a price typed and OK'd that did nothing
			   looked like it had worked. */
			note = "<br><br><span style='color:#ef5350'>Couldn't read \"" + escape(answer.toString())
				+ "\" as a price. Type a number like 277, 1.2k or 45m.</span>";
			prefill = answer;
		}
	}

	private static String escape(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	/** "277", "277 gp", "1,250", "1.2k", "45m", "2.1b" -> gp; anything else
	 *  -> 0. The game's own number boxes take k, m and b, so players type
	 *  them. */
	static long parsePaid(Object answer)
	{
		if (answer == null)
		{
			return 0;
		}
		final String t = answer.toString().trim().toLowerCase().replaceAll("(gp|,|\\s)", "");
		final java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,12})(?:\\.(\\d{1,3}))?([kmb]?)").matcher(t);
		if (!m.matches())
		{
			return 0;
		}
		final long scale = "k".equals(m.group(3)) ? 1_000L : "m".equals(m.group(3)) ? 1_000_000L
			: "b".equals(m.group(3)) ? 1_000_000_000L : 1L;
		final java.math.BigDecimal v = new java.math.BigDecimal(m.group(1) + (m.group(2) != null ? "." + m.group(2) : ""))
			.multiply(java.math.BigDecimal.valueOf(scale));
		if (v.signum() <= 0 || v.compareTo(java.math.BigDecimal.valueOf(Integer.MAX_VALUE)) > 0)
		{
			return 0; // no item costs that much, and a GE price is an int
		}
		return v.setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
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
		p.setBorder(BorderFactory.createEmptyBorder(4, 0, 1, 0));
		p.setAlignmentX(0f);

		/* Abbreviated, unlike the quantity on the offer chip — that one you
		   type, this one you read. "18K ×" buys back about 25px of item name
		   in a column that has none to spare, and the exact figure is a hover
		   away. Muted, and its own label: the count and the name ran
		   together in one colour, and the name is what the eye looks for. */
		final JLabel qty = new JLabel(QuantityFormatter.quantityToStackSize(f.quantity) + " × ");
		qty.setForeground(Brand.TEXT_STRUCTURAL);
		qty.setFont(qty.getFont().deriveFont(TEXT_SIZE));
		final JLabel name = new JLabel(f.itemName);
		name.setForeground(ROW_TEXT);
		name.setFont(name.getFont().deriveFont(TEXT_SIZE));

		final JLabel amount = new JLabel(signed(f.profit), SwingConstants.RIGHT);
		amount.setForeground(f.profit >= 0 ? POSITIVE : NEGATIVE);
		amount.setFont(amount.getFont().deriveFont(Font.BOLD, TEXT_SIZE));

		/* Name in CENTER and money in EAST, so a long item name ellipsizes
		   and the figure beside it never does — same priority as the stat
		   rows. The tooltip carries the full name either way. */
		final JPanel what = new JPanel(new BorderLayout(0, 0));
		what.setOpaque(false);
		what.add(qty, BorderLayout.WEST);
		what.add(name, BorderLayout.CENTER);
		p.add(what, BorderLayout.CENTER);
		p.add(amount, BorderLayout.EAST);

		final String tip = tooltip(f);
		qty.setToolTipText(tip);
		name.setToolTipText(tip);
		amount.setToolTipText(tip);
		p.setToolTipText(tip);

		p.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		final MouseAdapter open = new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				/* Left click only. RuneLite's popups are heavyweight, and on
				   Windows the click that follows a right-click's menu reached
				   the row too, opening the chart under "Correct what I
				   paid…". */
				if (actions != null && !e.isPopupTrigger() && !javax.swing.SwingUtilities.isRightMouseButton(e))
				{
					actions.openChart(f.itemName);
				}
			}
		};
		/* On the labels as well as the row. A label with a tooltip has a
		   mouse listener of its own (ToolTipManager's), so a click on the
		   text went to the label and never reached the row — only the gap
		   between name and figure opened the chart. */
		final MouseAdapter menu = correctMenu(f, p);
		for (java.awt.Component c : new java.awt.Component[]{p, qty, name, amount})
		{
			c.addMouseListener(open);
			if (menu != null)
			{
				c.addMouseListener(menu);
			}
		}
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
