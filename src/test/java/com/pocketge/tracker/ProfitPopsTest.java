package com.pocketge.tracker;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 * "if lets say rune darts sold. it would show +200gp or +2000gp or +20kgp
 * profit. for whatever batch just sold / how much profit over that square",
 * and "it also showed - red gp for when you lost money on somthing".
 */
public class ProfitPopsTest
{
	private static final Font FONT = new Font(Font.SANS_SERIF, Font.BOLD, 16);

	@Test
	public void saysWhatTheBatchMadeTheWayTheGameAbbreviates()
	{
		Assert.assertEquals("+200 gp", ProfitPops.text(200));
		Assert.assertEquals("+2,000 gp", ProfitPops.text(2_000));
		Assert.assertEquals("+20K gp", ProfitPops.text(20_000));
		Assert.assertEquals("-1,250 gp", ProfitPops.text(-1_250));
		Assert.assertEquals("-12K gp", ProfitPops.text(-12_000));
		Assert.assertEquals("+0 gp", ProfitPops.text(0));
		Assert.assertTrue(ProfitPops.text(Long.MIN_VALUE).startsWith("-"));
	}

	@Test
	public void greenForAGainRedForALoss()
	{
		Assert.assertEquals(ProfitPops.GAIN, ProfitPops.colour(1));
		Assert.assertEquals(ProfitPops.LOSS, ProfitPops.colour(-1));
		Assert.assertEquals(ProfitPops.EVEN, ProfitPops.colour(0));
	}

	/** Pops in a touch big, rises, fades, and is gone after its life. */
	@Test
	public void popsRisesAndFades()
	{
		Assert.assertTrue("starts small", ProfitPops.scale(0) < 1f);
		Assert.assertTrue("overshoots", ProfitPops.scale(100) > 1f);
		Assert.assertEquals("settles", 1f, ProfitPops.scale(400), 0f);
		Assert.assertEquals(0, ProfitPops.rise(0));
		Assert.assertTrue(ProfitPops.rise(1_000) < ProfitPops.rise(2_000));
		Assert.assertEquals(ProfitPops.RISE_PX, ProfitPops.rise(ProfitPops.LIFE_MS));
		Assert.assertEquals("solid at first", 1f, ProfitPops.alpha(500), 0f);
		Assert.assertTrue("fading late", ProfitPops.alpha(ProfitPops.LIFE_MS - 300) < 0.5f);
		Assert.assertEquals("gone", 0f, ProfitPops.alpha(ProfitPops.LIFE_MS), 0f);
		Assert.assertEquals("never before it happened", 0f, ProfitPops.alpha(-1), 0f);
	}

	private static final int DART = 811;
	private static final int PLANK = 8778;

	/** Everything on screen, each slot holding {@code item}. */
	private static List<ProfitPops.Pop> onScreen(ProfitPops pops, long now, int item)
	{
		return pops.show(now, slot -> true, slot -> item);
	}

	/** The Exchange shut. */
	private static List<ProfitPops.Pop> offScreen(ProfitPops pops, long now)
	{
		return pops.show(now, slot -> false, slot -> 0);
	}

	/** Batches of one offer on back-to-back ticks read as one sale: the
	 *  figure adds up and pops again. Another slot's is its own. */
	@Test
	public void batchesTogetherAddUp()
	{
		final ProfitPops pops = new ProfitPops();
		pops.add(2, DART, 1_000, 10_000);
		onScreen(pops, 10_000, DART);
		pops.add(3, DART, -300, 10_100);
		onScreen(pops, 10_100, DART);
		/* A tick later, give or take the client's frame. */
		pops.add(2, DART, 500, 10_630);
		final List<ProfitPops.Pop> live = onScreen(pops, 10_640, DART);
		Assert.assertEquals(2, live.size());
		final ProfitPops.Pop two = live.stream().filter(p -> p.slot == 2).findFirst().get();
		Assert.assertEquals(1_500, two.profit);
		Assert.assertEquals("pops again from the bar", 10_640, two.at);
		Assert.assertEquals(-300, live.stream().filter(p -> p.slot == 3).findFirst().get().profit);
	}

