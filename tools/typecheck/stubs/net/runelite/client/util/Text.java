package net.runelite.client.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/** Stub of RuneLite's Text utility. */
public class Text
{
	private static final Pattern TAG_REGEXP = Pattern.compile("<[^>]*>");
	private static final Pattern BR_REGEXP = Pattern.compile("<br\\s*/?>", Pattern.CASE_INSENSITIVE);

	public static String removeTags(String str)
	{
		return str == null ? null : TAG_REGEXP.matcher(str).replaceAll("");
	}

	public static String removeFormattingTags(String str)
	{
		return removeTags(str);
	}

	public static String sanitize(String str)
	{
		return str == null ? null : str.replace(' ', ' ').trim();
	}

	public static String standardize(String str)
	{
		return str == null ? null : sanitize(removeTags(str)).toLowerCase();
	}

	public static String escapeJagex(String str)
	{
		return str;
	}

	public static String toJagexName(String str)
	{
		return str == null ? null : str.replace('_', ' ').trim();
	}

	public static List<String> fromCSV(String str)
	{
		final List<String> out = new ArrayList<>();
		if (str == null || str.isEmpty())
		{
			return out;
		}
		for (String s : str.split(","))
		{
			final String t = s.trim();
			if (!t.isEmpty())
			{
				out.add(t);
			}
		}
		return out;
	}

	public static String toCSV(Collection<String> strings)
	{
		return String.join(",", strings);
	}

	public static String titleCase(Enum<?> o)
	{
		final String s = o.name().toLowerCase().replace('_', ' ');
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}
}
