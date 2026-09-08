package com.pocketge.tracker;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.QuantityFormatter;

/**
 * The profit headline + stat grid at the top of the panel — Flipping
 * Copilot's Session/1h/4h/.../All-time dropdown, profit total, unrealized
 * P/L, flip count, ROI%, hourly rate, and portfolio value, all computed from
 * data the tracker + portfolio valuer already produce.
 */
public class StatsHeaderPanel extends JPanel
{
	private static final Color POSITIVE = new Color(0x1F, 0xB8, 0x5C);
	private static final Color NEGATIVE = new Color(0xEF, 0x53, 0x50);
	private static final Color GOLD = new Color(0xE5, 0xC1, 0x58);

	public interface Actions
	{
		void onRangeChanged(FlipStats.Range range);
		void onResetSession();
	}

	private final JComboBox<FlipStats.Range> rangeBox = new JComboBox<>(FlipStats.Range.values());
	private final JLabel profitLabel = new JLabel("0 gp", SwingConstants.CENTER);
	/* GridBagLayout, not GridLayout.
	 *
	 * GridLayout gives EVERY cell the width of the widest one, so this grid
	 * asked for twice its longest caption: "Unrealized profit" is 120px, and
	 * 120 x 2 + 6 = 246px demanded inside a 225px sidebar. It was the only
	 * thing in the whole panel still overflowing. Nothing clipped, because a
	 * grid compresses when it is given less than it asked for — but it
	 * compressed the VALUE column just as hard as the caption column, so the
	 * numbers lost width to pad captions that did not need it.
	 *
	 * A caption/value list wants two columns that size independently, which is
	 * what this does: preferred width is now the widest caption plus the
	 * widest value, not double either one. */
	private final JPanel statGrid = new JPanel(new GridBagLayout());
	/** Which row statRow() is filling in. */
	private int statRows = 0;
	private final JLabel unrealizedVal = new JLabel();
	private final JLabel flipsVal = new JLabel();
	private final JLabel roiVal = new JLabel();
	private final JLabel hourlyVal = new JLabel();
	private final JLabel portfolioVal = new JLabel();
	private final JLabel sessionTimeVal = new JLabel();
	/** When the tracker says this session began, 0 for "not counting". Held
	 *  here so the clock can tick between advisor refreshes, which are up to a
	 *  minute apart — a seconds display that only moved on those would jump in
	 *  minute-long steps, which reads as broken rather than as a clock. */
	private long sessionStartMillis;
	private final javax.swing.Timer sessionClock;
	/** False between logout and the next login. Everything on this panel is
	 *  account state — profit, what you hold, what your bank is worth — and
	 *  none of it can be read with no character logged in. */
	private boolean loggedIn = true;

	public StatsHeaderPanel(Actions actions)
	{
		setLayout(new BorderLayout(0, 6));
		setOpaque(false);
		setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

		JPanel top = new JPanel(new BorderLayout());
		top.setOpaque(false);
		rangeBox.addActionListener(e -> actions.onRangeChanged((FlipStats.Range) rangeBox.getSelectedItem()));
		top.add(rangeBox, BorderLayout.WEST);
		javax.swing.JButton reset = new javax.swing.JButton("Reset session");
		reset.addActionListener(e -> actions.onResetSession());
		JPanel resetWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
		resetWrap.setOpaque(false);
		resetWrap.add(reset);
		top.add(resetWrap, BorderLayout.EAST);
		add(top, BorderLayout.NORTH);

		profitLabel.setFont(profitLabel.getFont().deriveFont(Font.BOLD, 24f));
		profitLabel.setBorder(BorderFactory.createEmptyBorder(6, 0, 8, 0));
		add(profitLabel, BorderLayout.CENTER);

		statGrid.setOpaque(false);
		/* "Unrealized", not "Unrealized profit". GridBag sizes a column to the
		   widest cell IN THAT COLUMN, so this one caption set the width of all
		   five — and paired with the widest value ("-987.7M gp/hr") it still
		   wanted 221px inside 217. The word "profit" was the 44px that did it,
		   and it is the least load-bearing word on the panel: everything here
		   is profit, the headline above it is a profit figure, and "unrealized"
		   is the term of art on its own. The full phrase is on the tooltip. */
		statRow("Unrealized", unrealizedVal, "Unrealized profit \u2014 what your open positions are worth "
			+ "against what you paid, before you have sold anything.");
		statRow("Flips made", flipsVal, null);
		statRow("ROI", roiVal, null);
		statRow("Hourly profit", hourlyVal, null);
		statRow("Portfolio value", portfolioVal, null);
		statRow("Session time", sessionTimeVal,
			"How long this session has been running. Resets with Reset session, and "
				+ "stops when you log out \u2014 it counts time played, not time the client was open.");
		add(statGrid, BorderLayout.SOUTH);

		/* One second, and only ever repaints one label. Started here rather
		   than on first update() so the field reads 0:00:00 immediately
		   instead of staying blank until the first advisor cycle lands. */
		sessionClock = new javax.swing.Timer(1000, e -> tickSessionTime());
		sessionClock.start();
		tickSessionTime();
	}

	/** The session clock, as H:MM:SS. */
	private void tickSessionTime()
	{
		sessionTimeVal.setText(formatDuration(loggedIn && sessionStartMillis > 0
			? System.currentTimeMillis() - sessionStartMillis : 0));
	}

