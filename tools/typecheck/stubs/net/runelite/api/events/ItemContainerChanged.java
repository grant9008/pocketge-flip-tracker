package net.runelite.api.events;

import net.runelite.api.ItemContainer;

/**
 * Stub of RuneLite's ItemContainerChanged event.
 *
 * <p>Upstream this is a Lombok {@code @Value} class (hence final, with final
 * fields, an all-args constructor and getters only).</p>
 */
public final class ItemContainerChanged
{
	private final int containerId;
	private final ItemContainer itemContainer;

	public ItemContainerChanged(int containerId, ItemContainer itemContainer)
	{
		this.containerId = containerId;
		this.itemContainer = itemContainer;
	}

	public int getContainerId()
	{
		throw new UnsupportedOperationException();
	}

	public ItemContainer getItemContainer()
	{
		throw new UnsupportedOperationException();
	}
}
