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
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Assert;
import org.junit.Test;

/**
 * A chart request sent to the open tab after RuneLite restarts.
 *
 * Reported as "right clicking in bank to open graph doesn't work. the
 * website doesn't open. it works if you click the graph button on the
 * inspect". The chat said the item was sent; the tab did nothing. The tab
 * had outlived the restart and remembered the old run's request number, and
 * the new run counted from 1 again, so everything it sent looked old.
 */
public class NavSeqTest
{
	private static long click(PocketGeTrackerPlugin plugin) throws Exception
	{
		final Field f = PocketGeTrackerPlugin.class.getDeclaredField("navSeq");
		f.setAccessible(true);
		return ((AtomicLong) f.get(plugin)).incrementAndGet();
	}

	/** A run's first request is above everything the run before it sent. */
	@Test
	public void aNewRunCountsOnFromTheLast() throws Exception
	{
		final PocketGeTrackerPlugin before = new PocketGeTrackerPlugin();
		long last = 0;
		for (int i = 0; i < 20; i++)
		{
			last = click(before);
		}
		Thread.sleep(30);
		final PocketGeTrackerPlugin after = new PocketGeTrackerPlugin();
		Assert.assertTrue(click(after) > last);
	}

	/** And the tab's wait for the next click, asked with the old run's
	 *  number, is answered by the new run's request. */
	@Test
	public void theOpenTabHearsTheNewRun() throws Exception
	{
		final PocketGeTrackerPlugin before = new PocketGeTrackerPlugin();
		final long tabRemembers = click(before);
		Thread.sleep(5);
		final PocketGeTrackerPlugin after = new PocketGeTrackerPlugin();

		final LocalBridgeServer bridge = new LocalBridgeServer(new Gson());
		bridge.start(0, HashMap::new, HashMap::new, null, null);
		try
		{
			final long seq = click(after);
			bridge.publishNav(new LocalBridgeServer.NavRequest(seq, "Ring of nature", 0, System.currentTimeMillis()));
			final long t0 = System.currentTimeMillis();
			final String got = get(bridge.port(), "/nav?since=" + tabRemembers);
			Assert.assertTrue(got, got.contains("Ring of nature"));
			Assert.assertTrue(got, got.contains("\"seq\":" + seq));
			Assert.assertTrue("answered at once, not after the 25s wait",
				System.currentTimeMillis() - t0 < 5_000);
		}
		finally
		{
			bridge.stop();
		}
	}

	private static String get(int port, String path) throws Exception
	{
		try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port))
		{
			s.setSoTimeout(10_000);
			final String req = "GET " + path + " HTTP/1.1\r\n"
				+ "Host: 127.0.0.1:" + port + "\r\n"
				+ "Origin: https://pocketge.com\r\n"
				+ "Connection: close\r\n\r\n";
			final OutputStream out = s.getOutputStream();
			out.write(req.getBytes(StandardCharsets.UTF_8));
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
					final String sofar = got.toString(StandardCharsets.UTF_8);
					if (sofar.contains("navRequest") && sofar.trim().endsWith("}"))
					{
						break;
					}
				}
			}
			catch (java.net.SocketTimeoutException done)
			{
				// what arrived is the answer
			}
			return got.toString(StandardCharsets.UTF_8);
		}
	}
}
