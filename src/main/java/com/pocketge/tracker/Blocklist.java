package com.pocketge.tracker;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The never-recommend list, stored in config as a comma-separated string
 * of item names (human-editable) and mirrored to a Set of ids for the
 * advisor. Names are the source of truth so a user can type into the
 * config box; ids are resolved against the live mapping.
 */
public final class Blocklist
{
	private Blocklist() {}

	public static List<String> parse(String csv)
	{
		List<String> out = new ArrayList<>();
		if (csv == null)
		{
			return out;
		}
		for (String part : csv.split(","))
		{
			String n = part.trim();
			if (!n.isEmpty())
			{
				out.add(n);
			}
		}
		return out;
	}

	public static String toCsv(Set<String> names)
	{
		return String.join(", ", names);
	}

	/** Add a name (case-insensitive de-dupe), returning the new CSV. */
	public static String add(String csv, String name)
	{
		Set<String> names = new LinkedHashSet<>(parse(csv));
		boolean present = names.stream().anyMatch(n -> n.equalsIgnoreCase(name));
		if (!present)
		{
			names.add(name);
		}
		return toCsv(names);
	}

	/** Remove a name (case-insensitive), returning the new CSV. */
	public static String remove(String csv, String name)
	{
		Set<String> names = new LinkedHashSet<>();
		for (String n : parse(csv))
		{
			if (!n.equalsIgnoreCase(name))
			{
				names.add(n);
			}
		}
		return toCsv(names);
	}

	public static boolean contains(String csv, String name)
	{
		for (String n : parse(csv))
		{
			if (n.equalsIgnoreCase(name))
			{
				return true;
			}
		}
		return false;
	}
}
