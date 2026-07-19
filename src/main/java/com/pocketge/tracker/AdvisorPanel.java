package com.pocketge.tracker;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.QuantityFormatter;

/**
 * Advisor UI: a stack of suggestion cards (buy / sell / adjust), each with a
 * Skip (session) and Block (never again) control, plus an editable
 * never-recommend list of chips at the bottom.
 */
public class AdvisorPanel extends JPanel
{
	private static final Color POSITIVE = new Color(0x1F, 0xB8, 0x5C);
	private static final Color GOLD = new Color(0xE5, 0xC1, 0x58);
	private static final Color TEAL = new Color(0x26, 0xA6, 0x9A);
	private static final Color ADJUST = new Color(0xFF, 0x9F, 0x43);

	public interface Actions
	{
		void skip(int itemId);
		void block(String itemName);
		void unblock(String itemName);
	}

	private final Actions actions;
	private final JLabel status = new JLabel("Advisor off", SwingConstants.CENTER);
	private final JPanel cards = new JPanel();
	private final JPanel blockChips = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));

	public AdvisorPanel(Actions actions)
	{
		this.actions = actions;
		setLayout(new BorderLayout(0, 8));
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		add(status, BorderLayout.NORTH);

		cards.setLayout(new BoxLayout(cards, BoxLayout.Y_AXIS));
		cards.setOpaque(false);

		JPanel south = new JPanel(new BorderLayout(0, 4));
		south.setOpaque(false);
		JLabel blkTitle = new JLabel("Never recommend");
		blkTitle.setForeground(GOLD);
		blkTitle.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		south.add(blkTitle, BorderLayout.NORTH);
		blockChips.setOpaque(false);
		south.add(blockChips, BorderLayout.CENTER);

		JPanel center = new JPanel(new BorderLayout(0, 8));
		center.setOpaque(false);
		center.add(cards, BorderLayout.NORTH);
		center.add(south, BorderLayout.CENTER);
		add(center, BorderLayout.CENTER);
	}

	public void setStatus(String s)
	{
		status.setText(s);
	}

	/** Rebuild suggestion cards + block chips. Call on the EDT. */
	public void update(List<Advisor.Suggestion> suggestions, List<String> blocked)
	{
		cards.removeAll();
		if (suggestions == null || suggestions.isEmpty())
		{
			JLabel none = new JLabel("<html><center>No suggestions right now.<br>Prices refresh on your chosen interval.</center></html>", SwingConstants.CENTER);
			none.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			none.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
			cards.add(none);
		}
		else
		{
			for (Advisor.Suggestion s : suggestions)
			{
				cards.add(card(s));
				cards.add(Box.createVerticalStrut(6));
			}
		}

		blockChips.removeAll();
		if (blocked == null || blocked.isEmpty())
		{
			JLabel empty = new JLabel("Nothing blocked");
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			blockChips.add(empty);
		}
		else
		{
			for (String name : blocked)
			{
				blockChips.add(chip(name));
			}
		}
		revalidate();
		repaint();
	}

	private JPanel card(Advisor.Suggestion s)
	{
		JPanel p = new JPanel(new BorderLayout(6, 2));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 3, 0, 0, accent(s.type)),
			BorderFactory.createEmptyBorder(6, 8, 6, 6)));
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 96));

		JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		text.setOpaque(false);

		JLabel head = new JLabel(verb(s.type) + " " + s.name);
		head.setForeground(Color.WHITE);
		head.setFont(head.getFont().deriveFont(Font.BOLD));

		JLabel line = new JLabel(QuantityFormatter.quantityToStackSize(s.price) + " gp × "
			+ QuantityFormatter.quantityToStackSize(s.quantity)
			+ (s.expectedProfit > 0 ? "   +" + QuantityFormatter.quantityToStackSize(s.expectedProfit) + " gp" : ""));
		line.setForeground(s.expectedProfit > 0 ? POSITIVE : ColorScheme.LIGHT_GRAY_COLOR);

		JLabel why = new JLabel("<html><body style='width:150px'>" + s.reason + "</html>");
		why.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		why.setFont(why.getFont().deriveFont(10f));

		text.add(head);
		text.add(line);
		text.add(why);
		p.add(text, BorderLayout.CENTER);

		JPanel btns = new JPanel();
		btns.setLayout(new BoxLayout(btns, BoxLayout.Y_AXIS));
		btns.setOpaque(false);
		btns.add(smallBtn("Skip", "Hide this for the session", e -> actions.skip(s.itemId)));
		btns.add(Box.createVerticalStrut(4));
		btns.add(smallBtn("Block", "Never recommend " + s.name + " again", e -> actions.block(s.name)));
		p.add(btns, BorderLayout.EAST);

		// clicking the card opens the item's live chart
		p.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		p.setToolTipText("Open the live " + s.name + " chart on PocketGE");
		p.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				LinkBrowser.browse("https://pocketge.com/?q=" + urlEncode(s.name));
			}
		});
		return p;
	}

	private JButton smallBtn(String label, String tip, java.awt.event.ActionListener a)
	{
		JButton b = new JButton(label);
		b.setToolTipText(tip);
		b.setFocusPainted(false);
		b.setFont(b.getFont().deriveFont(10f));
		b.setMargin(new java.awt.Insets(2, 6, 2, 6));
		b.addActionListener(a);
		return b;
	}

	private JPanel chip(String name)
	{
		JPanel c = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
		c.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		c.setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 4));
		JLabel n = new JLabel(name);
		n.setForeground(Color.WHITE);
		n.setFont(n.getFont().deriveFont(11f));
		JButton x = new JButton("×");
		x.setToolTipText("Remove " + name + " from the never-recommend list");
		x.setFocusPainted(false);
		x.setMargin(new java.awt.Insets(0, 4, 0, 4));
		x.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		x.addActionListener(e -> actions.unblock(name));
		c.add(n);
		c.add(x);
		return c;
	}

	private static Color accent(Advisor.Suggestion.Type t)
	{
		switch (t)
		{
			case BUY: return GOLD;
			case SELL: return TEAL;
			default: return ADJUST;
		}
	}

	private static String verb(Advisor.Suggestion.Type t)
	{
		switch (t)
		{
			case BUY: return "Buy";
			case SELL: return "Sell";
			case ADJUST_BUY: return "Adjust bid:";
			case ADJUST_SELL: return "Adjust ask:";
			default: return "";
		}
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
