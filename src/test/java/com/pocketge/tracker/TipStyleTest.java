package com.pocketge.tracker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Assert;
import org.junit.Test;

/**
 * The two in-game tooltips speak one vocabulary.
 *
 * They did not. Each overlay wrote its own colours as hex literals inside the
 * markup, so the bank's tooltip was fixed and tested while the Exchange's kept
 * the exact brown the bank had just been rid of — {@code 8a8274}, two shades
 * off the tooltip's own background — on four of its lines.
 *
 * The test reads the sources, because that is where the defect lives: a hex
 * literal beside a {@link java.awt.Color} constant holding the same value is
 * the thing that let the border and the sentence explaining the border be
 * changed apart.
 */
public class TipStyleTest
{
	private static final List<String> OVERLAYS = List.of(
		"BankHighlightOverlay", "GeOfferGridOverlay");

	/**
	 * The class's CODE, with comments stripped.
	 *
	 * The comments name the colours that were removed, and are the record of
	 * why — deleting them to satisfy a grep would throw away the only account
	 * of a defect that has now been introduced twice.
	 */
	private static String source(String cls) throws IOException
	{
		final String raw = Files.readString(
			Path.of("src/main/java/com/pocketge/tracker/" + cls + ".java"));
		return raw.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
	}

	/**
	 * No colour is spelled out inside the markup any more.
	 *
	 * {@code <col=} followed by six hex digits is the whole bug class: it
	 * cannot be found by a compiler, cannot be moved with the theme, and reads
	 * as the same kind of thing whether it matches a constant or not.
	 */
	@Test
	public void noOverlayWritesAColourAsHex() throws Exception
	{
		final Pattern lit = Pattern.compile("<col=[0-9a-fA-F]{6}");
		for (String cls : OVERLAYS)
		{
			final Matcher m = lit.matcher(source(cls));
			Assert.assertFalse(cls + " still writes a colour into its markup: "
				+ (m.find() ? m.group() : ""), m.reset().find());
		}
	}

	/**
	 * The unreadable brown is gone from BOTH surfaces, not just the one that
	 * was reported.
	 */
	@Test
	public void theBrownIsGoneEverywhere() throws Exception
	{
		for (String cls : OVERLAYS)
		{
			Assert.assertFalse(cls + " still uses the 8a8274 brown",
				source(cls).toLowerCase().contains("8a8274"));
		}
		Assert.assertFalse("and the shared muted colour is not it",
			TipStyle.hex(TipStyle.MUTED).equals("8a8274"));
	}

	/**
	 * The near-gold that titled every Exchange tooltip is gone.
	 *
	 * e5c158 was not the theme's buy gold (E5B842), not the parchment the
	 * sidebar names items in, and not any other colour in the plugin — close
	 * enough to several to look deliberate and match none of them.
	 */
	@Test
	public void theNearMissGoldIsGone() throws Exception
	{
		Assert.assertFalse(source("GeOfferGridOverlay").toLowerCase().contains("e5c158"));
	}

	/** An item's name is parchment on every surface — the sidebar's TEXT_MAIN. */
	@Test
	public void namesTheSubjectInTheSameColourTheSidebarDoes()
	{
		Assert.assertEquals("d9d3c7", TipStyle.hex(TipStyle.SUBJECT));
		Assert.assertTrue(TipStyle.subject("Uncut ruby").contains("<col=d9d3c7>"));
	}

	/** The working is muted, and legibly so. */
	@Test
	public void mutesTheWorking()
	{
		Assert.assertEquals("a5a5a5", TipStyle.hex(TipStyle.MUTED));
	}

	/**
	 * Money keeps the sidebar's own green and red, and carries its sign.
	 *
	 * Same values as AdvisorPanel.POSITIVE and NEGATIVE, so a loss is the same
	 * red in the Exchange as on the card describing the same offer.
	 */
	@Test
	public void aGainAndALossWearTheSidebarsColours()
	{
		Assert.assertEquals(0x1FB85C, TipStyle.GOOD.getRGB() & 0xFFFFFF);
		Assert.assertEquals(0xEF5350, TipStyle.BAD.getRGB() & 0xFFFFFF);
		Assert.assertTrue(TipStyle.money(1_100_000).contains("+1.1M gp"));
		Assert.assertTrue(TipStyle.money(1_100_000).contains("1fb85c"));
		Assert.assertTrue(TipStyle.money(-250_000).contains("ef5350"));
		Assert.assertFalse("a loss needs no plus sign", TipStyle.money(-250_000).contains("+"));
	}

	/**
	 * A state line takes the colour of the mark it explains, from the caller.
	 *
	 * This is the one colour TipStyle refuses to choose. The bank's marks move
	 * with the theme and the Exchange's borders do not, and a tooltip that
	 * picked its own would be free to disagree with the square under the
	 * cursor — which is exactly how the bank ended up printing a frozen teal
	 * line under three themes that had moved.
	 */
	@Test
	public void aStateLineWearsTheMarksOwnColour()
	{
		final java.awt.Color neon = PocketGeTrackerConfig.ColourTheme.NEON.sell();
		Assert.assertTrue(TipStyle.state(neon, "up 12%").contains(TipStyle.hex(neon)));
	}

	/** Game markup, not HTML: the break is {@code </br>}. A {@code <br>} here
	 *  prints as four literal characters in the client. */
	@Test
	public void usesTheGamesOwnLineBreak()
	{
		Assert.assertEquals("</br>", TipStyle.BREAK);
		for (String cls : OVERLAYS)
		{
			try
			{
				Assert.assertFalse(cls + " uses an HTML break",
					source(cls).contains("\"<br>\""));
			}
			catch (IOException e)
			{
				throw new AssertionError(e);
			}
		}
	}
}
