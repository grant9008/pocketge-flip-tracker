package com.pocketge.tracker;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

/**
 * What the eight sidebar squares say, and in which colours.
 *
 * A box only when the slot needs you, and the bar in the side's colour:
 * "i dont like the white boxes honestly ... boxes only appear for change?
 * the yellow box conflicts meaning with the theme". A grey box went round
 * every busy slot, and the yellow reprice box went round sells too, in a
 * theme whose yellow means buy.
 *
 * The sidebar and the in-game box agree about the same slot — including a
 * slot you are pricing yourself, which once muted one and not the other.
 */
public class SlotAccentTest
{
	private static GeSlotsPanel.SlotInfo slot(GeSlotsPanel.SlotState state, boolean buy, boolean skipped)
	{
		final GeSlotsPanel.SlotInfo s = new GeSlotsPanel.SlotInfo();
		s.state = state;
		s.buy = buy;
		s.adviceSkipped = skipped;
		return s;
	}

	@After
	public void backToTheDefault()
	{
		final PocketGeTrackerConfig.ColourTheme t = PocketGeTrackerConfig.ColourTheme.CONTRAST;
		GeSlotsPanel.setTheme(t.buy(), t.sell());
	}

	/** Working, empty, or no offer at all: no box. The square is its
	 *  picture and its bar. */
	@Test
	public void aWorkingSlotHasNoBox()
	{
		Assert.assertNull(GeSlotsPanel.box(slot(GeSlotsPanel.SlotState.ACTIVE_OK, true, false)));
		Assert.assertNull(GeSlotsPanel.box(slot(GeSlotsPanel.SlotState.ACTIVE_OK, false, false)));
		Assert.assertNull(GeSlotsPanel.box(slot(GeSlotsPanel.SlotState.EMPTY, false, false)));
		Assert.assertNull(GeSlotsPanel.box(null));
	}

	/**
	 * A slot wanting a new price is boxed in white, the plugin's "act here",
	 * buy or sell and whatever the theme. In the buy colour it put the old
	 * yellow back round every buy in the default theme: "this yellow i think
	 * should be an ew color ... or white to match our theme actualy for
	 * directing the user". Losing at a bid is a reprice too.
	 */
	@Test
	public void aRepriceIsBoxedInWhiteWhateverTheTheme()
	{
		for (PocketGeTrackerConfig.ColourTheme t : PocketGeTrackerConfig.ColourTheme.values())
		{
			GeSlotsPanel.setTheme(t.buy(), t.sell());
			Assert.assertEquals(GeSlotsPanel.REPRICE_COLOR, GeSlotsPanel.box(slot(GeSlotsPanel.SlotState.ACTIVE_ADJUST, true, false)));
			Assert.assertEquals(GeSlotsPanel.REPRICE_COLOR, GeSlotsPanel.box(slot(GeSlotsPanel.SlotState.ACTIVE_ADJUST, false, false)));
			Assert.assertEquals(GeSlotsPanel.REPRICE_COLOR, GeSlotsPanel.box(slot(GeSlotsPanel.SlotState.ACTIVE_LOSING, true, false)));
		}
		Assert.assertEquals("one white, for act here", BankHighlightOverlay.RECOMMENDED_COLOR, GeSlotsPanel.REPRICE_COLOR);
	}

	/** The bar is the side's colour whatever the state. */
	@Test
	public void theBarIsTheSidesColour()
	{
		for (PocketGeTrackerConfig.ColourTheme t : PocketGeTrackerConfig.ColourTheme.values())
		{
			GeSlotsPanel.setTheme(t.buy(), t.sell());
			Assert.assertEquals(t + " buy", t.buy(), GeSlotsPanel.sideColour(true));
			Assert.assertEquals(t + " sell", t.sell(), GeSlotsPanel.sideColour(false));
		}
	}

