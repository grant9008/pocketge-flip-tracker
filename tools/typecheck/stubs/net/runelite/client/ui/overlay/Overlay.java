package net.runelite.client.ui.overlay;

import java.awt.Dimension;
import java.awt.Graphics2D;

public abstract class Overlay
{
	public abstract Dimension render(Graphics2D g);
	protected void setPosition(OverlayPosition p) { }
	protected void setLayer(OverlayLayer l) { }
	protected void setPriority(float p) { }

	/* Recorded as upstream records them (a list, so a hook registered twice
	   is there twice, and OverlayManager draws the overlay twice for it),
	   so a test can see what an overlay asked to be drawn after. */
	private final java.util.List<Integer> drawHooks = new java.util.ArrayList<>();

	public java.util.List<Integer> getDrawHooks() { return drawHooks; }

	protected void drawAfterInterface(int interfaceId) { drawHooks.add(interfaceId << 16 | 0xffff); }

	protected void drawAfterLayer(int component) { drawHooks.add(component); }
}
