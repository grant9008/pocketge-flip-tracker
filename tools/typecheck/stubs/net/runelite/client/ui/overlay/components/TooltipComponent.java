package net.runelite.client.ui.overlay.components;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;

/**
 * Stub of RuneLite's TooltipComponent.
 *
 * The setters are lombok-generated on the real class (@Setter on the type);
 * the four fields it declares are text, backgroundColor, position and
 * modIcons. modIcons is null-guarded in the real render(), so a tooltip
 * carrying no &lt;img=&gt; tag does not need it set.
 *
 * Why this exists at all: TooltipOverlay forces the background colour of a
 * Tooltip(String), and of a Tooltip whose component is a PanelComponent, to
 * the client's overlayBackgroundColor. A TooltipComponent is neither, so
 * passing one through Tooltip(LayoutableRenderableEntity) is the only way to
 * choose the background — see TipStyle.
 */
public class TooltipComponent implements LayoutableRenderableEntity
{
	/* Private, exactly as on the real class, which has no getter for it.
	   A harness that needs to draw or assert on the markup reads this field
	   reflectively rather than being handed an accessor the real class
	   would refuse to compile against. */
	private String text;

	public void setText(String text)
	{
		this.text = text;
	}

	public void setBackgroundColor(Color backgroundColor)
	{
	}

	public void setPosition(Point position)
	{
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		return new Dimension();
	}

	@Override
	public Rectangle getBounds()
	{
		return new Rectangle();
	}

	@Override
	public void setPreferredLocation(Point position)
	{
	}

	@Override
	public void setPreferredSize(Dimension dimension)
	{
	}
}