	/** A slot you are pricing yourself is never boxed for a new price: the
	 *  plugin has nothing to say about it. */
	@Test
	public void aSlotYouArePricingYourselfHasNoBox()
	{
		Assert.assertNull(GeSlotsPanel.box(slot(GeSlotsPanel.SlotState.ACTIVE_OK, true, true)));
		Assert.assertNull(GeSlotsPanel.box(slot(GeSlotsPanel.SlotState.ACTIVE_ADJUST, false, true)));
		Assert.assertNull(GeSlotsPanel.box(slot(GeSlotsPanel.SlotState.ACTIVE_LOSING, true, true)));
	}

	/**
	 * But a collectable one always is. The flag outlives the offer it was
	 * set on, and hiding a slot that is ready to collect would hide the one
	 * state that actually wants a click.
	 */
	@Test
	public void aCollectableSlotIsAlwaysBoxedGreen()
	{
		Assert.assertEquals(GeSlotsPanel.COLLECT_COLOR,
			GeSlotsPanel.box(slot(GeSlotsPanel.SlotState.READY_COLLECT, false, true)));
		Assert.assertEquals(GeSlotsPanel.COLLECT_COLOR,
			GeSlotsPanel.box(slot(GeSlotsPanel.SlotState.READY_COLLECT, true, false)));
		Assert.assertEquals(TipStyle.GOOD, GeSlotsPanel.COLLECT_COLOR);
	}

	/**
	 * Done is the game's green, and no theme's buy or sell colour is
	 * anywhere near it — so a green box can only mean one thing, whichever
	 * theme is on. Measured as a hue gap rather than equality: a theme green
	 * two shades off would mislead just as well. The nearest today is
	 * Terminal's teal sell, 37 degrees round from it with far more blue in
	 * it — and a done slot's bar is full besides.
	 */
	@Test
	public void noThemeColourIsTheDoneGreen()
	{
		final float green = hue(GeSlotsPanel.COLLECT_COLOR);
		for (PocketGeTrackerConfig.ColourTheme t : PocketGeTrackerConfig.ColourTheme.values())
		{
			for (Color c : new Color[]{t.buy(), t.sell()})
			{
				final float[] hsb = Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
				final float gap = Math.min(Math.abs(hsb[0] - green), 1 - Math.abs(hsb[0] - green));
				Assert.assertTrue(t + " " + c + " reads as the done green",
					hsb[1] < 0.25f || gap > 0.08f);
			}
		}
	}

