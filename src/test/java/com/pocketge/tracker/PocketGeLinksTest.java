package com.pocketge.tracker;

import org.junit.Assert;
import org.junit.Test;

public class PocketGeLinksTest
{
	@Test
	public void everyLinkIdentifiesItselfAsComingFromThePlugin()
	{
		for (String url : new String[]{
			PocketGeLinks.home("toolbar"),
			PocketGeLinks.home("bottom_link"),
			PocketGeLinks.home("flip_history"),
			PocketGeLinks.item("Emerald%20necklace", "chart"),
		})
		{
			Assert.assertTrue(url, url.startsWith("https://pocketge.com/?"));
			Assert.assertTrue(url, url.contains("utm_source=runelite"));
			Assert.assertTrue(url, url.contains("utm_medium=plugin"));
		}
	}

	/**
	 * q stays FIRST. The site reads that parameter to decide which item to
	 * open, and it is the half a human might read in the address bar; the
	 * tracking belongs behind it.
	 */
	@Test
	public void theItemNameStaysTheFirstParameter()
	{
		final String url = PocketGeLinks.item("Emerald%20necklace", "chart");
		Assert.assertEquals("https://pocketge.com/?q=Emerald%20necklace"
			+ "&utm_source=runelite&utm_medium=plugin&utm_content=chart", url);
		Assert.assertTrue("q must precede the tags",
			url.indexOf("q=") < url.indexOf("utm_"));
	}

	/** The whole point of utm_content: the chart button and the toolbar globe
	 *  have to be distinguishable once they arrive. */
	@Test
	public void eachControlIsDistinguishable()
	{
		Assert.assertTrue(PocketGeLinks.home("toolbar").endsWith("utm_content=toolbar"));
		Assert.assertTrue(PocketGeLinks.home("flip_history").endsWith("utm_content=flip_history"));
		Assert.assertNotEquals(PocketGeLinks.home("toolbar"), PocketGeLinks.home("bottom_link"));
	}

	/** An encoded name must survive untouched — double-encoding it here would
	 *  open the wrong item, or none. */
	@Test
	public void anAlreadyEncodedNameIsNotEncodedAgain()
	{
		Assert.assertTrue(PocketGeLinks.item("Gold%20amulet%20(u)", "chart")
			.contains("q=Gold%20amulet%20(u)&"));
	}
}
