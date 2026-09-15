package net.runelite.client.util;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Stub reimplementation of RuneLite's QuantityFormatter, matching its
 * documented behaviour: values under 10,000 print in full, everything above
 * is abbreviated K/M/B with at most one decimal place.
 */
public class QuantityFormatter
{
	private static final NumberFormat PRECISE_DECIMAL_FORMATTER =
		new DecimalFormat("#,###.###", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
	private static final NumberFormat DECIMAL_FORMATTER =
		new DecimalFormat("#,###.#", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
	private static final NumberFormat NUMBER_FORMATTER =
		new DecimalFormat("#,###", DecimalFormatSymbols.getInstance(Locale.ENGLISH));

	private static final TreeMap<Long, String> SUFFIXES = new TreeMap<>();

	static
	{
		SUFFIXES.put(1_000L, "K");
		SUFFIXES.put(1_000_000L, "M");
		SUFFIXES.put(1_000_000_000L, "B");
		SUFFIXES.put(1_000_000_000_000L, "T");
	}

	public static synchronized String quantityToStackSize(long quantity)
	{
		if (quantity < 0)
		{
			return "-" + quantityToStackSize(quantity == Long.MIN_VALUE ? Long.MAX_VALUE : -quantity);
		}
		else if (quantity < 10_000)
		{
			return String.valueOf(quantity);
		}

		final Map.Entry<Long, String> entry = SUFFIXES.floorEntry(quantity);
		if (entry == null)
		{
			return String.valueOf(quantity);
		}
		final long divideBy = entry.getKey();
		final String suffix = entry.getValue();
		return DECIMAL_FORMATTER.format((double) quantity / divideBy) + suffix;
	}

	public static synchronized String quantityToRSDecimalStack(int quantity)
	{
		return quantityToRSDecimalStack(quantity, false);
	}

	public static synchronized String quantityToRSDecimalStack(int quantity, boolean precise)
	{
		final String quantityStr = String.valueOf(quantity);
		if (quantityStr.length() <= 4)
		{
			return quantityStr;
		}

		final long power = (long) Math.pow(10, quantityStr.length() - 1);
		return precise
			? PRECISE_DECIMAL_FORMATTER.format(quantity / (double) power)
			: DECIMAL_FORMATTER.format(quantity / (double) power);
	}

	public static synchronized String formatNumber(long amount)
	{
		return NUMBER_FORMATTER.format(amount);
	}

	public static synchronized String formatNumber(final double amount)
	{
		return PRECISE_DECIMAL_FORMATTER.format(amount);
	}

	public static int parseQuantity(String string) throws NumberFormatException
	{
		return (int) parseLongQuantity(string);
	}

	public static long parseLongQuantity(String string) throws NumberFormatException
	{
		string = string.trim().replace(",", "");
		if (string.isEmpty())
		{
			throw new NumberFormatException("empty");
		}
		final char last = Character.toUpperCase(string.charAt(string.length() - 1));
		long mult = 1;
		if (last == 'K')
		{
			mult = 1_000L;
		}
		else if (last == 'M')
		{
			mult = 1_000_000L;
		}
		else if (last == 'B')
		{
			mult = 1_000_000_000L;
		}
		if (mult > 1)
		{
			string = string.substring(0, string.length() - 1);
		}
		return (long) (Double.parseDouble(string) * mult);
	}
}
