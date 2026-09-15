package net.runelite.client.ui;

import java.awt.image.BufferedImage;
import java.util.Map;

/**
 * Compile-only stub of RuneLite's NavigationButton.
 *
 * Upstream this is a Lombok @Value @Builder class; this hand-writes the
 * shape Lombok generates: a final class with private final fields, getX()
 * accessors, a static builder() and a NavigationButtonBuilder with one
 * setter per field returning the builder, plus build(). The tooltip field
 * is @Builder.Default "".
 */
public final class NavigationButton
{
	private final BufferedImage icon;
	private final String tooltip;
	private final Runnable onClick;
	private final PluginPanel panel;
	private final int priority;
	private final Map<String, Runnable> popup;

	private NavigationButton(BufferedImage icon, String tooltip, Runnable onClick, PluginPanel panel, int priority, Map<String, Runnable> popup)
	{
		this.icon = icon;
		this.tooltip = tooltip;
		this.onClick = onClick;
		this.panel = panel;
		this.priority = priority;
		this.popup = popup;
	}

	public static NavigationButtonBuilder builder()
	{
		return new NavigationButtonBuilder();
	}

	public BufferedImage getIcon()
	{
		return icon;
	}

	public String getTooltip()
	{
		return tooltip;
	}

	public Runnable getOnClick()
	{
		return onClick;
	}

	public PluginPanel getPanel()
	{
		return panel;
	}

	public int getPriority()
	{
		return priority;
	}

	public Map<String, Runnable> getPopup()
	{
		return popup;
	}

	public static class NavigationButtonBuilder
	{
		private BufferedImage icon;
		private String tooltip = "";
		private Runnable onClick;
		private PluginPanel panel;
		private int priority;
		private Map<String, Runnable> popup;

		NavigationButtonBuilder()
		{
		}

		public NavigationButtonBuilder icon(BufferedImage icon)
		{
			this.icon = icon;
			return this;
		}

		public NavigationButtonBuilder tooltip(String tooltip)
		{
			this.tooltip = tooltip;
			return this;
		}

		public NavigationButtonBuilder onClick(Runnable onClick)
		{
			this.onClick = onClick;
			return this;
		}

		public NavigationButtonBuilder panel(PluginPanel panel)
		{
			this.panel = panel;
			return this;
		}

		public NavigationButtonBuilder priority(int priority)
		{
			this.priority = priority;
			return this;
		}

		public NavigationButtonBuilder popup(Map<String, Runnable> popup)
		{
			this.popup = popup;
			return this;
		}

		public NavigationButton build()
		{
			return new NavigationButton(icon, tooltip, onClick, panel, priority, popup);
		}
	}
}
