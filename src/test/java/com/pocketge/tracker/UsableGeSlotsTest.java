package com.pocketge.tracker;

import java.util.EnumSet;
import net.runelite.api.Client;
import net.runelite.api.WorldType;
import org.junit.Assert;
import org.junit.Test;

/**
 * How many Grand Exchange slots a world lets you use.
 *
 * The number was right in the advice text and wrong in the slot overlay,
 * because only one of them asked. On a free world the client reports the
 * five members-only slots as EMPTY — present in the interface, just locked —
 * and the overlay walked all eight looking for the first empty one, so the
 * "start your offer here" ring landed on a slot the player could not open:
 * "its trying to tell me to use a ge slot i dont have".
 */
public class UsableGeSlotsTest
{
	private static Client world(WorldType... types)
	{
		final EnumSet<WorldType> set = types.length == 0
			? EnumSet.noneOf(WorldType.class) : EnumSet.of(types[0], types);
		return new Client()
		{
			@Override
			public EnumSet<WorldType> getWorldType()
			{
				return set;
			}
		};
	}

	@Test
	public void aFreeWorldHasThree()
	{
		Assert.assertEquals(3, PocketGeTrackerPlugin.usableGeSlots(world()));
	}

	@Test
	public void aMembersWorldHasEight()
	{
		Assert.assertEquals(8, PocketGeTrackerPlugin.usableGeSlots(world(WorldType.MEMBERS)));
	}

	/** Before the client knows what world it is on, assume the smaller
	 *  number — a ring on a slot you cannot use is worse than no ring. */
	@Test
	public void unknownWorldIsTreatedAsFree()
	{
		Assert.assertEquals(3, PocketGeTrackerPlugin.usableGeSlots(null));
		Assert.assertEquals(3, PocketGeTrackerPlugin.usableGeSlots(new Client()
		{
			@Override
			public EnumSet<WorldType> getWorldType()
			{
				return null;
			}
		}));
	}

	/** The two constants the rest of the plugin sizes against are the same
	 *  numbers this returns, so the advice and the overlay cannot disagree. */
	@Test
	public void agreesWithTheSizingConstants()
	{
		Assert.assertEquals(PocketGeTrackerPlugin.F2P_GE_SLOTS,
			PocketGeTrackerPlugin.usableGeSlots(world()));
		Assert.assertEquals(PocketGeTrackerPlugin.MEMBERS_GE_SLOTS,
			PocketGeTrackerPlugin.usableGeSlots(world(WorldType.MEMBERS)));
	}
}
