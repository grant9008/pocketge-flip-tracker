package net.runelite.client.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.border.EmptyBorder;

/**
 * Hand-written stub mirroring RuneLite's real PluginPanel semantics exactly
 * (wrap / no-wrap branch, preferred/minimum size override).
 */
public abstract class PluginPanel extends JPanel
{
	public static final int PANEL_WIDTH = 225;
	public static final int SCROLLBAR_WIDTH = 17;

	private final JScrollPane scrollPane;
	private final JPanel wrappedPanel;

	protected PluginPanel()
	{
		this(true);
	}

	protected PluginPanel(boolean wrap)
	{
		if (wrap)
		{
			setBorder(new EmptyBorder(6, 6, 6, 6));
			setLayout(new DynamicGridLayout(0, 1, 0, 3));
			setBackground(ColorScheme.DARK_GRAY_COLOR);

			JPanel northPanel = new JPanel(new BorderLayout());
			northPanel.add(this, BorderLayout.NORTH);

			scrollPane = new JScrollPane(northPanel);
			scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

			wrappedPanel = new JPanel();
			wrappedPanel.setPreferredSize(new Dimension(PANEL_WIDTH + SCROLLBAR_WIDTH, 0));
			wrappedPanel.setLayout(new BorderLayout());
			wrappedPanel.add(scrollPane, BorderLayout.CENTER);
		}
		else
		{
			scrollPane = null;
			wrappedPanel = this;
		}
	}

	@Override
	public Dimension getPreferredSize()
	{
		int width = this == wrappedPanel ? PANEL_WIDTH + SCROLLBAR_WIDTH : PANEL_WIDTH;
		return new Dimension(width, super.getPreferredSize().height);
	}

	@Override
	public Dimension getMinimumSize()
	{
		int width = this == wrappedPanel ? PANEL_WIDTH + SCROLLBAR_WIDTH : PANEL_WIDTH;
		return new Dimension(width, super.getMinimumSize().height);
	}

	protected JScrollPane getScrollPane()
	{
		return scrollPane;
	}

	public JPanel getWrappedPanel()
	{
		return wrappedPanel;
	}

	public void onActivate()
	{
	}

	public void onDeactivate()
	{
	}
}
