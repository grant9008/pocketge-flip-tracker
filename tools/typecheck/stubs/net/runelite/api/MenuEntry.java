package net.runelite.api;

import java.util.function.Consumer;

import net.runelite.api.widgets.Widget;

/**
 * Stub of RuneLite's MenuEntry. The upstream accessors returning NPC, Player,
 * Actor and Menu are omitted, since those types are not stubbed.
 */
public interface MenuEntry
{
	String getOption();

	MenuEntry setOption(String option);

	String getTarget();

	MenuEntry setTarget(String target);

	int getIdentifier();

	MenuEntry setIdentifier(int identifier);

	MenuAction getType();

	MenuEntry setType(MenuAction type);

	int getParam0();

	MenuEntry setParam0(int param0);

	int getParam1();

	MenuEntry setParam1(int param1);

	boolean isForceLeftClick();

	MenuEntry setForceLeftClick(boolean forceLeftClick);

	int getWorldViewId();

	MenuEntry setWorldViewId(int worldViewId);

	boolean isDeprioritized();

	MenuEntry setDeprioritized(boolean deprioritized);

	MenuEntry onClick(Consumer<MenuEntry> callback);

	Consumer<MenuEntry> onClick();

	boolean isItemOp();

	int getItemOp();

	int getItemId();

	MenuEntry setItemId(int itemId);

	Widget getWidget();
}