	/**
	 * Never two figures on one slot. Each rises a line's height over its
	 * life, so two a second or so apart were drawn into each other — four
	 * "+2,000 gp" smeared together, from batches 600 ms apart.
	 */
	@Test
	public void neverTwoFiguresOnOneSlot()
	{
		final ProfitPops pops = new ProfitPops();
		final long[] at = {10_000, 10_610, 11_215, 11_830, 13_500, 16_000};
		for (long t : at)
		{
			pops.add(0, DART, 2_000, t);
			Assert.assertEquals("one on the slot at " + t, 1, onScreen(pops, t, DART).size());
		}
		final ProfitPops.Pop last = onScreen(pops, 16_000, DART).get(0);
		Assert.assertEquals("a later batch is its own figure, not the run before it", 2_000, last.profit);
		Assert.assertEquals(16_000, last.at);
	}

	/**
	 * "even if i missed it live, the next time i load into this ge screen, it
	 * should have said +64kgp over rune darts in that square" — the Rune dart
	 * sale finished with the Exchange shut, and nothing ever showed.
	 */
	@Test
	public void aSaleNobodySawPopsWhenItsSquareIsNextOnScreen()
	{
		final ProfitPops pops = new ProfitPops();
		pops.add(2, DART, 20_000, 100_000);
		Assert.assertTrue(offScreen(pops, 100_000).isEmpty());
		pops.add(2, DART, 30_000, 400_000);
		Assert.assertTrue(offScreen(pops, 400_000).isEmpty());
		pops.add(2, DART, 14_000, 900_000);
		Assert.assertTrue("still waiting, long past a pop's life", offScreen(pops, 5_000_000).isEmpty());
		Assert.assertEquals(1, pops.held());

		final List<ProfitPops.Pop> opened = onScreen(pops, 6_000_000, DART);
		Assert.assertEquals(1, opened.size());
		Assert.assertEquals("the whole sale, not its last batch", 64_000, opened.get(0).profit);
		Assert.assertEquals("+64K gp", ProfitPops.text(opened.get(0).profit));
		Assert.assertEquals("pops from the moment it is seen", 6_000_000, opened.get(0).at);

		Assert.assertEquals(1, onScreen(pops, 6_000_000 + ProfitPops.LIFE_MS - 1, DART).size());
		Assert.assertTrue("and once only", onScreen(pops, 6_000_000 + ProfitPops.LIFE_MS, DART).isEmpty());
		Assert.assertEquals(0, pops.held());
	}

	/** One already popping when the Exchange shuts runs out unseen: it does
	 *  not start over when the Exchange opens again. */
	@Test
	public void aFigureSeenOnceIsNotShownAgain()
	{
		final ProfitPops pops = new ProfitPops();
		pops.add(1, DART, 5_000, 10_000);
		Assert.assertEquals(1, onScreen(pops, 10_000, DART).size());
		Assert.assertTrue(offScreen(pops, 11_000).isEmpty());
		Assert.assertTrue(onScreen(pops, 10_000 + ProfitPops.LIFE_MS, DART).isEmpty());
	}

	/** The offer it was for is gone when the square is next seen — collected
	 *  where the plugin could not see — so its figure goes too, rather than
	 *  landing on whatever is there now. Only judged on screen: with the
	 *  Exchange shut the slots read empty at every login. */
	@Test
	public void aWaitingFigureForAnOfferThatIsGoneIsDropped()
	{
		final ProfitPops pops = new ProfitPops();
		pops.add(4, DART, 9_000, 1_000);
		Assert.assertTrue(pops.show(2_000, slot -> false, slot -> 0).isEmpty());
		Assert.assertEquals("not judged while off screen", 1, pops.held());
		Assert.assertTrue(onScreen(pops, 3_000, PLANK).isEmpty());
		Assert.assertEquals(0, pops.held());
		Assert.assertTrue("and not back for the right item later", onScreen(pops, 4_000, DART).isEmpty());
	}

