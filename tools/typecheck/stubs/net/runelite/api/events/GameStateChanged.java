package net.runelite.api.events;

import net.runelite.api.GameState;

/**
 * Stub of RuneLite's GameStateChanged event.
 *
 * <p>Upstream this is a Lombok {@code @Data} class over the single field
 * {@code gameState}.</p>
 */
public class GameStateChanged
{
	private GameState gameState;

	public GameState getGameState()
	{
		throw new UnsupportedOperationException();
	}

	public void setGameState(GameState gameState)
	{
		throw new UnsupportedOperationException();
	}
}
