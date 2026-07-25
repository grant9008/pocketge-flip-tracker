package com.pocketge.tracker;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
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
	private final JPanel statGrid = new JPanel(new GridLayout(0, 2, 4, 3));
	private final JLabel unrealizedVal = new JLabel();
	private final JLabel flipsVal = new JLabel();
	private final JLabel roiVal = new JLabel();
	private final JLabel hourlyVal = new JLabel();
	private final JLabel portfolioVal = new JLabel();

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
		statRow("Unrealized profit", unrealizedVal);
		statRow("Flips made", flipsVal);
		statRow("ROI", roiVal);
		statRow("Hourly profit", hourlyVal);
		statRow("Portfolio value", portfolioVal);
		add(statGrid, BorderLayout.SOUTH);
	}

	private void statRow(String label, JLabel valueLabel)
	{
		JLabel k = new JLabel(label);
		k.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		k.setFont(k.getFont().deriveFont(11f));
		valueLabel.setHorizontalAlignment(SwingConstants.RIGHT);
		valueLabel.setForeground(Color.WHITE);
		valueLabel.setFont(valueLabel.getFont().deriveFont(11f));
		statGrid.add(k);
		statGrid.add(valueLabel);
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

	public void update(FlipStats.Stats stats, PortfolioValuer.Result portfolio)
	{
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