	/** Another item's sale on the slot takes it over; it is not added in. */
	@Test
	public void anotherOffersSaleTakesTheSlotOver()
	{
		final ProfitPops pops = new ProfitPops();
		pops.add(0, DART, 9_000, 1_000);
		pops.add(0, PLANK, 400, 2_000);
		final List<ProfitPops.Pop> live = onScreen(pops, 3_000, PLANK);
		Assert.assertEquals(1, live.size());
		Assert.assertEquals(400, live.get(0).profit);
	}

	@Test
	public void clearDropsWaitingFigures()
	{
		final ProfitPops pops = new ProfitPops();
		pops.add(0, DART, 9_000, 1_000);
		pops.add(1, DART, 9_000, 1_000);
		pops.clear();
		Assert.assertTrue(onScreen(pops, 2_000, DART).isEmpty());
	}

	@Test
	public void goneOnceItsLifeIsOver()
	{
		final ProfitPops pops = new ProfitPops();
		pops.add(0, DART, 1_000, 5_000);
		onScreen(pops, 5_000, DART);
		Assert.assertEquals(1, onScreen(pops, 5_000 + ProfitPops.LIFE_MS - 1, DART).size());
		Assert.assertTrue(onScreen(pops, 5_000 + ProfitPops.LIFE_MS, DART).isEmpty());
		pops.add(0, DART, 1_000, 50_000);
		onScreen(pops, 50_000, DART);
		Assert.assertTrue("a clock set back does not leave it hanging", onScreen(pops, 40_000, DART).isEmpty());
	}

	/** Past the guard, the oldest go and the newest stay, in order. */
	@Test
	public void aBurstKeepsTheNewest()
	{
		final ProfitPops pops = new ProfitPops();
		for (int i = 0; i < 40; i++)
		{
			pops.add(i, i, i, 1_000 + i);
		}
		final List<ProfitPops.Pop> live = pops.show(1_100, slot -> true, slot -> slot);
		Assert.assertEquals(ProfitPops.MAX, live.size());
		Assert.assertEquals("the oldest gone", 40 - ProfitPops.MAX, live.get(0).slot);
		Assert.assertEquals("the newest kept", 39, live.get(live.size() - 1).slot);
	}

	/** Drawn over its own square, in its colour, and not once it is over. */
	@Test
	public void paintsOverItsSquareOnly()
	{
		final Rectangle slot = new Rectangle(120, 40, 115, 110);
		final ProfitPops.Pop gain = new ProfitPops.Pop(1, DART, 0, 2_000);
		final BufferedImage img = paint(slot, gain, 400);
		Assert.assertTrue("green inside the square", count(img, slot, ProfitPops.GAIN) > 0);
		Assert.assertTrue("outlined in black, as the game's floating text is", count(img, slot, java.awt.Color.BLACK) > 0);
		Assert.assertEquals("nothing outside it", 0, count(img, outside(img, slot), ProfitPops.GAIN));

		final BufferedImage red = paint(slot, new ProfitPops.Pop(1, DART, 0, -1_250), 400);
		Assert.assertTrue("a loss in red", count(red, slot, ProfitPops.LOSS) > 0);
		Assert.assertEquals(0, count(red, slot, ProfitPops.GAIN));

		final BufferedImage after = paint(slot, gain, ProfitPops.LIFE_MS);
		Assert.assertEquals("gone at the end", 0, count(after, new Rectangle(0, 0, img.getWidth(), img.getHeight()),
			ProfitPops.GAIN));
	}

