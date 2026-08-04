package com.pocketge.tracker;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.LinkBrowser;

/**
 * Flip history is a reporting feature, not something worth a scrolling,
 * paginated list eating space in a narrow sidebar — this just shows how many
 * flips are on record and links out to the full history on pocketge.com.
 */
public class HistoryPanel extends JPanel
{
	private final JLabel countLabel = new JLabel();

	public HistoryPanel()
	{
		setLayout(new BorderLayout(0, 2));
		setOpaque(false);
		setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

		countLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		countLabel.setFont(countLabel.getFont().deriveFont(12f));
		add(countLabel, BorderLayout.WEST);

		JLabel link = new JLabel("Flip history ↗", SwingConstants.RIGHT);
		link.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		link.setFont(link.getFont().deriveFont(12f));
		link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		link.setToolTipText("View your full flip history on pocketge.com");
		link.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				LinkBrowser.browse("https://pocketge.com/");
			}
		});
		add(link, BorderLayout.EAST);

		update(List.of());
	}

	/** Call on the Swing EDT whenever the flip list changes. */
	public void update(List<Flip> flips)
	{
		int n = flips.size();
		countLabel.setText(n + (n == 1 ? " flip recorded" : " flips recorded"));
	}
}
