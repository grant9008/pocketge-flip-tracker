package net.runelite.api;

/** Stub of RuneLite's GameState. */
public enum GameState
{
	UNKNOWN,
	STARTING,
	LOGIN_SCREEN,
	LOGIN_SCREEN_AUTHENTICATOR,
	LOGGING_IN,
	LOADING,
	LOGGED_IN,
	CONNECTION_LOST,
	HOPPING;

	public int getState()
	{
		return 0;
	}

	public static GameState of(int state)
	{
		return UNKNOWN;
	}
}
