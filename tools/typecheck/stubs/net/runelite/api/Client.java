package net.runelite.api;

import java.util.EnumSet;

import net.runelite.api.widgets.Widget;

/**
 * Stub of RuneLite's Client. Upstream this is
 * {@code public interface Client extends OAuthApi, GameEngine}; getAccountHash
 * is inherited from com.jagex.oldscape.pub.OAuthApi and is declared directly
 * here instead, which compiles identically for callers.
 */
public interface Client
{
	// From OAuthApi upstream.
	default long getAccountHash() { return 0; }

	default Widget getWidget(int id) { return null; }

	default Point getMouseCanvasPosition() { return null; }

	default int getCanvasWidth() { return 0; }

	default int getCanvasHeight() { return 0; }

	default GameState getGameState() { return null; }

	default EnumSet<WorldType> getWorldType() { return null; }

	default GrandExchangeOffer[] getGrandExchangeOffers() { return null; }

	default ItemContainer getItemContainer(InventoryID inventory) { return null; }

	default ItemContainer getItemContainer(int id) { return null; }

	@Deprecated
	default MenuEntry createMenuEntry(int idx) { return null; }

	default int getVarbitValue(int varbit) { return 0; }

	default int getVarpValue(int varpId) { return 0; }

	default void setVarcStrValue(int var, String value) {  }

	default void runScript(Object... args) {  }
}
