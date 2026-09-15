package net.runelite.client.ui.overlay;

import java.awt.Dimension;
import java.awt.Graphics2D;
import net.runelite.api.widgets.WidgetItem;

/**
 * Compile-only stub of RuneLite's WidgetItemOverlay.
 * Signatures verified against runelite-parent-1.12.38.
 *
 * Upstream the constructor calls super.setPosition(OverlayPosition.DYNAMIC),
 * super.setPriority(PRIORITY_LOW) and super.setLayer(OverlayLayer.MANUAL);
 * bodies are not load-bearing here, so it is left empty rather than pulling
 * those constants into the sibling Overlay/OverlayLayer stubs.
 */
public abstract class WidgetItemOverlay extends Overlay
{
	private OverlayManager overlayManager;

	protected WidgetItemOverlay()
	{
	}

	void setOverlayManager(OverlayManager overlayManager)
	{
		this.overlayManager = overlayManager;
	}

	public abstract void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem);

	@Override
	public Dimension render(Graphics2D graphics)
	{
		throw new UnsupportedOperationException();
	}

	protected void showOnInventory()
	{
		throw new UnsupportedOperationException();
	}

	protected void showOnBank()
	{
		throw new UnsupportedOperationException();
	}

	protected void showOnEquipment()
	{
		throw new UnsupportedOperationException();
	}

	protected void showOnInterfaces(int... ids)
	{
		throw new UnsupportedOperationException();
	}

	// Don't allow setting position or layer

	@Override
	public void setPosition(OverlayPosition position)
	{
		throw new IllegalStateException();
	}

	@Override
	public void setLayer(OverlayLayer layer)
	{
		throw new IllegalStateException();
	}
}
