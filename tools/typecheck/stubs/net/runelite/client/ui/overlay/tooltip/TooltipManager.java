package net.runelite.client.ui.overlay.tooltip;

import java.util.ArrayList;
import java.util.List;

/**
 * Stub of RuneLite's TooltipManager. Keeps what was added so a harness can
 * assert on — or draw — the tooltip the overlay asked for, rather than only
 * observing that it asked for one.
 */
public class TooltipManager
{
	private final List<Tooltip> tooltips = new ArrayList<>();

	public void add(Tooltip t)
	{
		tooltips.add(t);
	}

	public List<Tooltip> getTooltips()
	{
		return tooltips;
	}

	public void clear()
	{
		tooltips.clear();
	}
}
