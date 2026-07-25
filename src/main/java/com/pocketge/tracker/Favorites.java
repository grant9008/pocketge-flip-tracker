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
}
