package net.runelite.client.input;

import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;

/**
 * Compile-only stub of RuneLite's MouseManager.
 * Signatures verified against runelite-parent-1.12.38.
 *
 * Upstream the private @Inject constructor takes a RuneLiteConfig; that type
 * is not stubbed and the constructor is not reachable from plugin code, so
 * this keeps a private no-arg one instead.
 */
public class MouseManager
{
	private MouseManager()
	{
	}

	public void registerMouseListener(MouseListener mouseListener)
	{
		throw new UnsupportedOperationException();
	}

	public void registerMouseListener(int position, MouseListener mouseListener)
	{
		throw new UnsupportedOperationException();
	}

	public void unregisterMouseListener(MouseListener mouseListener)
	{
		throw new UnsupportedOperationException();
	}

	public void registerMouseWheelListener(MouseWheelListener mouseWheelListener)
	{
		throw new UnsupportedOperationException();
	}

	public void registerMouseWheelListener(int position, MouseWheelListener mouseWheelListener)
	{
		throw new UnsupportedOperationException();
	}

	public void unregisterMouseWheelListener(MouseWheelListener mouseWheelListener)
	{
		throw new UnsupportedOperationException();
	}

	public MouseEvent processMousePressed(MouseEvent mouseEvent)
	{
		throw new UnsupportedOperationException();
	}

	public MouseEvent processMouseReleased(MouseEvent mouseEvent)
	{
		throw new UnsupportedOperationException();
	}

	public MouseEvent processMouseClicked(MouseEvent mouseEvent)
	{
		throw new UnsupportedOperationException();
	}

	public MouseEvent processMouseEntered(MouseEvent mouseEvent)
	{
		throw new UnsupportedOperationException();
	}

	public MouseEvent processMouseExited(MouseEvent mouseEvent)
	{
		throw new UnsupportedOperationException();
	}

	public MouseEvent processMouseDragged(MouseEvent mouseEvent)
	{
		throw new UnsupportedOperationException();
	}

	public MouseEvent processMouseMoved(MouseEvent mouseEvent)
	{
		throw new UnsupportedOperationException();
	}

	public MouseWheelEvent processMouseWheelMoved(MouseWheelEvent mouseWheelEvent)
	{
		throw new UnsupportedOperationException();
	}
}
