package com.pocketge.tracker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The local favorites watchlist, stored in config as "id:name" pairs
 * (unlike the blocklist's bare names) so display never needs a
 * name-to-item-id search — favoriting always happens from a suggestion or
 * history row where both are already known, so both are just carried
 * straight through.
 */
public final class Favorites
{
	private Favorites() {}

	public static class Fav
	{
		public final int id;
		public final String name;
		public Fav(int id, String name) { this.id = id; this.name = name; }
	}

	public static List<Fav> parse(String csv)
	{
		List<Fav> out = new ArrayList<>();
		if (csv == null || csv.isEmpty())
		{
			return out;
		}
		for (String part : csv.split(","))
		{
			String p = part.trim();
			int sep = p.indexOf(':');
			if (sep <= 0 || sep == p.length() - 1)
			{
				continue; // malformed entry — skip rather than crash
			}
			try
			{
				int id = Integer.parseInt(p.substring(0, sep).trim());
				String name = p.substring(sep + 1).trim();
				if (!name.isEmpty())
				{
					out.add(new Fav(id, name));
				}
			}
			catch (NumberFormatException ignore)
			{
				// skip malformed entry
			}
		}
		return out;
	}

	private static String toCsv(Map<Integer, String> byId)
	{
		List<String> parts = new ArrayList<>();
		for (Map.Entry<Integer, String> e : byId.entrySet())
		{
			parts.add(e.getKey() + ":" + e.getValue());
		}
		return String.join(",", parts);
	}

	public static String add(String csv, int id, String name)
	{
		Map<Integer, String> byId = new LinkedHashMap<>();
		for (Fav f : parse(csv))
		{
			byId.put(f.id, f.name);
		}
		byId.put(id, name);
		return toCsv(byId);
	}

	public static String remove(String csv, int id)
	{
		Map<Integer, String> byId = new LinkedHashMap<>();
		for (Fav f : parse(csv))
		{
			byId.put(f.id, f.name);
		}
		byId.remove(id);
		return toCsv(byId);
	}

	public static boolean contains(String csv, int id)
	{
		for (Fav f : parse(csv))
		{
			if (f.id == id)
			{
				return true;
			}
		}
		return false;
	}

	/** Swaps the favorite at id with its neighbour delta positions away
	 *  (delta is -1 or +1 for the up/down reorder buttons). No-op if id
	 *  isn't found or the swap would go out of the list's bounds — same
	 *  semantics as the website's moveFavorite(). */
	public static String move(String csv, int id, int delta)
	{
		List<Fav> favs = parse(csv);
		int i = -1;
		for (int idx = 0; idx < favs.size(); idx++)
		{
			if (favs.get(idx).id == id)
			{
				i = idx;
				break;
			}
		}
		int j = i + delta;
		if (i < 0 || j < 0 || j >= favs.size())
		{
			return csv;
		}
		Fav tmp = favs.get(i);
		favs.set(i, favs.get(j));
		favs.set(j, tmp);
		List<String> parts = new ArrayList<>();
		for (Fav f : favs)
		{
			parts.add(f.id + ":" + f.name);
		}
		return String.join(",", parts);
	}
}
