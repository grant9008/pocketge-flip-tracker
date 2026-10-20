package com.pocketge.tracker;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import org.junit.Assert;
import org.junit.Test;

/**
 * The "Copy PocketGE report" text: the numbers no card shows, raw, and
 * nothing that identifies the player.
 *
 * Asked about after PAID @ 964 on Sapphire necklaces bought at 453-559 —
 * a figure that can only be checked against the buy lots and fills behind
 * it, which this prints.
 */
public class ReportTest
{
	private static final int SAPPHIRE_NECKLACE = 1656;
	private static final long ACCOUNT = 987_654_321_012L;

	private static void set(Object o, String field, Object v) throws Exception
	{
		final Field f = o.getClass().getDeclaredField(field);
		f.setAccessible(true);
		f.set(o, v);
	}

	@SuppressWarnings("unchecked")
	private static <T> T stub(Class<T> type, Map<String, Object> answers)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) ->
		{
			if (answers.containsKey(m.getName()))
			{
				return answers.get(m.getName());
			}
			final Class<?> r = m.getReturnType();
			if (r == boolean.class)
			{
				return false;
			}
			return r.isPrimitive() ? (r == long.class ? (Object) 0L : (Object) 0) : null;
		});
	}

	private static String report() throws Exception
	{
		final PocketGeTrackerPlugin plugin = new PocketGeTrackerPlugin();
		set(plugin, "client", stub(Client.class, Map.of("getGrandExchangeOffers", new GrandExchangeOffer[8])));
		set(plugin, "config", stub(PocketGeTrackerConfig.class, Map.of()));
		final Field tf = PocketGeTrackerPlugin.class.getDeclaredField("tracker");
		tf.setAccessible(true);
		final FlipTracker tracker = (FlipTracker) tf.get(plugin);
		tracker.setAccountHash(ACCOUNT);
		tracker.onOffer(1_000L, 0, SAPPHIRE_NECKLACE, "Sapphire necklace", true, 0, 0L, 453, 5_000, false);
		tracker.onOffer(2_000L, 0, SAPPHIRE_NECKLACE, "Sapphire necklace", true, 5_000, 5_000L * 453, 453, 5_000, false);
		return plugin.buildReport(SAPPHIRE_NECKLACE, "Sapphire necklace", "  verb SELL\n");
	}

	@Test
	public void itShowsTheLotsAndFillsBehindPaid() throws Exception
	{
		final String r = report();
		Assert.assertTrue(r, r.contains("== open buy lots"));
		Assert.assertTrue(r, r.contains("5000 for 2265000 (453.0 each)"));
		Assert.assertTrue(r, r.contains("BUY  5000 for 2265000 (453 each)"));
		Assert.assertTrue("the card's own fields come through", r.contains("verb SELL"));
		Assert.assertTrue("every slot is listed", r.contains("slot 8: empty"));
	}

	/** The same lot twice is what an old build's hop re-booking left, and
	 *  more bought than held says the lots are wrong — both flagged. */
	@Test
	public void itFlagsALotBookedTwice() throws Exception
	{
		final PocketGeTrackerPlugin plugin = new PocketGeTrackerPlugin();
		set(plugin, "client", stub(Client.class, Map.of("getGrandExchangeOffers", new GrandExchangeOffer[8])));
		set(plugin, "config", stub(PocketGeTrackerConfig.class, Map.of()));
		final Field tf = PocketGeTrackerPlugin.class.getDeclaredField("tracker");
		tf.setAccessible(true);
		final FlipTracker tracker = (FlipTracker) tf.get(plugin);
		final FlipTracker.State s = new FlipTracker.State();
		s.openBuys = new java.util.HashMap<>();
		s.openBuys.put(SAPPHIRE_NECKLACE, java.util.List.of(
			new long[]{5_000, 5_000L * 453, 1_000L}, new long[]{5_000, 5_000L * 453}));
		tracker.restore(s);
		final String r = plugin.buildReport(SAPPHIRE_NECKLACE, "Sapphire necklace", "");
		Assert.assertTrue(r, r.contains("(no time)  <- same total as the lots just before it"));
		Assert.assertTrue(r, r.contains("more than this character holds (bank + inventory + GE slots = 0)"));
	}

	/** An honest offline refill at the same bid matches the fills before
	 *  it too: the match is stated, but nothing says "more than you hold"
	 *  while the stack is all there. */
	@Test
	public void lotsInStepWithTheStackAreNotFlagged() throws Exception
	{
		final PocketGeTrackerPlugin plugin = new PocketGeTrackerPlugin();
		set(plugin, "client", stub(Client.class, Map.of("getGrandExchangeOffers", new GrandExchangeOffer[8])));
		set(plugin, "config", stub(PocketGeTrackerConfig.class, Map.of()));
		final Field tf = PocketGeTrackerPlugin.class.getDeclaredField("tracker");
		tf.setAccessible(true);
		final FlipTracker tracker = (FlipTracker) tf.get(plugin);
		final FlipTracker.State s = new FlipTracker.State();
		s.openBuys = new java.util.HashMap<>();
		s.openBuys.put(SAPPHIRE_NECKLACE, java.util.List.of(
			new long[]{5_000, 5_000L * 453, 1_000L}, new long[]{5_000, 5_000L * 453}));
		tracker.restore(s);
		final Field bank = PocketGeTrackerPlugin.class.getDeclaredField("lastBank");
		bank.setAccessible(true);
		@SuppressWarnings("unchecked")
		final Map<Integer, Integer> lastBank = (Map<Integer, Integer>) bank.get(plugin);
		lastBank.put(SAPPHIRE_NECKLACE, 10_000);
		final String r = plugin.buildReport(SAPPHIRE_NECKLACE, "Sapphire necklace", "");
		Assert.assertTrue(r, r.contains("same total as the lots just before it"));
		Assert.assertFalse(r, r.contains("more than"));
	}

	/** Which lots look re-booked: a time-less lot equal to a run of the
	 *  lots before it. An offer filling in equal timed chunks is honest. */
	@Test
	public void aRebookIsTheSumOfTheFillsBeforeIt()
	{
		final java.util.List<long[]> watched = java.util.List.of(
			new long[]{173, 173L * 876, 1_000L}, new long[]{3_565, 3_565L * 876, 2_000L},
			new long[]{3_738, 3_738L * 876, 0L});
		Assert.assertTrue("173 + 3,565 re-booked as 3,738", PocketGeTrackerPlugin.repeatsEarlierLots(watched, 2));
		Assert.assertFalse(PocketGeTrackerPlugin.repeatsEarlierLots(watched, 1));

		final java.util.List<long[]> chunks = java.util.List.of(
			new long[]{1, 1_250_000L, 1_000L}, new long[]{1, 1_250_000L, 2_000L});
		Assert.assertFalse("one at a time at the bid", PocketGeTrackerPlugin.repeatsEarlierLots(chunks, 1));

		final java.util.List<long[]> unrelated = java.util.List.of(
			new long[]{100, 87_600L, 1_000L}, new long[]{250, 219_000L});
		Assert.assertFalse("no run of earlier lots adds up to it", PocketGeTrackerPlugin.repeatsEarlierLots(unrelated, 1));
	}

	@Test
	public void itSaysWhichBuild() throws Exception
	{
		Assert.assertTrue(report().contains("by PocketGE " + BuildInfo.VERSION));
	}

	@Test
	public void itNeverCarriesTheAccount() throws Exception
	{
		final String r = report();
		Assert.assertFalse(r, r.contains(String.valueOf(ACCOUNT)));
	}
}