	private static float hue(Color c)
	{
		return Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null)[0];
	}

	/**
	 * The painted square, not just the colour picked for it: a working sell
	 * shows no box colour on its edge and a sell-coloured bar; the same
	 * slot wanting a new price has the sell colour on its edge.
	 */
	@Test
	public void theSquareIsPaintedThatWay() throws Exception
	{
		final PocketGeTrackerConfig.ColourTheme t = PocketGeTrackerConfig.ColourTheme.CONTRAST;
		final GeSlotsPanel.SlotInfo working = slot(GeSlotsPanel.SlotState.ACTIVE_OK, false, false);
		working.quantityTotal = 10;
		working.quantityFilled = 10;
		final BufferedImage ok = paintCell(working);
		Assert.assertEquals("no box on a working slot", GeSlotsPanel.EMPTY_BORDER.getRGB(), ok.getRGB(ok.getWidth() / 2, 0));
		Assert.assertEquals("its bar in the sell colour", t.sell().getRGB(), ok.getRGB(ok.getWidth() / 2, ok.getHeight() - 1));
		Assert.assertEquals("on a grey tile", GeSlotsPanel.SLOT_FILL.getRGB(), ok.getRGB(3, 3));
		final BufferedImage empty = paintCell(slot(GeSlotsPanel.SlotState.EMPTY, false, false));
		Assert.assertEquals("an empty slot has none", 0, empty.getRGB(3, 3) >>> 24);

		final GeSlotsPanel.SlotInfo reprice = slot(GeSlotsPanel.SlotState.ACTIVE_ADJUST, false, false);
		final BufferedImage boxed = paintCell(reprice);
		Assert.assertEquals("boxed in white", GeSlotsPanel.REPRICE_COLOR.getRGB(), boxed.getRGB(boxed.getWidth() / 2, 0));

		/* Done: boxed green, and the bar full in the side's own colour, so a
		   finished sale and a finished buy differ — "go with A". */
		final BufferedImage sold = paintCell(slot(GeSlotsPanel.SlotState.READY_COLLECT, false, false));
		final int w = sold.getWidth();
		final int h = sold.getHeight();
		Assert.assertEquals("boxed green", GeSlotsPanel.COLLECT_COLOR.getRGB(), sold.getRGB(w / 2, 0));
		for (int y = h - 3; y < h; y++)
		{
			Assert.assertEquals("a full bar in the sell colour", t.sell().getRGB(), sold.getRGB(w - 2, y));
		}
		final BufferedImage bought = paintCell(slot(GeSlotsPanel.SlotState.READY_COLLECT, true, false));
		Assert.assertEquals("boxed green", GeSlotsPanel.COLLECT_COLOR.getRGB(), bought.getRGB(w / 2, 0));
		for (int y = h - 3; y < h; y++)
		{
			Assert.assertEquals("a full bar in the buy colour", t.buy().getRGB(), bought.getRGB(w - 2, y));
		}
		Assert.assertNotEquals("so the two differ", t.buy().getRGB(), t.sell().getRGB());
	}

	/** Green once done, buy or sell; the side's colour until then. */
	@Test
	public void aFinishedSlotsBarKeepsItsSidesColour()
	{
		final PocketGeTrackerConfig.ColourTheme t = PocketGeTrackerConfig.ColourTheme.CONTRAST;
		GeSlotsPanel.setTheme(t.buy(), t.sell());
		for (boolean buy : new boolean[]{true, false})
		{
			Assert.assertEquals(GeSlotsPanel.sideColour(buy),
				GeSlotsPanel.barColour(slot(GeSlotsPanel.SlotState.READY_COLLECT, buy, false)));
			Assert.assertEquals(GeSlotsPanel.sideColour(buy),
				GeSlotsPanel.barColour(slot(GeSlotsPanel.SlotState.ACTIVE_OK, buy, false)));
			Assert.assertEquals(GeSlotsPanel.sideColour(buy),
				GeSlotsPanel.barColour(slot(GeSlotsPanel.SlotState.ACTIVE_ADJUST, buy, false)));
		}
	}

	/** One of the panel's squares, sized as the grid lays it out and
	 *  painted into an image. */
	private static BufferedImage paintCell(GeSlotsPanel.SlotInfo info) throws Exception
	{
		final GeSlotsPanel panel = new GeSlotsPanel(null, null);
		panel.update(new GeSlotsPanel.SlotInfo[]{info, null, null, null, null, null, null, null});
		panel.setSize(225, 120);
		panel.doLayout();
		javax.swing.JComponent cell = null;
		for (java.awt.Component c : allOf(panel))
		{
			if (c.getClass().getSimpleName().equals("Cell"))
			{
				cell = (javax.swing.JComponent) c;
				break;
			}
		}
		Assert.assertNotNull("a Cell in the grid", cell);
		cell.setSize(49, 40);
		final BufferedImage img = new BufferedImage(cell.getWidth(), cell.getHeight(), BufferedImage.TYPE_INT_ARGB);
		final Graphics g = img.createGraphics();
		cell.paint(g);
		g.dispose();
		return img;
	}

	private static java.util.List<java.awt.Component> allOf(java.awt.Container root)
	{
		final java.util.List<java.awt.Component> out = new java.util.ArrayList<>();
		for (java.awt.Component c : root.getComponents())
		{
			out.add(c);
			if (c instanceof java.awt.Container)
			{
				out.addAll(allOf((java.awt.Container) c));
			}
		}
		return out;
	}
}
