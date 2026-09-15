package net.runelite.api.events;

import net.runelite.api.MenuEntry;

/**
 * Stub of RuneLite's MenuEntryAdded event.
 *
 * <p>Upstream this wraps a single final {@link MenuEntry} (Lombok
 * {@code @RequiredArgsConstructor}) and delegates every accessor to it;
 * note {@code getType()} returns an {@code int}, not a MenuAction.</p>
 */
public class MenuEntryAdded
{
	private final MenuEntry menuEntry;

	public MenuEntryAdded(MenuEntry menuEntry)
	{
		this.menuEntry = menuEntry;
	}

	public MenuEntry getMenuEntry()
	{
		throw new UnsupportedOperationException();
	}

	public String getOption()
	{
		throw new UnsupportedOperationException();
	}

	public String getTarget()
	{
		throw new UnsupportedOperationException();
	}

	public int getType()
	{
		throw new UnsupportedOperationException();
	}

	public int getIdentifier()
	{
		throw new UnsupportedOperationException();
	}

	public int getActionParam0()
	{
		throw new UnsupportedOperationException();
	}

	public int getActionParam1()
	{
		throw new UnsupportedOperationException();
	}

	public int getItemId()
	{
		throw new UnsupportedOperationException();
	}
}