	/** The sale the plugin pops for: what the slot's booked total grew by. */
	@Test
	public void theBatchIsWhatTheSlotsBookingsGrewBy()
	{
		Assert.assertEquals(Long.valueOf(2_000), PocketGeTrackerPlugin.soldBatch(null, new long[]{100, 2_000}));
		Assert.assertEquals(Long.valueOf(-500),
			PocketGeTrackerPlugin.soldBatch(new long[]{100, 2_000}, new long[]{150, 1_500}));
		Assert.assertNull("no sale booked", PocketGeTrackerPlugin.soldBatch(new long[]{100, 2_000}, new long[]{100, 2_000}));
		Assert.assertNull("nothing booked at all", PocketGeTrackerPlugin.soldBatch(null, null));
		Assert.assertNull("another offer now", PocketGeTrackerPlugin.soldBatch(new long[]{100, 2_000}, null));
		Assert.assertNull("another offer, fewer units", PocketGeTrackerPlugin.soldBatch(new long[]{100, 2_000}, new long[]{40, 900}));
	}

	/** End to end through the tracker: a live sell's batches each pop what
	 *  they closed; a sell of something never seen bought pops nothing. */
	@Test
	public void eachBatchOfARealSellPopsItsProfit()
	{
		final FlipTracker t = new FlipTracker();
		t.setAccountHash(42L);
		final int dart = 811;
		t.onOffer(1_000, 0, dart, "Rune dart", true, 0, 0, 100, 1_000, false);
		t.onOffer(2_000, 0, dart, "Rune dart", true, 1_000, 100_000, 100, 1_000, false);
		t.onOffer(3_000, 0, 0, "", false, 0, 0, 0, 0, true);
		t.onOffer(4_000, 1, dart, "Rune dart", false, 0, 0, 120, 1_000, false);

		long[] before = t.bookedBy(1);
		t.onOffer(5_000, 1, dart, "Rune dart", false, 400, 48_000, 120, 1_000, false);
		final Long first = PocketGeTrackerPlugin.soldBatch(before, t.bookedBy(1));
		Assert.assertNotNull(first);
		/* 400 at 120 less the 2% tax (2 gp each), against 100 paid. */
		Assert.assertEquals(400L * (120 - 2 - 100), first.longValue());

		before = t.bookedBy(1);
		t.onOffer(6_000, 1, dart, "Rune dart", false, 1_000, 120_000, 120, 1_000, false);
		Assert.assertEquals(Long.valueOf(600L * (120 - 2 - 100)), PocketGeTrackerPlugin.soldBatch(before, t.bookedBy(1)));

		t.onOffer(7_000, 2, 565, "Blood rune", false, 0, 0, 400, 50, false);
		before = t.bookedBy(2);
		t.onOffer(8_000, 2, 565, "Blood rune", false, 50, 20_000, 400, 50, false);
		Assert.assertNull("never seen bought: nothing to call profit",
			PocketGeTrackerPlugin.soldBatch(before, t.bookedBy(2)));
	}

	private static BufferedImage paint(Rectangle slot, ProfitPops.Pop p, long now)
	{
		final BufferedImage img = new BufferedImage(360, 200, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = img.createGraphics();
		ProfitPops.draw(g, slot, p, now, FONT);
		g.dispose();
		return img;
	}

	private static Rectangle outside(BufferedImage img, Rectangle slot)
	{
		return new Rectangle(slot.x + slot.width, 0, img.getWidth() - slot.x - slot.width, img.getHeight());
	}

	/** Pixels in {@code area} that are {@code c}, near enough for antialiasing. */
	private static int count(BufferedImage img, Rectangle area, java.awt.Color c)
	{
		int n = 0;
		for (int y = Math.max(0, area.y); y < Math.min(img.getHeight(), area.y + area.height); y++)
		{
			for (int x = Math.max(0, area.x); x < Math.min(img.getWidth(), area.x + area.width); x++)
			{
				final int rgb = img.getRGB(x, y);
				if ((rgb >>> 24) > 200 && Math.abs(((rgb >> 16) & 0xFF) - c.getRed()) < 12
					&& Math.abs(((rgb >> 8) & 0xFF) - c.getGreen()) < 12 && Math.abs((rgb & 0xFF) - c.getBlue()) < 12)
				{
					n++;
				}
			}
		}
		return n;
	}
}
