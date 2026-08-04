package com.pocketge.tracker;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Best-effort extraction of "what item is this trade-chat line about" —
 * e.g. "buying venator bow 68m" -> "venator bow", "s> soul reaper axe 450m
 * ea" -> "soul reaper axe". Pure string heuristics (no RuneLite deps, no
 * knowledge of the real item list): strip known trade-chat verbs/prefixes
 * off the front, strip price-looking tokens off the back, and whatever's
 * left in the middle is the guess. Deliberately conservative — a bad guess
 * here means a right-click option that searches for the wrong thing, so
 * this returns null rather than a low-confidence guess whenever the result
 * looks implausible (empty, too long, or still price-shaped).
 */
public final class ChatTradeParser
{
	private ChatTradeParser() {}

	private static final Set<String> LEADING_WORDS = new LinkedHashSet<>(Arrays.asList(
		"buying", "buy", "selling", "sell", "wtb", "wts", "want", "wanted", "to", "for",
		"looking", "lf", "trading", "trade"
	));
	private static final Set<String> LEADING_SYMBOLS = new LinkedHashSet<>(Arrays.asList(
		"b>", "s>", ">", "-", "|"
	));
	private static final Set<String> TRAILING_UNIT_WORDS = new LinkedHashSet<>(Arrays.asList(
		"gp", "gold", "each", "ea", "/ea", "/each", "per"
	));
	/** A bare number (with optional thousands separators/decimals), an
	 *  optional k/m/b magnitude suffix, and an optional glued-on "gp" —
	 *  "68m", "450,000", "1.5b", "68", "15000gp". */
	private static final Pattern PRICE_TOKEN = Pattern.compile(
		"^[\\d][\\d,.]*(?:[kKmMbB]|[gG][pP])?[.,!?:;]*$");
	private static final int MAX_WORDS = 6;
	private static final int MIN_CHARS = 3;

	/** Returns the guessed item name, or null if the line doesn't look
	 *  confidently like "<verb> <item name> <price>". */
	public static String extractItemName(String message)
	{
		if (message == null)
		{
			return null;
		}
		List<String> tokens = new java.util.ArrayList<>(Arrays.asList(message.trim().split("\\s+")));
		if (tokens.isEmpty())
		{
			return null;
		}

		int head = 0;
		while (head < tokens.size())
		{
			final String t = tokens.get(head);
			final String lower = stripPunct(t).toLowerCase();
			if (LEADING_WORDS.contains(lower) || LEADING_SYMBOLS.contains(t))
			{
				head++;
				continue;
			}
			break;
		}

		int tail = tokens.size();
		boolean sawPrice = false;
		while (tail > head)
		{
			final String t = tokens.get(tail - 1);
			final String lower = stripPunct(t).toLowerCase();
			if (PRICE_TOKEN.matcher(t).matches())
			{
				sawPrice = true;
				tail--;
				continue;
			}
			if (TRAILING_UNIT_WORDS.contains(lower))
			{
				tail--;
				continue;
			}
			break;
		}

		/* A price is the one signal that reliably tells "buying X 68m" (real
		   trade chat) apart from an ordinary sentence that happens to start
		   with one of the leading words above ("buying groceries later",
		   "lol nice kill") — require it rather than guessing off the leading
		   word alone. */
		if (!sawPrice)
		{
			return null;
		}
		if (tail <= head)
		{
			return null; // nothing left after stripping — e.g. just "buying 68m"
		}
		final List<String> middle = tokens.subList(head, tail);
		if (middle.size() > MAX_WORDS)
		{
			return null; // too long to plausibly be one item name
		}
		final StringBuilder sb = new StringBuilder();
		for (String t : middle)
		{
			final String cleaned = stripPunct(t);
			if (cleaned.isEmpty())
			{
				continue;
			}
			if (sb.length() > 0)
			{
				sb.append(' ');
			}
			sb.append(cleaned);
		}
		final String result = sb.toString().trim();
		if (result.length() < MIN_CHARS || PRICE_TOKEN.matcher(result).matches())
		{
			return null;
		}
		return result;
	}

	private static String stripPunct(String s)
	{
		int start = 0, end = s.length();
		while (start < end && isEdgePunct(s.charAt(start)))
		{
			start++;
		}
		while (end > start && isEdgePunct(s.charAt(end - 1)))
		{
			end--;
		}
		return s.substring(start, end);
	}

	private static boolean isEdgePunct(char c)
	{
		return c == ',' || c == '.' || c == '!' || c == '?' || c == ':' || c == ';'
			|| c == '(' || c == ')' || c == '[' || c == ']' || c == '"' || c == '\'';
	}
}
