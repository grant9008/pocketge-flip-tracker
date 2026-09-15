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
}
