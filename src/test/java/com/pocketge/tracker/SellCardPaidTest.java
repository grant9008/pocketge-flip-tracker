package com.pocketge.tracker;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.junit.Assert;
import org.junit.Test;

/**
 * The inspected-item card for a stack you hold: what you paid, and what
 * selling makes.
 *
 * Reported from a real screen: 639 Mithril bars, 173 of them bought through
 * the plugin at 876 gp. The card said "PAID @ 3,252", "+1,038 gp profit" and
 * "Up -73% on what you paid", beside a SELL @ box of 896. The paid figure
 * divided the whole stack's proceeds by the 173 tracked units; the profit was
 * priced at the live insta-buy (900), not at the 896 the same card said to
 * sell at; and a negative change was printed after the word "Up".
 */
public class SellCardPaidTest
{
	private static final int MITHRIL_BAR = 2359;

	private static AdvisorPanel.Rec recFor(FavoritesPanel.Row row) throws Exception
	{
		final AdvisorPanel.Actions actions = (AdvisorPanel.Actions) Proxy.newProxyInstance(
			AdvisorPanel.Actions.class.getClassLoader(), new Class<?>[]{AdvisorPanel.Actions.class},
			(p, m, a) ->
			{
				final Class<?> t = m.getReturnType();
				if (t == boolean.class)
				{
					return false;
				}
				return t.isPrimitive() ? (t == long.class ? (Object) 0L : (Object) 0) : null;
			});
		final AdvisorPanel panel = new AdvisorPanel(null, actions);
		final Method m = AdvisorPanel.class.getDeclaredMethod("recFor", FavoritesPanel.Row.class);
		m.setAccessible(true);
		return (AdvisorPanel.Rec) m.invoke(panel, row);
	}

	/** The Mithril bar stack exactly as it was held. */
	private static FavoritesPanel.Row mithril()
	{
		final FavoritesPanel.Row r = new FavoritesPanel.Row();
		r.id = MITHRIL_BAR;
		r.name = "Mithril bar";
		r.price = 889;
		r.targetSell = 896;
		final PortfolioValuer.HeldPosition p = PortfolioValuer.heldPosition(
			MITHRIL_BAR, 639, quote(889, 900), new long[]{173, 173L * 876});
		r.heldQty = p.heldQty;
		r.sellValue = p.sellValue;
		r.hasCostBasis = p.hasCostBasis;
		r.pricedQty = p.pricedQty;
		r.pricedCost = p.costOfPriced;
		r.heldProfit = p.heldProfit;
		return r;
	}

	private static Advisor.Quote quote(long low, long high)
	{
		final Advisor.Quote q = new Advisor.Quote();
		q.low = low;
		q.high = high;
		return q;
	}

	@Test
	public void paidIsWhatTheTrackedUnitsCost() throws Exception
	{
		Assert.assertEquals(876, recFor(mithril()).unitCost);
	}

	/** At the card's own SELL @: 173 x (896 - 17 tax) - 173 x 876. */
	@Test
	public void theGainIsPricedAtTheCardsOwnSell() throws Exception
	{
		Assert.assertEquals(173L * (896 - 17) - 173L * 876, recFor(mithril()).profit);
	}

	@Test
	public void theUntrackedRestIsValuedAtTheSameSell() throws Exception
	{
		final AdvisorPanel.Rec rec = recFor(mithril());
		Assert.assertEquals(639 - 173, rec.untrackedQty);
		Assert.assertEquals((639L - 173) * (896 - 17), rec.untrackedValue);
	}

	/**
	 * The later screen: 3,738 bought this time, 639 still in the bank from
	 * before. The old formula loaded the bank bars' sale value onto the
	 * tracked ones and read PAID @ 1,025; it is what those 3,738 cost.
	 */
	@Test
	public void theBankBarsAreNotLoadedOntoTheTrackedOnes() throws Exception
	{
		final FavoritesPanel.Row r = new FavoritesPanel.Row();
		r.id = MITHRIL_BAR;
		r.name = "Mithril bar";
		r.price = 870;
		r.targetSell = 888;
		final PortfolioValuer.HeldPosition p = PortfolioValuer.heldPosition(
			MITHRIL_BAR, 4_377, quote(870, 888), new long[]{3_738, 3_738L * 876});
		r.heldQty = p.heldQty;
		r.sellValue = p.sellValue;
		r.hasCostBasis = p.hasCostBasis;
		r.pricedQty = p.pricedQty;
		r.pricedCost = p.costOfPriced;
		r.heldProfit = p.heldProfit;
		final AdvisorPanel.Rec rec = recFor(r);
		Assert.assertEquals(876, rec.unitCost);
		Assert.assertEquals(639, rec.untrackedQty);
		Assert.assertEquals("-5 an item after tax, on the tracked 3,738",
			3_738L * (888 - 17) - 3_738L * 876, rec.profit);
	}

	private static final int AETHER_CATALYST = 30771;

	private static Advisor.OfferView offer(boolean buy, long price, int total, int sold)
	{
		final Advisor.OfferView o = new Advisor.OfferView();
		o.itemId = AETHER_CATALYST;
		o.itemName = "Aether catalyst";
		o.buy = buy;
		o.price = price;
		o.totalQuantity = total;
		o.quantitySold = sold;
		o.active = true;
		return o;
	}

