package com.pocketge.tracker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/**
 * "i just bought 25k and flipped them, and its still reccomending me that i
 * buy this flip, it should know im on cool down for 4h". The Exchange's buy
 * limit runs on a four-hour clock from the first purchase; what was bought
 * in it is taken off what the plugin will suggest buying.
 */
public class BuyLimitWindowTest
{
	private static final long H = 60L * 60 * 1000;
	private static final long T0 = 1_790_000_000_000L;
	private static final int BLOOD = 565;

	@Test
	public void theClockStartsAtTheFirstBuyAndRunsFourHours()
	{
		final FlipTracker t = new FlipTracker();
		t.setAccountHash(42L);
		t.noteBuyForLimit(BLOOD, 10_000, T0);
		t.noteBuyForLimit(BLOOD, 15_000, T0 + H);
		Assert.assertEquals(25_000, t.boughtTowardLimit(BLOOD, T0 + H));
		Assert.assertEquals("not from the later buy", T0 + 4 * H, t.limitResetsAt(BLOOD, T0 + 2 * H));
		Assert.assertEquals(25_000, t.boughtTowardLimit(BLOOD, T0 + 4 * H - 1));
		Assert.assertEquals("whole again", 0, t.boughtTowardLimit(BLOOD, T0 + 4 * H));
		Assert.assertEquals(0, t.limitResetsAt(BLOOD, T0 + 4 * H));

		t.noteBuyForLimit(BLOOD, 3_000, T0 + 5 * H);
		Assert.assertEquals("a new window from the next buy", 3_000, t.boughtTowardLimit(BLOOD, T0 + 5 * H));
		Assert.assertEquals(T0 + 9 * H, t.limitResetsAt(BLOOD, T0 + 5 * H));
		Assert.assertEquals("another item has its own", 0, t.boughtTowardLimit(1601, T0 + 5 * H));
	}

	@Test
	public void aBuyThroughAnOfferCountsAndASellDoesNot()
	{
		final FlipTracker t = new FlipTracker();
		t.setAccountHash(42L);
		final long now = System.currentTimeMillis();
		t.onOffer(now, 0, BLOOD, "Blood rune", true, 0, 0L, false);
		t.onOffer(now + 1_000, 0, BLOOD, "Blood rune", true, 25_000, 7_300_000L, false);
		Assert.assertEquals(25_000, t.boughtTowardLimit(BLOOD, now + 2_000));
		t.onOffer(now + 3_000, 1, BLOOD, "Blood rune", false, 0, 0L, false);
		t.onOffer(now + 4_000, 1, BLOOD, "Blood rune", false, 25_000, 7_600_000L, false);
		Assert.assertEquals("selling them back does not give the limit back", 25_000,
			t.boughtTowardLimit(BLOOD, now + 5_000));
	}

	/** Per character, as the game's limit is; and through a restart, as the
	 *  game's clock does not stop for one. */
	@Test
	public void eachCharacterHasItsOwnAndARestartKeepsIt()
	{
		final FlipTracker t = new FlipTracker();
		t.setAccountHash(42L);
		t.noteBuyForLimit(BLOOD, 25_000, T0);
		t.setAccountHash(77L);
		Assert.assertEquals("another character's limit is untouched", 0, t.boughtTowardLimit(BLOOD, T0 + H));
		t.setAccountHash(42L);

		final FlipTracker after = new FlipTracker();
		after.restore(new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(t.snapshot()),
			FlipTracker.State.class));
		after.setAccountHash(42L);
		Assert.assertEquals(25_000, after.boughtTowardLimit(BLOOD, T0 + H));
		Assert.assertEquals(T0 + 4 * H, after.limitResetsAt(BLOOD, T0 + H));

		final FlipTracker older = new FlipTracker();
		older.restore(new FlipTracker.State());
		older.setAccountHash(42L);
		Assert.assertEquals("a save from before this knows of no buys", 0, older.boughtTowardLimit(BLOOD, T0 + H));
	}

	/** Updating straight after buying the limit: a save from before limits
	 *  were tracked is seeded from its flips and open lots of the last four
	 *  hours, so the flip just made is not offered again. */
	@Test
	public void anOlderSaveIsSeededFromRecentBuys()
	{
		final long now = System.currentTimeMillis();
		final FlipTracker.State old = new FlipTracker.State();
		old.accountHash = 42L;
		old.flips = new ArrayList<>();
		old.flips.add(new Flip(1L, now - H, now - H / 2, BLOOD, "Blood rune", 25_000, 7_300_000L, 7_600_000L, 76_000L));
		old.flips.add(new Flip(2L, now - 5 * H, now - 4 * H, 1601, "Diamond", 100, 200_000L, 210_000L, 2_100L));
		old.openBuys = new HashMap<>();
		old.openBuys.put(1601, List.<long[]>of(new long[]{40, 80_000L, now - 2 * H}));
		final FlipTracker t = new FlipTracker();
		t.restore(old);
		Assert.assertEquals(25_000, t.boughtTowardLimit(BLOOD, now));
		Assert.assertEquals("from the open lot only; the old flip is past four hours", 40,
			t.boughtTowardLimit(1601, now));
		Assert.assertEquals(now - H + 4 * H, t.limitResetsAt(BLOOD, now));
	}

	private static List<Advisor.Suggestion> buys(int limitUsed)
	{
		final Advisor.Quote q = new Advisor.Quote();
		q.high = 304;
		q.low = 292;
		q.highTime = 1_000_000L;
		q.lowTime = 1_000_000L;
		final Map<Integer, Advisor.Quote> quotes = new HashMap<>();
		quotes.put(BLOOD, q);
		final Advisor.ItemMeta m = new Advisor.ItemMeta();
		m.id = BLOOD;
		m.name = "Blood rune";
		m.limit = 25_000;
		m.limitUsed = limitUsed;
		m.dailyVolume = 50_000_000L;
		final Map<Integer, Advisor.ItemMeta> meta = new HashMap<>();
		meta.put(BLOOD, m);
		final List<Advisor.Suggestion> out = new ArrayList<>();
		for (Advisor.Suggestion s : Advisor.advise(1_000_000L, quotes, meta, 100_000_000L, new HashMap<>(),
			new ArrayList<>(), new HashSet<>(), new HashSet<>(), 0, 0.01, 4, new HashMap<>(), new HashMap<>(), 0))
		{
			if (s.type == Advisor.Suggestion.Type.BUY)
			{
				out.add(s);
			}
		}
		return out;
	}

	@Test
	public void theAdvisorBuysWhatIsLeftAndNothingOnceItIsGone()
	{
		Assert.assertEquals("the whole limit, fresh", 25_000, buys(0).get(0).quantity);
		Assert.assertEquals("what is left", 15_000, buys(10_000).get(0).quantity);
		Assert.assertTrue("used up: no buy at all", buys(25_000).isEmpty());
	}

	@Test
	public void untilReadsLikeAClock()
	{
		Assert.assertEquals("2h 14m", FlipTracker.untilText(2 * H + 14 * 60_000));
		Assert.assertEquals("38m", FlipTracker.untilText(38 * 60_000));
		Assert.assertEquals("under a minute", FlipTracker.untilText(20_000));
		Assert.assertEquals("under a minute", FlipTracker.untilText(-5));
	}
}
