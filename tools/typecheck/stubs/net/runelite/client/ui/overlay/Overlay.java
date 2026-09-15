package net.runelite.client.ui.overlay;

import java.awt.Dimension;
import java.awt.Graphics2D;

public abstract class Overlay
{
	public abstract Dimension render(Graphics2D g);
	protected void setPosition(OverlayPosition p) { }
	protected void setLayer(OverlayLayer l) { }
	protected void setPriority(float p) { }
}