	/** A row built the way the plugin builds it: offers first, then the
	 *  held position over bank + still-listed units. */
	private static FavoritesPanel.Row aether(int inBank, Advisor.OfferView... offers)
	{
		final FavoritesPanel.Row r = new FavoritesPanel.Row();
		r.id = AETHER_CATALYST;
		r.name = "Aether catalyst";
		r.price = 365;
		r.targetBuy = 353;
		r.targetSell = 371;
		r.limit = 25_000;
		PocketGeTrackerPlugin.fillOfferPosition(r, java.util.List.of(offers));
		final PortfolioValuer.HeldPosition p = PortfolioValuer.heldPosition(
			AETHER_CATALYST, inBank + r.listedQty, quote(365, 372), new long[]{25_000, 8_975_000});
		r.heldQty = p.heldQty;
		r.sellValue = p.sellValue;
		r.hasCostBasis = p.hasCostBasis;
		r.pricedQty = p.pricedQty;
		r.pricedCost = p.costOfPriced;
		r.heldProfit = p.heldProfit;
		r.heldWhere = PocketGeTrackerPlugin.positionWhere(r.listedQty, inBank > 0 ? "from your bank" : null);
		return r;
	}

	/**
	 * "i have 25k of them in ge waiting to sell at 374 ... thats way more
	 * important than capped by the 4h buy limit". Bought at 359 (8,975,000
	 * for 25,000), listed at 374: the card is that position, priced at the
	 * listing — the same +200K the slot's own tooltip shows.
	 */
	@Test
	public void aStackListedForSaleIsShownAsYourPosition() throws Exception
	{
		final AdvisorPanel.Rec rec = recFor(aether(0, offer(false, 374, 25_000, 0)));
		Assert.assertTrue("a sell, not the buy pitch", rec.sell);
		Assert.assertEquals(25_000, rec.quantity);
		Assert.assertEquals("PAID @", 359, rec.unitCost);
		Assert.assertEquals("SELL @ is your listing", 374, rec.unitPrice);
		Assert.assertEquals(25_000L * (374 - 7) - 8_975_000L, rec.profit);
		Assert.assertEquals(200_000, rec.profit);
		Assert.assertEquals("listed in the Exchange", rec.heldWhere);
		Assert.assertEquals("Listed at 374 \u2014 0 of 25,000 sold", rec.listingNote);
	}

	/** Part sold, and more in the bank: both counted, and said. */
	@Test
	public void aPartSoldListingWithMoreInTheBank() throws Exception
	{
		final AdvisorPanel.Rec rec = recFor(aether(5_000, offer(false, 374, 20_000, 8_000)));
		Assert.assertEquals("12,000 still listed + 5,000 in the bank", 17_000, rec.quantity);
		Assert.assertEquals("listed in the Exchange, more in your bank", rec.heldWhere);
		Assert.assertEquals("Listed at 374 \u2014 8,000 of 20,000 sold", rec.listingNote);
	}

	/** Still buying it: the card says where that offer is. */
	@Test
	public void aBuyInProgressSaysWhereItIs() throws Exception
	{
		final AdvisorPanel.Rec rec = recFor(aether(0, offer(true, 359, 25_000, 12_000)));
		Assert.assertFalse(rec.sell);
		Assert.assertEquals("you're bidding 359 gp \u2014 12,000 of 25,000 bought", rec.note);
	}

	/**
	 * "if im in my bank and wanna know how much i could profit or lose on a
	 * stack, i may want to inspect it to see that trade rather than a new
	 * trade" — and the other way round. The card swaps; the item menu offers
	 * whichever view is not showing.
	 */
	@Test
	public void aHeldStackCanBeSwappedToTheTradeIdeaAndBack() throws Exception
	{
		final AdvisorPanel.Actions actions = (AdvisorPanel.Actions) Proxy.newProxyInstance(
			AdvisorPanel.Actions.class.getClassLoader(), new Class<?>[]{AdvisorPanel.Actions.class},
			(p, m, a) ->
			{
				final Class<?> t = m.getReturnType();
				if (t == boolean.class)
				{
					return false;
				}
				return t.isPrimitive() ? (t == long.class ? (Object) 0L : (Object) 0) : null;
			});
		final AdvisorPanel panel = new AdvisorPanel(null, actions);
		final FavoritesPanel.Row row = aether(25_000);
		final Method recFor = AdvisorPanel.class.getDeclaredMethod("recFor", FavoritesPanel.Row.class);
		recFor.setAccessible(true);

		javax.swing.SwingUtilities.invokeAndWait(() -> panel.setSelectedItem(row));
		Assert.assertTrue("held: the stack first", ((AdvisorPanel.Rec) recFor.invoke(panel, row)).sell);
		Assert.assertEquals("the item menu offers the trade idea", Boolean.TRUE, panel.inspectSwapTarget(AETHER_CATALYST));

		javax.swing.SwingUtilities.invokeAndWait(() -> panel.setInspectView(true));
		final AdvisorPanel.Rec idea = (AdvisorPanel.Rec) recFor.invoke(panel, row);
		Assert.assertFalse("swapped: a new trade", idea.sell);
		Assert.assertEquals(353, idea.unitPrice);
		Assert.assertEquals("and the menu offers the stack back", Boolean.FALSE, panel.inspectSwapTarget(AETHER_CATALYST));
		Assert.assertNull("no swap offered on another item", panel.inspectSwapTarget(MITHRIL_BAR));

		final FavoritesPanel.Row other = mithril();
		javax.swing.SwingUtilities.invokeAndWait(() -> panel.setSelectedItem(other));
		Assert.assertTrue("another item starts on its stack again", ((AdvisorPanel.Rec) recFor.invoke(panel, other)).sell);
	}

	/** The value the old formula leaned on is still there for the row's own
	 *  tooltip — this only changes what the CARD derives from it. */
	@Test
	public void theCostIsReportedAlongsideTheProfit()
	{
		final PortfolioValuer.HeldPosition p = PortfolioValuer.heldPosition(
			MITHRIL_BAR, 639, quote(889, 900), new long[]{173, 173L * 876});
		Assert.assertEquals(173L * 876, p.costOfPriced);
		Assert.assertEquals(173, p.pricedQty);
	}
}
