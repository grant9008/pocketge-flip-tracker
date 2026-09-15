package net.runelite.http.api.item;

/**
 * Compile-only stub.
 *
 * net.runelite.http.api.* is not published in the runelite/runelite tree (every
 * ref returns 404 for this path), so unlike every other stub here the members
 * were not read from upstream source. They are pinned by something at least as
 * strong: ItemManager.search(String) is declared `List<ItemPrice> search(...)`
 * at runelite-client/.../game/ItemManager.java:436, and the plugin line that
 * consumes it —
 *     r.id = ip.getId(); r.name = ip.getName();
 * with SearchResult.id an int and .name a String — is already in 0.6.3, which
 * the plugin hub built successfully. A real compiler has therefore accepted
 * exactly these two calls against the real class.
 */
public class ItemPrice
{
	public int getId()
	{
		return 0;
	}

	public String getName()
	{
		return null;
	}
}
