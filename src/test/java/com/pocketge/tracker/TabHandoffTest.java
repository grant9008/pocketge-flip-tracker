package com.pocketge.tracker;

import com.google.gson.Gson;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Whether the plugin believes a PocketGE tab is open, after it has closed.
 *
 * Reported as "it takes it over right away if there is already a tab open",
 * but with the tab just closed "it took 4 times doing that for it to finally
 * open a new tab". A closed tab's parked /nav could not be seen from the
 * plugin and counted as a listener for up to 25 seconds; the answer written
 * to it then counted as a fresh poll for 8 more. Every click in that window
 * was "sent to your open tab" — which no longer existed.
 */
public class TabHandoffTest
{
	private static final String SITE = "https://pocketge.com";
	private static final long FAR = Long.MAX_VALUE / 2; // a since no request reaches, so it parks

	private LocalBridgeServer bridge;
	private final List<Long> acks = new CopyOnWriteArrayList<>();

	@Before
	public void start() throws Exception
	{
		bridge = new LocalBridgeServer(new Gson());
		bridge.setAckListener(acks::add);
		bridge.start(0, HashMap::new, HashMap::new, null, null);
	}

	@After
	public void stop()
	{
		bridge.stop();
	}

	// -- a tab that closes -------------------------------------------------

	/** The browser hanging up does not reach a parked request. Its goodbye
	 *  does, and then nothing is counted as open. */
	@Test
	public void aClosedTabsGoodbyeEndsItsListener() throws Exception
	{
		final Socket tab = park("/nav?since=" + FAR + "&tab=111-1&ack=1");
		waitFor(bridge::hasParkedNavListener);
		Assert.assertTrue(bridge.hasConfirmingListener());

		tab.close();
		Thread.sleep(150);
		Assert.assertTrue("the plugin cannot see a hang-up — why the goodbye exists",
			bridge.hasParkedNavListener());

		Assert.assertEquals(204, status(get("/tab/closed?tab=111-1", SITE)));
		waitFor(() -> !bridge.hasParkedNavListener());
		Assert.assertFalse("nor does its last request count as a recent poll",
			bridge.hasRecentClient(8_000));
		Assert.assertFalse(bridge.hasConfirmingListener());
	}

	/** One tab closing says nothing about the others. */
	@Test
	public void anotherTabStaysOpen() throws Exception
	{
		final Socket a = park("/nav?since=" + FAR + "&tab=111-1&ack=1");
		final Socket b = park("/nav?since=" + FAR + "&tab=222-2&ack=1");
		waitFor(() -> parked() == 2);
		a.close();
		get("/tab/closed?tab=111-1", SITE);
		waitFor(() -> parked() == 1);
		Assert.assertTrue(bridge.hasParkedNavListener());
		Assert.assertTrue(bridge.hasRecentClient(8_000));
		b.close();
	}

	/** Closed between two of its polls, with nothing parked anywhere: that
	 *  was the last tab. */
	@Test
	public void aGoodbyeWithNothingParkedForgetsTheTab() throws Exception
	{
		get("/flips", SITE);
		Assert.assertTrue(bridge.hasRecentClient(8_000));
		get("/tab/closed?tab=111-1", SITE);
		Assert.assertFalse(bridge.hasRecentClient(8_000));
	}

	/** The end of a park is not a poll. A dead connection's park ends exactly
	 *  like a live one's, and refreshing lastPollAt there kept a closed tab
	 *  "recent" for the next several clicks. */
	@Test
	public void theEndOfAParkIsNotAPoll() throws Exception
	{
		final Socket tab = park("/nav?since=0&tab=111-1&ack=1");
		waitFor(bridge::hasParkedNavListener);
		tab.close();
		ageLastPoll(20_000); // it asked twenty seconds ago, as a closed tab's park would have

		bridge.publishNav(new LocalBridgeServer.NavRequest(5, "Ring of nature", 0, System.currentTimeMillis()));
		waitFor(() -> !bridge.hasParkedNavListener());
		Assert.assertFalse(bridge.hasRecentClient(8_000));
	}

	// -- the ack ----------------------------------------------------------

