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

	/* The registrations upstream makes, with its gameval InterfaceID values
	   written out (the stubs carry no such constants), so a test can check
	   an overlay against what RuneLite really registers — the Exchange's own
	   inventory (467) is in this list, and registering it again drew twice. */
	protected void showOnInventory()
	{
		showOnInterfaces(
			192, // BANK_DEPOSITBOX
			15,  // BANKSIDE
			301, // SHOPSIDE
			467, // GE_OFFERS_SIDE
			238, // GE_PRICECHECKER_SIDE
			85,  // EQUIPMENT_SIDE
			149, // INVENTORY
			630, // SEED_VAULT_DEPOSIT
			335, // TRADEMAIN
			336, // TRADESIDE
			674); // POH_COSTUMES_SIDE
	}

	protected void showOnBank()
	{
		drawAfterLayer(0x000c_000c); // Bankmain.ITEMS
		drawAfterLayer(0x02d4_000a); // SharedBank.ITEMS
	}

	protected void showOnEquipment()
	{
		showOnInterfaces(387); // WORNITEMS
	}

	/* Upstream this ADDS to the registered set rather than replacing it,
	   which is why an overlay can call showOnInventory/showOnBank/
	   showOnEquipment in sequence and have all three take effect. */
	protected void showOnInterfaces(int... ids)
	{
		for (int id : ids)
		{
			drawAfterInterface(id);
		}
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
