package net.runelite.api.widgets;

import java.awt.Rectangle;
import net.runelite.api.Point;

/** An item that is being represented in a {@link Widget}. */
public class WidgetItem
{
	private final int id;
	private final int quantity;
	private final Rectangle canvasBounds;
	private final Widget widget;
	private final Rectangle draggingCanvasBounds;

	public WidgetItem(int id, int quantity, Rectangle canvasBounds, Widget widget, Rectangle draggingCanvasBounds)
	{
		this.id = id;
		this.quantity = quantity;
		this.canvasBounds = canvasBounds;
		this.widget = widget;
		this.draggingCanvasBounds = draggingCanvasBounds;
	}

	public int getId() { return id; }

	public int getQuantity() { return quantity; }

	public Widget getWidget() { return widget; }

	public Rectangle getDraggingCanvasBounds() { return draggingCanvasBounds; }

	public Rectangle getCanvasBounds()
	{
		return draggingCanvasBounds == null ? canvasBounds : draggingCanvasBounds;
	}

	public Rectangle getCanvasBounds(boolean dragging)
	{
		return dragging ? draggingCanvasBounds : canvasBounds;
	}

	public Point getCanvasLocation()
	{
		final Rectangle bounds = getCanvasBounds();
		return new Point((int) bounds.getX(), (int) bounds.getY());
	}
}
