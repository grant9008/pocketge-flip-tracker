package com.pocketge.tracker;

import java.lang.reflect.Proxy;
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
	/**
	 * A Client that answers getWorldType and nothing else, as a dynamic proxy.
	 *
	 * NOT {@code new Client(){ ... }}. The first version of this test did
	 * that, and it compiled against the offline stub — whose methods all had
	 * default bodies — and then failed the real Gradle build, where Client
	 * has several hundred abstract methods. BankHighlightOverlayTest had
	 * already hit and documented the identical mistake, in the same
	 * directory. The stub is abstract now, so the anonymous form no longer
	 * compiles anywhere; a proxy is shaped by the interface at runtime and
	 * cannot fall out of step with either version of it.
	 */
	private static Client world(EnumSet<WorldType> types)
	{
		return (Client) Proxy.newProxyInstance(
			Client.class.getClassLoader(),
			new Class<?>[]{Client.class},
			(proxy, method, args) ->
			{
				if ("getWorldType".equals(method.getName()))
				{
					return types;
				}
				final Class<?> r = method.getReturnType();
				if (r == boolean.class)
				{
					return false;
				}
				if (r.isPrimitive())
				{
					return 0;
				}
				return null;
			});
	}

	private static Client free()
	{
		return world(EnumSet.noneOf(WorldType.class));
	}

	private static Client members()
	{
		return world(EnumSet.of(WorldType.MEMBERS));
	}

	@Test
	public void aFreeWorldHasThree()
	{
		Assert.assertEquals(3, PocketGeTrackerPlugin.usableGeSlots(free()));
	}

	@Test
	public void aMembersWorldHasEight()
	{
		Assert.assertEquals(8, PocketGeTrackerPlugin.usableGeSlots(members()));
	}

	/** Before the client knows what world it is on, assume the smaller
	 *  number — a ring on a slot you cannot use is worse than no ring. */
	@Test
	public void unknownWorldIsTreatedAsFree()
	{
		Assert.assertEquals(3, PocketGeTrackerPlugin.usableGeSlots(null));
		Assert.assertEquals(3, PocketGeTrackerPlugin.usableGeSlots(world(null)));
	}

	/** The two constants the rest of the plugin sizes against are the same
	 *  numbers this returns, so the advice and the overlay cannot disagree. */
	@Test
	public void agreesWithTheSizingConstants()
	{
		Assert.assertEquals(PocketGeTrackerPlugin.F2P_GE_SLOTS,
			PocketGeTrackerPlugin.usableGeSlots(free()));
		Assert.assertEquals(PocketGeTrackerPlugin.MEMBERS_GE_SLOTS,
			PocketGeTrackerPlugin.usableGeSlots(members()));
	}
}
