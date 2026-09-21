package com.pocketge.tracker;

import java.awt.Color;
import net.runelite.client.util.QuantityFormatter;

/**
 * One vocabulary for the tooltips the plugin draws INSIDE the game — the bank
 * stack marks and the Exchange slot borders.
 *
 * <h2>Why this exists</h2>
 * The two overlays grew their own palettes, spelled as bare hex inside the
 * markup, and drifted apart exactly as you would expect:
 *
 * <ul>
 *   <li>{@code 8a8274}, a brown two shades off the tooltip's own background,
 *       carried four lines of the Exchange tooltip. The bank tooltip had the
 *       same brown, it came back in a screenshot as hard to read, it was
 *       replaced there — and a test was written banning it <i>in the bank</i>.
 *       The Exchange kept it, because nothing connected the two.</li>
 *   <li>{@code e5c158} titled every Exchange tooltip. It is not the theme's
 *       buy gold ({@code E5B842}), not the parchment the sidebar names items
 *       in, and not any other colour in the plugin — a near-miss of a brand
 *       colour, which is worse than a deliberate contrast.</li>
 *   <li>Both overlays wrote {@code 1fb85c} and {@code ef5350} as string
 *       literals beside {@code Color} constants holding the same values, so
 *       the border and the sentence explaining the border could be changed
 *       apart.</li>
 * </ul>
 *
 * <h2>The rule</h2>
 * A tooltip line is one of five things, and its colour says which:
 *
 * <ol>
 *   <li>{@link #subject} — what you are hovering. Parchment, the same
 *       {@code D9D3C7} the sidebar names items in.</li>
 *   <li>{@link #figure} — the number you opened it for. Parchment too, and it
 *       LEADS its line: "29.6M gp after tax", never "Worth selling: 29.6M".</li>
 *   <li>{@link #muted} — the working. Counts, unit prices, what a figure is
 *       scoped to, parentheticals. Never a colour with a meaning.</li>
 *   <li>{@link #state} — what this mark is telling you, in the colour of the
 *       mark itself, so the text and the border you are hovering cannot
 *       disagree. The caller passes the border's own colour.</li>
 *   <li>{@link #money} — a gain or a loss, and ONLY when it is genuinely one.
 *       Proceeds are not profit; see BankHighlightOverlay, which learned that
 *       by painting a stack's sale value green.</li>
 * </ol>
 *
 * Nothing here knows about Swing. RuneLite's in-game tooltips are game-markup
 * text — {@code <col=rrggbb>} and {@code </br>} — and the colours arrive as
 * {@link Color} so they can come from the same constants the overlays paint
 * their borders with, rather than being retyped as hex beside them.
 */
final class TipStyle
{
	/** What you are hovering, and the figures on it. AdvisorPanel.TEXT_MAIN. */
	static final Color SUBJECT = new Color(0xD9, 0xD3, 0xC7);

	/**
	 * The working: counts, unit prices, what a number covers.
	 *
	 * A5A5A5, and the specific thing it is not is {@code 8a8274} — a brown
	 * that sat almost on the tooltip's background. That was reported as hard
	 * to read, fixed in the bank, and left in place in the Exchange overlay
	 * for want of a shared constant. This is that constant.
	 */
	static final Color MUTED = new Color(0xA5, 0xA5, 0xA5);

	/** A real gain. AdvisorPanel.POSITIVE. */
	static final Color GOOD = new Color(0x1F, 0xB8, 0x5C);

	/** A real loss. AdvisorPanel.NEGATIVE. */
	static final Color BAD = new Color(0xEF, 0x53, 0x50);

	/** The line break the game's tooltip markup wants. Not {@code <br>}. */
	static final String BREAK = "</br>";

	private TipStyle()
	{
	}

	/** A colour as the six hex digits the game's {@code <col>} tag wants. */
	static String hex(Color c)
	{
		return String.format("%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
	}

	/** {@code text} in {@code c}, and nothing else — no break, so lines can be
	 *  built out of several colours. */
	static String col(Color c, String text)
	{
		return "<col=" + hex(c) + ">" + text + "</col>";
	}

	/** What you are hovering. */
	static String subject(String text)
	{
		return col(SUBJECT, text);
	}

	/** The number you opened the tooltip for. */
	static String figure(String text)
	{
		return col(SUBJECT, text);
	}

	/** The working behind the figure. */
	static String muted(String text)
	{
		return col(MUTED, text);
	}

	/**
	 * What this mark is saying, in the mark's own colour.
	 *
	 * The colour is the caller's because it has to be the one actually
	 * painted on screen — the bank's theme-driven sell teal or its white
	 * lead rim, the Exchange slot's green or red border. A tooltip that
	 * picked its own would be free to disagree with the square under the
	 * cursor, which is how the bank ended up with a frozen teal line under
	 * three themes that had moved.
	 */
	static String state(Color markColor, String text)
	{
		return col(markColor, text);
	}

	/**
	 * A gain or a loss, signed, abbreviated.
	 *
	 * Only for figures that really are one. "What this stack fetches" is not
	 * a gain however it is worded, and painting it green claims it is.
	 */
	static String money(long v)
	{
		return col(v >= 0 ? GOOD : BAD,
			(v >= 0 ? "+" : "") + QuantityFormatter.quantityToStackSize(v) + " gp");
	}
}
