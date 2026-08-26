package com.pocketge.tracker;

import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class CapitalPlannerTest
{
	private static CapitalPlanner.Candidate candidate(int id, String name, long unitBuy, long unitEdge,
		int limit, long dailyVolume)
	{
		CapitalPlanner.Candidate c = new CapitalPlanner.Candidate();
		c.id = id;
		c.name = name;
		c.unitBuy = unitBuy;
		c.unitEdge = unitEdge;
		c.limit = limit;
		c.dailyVolume = dailyVolume;
		return c;
	}

	private static long totalSpend(CapitalPlanner.Plan p)
	{
		long total = 0;
		for (CapitalPlanner.Position pos : p.positions)
		{
			total += pos.spend;
		}
		return total;
	}

	/** The bug this whole class exists to fix: Advisor.buildBuys sizes every
	 *  BUY against the full cash pile independently, so its suggestions can
	 *  collectively cost far more than you have. A plan must always be
	 *  affordable. */
	@Test
	public void neverPlansMoreThanYouCanAfford()
	{
		List<CapitalPlanner.Candidate> pool = new ArrayList<>();
		for (int i = 1; i <= 30; i++)
		{
			// Each of these could individually absorb the entire 100m.
			pool.add(candidate(i, "Item " + i, 1_000_000, 50_000, 500, 5_000_000));
		}
		final long cash = 100_000_000L;
		CapitalPlanner.Plan plan = CapitalPlanner.plan(cash, 8, pool);

		Assert.assertTrue("plan must be affordable", totalSpend(plan) <= cash);
		Assert.assertEquals(totalSpend(plan), plan.cashDeployed);
		Assert.assertEquals(cash - plan.cashDeployed, plan.cashIdle);
		Assert.assertTrue(plan.cashIdle >= 0);
	}

	@Test
	public void neverUsesMoreSlotsThanAreFree()
	{
		List<CapitalPlanner.Candidate> pool = new ArrayList<>();
		for (int i = 1; i <= 30; i++)
		{
			pool.add(candidate(i, "Item " + i, 10_000, 500, 100, 1_000_000));
		}
		CapitalPlanner.Plan plan = CapitalPlanner.plan(100_000_000L, 3, pool);
		Assert.assertTrue(plan.positions.size() <= 3);
		Assert.assertEquals(plan.positions.size(), plan.slotsUsed);
	}

	/**
	 * The slot-bound case, and the reason plain ROI-density greedy isn't
	 * good enough. With ONE slot and 100m: a 10%-edge item that can only
	 * absorb 100k earns 10k, while a 5%-edge item that can absorb the whole
	 * bank earns 5m. Density ranking prefers the first; the repair pass has
	 * to fix that.
	 */
	@Test
	public void prefersAbsorptionOverRoiWhenSlotsAreScarce()
	{
		List<CapitalPlanner.Candidate> pool = new ArrayList<>();
		// 10% edge, but its limit caps it at 100 units x 1,000 gp = 100k.
		pool.add(candidate(1, "Tiny high ROI", 1_000, 100, 100, 10_000_000));
		// 5% edge and able to soak up the full 100m (100 x 1,000,000).
		pool.add(candidate(2, "Big absorber", 1_000_000, 50_000, 100, 10_000_000));

		CapitalPlanner.Plan plan = CapitalPlanner.plan(100_000_000L, 1, pool);

		Assert.assertEquals(1, plan.positions.size());
		Assert.assertEquals("should spend its only slot on the item that can actually take the cash",
			2, plan.positions.get(0).id);
		Assert.assertTrue(plan.expectedProfit > 1_000_000L);
	}

	/** With enough slots it should take BOTH — the high-ROI one first (it's
	 *  cheap and strictly better per gp), then the absorber for the rest. */
	@Test
	public void takesHighRoiAndAbsorberWhenSlotsAllow()
	{
		List<CapitalPlanner.Candidate> pool = new ArrayList<>();
		pool.add(candidate(1, "Tiny high ROI", 1_000, 100, 100, 10_000_000));
		pool.add(candidate(2, "Big absorber", 1_000_000, 50_000, 100, 10_000_000));

		CapitalPlanner.Plan plan = CapitalPlanner.plan(100_000_000L, 2, pool);

		Assert.assertEquals(2, plan.positions.size());
		Assert.assertTrue(totalSpend(plan) <= 100_000_000L);
		// 10k from the small one + ~4.95m from the absorber on what's left.
		Assert.assertTrue(plan.expectedProfit > 4_000_000L);
	}

	/** Cash-bound regime: gp runs out long before slots do, so the plan
	 *  should be the density optimum and spend essentially everything. */
	@Test
	public void deploysNearlyAllCashWhenLimitsAllow()
	{
		List<CapitalPlanner.Candidate> pool = new ArrayList<>();
		pool.add(candidate(1, "Deep item", 1_000, 100, 1_000_000, 100_000_000L));

		final long cash = 1_000_000L;
		CapitalPlanner.Plan plan = CapitalPlanner.plan(cash, 8, pool);

		Assert.assertEquals(1, plan.positions.size());
		Assert.assertEquals(1000, plan.positions.get(0).quantity); // 1m / 1,000gp
		Assert.assertEquals(cash, plan.cashDeployed);
		Assert.assertEquals(CapitalPlanner.IdleReason.NONE, plan.idleReason);
		Assert.assertEquals(CapitalPlanner.Bound.CASH, plan.positions.get(0).boundBy);
	}

	/** A big bank against small buy limits genuinely cannot be deployed —
	 *  say so rather than implying it's all invested. */
	@Test
	public void reportsLimitsAsTheReasonCashSitsIdle()
	{
		List<CapitalPlanner.Candidate> pool = new ArrayList<>();
		for (int i = 1; i <= 10; i++)
		{
			// Each caps out at 8 units x 100k = 800k.
			pool.add(candidate(i, "Limited " + i, 100_000, 5_000, 8, 1_000_000));
		}
		CapitalPlanner.Plan plan = CapitalPlanner.plan(100_000_000L, 3, pool);

		Assert.assertEquals(CapitalPlanner.IdleReason.LIMITS_CAP_DEPLOYMENT, plan.idleReason);
		Assert.assertTrue(plan.cashIdle > 90_000_000L);
		Assert.assertEquals(CapitalPlanner.Bound.GE_LIMIT, plan.positions.get(0).boundBy);
	}

	@Test
	public void noFreeSlotsIsReportedNotSilentlyEmpty()
	{
		List<CapitalPlanner.Candidate> pool = new ArrayList<>();
		pool.add(candidate(1, "Anything", 1_000, 100, 100, 1_000_000));

		CapitalPlanner.Plan plan = CapitalPlanner.plan(100_000_000L, 0, pool);

		Assert.assertTrue(plan.positions.isEmpty());
		Assert.assertEquals(CapitalPlanner.IdleReason.NO_FREE_SLOTS, plan.idleReason);
		Assert.assertEquals(100_000_000L, plan.cashIdle);
	}

	/** An unknown GE limit must not read as "unlimited" — otherwise one
	 *  unverified item swallows the whole bank. */
	@Test
	public void unknownBuyLimitIsCappedNotTreatedAsUnlimited()
	{
		List<CapitalPlanner.Candidate> pool = new ArrayList<>();
		pool.add(candidate(1, "Unknown limit", 1_000, 100, 0, 100_000_000L));

		final long cash = 100_000_000L;
		CapitalPlanner.Plan plan = CapitalPlanner.plan(cash, 1, pool);

		Assert.assertEquals(1, plan.positions.size());
		Assert.assertTrue("must not deploy the whole bank into an item with no confirmed limit",
			plan.positions.get(0).spend < cash / 2);
		Assert.assertEquals(CapitalPlanner.Bound.LIMIT_UNKNOWN, plan.positions.get(0).boundBy);
	}

	@Test
	public void ignoresItemsWithNoEdgeOrUnaffordableUnitPrice()
	{
		List<CapitalPlanner.Candidate> pool = new ArrayList<>();
		pool.add(candidate(1, "No edge", 1_000, 0, 100, 1_000_000));
		pool.add(candidate(2, "Negative edge", 1_000, -50, 100, 1_000_000));
		pool.add(candidate(3, "Too expensive", 50_000_000, 1_000_000, 100, 1_000_000));

		CapitalPlanner.Plan plan = CapitalPlanner.plan(1_000_000L, 3, pool);

		Assert.assertTrue(plan.positions.isEmpty());
		Assert.assertEquals(CapitalPlanner.IdleReason.NO_VIABLE_ITEMS, plan.idleReason);
	}

	@Test
	public void emptyCandidateListIsHandled()
	{
		CapitalPlanner.Plan plan = CapitalPlanner.plan(100_000_000L, 8, new ArrayList<>());
		Assert.assertTrue(plan.positions.isEmpty());
		Assert.assertEquals(CapitalPlanner.IdleReason.NO_VIABLE_ITEMS, plan.idleReason);

		CapitalPlanner.Plan nullPlan = CapitalPlanner.plan(100_000_000L, 8, null);
		Assert.assertTrue(nullPlan.positions.isEmpty());
	}

	/** Members get 8 slots against f2p's 3, so the same bank should deploy
	 *  at least as much and earn at least as much with more of them. */
	@Test
	public void moreSlotsNeverEarnLess()
	{
		List<CapitalPlanner.Candidate> pool = new ArrayList<>();
		for (int i = 1; i <= 20; i++)
		{
			pool.add(candidate(i, "Item " + i, 100_000 + i * 1000, 4_000, 60, 2_000_000));
		}
		final long cash = 100_000_000L;
		CapitalPlanner.Plan f2p = CapitalPlanner.plan(cash, 3, pool);
		CapitalPlanner.Plan members = CapitalPlanner.plan(cash, 8, pool);

		Assert.assertTrue(members.expectedProfit >= f2p.expectedProfit);
		Assert.assertTrue(members.cashDeployed >= f2p.cashDeployed);
		Assert.assertTrue(totalSpend(members) <= cash);
	}

	@Test
	public void summaryFieldsAreSelfConsistent()
	{
		List<CapitalPlanner.Candidate> pool = new ArrayList<>();
		for (int i = 1; i <= 12; i++)
		{
			pool.add(candidate(i, "Item " + i, 250_000, 12_000, 40, 3_000_000));
		}
		CapitalPlanner.Plan plan = CapitalPlanner.plan(50_000_000L, 8, pool);

		long spend = 0;
		long profit = 0;
		for (CapitalPlanner.Position p : plan.positions)
		{
			Assert.assertEquals(p.quantity * p.unitBuy, p.spend);
			Assert.assertEquals(p.quantity * p.unitEdge, p.expectedProfit);
			spend += p.spend;
			profit += p.expectedProfit;
		}
		Assert.assertEquals(spend, plan.cashDeployed);
		Assert.assertEquals(profit, plan.expectedProfit);
		Assert.assertEquals(50_000_000L, plan.cashDeployed + plan.cashIdle);
		Assert.assertTrue(plan.deployedPct() >= 0 && plan.deployedPct() <= 100.0);
	}
}
