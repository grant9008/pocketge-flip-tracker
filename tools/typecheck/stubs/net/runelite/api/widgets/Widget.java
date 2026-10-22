package net.runelite.api.widgets;

import java.awt.Rectangle;

/** Only the surface the plugin actually reads. */
public interface Widget
{
	default int getId() { return 0; }
	default Widget getParent() { return null; }
	default String getText() { return null; }
	default boolean isHidden() { return false; }
	default Rectangle getBounds() { return null; }
	default String[] getActions() { return null; }
	default Widget[] getStaticChildren() { return null; }
	default Widget[] getDynamicChildren() { return null; }
	default Widget[] getNestedChildren() { return null; }
	default Widget getChild(int index) { return null; }
	default Widget[] getChildren() { return null; }
	default int getWidth() { return 0; }
	default int getHeight() { return 0; }
	default Widget createChild(int index, int type) { return null; }
	default Widget setText(String text) { return this; }
	default Widget setTextColor(int textColor) { return this; }
	default Widget setOpacity(int transparency) { return this; }
	default Widget setName(String name) { return this; }
	default Widget setItemId(int itemId) { return this; }
	default Widget setItemQuantity(int quantity) { return this; }
	default Widget setItemQuantityMode(int mode) { return this; }
	default Widget setOriginalX(int x) { return this; }
	default Widget setOriginalY(int y) { return this; }
	default Widget setOriginalWidth(int w) { return this; }
	default Widget setOriginalHeight(int h) { return this; }
	default void setAction(int index, String option) { }
	default void setOnOpListener(Object... args) { }
	default void setOnKeyListener(Object... args) { }
	default void setOnMouseOverListener(Object... args) { }
	default void setOnMouseLeaveListener(Object... args) { }
	default Widget setHasListener(boolean hasListener) { return this; }
	default void revalidate() { }
	default Widget setFontId(int id) { return this; }
	default void setBorderType(int thickness) { }
	default Widget setTextShadowed(boolean shadowed) { return this; }
	default Widget setXTextAlignment(int xta) { return this; }
	default Widget setYTextAlignment(int yta) { return this; }
	default Widget setFilled(boolean filled) { return this; }
}
