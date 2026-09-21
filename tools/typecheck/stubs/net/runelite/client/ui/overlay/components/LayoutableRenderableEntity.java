package net.runelite.client.ui.overlay.components;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import net.runelite.client.ui.overlay.RenderableEntity;

/** Stub of RuneLite's LayoutableRenderableEntity, transcribed from the real
 *  interface. TooltipOverlay calls setPreferredLocation then render on
 *  whatever a Tooltip carries. */
public interface LayoutableRenderableEntity extends RenderableEntity
{
	Rectangle getBounds();
	void setPreferredLocation(Point position);
	void setPreferredSize(Dimension dimension);
}
