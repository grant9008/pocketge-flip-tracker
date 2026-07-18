package com.pocketge.tracker;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
	name = "PocketGE Flip Tracker",
	description = "Tracks your GE flips as they fill — profit after the 2% tax — with one-click PocketGE price charts",
	tags = {"flipping", "grand exchange", "merchant", "profit", "ge"}
)
public class PocketGeTrackerPlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(PocketGeTrackerPlugin.class);

	@Inject
	private Client client;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ItemManager itemManager;

	@Inject
	private PocketGeTrackerConfig config;

	private final FlipTracker tracker = new FlipTracker();
	private final LocalBridgeServer bridge = new LocalBridgeServer();
	private PocketGeTrackerPanel panel;
	private NavigationButton navButton;

	@Provides
	PocketGeTrackerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PocketGeTrackerConfig.class);
	}

	@Override
	protected void startUp()
	{
		panel = new PocketGeTrackerPanel(() ->
		{
			tracker.reset();
			refreshPanel();
		});
		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");
		navButton = NavigationButton.builder()
			.tooltip("PocketGE Flip Tracker")
			.icon(icon)
			.priority(6)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		refreshPanel();
		syncBridge();
	}

	@Override
	protected void shutDown()
	{
		clientToolbar.removeNavigation(navButton);
		bridge.stop();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (PocketGeTrackerConfig.GROUP.equals(event.getGroup()))
		{
			syncBridge();
		}
	}

	private void syncBridge()
	{
		bridge.stop();
		if (config.localBridge())
		{
			try
			{
				bridge.start(config.bridgePort(), () -> LocalBridgeServer.payload(
					tracker.getSessionProfit(), tracker.getFlips(), tracker.getFills()));
				log.info("PocketGE local bridge listening on 127.0.0.1:{}", config.bridgePort());
			}
			catch (IOException e)
			{
				log.warn("PocketGE local bridge failed to start", e);
			}
		}
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		final GrandExchangeOffer offer = event.getOffer();
		final GrandExchangeOfferState state = offer.getState();
		final boolean emptied = state == GrandExchangeOfferState.EMPTY;
		final boolean buy = state == GrandExchangeOfferState.BUYING
			|| state == GrandExchangeOfferState.BOUGHT
			|| state == GrandExchangeOfferState.CANCELLED_BUY;
		/* Resolve the item name now — we're on the client thread here. */
		final String name = emptied ? "" : itemManager.getItemComposition(offer.getItemId()).getName();
		final TradeFill fill = tracker.onOffer(
			System.currentTimeMillis(),
			event.getSlot(),
			offer.getItemId(),
			name,
			buy,
			offer.getQuantitySold(),
			offer.getSpent(),
			emptied
		);
		if (fill != null)
		{
			refreshPanel();
		}
	}

	private void refreshPanel()
	{
		if (panel == null)
		{
			return;
		}
		final long profit = tracker.getSessionProfit();
		final java.util.List<Flip> flips = tracker.getFlips();
		final int max = config.maxFlips();
		SwingUtilities.invokeLater(() -> panel.update(profit, flips, max));
	}
}
