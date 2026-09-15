package net.runelite.client.ui.overlay;

import java.util.Collection;
import java.util.function.Predicate;
import net.runelite.api.widgets.WidgetItem;

/**
 * Compile-only stub of RuneLite's OverlayManager.
 * Signatures verified against runelite-parent-1.12.38.
 */
public class OverlayManager
{
	public static final String OPTION_CONFIGURE = "Configure";

	public synchronized boolean add(final Overlay overlay)
	{
		throw new UnsupportedOperationException();
	}

	public synchronized boolean remove(final Overlay overlay)
	{
		throw new UnsupportedOperationException();
	}

	public synchronized boolean removeIf(Predicate<Overlay> filter)
	{
		throw new UnsupportedOperationException();
	}

	public synchronized boolean anyMatch(Predicate<Overlay> filter)
	{
		throw new UnsupportedOperationException();
	}

	public synchronized void clear()
	{
		throw new UnsupportedOperationException();
	}

	public synchronized void saveOverlay(final Overlay overlay)
	{
		throw new UnsupportedOperationException();
	}

	public synchronized void resetOverlay(final Overlay overlay)
	{
		throw new UnsupportedOperationException();
	}

	public Collection<WidgetItem> getWidgetItems()
	{
		throw new UnsupportedOperationException();
	}

	public void setWidgetItems(Collection<WidgetItem> widgetItems)
	{
		throw new UnsupportedOperationException();
	}
}
