package net.runelite.api;

import java.util.Collection;
import java.util.EnumSet;

/** Stub of RuneLite's WorldType. */
public enum WorldType
{
	MEMBERS,
	PVP,
	BOUNTY,
	PVP_ARENA,
	SKILL_TOTAL,
	QUEST_SPEEDRUNNING,
	HIGH_RISK,
	LAST_MAN_STANDING,
	BETA_WORLD,
	LEGACY_ONLY,
	EOC_ONLY,
	NOSAVE_MODE,
	TOURNAMENT_WORLD,
	FRESH_START_WORLD,
	DEADMAN,
	SEASONAL;

	public static EnumSet<WorldType> fromMask(final int mask)
	{
		return EnumSet.noneOf(WorldType.class);
	}

	public static int toMask(final EnumSet<WorldType> types)
	{
		return 0;
	}

	public static boolean isPvpWorld(final Collection<WorldType> worldTypes)
	{
		return false;
	}
}
