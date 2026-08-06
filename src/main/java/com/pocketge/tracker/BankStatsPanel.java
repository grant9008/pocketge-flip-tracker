package com.pocketge.tracker;

import java.awt.Component;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.QuantityFormatter;

/**
 * Always-visible bank value / liquid bank value, pinned above everything
 * else in the sidebar so it never scrolls out of view — the number you
 * check most often shouldn't need scrolling to find.
 */
public class BankStatsPanel extends JPanel
{
	private final JLabel bankValueLabel = new JLabel("Bank: —");
	private final JLabel liquidLabel = new JLabel(" ");

	public BankStatsPanel()
	{
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(false);
		setBorder(BorderFactory.createEmptyBorder(0, 1, 8, 0));

		bankValueLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR.brighter());
		bankValueLabel.setFont(bankValueLabel.getFont().deriveFont(java.awt.Font.BOLD, 16f));
		bankValueLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

		liquidLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		liquidLabel.setFont(liquidLabel.getFont().deriveFont(10.5f));
		liquidLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

		add(bankValueLabel);
		add(liquidLabel);
	}

	/** Call on the EDT whenever the plugin recomputes stats. */
	public void update(long bankValue, long liquidValue, boolean bankSeen)
	{
		if (!bankSeen)
		{
			bankValueLabel.setText("Bank: open it once in-game to see this");
			bankValueLabel.setToolTipText("RuneLite can't read bank contents until you've opened it at least once this session.");
			liquidLabel.setText(" ");
			liquidLabel.setToolTipText(null);
			return;
		}
		bankValueLabel.setText("Bank: " + QuantityFormatter.quantityToStackSize(bankValue));
		bankValueLabel.setToolTipText("Everything in your bank, priced at today's insta-sell.");
		liquidLabel.setText("Liquid: " + QuantityFormatter.quantityToStackSize(liquidValue));
		liquidLabel.setToolTipText("Just the coins sitting in your bank — spendable right now with no selling needed.");
	}
}
