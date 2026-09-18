package com.pocketge.tracker;

import java.util.ArrayList;
import java.util.List;

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





}
