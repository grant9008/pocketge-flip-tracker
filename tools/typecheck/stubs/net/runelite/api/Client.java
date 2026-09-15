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
	/*
	 * EVERY method here is `default`, and that is a deliberate convenience
	 * with one sharp edge worth knowing about.
	 *
	 * It lets a test or harness write `new Client(){ ... }` and override only
	 * what it needs. The REAL interface declares these abstract, and declares
	 * a great many more besides, so that anonymous class compiles here and
	 * then fails the actual Gradle build on the first method it has never
	 * heard of. It has happened: macroExpand(String).
	 *
	 * So do not implement this interface anonymously in anything that ships.
	 * Use a java.lang.reflect.Proxy, which takes its shape from whichever
	 * version of the interface is on the classpath at the time — see
	 * BankHighlightOverlayTest.stubClient().
	 */
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
