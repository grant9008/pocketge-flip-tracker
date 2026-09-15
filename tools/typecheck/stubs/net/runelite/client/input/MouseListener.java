package net.runelite.client.input;

import java.awt.event.MouseEvent;

/**
 * Compile-only stub of RuneLite's MouseListener.
 * Signatures verified against runelite-parent-1.12.38.
 */
public interface MouseListener
{
	MouseEvent mouseClicked(MouseEvent mouseEvent);

	MouseEvent mousePressed(MouseEvent mouseEvent);

	MouseEvent mouseReleased(MouseEvent mouseEvent);

	MouseEvent mouseEntered(MouseEvent mouseEvent);

	MouseEvent mouseExited(MouseEvent mouseEvent);

	MouseEvent mouseDragged(MouseEvent mouseEvent);

	MouseEvent mouseMoved(MouseEvent mouseEvent);
}
