package com.pocketge.tracker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 * Order sync between the in-game watchlist and the same list open on
 * pocketge.com. Both ends can edit at once, so every test here is really
 * asking the same question: can a reorder arriving from a browser that is
 * one poll behind ever lose a favorite?
 */
public class FavoriteListsTest
{
	private static FavoriteLists.FavoriteList list(int... ids)
	{
		FavoriteLists.FavoriteList l = new FavoriteLists.FavoriteList("id", "Favorites", "#E5C158");
		l.items = new ArrayList<>();
		for (int id : ids)
		{
			FavoriteLists.addItem(l, id, "Item " + id);
		}
		return l;
	}

	private static List<Integer> ids(FavoriteLists.FavoriteList l)
	{
		List<Integer> out = new ArrayList<>();
		for (Favorites.Fav f : l.items)
		{
			out.add(f.id);
		}
		return out;
	}

	@Test
	public void reorderTo_appliesTheGivenOrder()
	{
		FavoriteLists.FavoriteList l = list(1, 2, 3, 4);
		FavoriteLists.reorderTo(l, Arrays.asList(4, 2, 1, 3));
		Assert.assertEquals(Arrays.asList(4, 2, 1, 3), ids(l));
	}

	@Test
	public void reorderTo_keepsItemsTheCallerNeverMentioned()
	{
		// The browser is a poll behind and doesn't know about item 9 yet.
		// Dropping it would silently delete a favorite the player just
		// starred in game.
		FavoriteLists.FavoriteList l = list(1, 2, 9);
		FavoriteLists.reorderTo(l, Arrays.asList(2, 1));
		Assert.assertEquals(Arrays.asList(2, 1, 9), ids(l));
	}

	@Test
	public void reorderTo_ignoresItemsThatAreNoLongerOnTheList()
	{
		// The mirror case: the browser still shows item 7, which was
		// unfavorited in game a moment ago. It must not come back.
		FavoriteLists.FavoriteList l = list(1, 2);
		FavoriteLists.reorderTo(l, Arrays.asList(7, 2, 1));
		Assert.assertEquals(Arrays.asList(2, 1), ids(l));
	}

	@Test
	public void reorderTo_neverChangesMembership()
	{
		FavoriteLists.FavoriteList l = list(1, 2, 3);
		FavoriteLists.reorderTo(l, Arrays.asList(3, 99, 1));
		Assert.assertEquals("membership must be untouched by a reorder", 3, l.items.size());
		Assert.assertTrue(FavoriteLists.contains(l, 1));
		Assert.assertTrue(FavoriteLists.contains(l, 2));
		Assert.assertTrue(FavoriteLists.contains(l, 3));
		Assert.assertFalse(FavoriteLists.contains(l, 99));
	}

	@Test
	public void reorderTo_honoursADuplicatedIdOnce()
	{
		FavoriteLists.FavoriteList l = list(1, 2, 3);
		FavoriteLists.reorderTo(l, Arrays.asList(2, 2, 1));
		Assert.assertEquals(Arrays.asList(2, 1, 3), ids(l));
	}

	@Test
	public void reorderTo_nullAndEmptyAreNoOps()
	{
		FavoriteLists.FavoriteList l = list(1, 2, 3);
		FavoriteLists.reorderTo(l, null);
		Assert.assertEquals(Arrays.asList(1, 2, 3), ids(l));
		FavoriteLists.reorderTo(l, new ArrayList<>());
		Assert.assertEquals(Arrays.asList(1, 2, 3), ids(l));
		FavoriteLists.reorderTo(null, Arrays.asList(1));
	}

	@Test
	public void reorderTo_survivesANullIdInTheList()
	{
		// Gson will hand us a null for `[1, null, 2]` rather than refusing it.
		FavoriteLists.FavoriteList l = list(1, 2, 3);
		FavoriteLists.reorderTo(l, Arrays.asList(3, null, 1));
		Assert.assertEquals(Arrays.asList(3, 1, 2), ids(l));
	}

	@Test
	public void reorderTo_roundTripsThroughDragOrdering()
	{
		/* The in-game drag and the website reorder must agree on what
		   "position 0" means, or a drag in one place would visibly jump
		   somewhere else in the other. */
		FavoriteLists.FavoriteList l = list(1, 2, 3, 4);
		FavoriteLists.moveItemToIndex(l, 4, 0);
		final List<Integer> afterDrag = ids(l);
		Assert.assertEquals(Arrays.asList(4, 1, 2, 3), afterDrag);

		FavoriteLists.FavoriteList mirror = list(1, 2, 3, 4);
		FavoriteLists.reorderTo(mirror, afterDrag);
		Assert.assertEquals(afterDrag, ids(mirror));
	}
}
