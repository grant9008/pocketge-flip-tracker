package net.runelite.api;

import java.util.EnumSet;

import net.runelite.api.widgets.Widget;

/**
 * Stub of RuneLite's Client. Upstream this is
 * {@code public interface Client extends OAuthApi, GameEngine}; getAccountHash
 * is inherited from com.jagex.oldscape.pub.OAuthApi and is declared directly
 * here instead, which compiles identically for callers.
 *
 * Every method is ABSTRACT, as on the real interface. They used to carry
 * {@code default} bodies so a test could write {@code new Client(){ ... }}
 * and override one method — which compiles here and then fails the real
 * Gradle build, because the real Client has several hundred abstract methods
 * and an anonymous class must implement all of them. That exact mistake has
 * now broken the build twice (macroExpand, then getWorldType). A stub whose
 * job is to catch what the real jar would reject cannot be more permissive
 * than the real jar on the one point that keeps biting.
 *
 * A test that needs a Client builds a java.lang.reflect.Proxy — see
 * BankHighlightOverlayTest.stubClient() — which is shaped by the interface
 * at runtime and so cannot disagree with either version of it. The headless
 * harness under the scratchpad keeps its own permissive copy of this file;
 * that copy never reaches Gradle.
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
	long getAccountHash();
	Widget getWidget(int id);
	Point getMouseCanvasPosition();
	int getCanvasWidth();
	int getCanvasHeight();
	GameState getGameState();
	EnumSet<WorldType> getWorldType();
	GrandExchangeOffer[] getGrandExchangeOffers();
	ItemContainer getItemContainer(InventoryID inventory);
	ItemContainer getItemContainer(int id);
	@Deprecated
	MenuEntry createMenuEntry(int idx);
	int getVarbitValue(int varbit);
	int getVarpValue(int varpId);
	void setVarcStrValue(int var, String value);
	void runScript(Object... args);
}
