package com.pocketge.tracker;

import java.awt.Color;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;
import net.runelite.client.util.QuantityFormatter;

/**
 * A small, draggable floating panel showing the advisor's top live
 * suggestion — Flipping Copilot's "Buy X for Y gp, Z profit" box, in our
 * theme. Unlike Copilot it isn't gated to the GE interface being open (that
 * needs a specific widget lookup whose id can drift between game updates);
 * it simply shows whenever the advisor has something to suggest, which also
 * means you see it before you've even opened the Exchange.
 */
@Singleton
public class GeOfferOverlay extends OverlayPanel
{
	private static final Color GOLD = new Color(0xE5, 0xC1, 0x58);
	private static final Color POSITIVE = new Color(0x1F, 0xB8, 0x5C);

	private volatile Advisor.Suggestion suggestion;

	@Inject
	private GeOfferOverlay()
	{
		setPosition(OverlayPosition.TOP_LEFT);
	}

	/** Called from the plugin whenever suggestions are recomputed. Null
	 *  hides the overlay (advisor off, or nothing to suggest). */
	public void setSuggestion(Advisor.Suggestion s)
	{
		this.suggestion = s;
	}

	@Override
	public java.awt.Dimension render(java.awt.Graphics2D graphics)
	{
		Advisor.Suggestion s = suggestion;
		if (s == null)
		{
			return null; // nothing to draw this frame
		}
		panelComponent.getChildren().clear();
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("PocketGE")
			.color(GOLD)
			.build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left(verb(s.type) + " " + s.name)
			.build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left(QuantityFormatter.quantityToStackSize(s.price) + " gp × " + QuantityFormatter.quantityToStackSize(s.quantity))
			.build());
		if (s.expectedProfit > 0)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Profit")
				.right("+" + QuantityFormatter.quantityToStackSize(s.expectedProfit) + " gp")
				.rightColor(POSITIVE)
				.build());
		}
		return super.render(graphics);
	}

	private static String verb(Advisor.Suggestion.Type t)
	{
		switch (t)
		{
			case BUY: return "Buy";
			case SELL: return "Sell";
			case ADJUST_BUY: return "Adjust bid:";
			case ADJUST_SELL: return "Adjust ask:";
			default: return "";
		}
	}
}
