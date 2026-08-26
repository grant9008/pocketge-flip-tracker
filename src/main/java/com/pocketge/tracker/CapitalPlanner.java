package com.pocketge.tracker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure capital-allocation planner (no RuneLite types — unit-testable):
 * "I have N gp liquid and S free Grand Exchange slots — what's the best way
 * to actually deploy it?"
 *
 * This exists because {@link Advisor#advise} answers a different question.
 * Its BUY suggestions are each sized against the FULL cash pile
 * independently, so with 100m in the bank it happily lists four flips that
 * would collectively need 300m+ to place. Every individual row is true; the
 * portfolio they imply is not affordable. This class allocates ONE budget
 * across the slots you actually have free, so the totals it reports are
 * ones you can really place right now.
 *
 * The scarce resource is a SLOT-WINDOW, not gp. A GE slot produces value
 * once per 4-hour buy-limit window, so what matters per slot is how much gp
 * that slot can absorb (its capacity = unit cap x unit price) and what edge
 * it earns on that. Two regimes fall out, and which one binds flips the
 * correct ranking key:
 *
 *   Cash-bound (your gp runs out before the good items do) — ranking by ROI
 *   density (edge per gp invested) is provably optimal, the textbook
 *   fractional-knapsack result.
 *
 *   Slot-bound (you run out of slots while gp is still sitting idle) —
 *   density is the WRONG key. Spending one of only three f2p slots on a
 *   high-ROI item that can absorb 200k, while 99m sits idle, is a bad trade
 *   no matter how good its percentage looks.
 *
 * Rather than switching keys on a mode flag, allocation always runs by
 * density (correct whenever quantities are what's being decided) and a
 * repair pass swaps whole positions in and out — which only ever finds an
 * improvement in the slot-bound regime. See {@link #plan}.
 *
 * Everything is after the 2% GE tax, via {@link FlipTracker#taxPerItem}.
 */
public final class CapitalPlanner
{
	private CapitalPlanner() {}

	/** A 4h buy-limit window is what a slot is effectively rented by. */
	public static final int WINDOW_HOURS = 4;
	/** Share of an item's daily flow one position may assume it can absorb.
	 *  Advisor.buildBuys uses /12 (~8% of a day) for its own sizing, but
	 *  that's ~50% of a single 4h window — far too aggressive to promise a
	 *  player their gp is really deployable. Half that rate is the honest
	 *  number for a plan whose whole point is "this is what you can
	 *  actually place right now". */
	private static final long VOLUME_SHARE_DIVISOR = 24;
	/** An unknown GE limit must never read as "unlimited". buildBuys falls
	 *  back to qtyByCash for limit==0, which is harmless when each
	 *  suggestion is sized alone but here would let one unverified item
	 *  swallow the entire bank. Cap its assumed capacity instead. */
	private static final double UNKNOWN_LIMIT_SHARE = 0.15;
	/** Below this a position isn't worth spending one of your slots on. */
	private static final long MIN_POSITION_PROFIT = 2_000;
	/** Candidates considered by the repair pass, best-potential first.
	 *  Bounds the swap search (slots x pool x passes) to something trivial
	 *  while still covering every item that could plausibly earn a slot. */
	private static final int POOL_SIZE = 150;
	private static final int MAX_REPAIR_PASSES = 6;
	/** Idle gp below this isn't worth reshuffling the plan over — chasing
	 *  the last fraction of a percent would churn the panel every refresh. */
	private static final long IDLE_TOLERANCE_FLOOR = 1_000_000;

	/** What stopped a position from growing — surfaced so the panel can say
	 *  WHY your gp isn't fully deployed instead of silently under-spending. */
	public enum Bound
	{
		/** The item's 4h Grand Exchange buy limit. */
		GE_LIMIT,
		/** This item's daily traded volume — buying more would be an
		 *  unrealistic share of the day's flow. */
		DAILY_VOLUME,
		/** Ran out of gp; the item itself could have absorbed more. */
		CASH,
		/** No confirmed GE limit for this item, so a conservative share of
		 *  cash was assumed rather than trusting it to be unlimited. */
		LIMIT_UNKNOWN
	}

	/** Why a plan left gp on the table. */
	public enum IdleReason
	{
		NONE,
		/** Every GE slot is already occupied. */
		NO_FREE_SLOTS,
		/** Slots are free, but buy limits / daily volume cap how much can
		 *  be deployed into them inside one 4h window. This is the normal
		 *  answer for a large bank and the honest one. */
		LIMITS_CAP_DEPLOYMENT,
		/** Nothing currently clears the profit bar. */
		NO_VIABLE_ITEMS
	}

	/** One item the planner would consider putting in a slot. The caller
	 *  resolves prices (raw quote or TradeEngine target — see
	 *  Advisor.advise, which already reprices this way) so this class never
	 *  needs to know where they came from. */
	public static class Candidate
	{
		public int id;
		public String name;
		public long unitBuy;     // what you'd pay per unit
		public long unitEdge;    // profit per unit AFTER tax
		public int limit;        // 4h GE buy limit, 0 = unknown
		public long dailyVolume;
	}

	/** One funded position in the finished plan — a single GE slot's worth. */
	public static class Position
	{
		public int id;
		public String name;
		public int quantity;
		public long unitBuy;
		public long unitEdge;
		public long spend;           // quantity * unitBuy
		public long expectedProfit;  // quantity * unitEdge, after tax
		public double roiPct;
		public Bound boundBy;
	}

	public static class Plan
	{
		public List<Position> positions = new ArrayList<>();
		public long cashAvailable;
		public long cashDeployed;
		public long cashIdle;
		public long expectedProfit;
		public int slotsAvailable;
		public int slotsUsed;
		public IdleReason idleReason = IdleReason.NONE;

		/** Deployed as a share of what you had — the "am I actually putting
		 *  my bank to work" number the whole feature exists to answer. */
		public double deployedPct()
		{
			return cashAvailable > 0 ? (cashDeployed * 100.0) / cashAvailable : 0;
		}

		/** Return on what actually got deployed. Flattering and, on a big
		 *  bank against small buy limits, misleading on its own — 3% of the
		 *  13m you could place is not 3% of your 100m. Always show it next
		 *  to {@link #roiBankPct()}, never instead of it. */
		public double roiPct()
		{
			return cashDeployed > 0 ? (expectedProfit * 100.0) / cashDeployed : 0;
		}

		/** Return on the whole bank — the honest one. */
		public double roiBankPct()
		{
			return cashAvailable > 0 ? (expectedProfit * 100.0) / cashAvailable : 0;
		}
	}

	/** Scored candidate — capacity/density derived once, then reused by
	 *  every fill and swap evaluation. */
	private static class Scored
	{
		Candidate c;
		int unitCap;         // most units one slot may take this window
		long capacityGp;     // unitCap * unitBuy — gp this slot can absorb
		double density;      // unitEdge / unitBuy — profit per gp invested
		Bound capBound;      // what set unitCap (before cash is considered)
	}

	/**
	 * Build the best affordable plan for {@code cash} across
	 * {@code freeSlots} slots.
	 *
	 * {@code freeSlots} must count slots occupied by offers that are merely
	 * awaiting collection — a BOUGHT/SOLD/CANCELLED offer still owns its
	 * slot until you collect it, so counting only actively-BUYING/SELLING
	 * offers would over-report what's free. See
	 * PocketGeTrackerPlugin.freeGeSlots().
	 */
	public static Plan plan(long cash, int freeSlots, List<Candidate> candidates)
	{
		final Plan plan = new Plan();
		plan.cashAvailable = Math.max(0, cash);
		plan.slotsAvailable = Math.max(0, freeSlots);
		plan.cashIdle = plan.cashAvailable;

		if (plan.cashAvailable <= 0 || plan.slotsAvailable <= 0)
		{
			plan.idleReason = plan.slotsAvailable <= 0 ? IdleReason.NO_FREE_SLOTS : IdleReason.NO_VIABLE_ITEMS;
			return plan;
		}

		final List<Scored> pool = pool(plan.cashAvailable, candidates);
		if (pool.isEmpty())
		{
			plan.idleReason = IdleReason.NO_VIABLE_ITEMS;
			return plan;
		}

		// Seed by density (optimal while cash is the binding constraint),
		// then let the repair pass fix the slot-bound case where density
		// alone would strand most of the bank.
		List<Scored> chosen = new ArrayList<>();
		for (Scored s : pool)
		{
			if (chosen.size() >= plan.slotsAvailable)
			{
				break;
			}
			chosen.add(s);
		}
		List<Position> best = fill(chosen, plan.cashAvailable);
		best = repair(best, chosen, pool, plan.cashAvailable, plan.slotsAvailable);

		for (Position p : best)
		{
			plan.positions.add(p);
			plan.cashDeployed += p.spend;
			plan.expectedProfit += p.expectedProfit;
		}
		plan.slotsUsed = plan.positions.size();
		plan.cashIdle = plan.cashAvailable - plan.cashDeployed;
		plan.idleReason = idleReason(plan);
		return plan;
	}

	/** Score, filter and rank every candidate, keeping the top POOL_SIZE by
	 *  what they could earn if handed the whole bank — the frontier worth
	 *  searching. Density order within the pool is what fill() relies on. */
	private static List<Scored> pool(long cash, List<Candidate> candidates)
	{
		final List<Scored> scored = new ArrayList<>();
		if (candidates == null)
		{
			return scored;
		}
		for (Candidate c : candidates)
		{
			if (c == null || c.unitBuy <= 0 || c.unitEdge <= 0 || c.unitBuy > cash)
			{
				continue;
			}
			final long volCap = Math.max(1, c.dailyVolume / VOLUME_SHARE_DIVISOR);
			final long limitCap = c.limit > 0
				? c.limit
				: Math.max(1, (long) (cash * UNKNOWN_LIMIT_SHARE / c.unitBuy));
			final long unitCap = Math.min(limitCap, volCap);
			if (unitCap <= 0)
			{
				continue;
			}
			final Scored s = new Scored();
			s.c = c;
			s.unitCap = (int) Math.min(Integer.MAX_VALUE, unitCap);
			s.capacityGp = unitCap * c.unitBuy;
			s.density = c.unitEdge / (double) c.unitBuy;
			s.capBound = c.limit <= 0 ? Bound.LIMIT_UNKNOWN
				: (limitCap <= volCap ? Bound.GE_LIMIT : Bound.DAILY_VOLUME);
			scored.add(s);
		}
		// Rank by what each could earn given the whole bank, so the pool
		// keeps genuine big absorbers that a pure density cut would drop.
		scored.sort(Comparator
			.comparingLong((Scored s) -> potential(s, cash)).reversed()
			.thenComparingInt(s -> s.c.id));
		final List<Scored> pool = new ArrayList<>(scored.subList(0, Math.min(POOL_SIZE, scored.size())));
		pool.sort(Comparator
			.comparingDouble((Scored s) -> s.density).reversed()
			.thenComparingLong((Scored s) -> -s.capacityGp)
			.thenComparingInt(s -> s.c.id));
		return pool;
	}

	private static long potential(Scored s, long cash)
	{
		final long qty = Math.min(s.unitCap, cash / s.c.unitBuy);
		return qty * s.c.unitEdge;
	}

	/**
	 * Allocate {@code cash} across a fixed set of slots, highest density
	 * first up to each item's cap. Optimal for a chosen set — the only
	 * open question this class faces is WHICH items to choose, which is
	 * what repair() searches.
	 */
	private static List<Position> fill(List<Scored> set, long cash)
	{
		final List<Scored> ordered = new ArrayList<>(set);
		ordered.sort(Comparator
			.comparingDouble((Scored s) -> s.density).reversed()
			.thenComparingInt(s -> s.c.id));

		final List<Position> out = new ArrayList<>();
		long remaining = cash;
		for (Scored s : ordered)
		{
			if (remaining <= 0)
			{
				break;
			}
			final long byCash = remaining / s.c.unitBuy;
			final int qty = (int) Math.min(s.unitCap, byCash);
			if (qty <= 0)
			{
				continue;
			}
			final long profit = qty * s.c.unitEdge;
			if (profit < MIN_POSITION_PROFIT)
			{
				continue;
			}
			final Position p = new Position();
			p.id = s.c.id;
			p.name = s.c.name;
			p.quantity = qty;
			p.unitBuy = s.c.unitBuy;
			p.unitEdge = s.c.unitEdge;
			p.spend = qty * s.c.unitBuy;
			p.expectedProfit = profit;
			p.roiPct = (profit * 100.0) / p.spend;
			p.boundBy = qty < s.unitCap ? Bound.CASH : s.capBound;
			out.add(p);
			remaining -= p.spend;
		}
		out.sort(Comparator.comparingLong((Position p) -> p.expectedProfit).reversed());
		return out;
	}

	/**
	 * Swap positions in and out while it raises total profit.
	 *
	 * This is what makes the slot-bound case come out right: density order
	 * alone will happily spend all three f2p slots on high-percentage items
	 * that between them absorb a couple of million, leaving most of a large
	 * bank idle. A swap that trades one of those for a lower-ROI item able
	 * to soak up 30m wins on absolute profit, and only ever wins when gp
	 * really is going spare — in the cash-bound regime no swap improves on
	 * the density seed, so this loop finds nothing and costs one pass.
	 */
	private static List<Position> repair(List<Position> seed, List<Scored> chosen, List<Scored> pool,
		long cash, int freeSlots)
	{
		List<Position> best = seed;
		long bestProfit = totalProfit(seed);
		Set<Integer> chosenIds = idsOf(chosen);
		List<Scored> current = new ArrayList<>(chosen);

		for (int pass = 0; pass < MAX_REPAIR_PASSES; pass++)
		{
			// Only worth searching while gp is genuinely stranded — the
			// plan is already the density optimum otherwise.
			if (totalSpend(best) >= cash - idleTolerance(cash) && current.size() >= Math.min(freeSlots, pool.size()))
			{
				break;
			}
			boolean improved = false;
			List<Scored> bestSet = null;

			for (int i = 0; i < current.size(); i++)
			{
				for (Scored cand : pool)
				{
					if (chosenIds.contains(cand.c.id))
					{
						continue;
					}
					final List<Scored> trial = new ArrayList<>(current);
					trial.set(i, cand);
					final List<Position> filled = fill(trial, cash);
					final long profit = totalProfit(filled);
					if (profit > bestProfit)
					{
						bestProfit = profit;
						best = filled;
						bestSet = trial;
						improved = true;
					}
				}
			}
			// Also try simply ADDING an unused slot — the seed can come up
			// short of freeSlots when low-density items failed the profit
			// floor at the cash left over at the time.
			if (current.size() < freeSlots)
			{
				for (Scored cand : pool)
				{
					if (chosenIds.contains(cand.c.id))
					{
						continue;
					}
					final List<Scored> trial = new ArrayList<>(current);
					trial.add(cand);
					final List<Position> filled = fill(trial, cash);
					final long profit = totalProfit(filled);
					if (profit > bestProfit)
					{
						bestProfit = profit;
						best = filled;
						bestSet = trial;
						improved = true;
					}
				}
			}

			if (!improved || bestSet == null)
			{
				break;
			}
			current = bestSet;
			chosenIds = idsOf(current);
		}
		return best;
	}

	private static long idleTolerance(long cash)
	{
		return Math.max(IDLE_TOLERANCE_FLOOR, cash / 100);
	}

	private static Set<Integer> idsOf(List<Scored> set)
	{
		final Set<Integer> ids = new LinkedHashSet<>();
		for (Scored s : set)
		{
			ids.add(s.c.id);
		}
		return ids;
	}

	private static long totalProfit(List<Position> positions)
	{
		long total = 0;
		for (Position p : positions)
		{
			total += p.expectedProfit;
		}
		return total;
	}

	private static long totalSpend(List<Position> positions)
	{
		long total = 0;
		for (Position p : positions)
		{
			total += p.spend;
		}
		return total;
	}

	/** Never claim a big bank is fully deployed when buy limits are what's
	 *  really stopping it — that's the honest answer players need, and the
	 *  usual one above a few tens of millions. */
	private static IdleReason idleReason(Plan plan)
	{
		if (plan.positions.isEmpty())
		{
			return plan.slotsAvailable <= 0 ? IdleReason.NO_FREE_SLOTS : IdleReason.NO_VIABLE_ITEMS;
		}
		if (plan.cashIdle <= idleTolerance(plan.cashAvailable))
		{
			return IdleReason.NONE;
		}
		return IdleReason.LIMITS_CAP_DEPLOYMENT;
	}
}
