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
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.QuantityFormatter;

/**
 * Sidebar panel: session profit, then the most recent completed flips.
 * Every row links to the live PocketGE chart for that item.
 */
public class PocketGeTrackerPanel extends PluginPanel
{
	private static final Color POSITIVE = new Color(0x1F, 0xB8, 0x5C);
	private static final Color NEGATIVE = new Color(0xEF, 0x53, 0x50);
	private static final Color GOLD = new Color(0xE5, 0xC1, 0x58);

	private final JLabel profitLabel = new JLabel("0 gp", SwingConstants.CENTER);
	private final JPanel flipList = new JPanel();
	private final Runnable onReset;

	public PocketGeTrackerPanel(Runnable onReset)
	{
		this.onReset = onReset;
		setLayout(new BorderLayout(0, 8));
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel head = new JPanel(new GridLayout(0, 1, 0, 4));
		head.setOpaque(false);
		JLabel title = new JLabel("Session flip profit", SwingConstants.CENTER);
		title.setForeground(GOLD);
		profitLabel.setFont(profitLabel.getFont().deriveFont(20f));
		head.add(title);
		head.add(profitLabel);

		JPanel actions = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
		actions.setOpaque(false);
		JButton reset = new JButton("Reset session");
		reset.addActionListener(e -> onReset.run());
		JButton site = new JButton("Open PocketGE");
		site.addActionListener(e -> LinkBrowser.browse("https://pocketge.com/"));
		actions.add(reset);
		actions.add(site);
		head.add(actions);
		add(head, BorderLayout.NORTH);

		flipList.setLayout(new BoxLayout(flipList, BoxLayout.Y_AXIS));
		flipList.setOpaque(false);
		add(flipList, BorderLayout.CENTER);
	}

	/** Rebuild the panel from tracker state. Call on the Swing EDT. */
	public void update(long sessionProfit, List<Flip> flips, int maxFlips)
	{
		profitLabel.setText((sessionProfit >= 0 ? "+" : "") + QuantityFormatter.quantityToStackSize(sessionProfit) + " gp");
		profitLabel.setForeground(sessionProfit >= 0 ? POSITIVE : NEGATIVE);

		flipList.removeAll();
		int from = Math.max(0, flips.size() - maxFlips);
		if (flips.isEmpty())
		{
			JLabel empty = new JLabel("<html><center>No completed flips yet.<br>Buy low, sell high — fills are tracked automatically.</center></html>", SwingConstants.CENTER);
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			empty.setBorder(BorderFactory.createEmptyBorder(16, 0, 0, 0));
			flipList.add(empty);
		}
		for (int i = flips.size() - 1; i >= from; i--)
		{
			flipList.add(row(flips.get(i)));
			flipList.add(Box.createVerticalStrut(6));
		}
		flipList.revalidate();
		flipList.repaint();
	}

	private JPanel row(Flip f)
	{
		JPanel p = new JPanel(new GridLayout(0, 1, 0, 2));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 64));

		JLabel name = new JLabel(f.itemName + "  ×" + NumberFormat.getIntegerInstance().format(f.quantity));
		name.setForeground(Color.WHITE);

		JLabel detail = new JLabel(QuantityFormatter.quantityToStackSize(f.avgBuy()) + " → "
			+ QuantityFormatter.quantityToStackSize(f.avgSell()) + "   "
			+ (f.profit >= 0 ? "+" : "") + QuantityFormatter.quantityToStackSize(f.profit) + " gp");
		detail.setForeground(f.profit >= 0 ? POSITIVE : NEGATIVE);

		p.add(name);
		p.add(detail);
		p.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		p.setToolTipText("Open the live " + f.itemName + " chart on PocketGE");
		p.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				LinkBrowser.browse("https://pocketge.com/?q=" + urlEncode(f.itemName));
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
