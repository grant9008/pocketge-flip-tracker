package net.runelite.client.ui.overlay.tooltip;

import net.runelite.client.ui.overlay.components.LayoutableRenderableEntity;

/**
 * Stub of RuneLite's Tooltip, which is a lombok @Data holding EITHER a string
 * or a component. TooltipOverlay builds its own TooltipComponent for the
 * string form and forces that one's background colour; a component is
 * rendered as given unless it is a PanelComponent.
 */
public class Tooltip
{
	private final String text;
	private final LayoutableRenderableEntity component;

	public Tooltip(String text)
	{
		this.text = text;
		this.component = null;
	}

	public Tooltip(LayoutableRenderableEntity component)
	{
		this.text = null;
		this.component = component;
	}

	public String getText() { return text; }

	public LayoutableRenderableEntity getComponent() { return component; }
}