	@Test
	public void aTabsAckReachesThePlugin() throws Exception
	{
		Assert.assertEquals(204, status(get("/tab/ack?seq=1800000000123", SITE)));
		Assert.assertEquals(List.of(1_800_000_000_123L), acks);
		Assert.assertTrue("a page answering is a page that is there", bridge.hasRecentClient(8_000));
	}

	/** Same rule as every other endpoint: another website cannot speak for
	 *  the tab. */
	@Test
	public void anotherSitesAckIsRefused() throws Exception
	{
		Assert.assertEquals(403, status(get("/tab/ack?seq=7", "https://evil.example")));
		Assert.assertEquals(403, status(get("/tab/closed?tab=111-1", "https://evil.example")));
		Assert.assertTrue(acks.isEmpty());
	}

	/** A page from before the ack never makes the plugin wait for one. */
	@Test
	public void anOlderPageIsNotWaitedOn() throws Exception
	{
		final Socket old = park("/nav?since=" + FAR);
		waitFor(bridge::hasParkedNavListener);
		Assert.assertFalse(bridge.hasConfirmingListener());
		old.close();
	}

	@Test
	public void theRequestCarriesTheAckFlag()
	{
		final String json = new Gson().toJson(new LocalBridgeServer.NavRequest(9, "Ring of nature", 0, 1L, true));
		Assert.assertTrue(json, json.contains("\"ack\":true"));
	}

	@Test
	public void onlyTabIdsThePageMintsAreKept()
	{
		Assert.assertEquals("8231-1800000000000", LocalBridgeServer.tabId("8231-1800000000000"));
		Assert.assertNull(LocalBridgeServer.tabId("<script>"));
		Assert.assertNull(LocalBridgeServer.tabId(""));
		Assert.assertNull(LocalBridgeServer.tabId(null));
		Assert.assertNull(LocalBridgeServer.tabId("1".repeat(65)));
	}

	// -- plumbing ---------------------------------------------------------

	private int parked()
	{
		try
		{
			final Field f = LocalBridgeServer.class.getDeclaredField("parkedNavs");
			f.setAccessible(true);
			return ((java.util.concurrent.atomic.AtomicInteger) f.get(bridge)).get();
		}
		catch (ReflectiveOperationException e)
		{
			throw new AssertionError(e);
		}
	}

	private void ageLastPoll(long ms) throws Exception
	{
		final Field f = LocalBridgeServer.class.getDeclaredField("lastPollAt");
		f.setAccessible(true);
		f.setLong(bridge, System.currentTimeMillis() - ms);
	}

	private static void waitFor(BooleanSupplier condition) throws Exception
	{
		final long until = System.currentTimeMillis() + 3_000;
		while (!condition.getAsBoolean())
		{
			if (System.currentTimeMillis() > until)
			{
				Assert.fail("timed out");
			}
			Thread.sleep(10);
		}
	}

	/** Send a request and leave it open, as a tab's parked /nav is. */
	private Socket park(String path) throws Exception
	{
		final Socket s = new Socket(InetAddress.getLoopbackAddress(), bridge.port());
		final OutputStream out = s.getOutputStream();
		out.write(request(path, SITE).getBytes(StandardCharsets.UTF_8));
		out.flush();
		return s;
	}

	private String request(String path, String origin)
	{
		return "GET " + path + " HTTP/1.1\r\n"
			+ "Host: 127.0.0.1:" + bridge.port() + "\r\n"
			+ "Origin: " + origin + "\r\n"
			+ "Connection: close\r\n\r\n";
	}

	private String get(String path, String origin) throws Exception
	{
		try (Socket s = new Socket(InetAddress.getLoopbackAddress(), bridge.port()))
		{
			s.setSoTimeout(3_000);
			final OutputStream out = s.getOutputStream();
			out.write(request(path, origin).getBytes(StandardCharsets.UTF_8));
			out.flush();
			final InputStream in = s.getInputStream();
			final ByteArrayOutputStream got = new ByteArrayOutputStream();
			final byte[] buf = new byte[4096];
			int n;
			try
			{
				while ((n = in.read(buf)) > 0)
				{
					got.write(buf, 0, n);
				}
			}
			catch (java.net.SocketTimeoutException done)
			{
				// what arrived is the answer
			}
			return got.toString(StandardCharsets.UTF_8);
		}
	}

	private static int status(String response)
	{
		return Integer.parseInt(response.substring(9, 12));
	}
}
