package com.pocketge.tracker;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/**
 * The sidebar wears pocketge.com's colours, and keeps wearing them.
 *
 * The card had been the site's {@code --bg-panel} since it was built and
 * everything around it was RuneLite's stock grey, which reads as exactly what
 * it was: a warm brown panel in a grey box. Spotted from a screenshot — "im
 * not crazy right, that top section is brown? i like it alot, matches the
 * website".
 *
 * These values are copied from {@code app.css}. Nothing in this repo can read
 * that file, so the copy is pinned here against the custom property it came
 * from: a change on either side has to be made on both, deliberately.
 */
public class BrandTest
{
	/** Every surface colour, against the CSS custom property it was taken
	 *  from. Keep in step with app.css :root. */
	private static final Map<String, Color> FROM_APP_CSS = Map.of(
		"--bg-base", Brand.BG_BASE,
		"--bg-chart", Brand.BG_CHART,
		"--bg-panel", Brand.BG_PANEL,
		"--bg-input", Brand.BG_INPUT,
		"--border-main", Brand.BORDER_MAIN,
		"--border-light", Brand.BORDER_LIGHT,
		"--text-main", Brand.TEXT_MAIN,
		"--text-structural", Brand.TEXT_STRUCTURAL,
		"--text-muted", Brand.TEXT_MUTED,
		"--accent-bright", Brand.ACCENT_BRIGHT);

	private static final Map<String, String> EXPECTED = Map.of(
		"--bg-base", "0c0b09",
		"--bg-chart", "131110",
		"--bg-panel", "1b1815",
		"--bg-input", "282320",
		"--border-main", "2b2621",
		"--border-light", "3c352b",
		"--text-main", "d9d3c7",
		"--text-structural", "948a78",
		"--text-muted", "8a8274",
		"--accent-bright", "e5c158");

	private static String hex(Color c)
	{
		return String.format("%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
	}

	@Test
	public void everySurfaceIsTheWebsitesOwn()
	{
		for (Map.Entry<String, Color> e : FROM_APP_CSS.entrySet())
		{
			Assert.assertEquals("app.css " + e.getKey(),
				EXPECTED.get(e.getKey()), hex(e.getValue()));
		}
	}

	/**
	 * The card's own background is the same object the rest of the sidebar is
	 * laid out against — not a second copy of 1B1815 that can drift.
	 */
	@Test
	public void theCardAndTheBrandAgreeOnThePanelColour() throws Exception
	{
		final String src = Files.readString(
			Path.of("src/main/java/com/pocketge/tracker/AdvisorPanel.java"));
		Assert.assertTrue("OBSIDIAN_BG should come from Brand",
			src.contains("OBSIDIAN_BG = Brand.BG_PANEL"));
	}

	/**
	 * No panel reaches for a RuneLite grey any more.
	 *
	 * DARK/DARKER/MEDIUM_GRAY are neutral, and one of them left in a warm
	 * sidebar is a grey rectangle in a brown column — which is the defect
	 * this whole change is about, at the scale of a single widget.
	 *
	 * LIGHT_GRAY_COLOR is on the list too. It was the label colour on 34
	 * labels; the site paints those {@code --text-structural}, which is warm.
	 */
	@Test
	public void noSidebarPanelStillUsesARuneliteGrey() throws Exception
	{
		final List<String> panels = List.of(
			"AdvisorPanel", "FavoritesPanel", "FinderPanel", "GeSlotsPanel",
			"HistoryPanel", "MainPanel", "StatsHeaderPanel");
		for (String cls : panels)
		{
			final Path p = Path.of("src/main/java/com/pocketge/tracker/" + cls + ".java");
			if (!Files.exists(p))
			{
				continue;
			}
			final String src = stripComments(Files.readString(p));
			for (String grey : List.of("DARK_GRAY_COLOR", "DARKER_GRAY_COLOR",
				"MEDIUM_GRAY_COLOR", "LIGHT_GRAY_COLOR"))
			{
				Assert.assertFalse(cls + " still uses ColorScheme." + grey,
					src.contains("ColorScheme." + grey));
			}
		}
	}

	/**
	 * The label colour stays readable on the surfaces it is actually drawn on.
	 *
	 * Warm is not worth much if it cannot be read: {@code --text-structural}
	 * is a step darker than the neutral grey it replaced, and it sits on the
	 * two darkest surfaces in the plugin. WCAG AA for body text is 4.5:1.
	 */
	@Test
	public void theLabelColourClearsAaOnEverySurfaceItIsUsedOn()
	{
		for (Color bg : List.of(Brand.BG_BASE, Brand.BG_PANEL, Brand.BG_INPUT))
		{
			final double ratio = contrast(Brand.TEXT_STRUCTURAL, bg);
			Assert.assertTrue("structural text on " + hex(bg) + " is only "
				+ String.format("%.1f", ratio) + ":1", ratio >= 4.5);
		}
		Assert.assertTrue("and item names are brighter still",
			contrast(Brand.TEXT_MAIN, Brand.BG_PANEL)
				> contrast(Brand.TEXT_STRUCTURAL, Brand.BG_PANEL));
	}

	/**
	 * The buy and sell hues are NOT here.
	 *
	 * They are the player's, chosen in settings. A frozen copy in the brand
	 * palette would be a second, unthemed source for the two colours that
	 * carry the plugin's only real meaning — the drift the bank marks and the
	 * watchlist badges have each been fixed for once already.
	 */
	@Test
	public void theBrandDoesNotFreezeTheBuyAndSellColours() throws Exception
	{
		final String src = stripComments(Files.readString(
			Path.of("src/main/java/com/pocketge/tracker/Brand.java")));
		for (String themed : List.of("BUY", "SELL"))
		{
			Assert.assertFalse("Brand should not name a " + themed + " colour",
				src.contains("Color " + themed));
		}
		Assert.assertFalse("nor the default theme's values",
			src.toLowerCase().contains("0xe5, 0xb8, 0x42")
				|| src.toLowerCase().contains("0x26, 0xa9, 0xab"));
	}

	private static String stripComments(String s)
	{
		return s.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
	}

	/** WCAG relative luminance contrast ratio. */
	private static double contrast(Color a, Color b)
	{
		final double la = luminance(a);
		final double lb = luminance(b);
		return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
	}

	private static double luminance(Color c)
	{
		final double[] v = new double[3];
		final int[] raw = {c.getRed(), c.getGreen(), c.getBlue()};
		for (int i = 0; i < 3; i++)
		{
			final double s = raw[i] / 255d;
			v[i] = s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
		}
		return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2];
	}
}
