package com.pocketge.tracker;

/**
 * One high alchemy cast on an item you hold: what it makes after the nature
 * rune it burns, against what one of the item cost you.
 *
 * "thoughts on hover over also showing high alch profit ea alch?" — the
 * question a stack in the bank actually poses is whether to sell it or alch
 * it, and nothing on screen said what alching comes to. The cost is what
 * you paid when the plugin watched you buy, and otherwise the price the
 * stack would sell at: alching it is then giving up that sale.
 *
 * Plain arithmetic, so the hover and the card cannot work it out two ways.
 */
final class HighAlch
{
	/** The rune every cast burns; priced at its insta-buy, what one costs
	 *  you now. */
	static final int NATURE_RUNE = 561;

	private HighAlch()
	{
	}

	/** What one cast makes: the alch value, less a nature rune, less what
	 *  one of the item cost. */
	static long perCast(long alchValue, long naturePrice, long itemCost)
	{
		return alchValue - naturePrice - itemCost;
	}

	/** Whether there is anything to say: an item that alchs for something,
	 *  a nature rune with a price, and a cost to measure against. */
	static boolean known(long alchValue, long naturePrice, long itemCost)
	{
		return alchValue > 0 && naturePrice > 0 && itemCost > 0;
	}

	/** "+15", "-1,265", "+24,247": exact, always signed — the hover, which
	 *  has the room. */
	static String signed(long gp)
	{
		return (gp < 0 ? "-" : "+") + String.format("%,d", Math.abs(gp));
	}

	/** "+15", "-797", "+1.2K", "-24.2K", "+1.5M": always signed, at most
	 *  four characters of figure — the card's stat cell, which has about
	 *  fifty pixels beside QUANTITY and VALUE. */
	static String compact(long gp)
	{
		final long abs = Math.abs(gp);
		final String n = abs < 1_000 ? Long.toString(abs)
			: abs < 999_950 ? oneDecimal(abs / 1_000.0) + "K"
			: oneDecimal(abs / 1_000_000.0) + "M";
		return (gp < 0 ? "-" : "+") + n;
	}

	private static String oneDecimal(double v)
	{
		final String s = String.format(java.util.Locale.ROOT, "%.1f", v);
		return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
	}
}
