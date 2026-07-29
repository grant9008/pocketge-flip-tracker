package com.pocketge.tracker;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Multiple named, color-flagged favorites lists (TradingView-style
 * watchlists) instead of one flat list. Stored as JSON in config since a
 * flat "id:name" CSV (the old {@link Favorites} format) can't carry
 * per-list membership or metadata. The Gson instance is always passed in
 * rather than constructed here — RuneLite's plugin-hub verification
 * rejects plugins that build their own Gson instead of using the injected
 * one (see LocalBridgeServer's constructor comment).
 */
public final class FavoriteLists
{
	private FavoriteLists() {}

	private static final Type LIST_TYPE = new TypeToken<List<FavoriteList>>() {}.getType();

	/** A small fixed palette, TradingView-watchlist-dot style, rather than a
	 *  full color picker — keeps assigning a color a couple of taps instead
	 *  of a dialog. */
	public static final String[] PALETTE = {
		"#E5C158", // gold
		"#26A69A", // teal
		"#EF5350", // red
		"#42A5F5", // blue
		"#AB47BC", // purple
		"#66BB6A", // green
		"#FF9F43", // orange
	};

	public static class FavoriteList
	{
		public String id;
		public String name;
		public String color;
		public List<Favorites.Fav> items = new ArrayList<>();

		public FavoriteList() {}

		public FavoriteList(String id, String name, String color)
		{
			this.id = id;
			this.name = name;
			this.color = color;
		}
	}

	public static List<FavoriteList> parse(Gson gson, String json)
	{
		if (json == null || json.isEmpty())
		{
			return new ArrayList<>();
		}
		try
		{
			List<FavoriteList> lists = gson.fromJson(json, LIST_TYPE);
			return lists != null ? lists : new ArrayList<>();
		}
		catch (Exception e)
		{
			return new ArrayList<>();
		}
	}

	public static String toJson(Gson gson, List<FavoriteList> lists)
	{
		return gson.toJson(lists);
	}

	public static String newListId()
	{
		return UUID.randomUUID().toString();
	}

	/** One-time migration from the old flat "id:name" CSV list ({@link
	 *  Favorites}) — wraps it in a single default list so nobody's existing
	 *  favorites vanish when this ships. */
	public static List<FavoriteList> migrateFromCsv(String csv)
	{
		List<FavoriteList> out = new ArrayList<>();
		List<Favorites.Fav> favs = Favorites.parse(csv);
		if (!favs.isEmpty())
		{
			FavoriteList def = new FavoriteList(newListId(), "Favorites", PALETTE[0]);
			def.items.addAll(favs);
			out.add(def);
		}
		return out;
	}

	public static FavoriteList findList(List<FavoriteList> lists, String id)
	{
		if (id == null)
		{
			return null;
		}
		for (FavoriteList l : lists)
		{
			if (id.equals(l.id))
			{
				return l;
			}
		}
		return null;
	}

	public static boolean contains(FavoriteList list, int itemId)
	{
		if (list == null)
		{
			return false;
		}
		for (Favorites.Fav f : list.items)
		{
			if (f.id == itemId)
			{
				return true;
			}
		}
		return false;
	}

	/** Re-adds at the end if already present, so favoriting an item you
	 *  already have (e.g. re-clicking the star with stale UI state) doesn't
	 *  duplicate it. */
	public static void addItem(FavoriteList list, int itemId, String name)
	{
		removeItem(list, itemId);
		list.items.add(new Favorites.Fav(itemId, name));
	}

	public static void removeItem(FavoriteList list, int itemId)
	{
		list.items.removeIf(f -> f.id == itemId);
	}

	/** Same swap-with-neighbour semantics as {@link Favorites#move}. */
	public static void moveItem(FavoriteList list, int itemId, int delta)
	{
		int i = -1;
		for (int idx = 0; idx < list.items.size(); idx++)
		{
			if (list.items.get(idx).id == itemId)
			{
				i = idx;
				break;
			}
		}
		int j = i + delta;
		if (i < 0 || j < 0 || j >= list.items.size())
		{
			return;
		}
		Favorites.Fav tmp = list.items.get(i);
		list.items.set(i, list.items.get(j));
		list.items.set(j, tmp);
	}
}
