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
