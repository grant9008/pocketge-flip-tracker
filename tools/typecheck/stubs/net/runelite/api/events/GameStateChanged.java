package net.runelite.api.events;

import net.runelite.api.GameState;

/**
 * Stub of RuneLite's GameStateChanged event.
 *
 * <p>Upstream this is a Lombok {@code @Data} class over the single field
 * {@code gameState}.</p>
 */
/* Stores its values, like the real Lombok @Data class, so tests can fire it. */
public class GameStateChanged
{
	private GameState gameState;

	public GameState getGameState()
	{
		return gameState;
	}

	public void setGameState(GameState gameState)
	{
		this.gameState = gameState;
	}
}