	static String formatDuration(long millis)
	{
		final long total = Math.max(0, millis) / 1000L;
		return String.format("%d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60);
	}

	/**
	 * Blank the panel between logout and the next login.
	 *
	 * Every figure here is account state. Logged out there is no bank, no
	 * inventory and no offers to read, so the plugin cannot recompute any of
	 * it — and what was on screen simply stayed there. That is how a logged-out
	 * client sat showing "Unrealized +358K": not a live number, the last one
	 * from before you logged out, presented exactly like a live one.
	 *
	 * Zeroed rather than hidden, so the panel keeps its shape and you can see
	 * what it will tell you once you are in. The clock stops too — it measures
	 * time played, not time the client was left open.
	 */
	public void setLoggedIn(boolean loggedIn)
	{
		if (this.loggedIn == loggedIn)
		{
			return;
		}
		this.loggedIn = loggedIn;
		if (!loggedIn)
		{
			blank();
		}
		tickSessionTime();
	}

	/** The zero state. Same strings update() would produce for an empty
	 *  session, so logging out and logging back in with nothing traded look
	 *  identical — as they should. */
	private void blank()
	{
		profitLabel.setText("0 gp");
		profitLabel.setForeground(POSITIVE);
		unrealizedVal.setText("+0 gp");
		unrealizedVal.setForeground(POSITIVE);
		flipsVal.setText("0");
		roiVal.setText("0.00%");
		roiVal.setForeground(POSITIVE);
		hourlyVal.setText("+0 gp/hr");
		hourlyVal.setForeground(POSITIVE);
		portfolioVal.setText("0 gp");
		portfolioVal.setForeground(GOLD);
	}

	private void statRow(String label, JLabel valueLabel, String tooltip)
	{
		JLabel k = new JLabel(label);
		k.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		k.setFont(k.getFont().deriveFont(12f));
		/* The caption is the half allowed to ellipsize (see below), so it
		   always carries its own text on hover — plus a fuller explanation
		   where the visible label had to be abbreviated to fit. */
		k.setToolTipText(tooltip != null ? tooltip : label);
		valueLabel.setHorizontalAlignment(SwingConstants.RIGHT);
		valueLabel.setForeground(Color.WHITE);
		valueLabel.setFont(valueLabel.getFont().deriveFont(Font.BOLD, 12f));

		/* The CAPTION column carries the weight, not the value column, and that
		   is deliberate in both directions:

		   spare width -> the caption's cell grows, so the caption stays hard
		   left and the number stays hard right, which is the shape a stat list
		   is supposed to have;

		   short width -> GridBag takes the shortfall from the weighted column
		   first, so a cramped sidebar eats into "Unrealized profit" (which
		   ellipsises, and has a tooltip) rather than into "+1.2M gp", which is
		   the thing you are actually reading. Squeezing the number to protect
		   the word for it would be exactly backwards. */
		final GridBagConstraints key = new GridBagConstraints();
		key.gridx = 0;
		key.gridy = statRows;
		key.weightx = 1;
		key.anchor = GridBagConstraints.WEST;
		key.insets = new Insets(statRows == 0 ? 0 : 5, 0, 0, 6);
		statGrid.add(k, key);

		final GridBagConstraints value = new GridBagConstraints();
		value.gridx = 1;
		value.gridy = statRows;
		value.weightx = 0;
		value.anchor = GridBagConstraints.EAST;
		value.insets = new Insets(statRows == 0 ? 0 : 5, 0, 0, 0);
		statGrid.add(valueLabel, value);
		statRows++;
	}

	/** Call on the Swing EDT after the range dropdown selection changes, so
	 *  the visible selection matches the caller's authoritative state
	 *  (e.g. after config load) without re-triggering onRangeChanged. */
	public void setSelectedRangeQuietly(FlipStats.Range range)
	{
		if (rangeBox.getSelectedItem() != range)
		{
			rangeBox.setSelectedItem(range);
		}
	}

	public void update(FlipStats.Stats stats, PortfolioValuer.Result portfolio, long sessionStartMillis)
	{
		this.sessionStartMillis = sessionStartMillis;
		tickSessionTime();
		if (!loggedIn)
		{
			/* A refresh can still land after logout — the advisor cycle runs on
			   its own timer and does not stop. Dropping it here is what keeps
			   the blanked panel blank, rather than having stale figures quietly
			   reappear a few seconds later. */
			return;
		}
		long profit = stats.profit;
		profitLabel.setText((profit >= 0 ? "+" : "") + QuantityFormatter.quantityToStackSize(profit) + " gp");
		profitLabel.setForeground(profit >= 0 ? POSITIVE : NEGATIVE);

		unrealizedVal.setText(signed(stats.unrealizedProfit));
		unrealizedVal.setForeground(stats.unrealizedProfit >= 0 ? POSITIVE : NEGATIVE);

		flipsVal.setText(String.valueOf(stats.flipCount));

		roiVal.setText(String.format("%.2f%%", stats.roiPct));
		roiVal.setForeground(stats.roiPct >= 0 ? POSITIVE : NEGATIVE);

		hourlyVal.setText(signed(stats.hourlyRate) + "/hr");
		hourlyVal.setForeground(stats.hourlyRate >= 0 ? POSITIVE : NEGATIVE);

		portfolioVal.setText(QuantityFormatter.quantityToStackSize(portfolio.total) + " gp");
		portfolioVal.setForeground(GOLD);
	}

	private static String signed(long v)
	{
		return (v >= 0 ? "+" : "") + QuantityFormatter.quantityToStackSize(v) + " gp";
	}
}
