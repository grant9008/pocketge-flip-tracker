package com.pocketge.tracker;

import com.google.gson.Gson;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Who the local bridge will talk to.
 *
 * The hub submission said "requests from any other origin are refused", and
 * it was not true: CORS only stops a foreign page READING a reply, so a POST
 * from any site open in the same browser still landed and edited your
 * favourite lists. These pin the rule to the code — first as the pure rule,
 * then over a real socket.
 *
 * What the rule is FOR is other websites in the browser, which cannot fake
 * an Origin. A program on the same machine can send any header, and these
 * tests do not claim otherwise.
 */
public class BridgeAccessTest
{
	private static final String SITE = "https://pocketge.com";
	private static final int PORT = 8477;
	private static final String HOST = "127.0.0.1:" + PORT;

	// -- the rule ----------------------------------------------------------

	@Test
	public void servesThePocketGeSite()
	{
		Assert.assertNull(LocalBridgeServer.refusal("GET", SITE, HOST, null, PORT));
		Assert.assertNull(LocalBridgeServer.refusal("GET", "https://www.pocketge.com", HOST, null, PORT));
		Assert.assertNull(LocalBridgeServer.refusal("POST", SITE, HOST, "application/json", PORT));
		Assert.assertNull("charset is fine",
			LocalBridgeServer.refusal("POST", SITE, HOST, "application/json; charset=utf-8", PORT));
		Assert.assertNull("a preflight carries no body type",
			LocalBridgeServer.refusal("OPTIONS", SITE, HOST, null, PORT));
	}

	@Test
	public void refusesAnyOtherOrigin()
	{
		Assert.assertEquals("origin", LocalBridgeServer.refusal("GET", "https://evil.example", HOST, null, PORT));
		Assert.assertEquals("origin", LocalBridgeServer.refusal("GET", "http://pocketge.com", HOST, null, PORT));
		Assert.assertEquals("origin", LocalBridgeServer.refusal("GET", "https://pocketge.com.evil.example", HOST, null, PORT));
		Assert.assertEquals("origin", LocalBridgeServer.refusal("GET", "null", HOST, null, PORT));
	}

	/** No Origin at all is not the site's own fetch, which always carries
	 *  one. */
	@Test
	public void refusesNoOrigin()
	{
		Assert.assertEquals("origin", LocalBridgeServer.refusal("GET", null, HOST, null, PORT));
		Assert.assertEquals("origin", LocalBridgeServer.refusal("POST", null, HOST, "application/json", PORT));
	}

	/** DNS rebinding: a page that points its own name at 127.0.0.1 still
	 *  sends its own name as the Host. */
	@Test
	public void refusesAForeignHost()
	{
		Assert.assertEquals("host", LocalBridgeServer.refusal("GET", SITE, "evil.example:" + PORT, null, PORT));
		Assert.assertEquals("host", LocalBridgeServer.refusal("GET", SITE, "127.0.0.1:9999", null, PORT));
		Assert.assertEquals("host", LocalBridgeServer.refusal("GET", SITE, null, null, PORT));
		Assert.assertNull(LocalBridgeServer.refusal("GET", SITE, "localhost:" + PORT, null, PORT));
		Assert.assertNull(LocalBridgeServer.refusal("GET", SITE, "[::1]:" + PORT, null, PORT));
	}

	/** A write must say it is JSON — which is what makes the browser ask
	 *  first. A form post or text/plain is the shape a cross-site write
	 *  takes to avoid that question. */
	@Test
	public void refusesAWriteThatIsNotJson()
	{
		Assert.assertEquals("content-type", LocalBridgeServer.refusal("POST", SITE, HOST, "text/plain", PORT));
		Assert.assertEquals("content-type",
			LocalBridgeServer.refusal("POST", SITE, HOST, "application/x-www-form-urlencoded", PORT));
		Assert.assertEquals("content-type", LocalBridgeServer.refusal("POST", SITE, HOST, null, PORT));
	}

	// -- over a socket -------------------------------------------------------

	private LocalBridgeServer bridge;
	private final List<String> writes = new ArrayList<>();

	@Before
	public void start() throws Exception
	{
		bridge = new LocalBridgeServer(new Gson());
		bridge.start(0, () ->
		{
			final Map<String, Object> m = new HashMap<>();
			m.put("secret", "your flips");
			return m;
		}, HashMap::new,
			(listId, itemId, name, remove) -> writes.add(itemId + ":" + remove),
			new LocalBridgeServer.ListWriter()
			{
				@Override
				public String create(String name)
				{
					writes.add("create:" + name);
					return "l1";
				}

				@Override
				public void rename(String listId, String name)
				{
					writes.add("rename");
				}

				@Override
				public void delete(String listId)
				{
					writes.add("delete");
				}

				@Override
				public void reorder(String listId, List<Integer> itemIds)
				{
					writes.add("reorder");
				}
			});
	}

	@After
	public void stop()
	{
		bridge.stop();
	}

	/** Raw HTTP so the test controls Origin and Host, which Java's own
	 *  clients will not let a caller set. Returns the whole response. */
	private String send(String method, String path, String origin, String host, String type, String body)
		throws Exception
	{
		try (Socket s = new Socket(InetAddress.getLoopbackAddress(), bridge.port()))
		{
			s.setSoTimeout(5_000);
			final byte[] b = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
			final StringBuilder req = new StringBuilder();
			req.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
			req.append("Host: ").append(host).append("\r\n");
			if (origin != null)
			{
				req.append("Origin: ").append(origin).append("\r\n");
			}
			if (type != null)
			{
				req.append("Content-Type: ").append(type).append("\r\n");
			}
			req.append("Content-Length: ").append(b.length).append("\r\n");
			req.append("Connection: close\r\n\r\n");
			final OutputStream out = s.getOutputStream();
			out.write(req.toString().getBytes(StandardCharsets.UTF_8));
			out.write(b);
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
				// keep-alive despite Connection: close — what arrived is the answer
			}
			return got.toString(StandardCharsets.UTF_8);
		}
	}

	private String host()
	{
		return "127.0.0.1:" + bridge.port();
	}

	private static int status(String response)
	{
		return Integer.parseInt(response.substring(9, 12));
	}

	/** The portfolio over time, for the chart on the flip history page: to
	 *  the site, and to nobody else — it is as private as the ledger. */
	@Test
	public void servesThePortfolioToTheSiteOnly() throws Exception
	{
		bridge.setPortfolioSource(() ->
		{
			final Map<String, Object> m = new HashMap<>();
			m.put("points", java.util.Arrays.asList(new long[]{1_790_000_000_000L, 148_000_000L}));
			return m;
		});
		final String ok = send("GET", "/portfolio", SITE, host(), null, null);
		Assert.assertEquals(200, status(ok));
		Assert.assertTrue(ok, ok.contains("[[1790000000000,148000000]]"));
		Assert.assertEquals(403, status(send("GET", "/portfolio", "https://evil.example", host(), null, null)));
	}

	/** No source yet — the plugin has not sampled anyone — is an empty
	 *  answer, not an error the page has to tell apart from "too old". */
	@Test
	public void noPortfolioYetIsEmptyNotAnError() throws Exception
	{
		final String r = send("GET", "/portfolio", SITE, host(), null, null);
		Assert.assertEquals(200, status(r));
		Assert.assertTrue(r, r.endsWith("{}"));
	}

	@Test
	public void theSiteGetsItsData() throws Exception
	{
		final String r = send("GET", "/flips", SITE, host(), null, null);
		Assert.assertEquals(r, 200, status(r));
		Assert.assertTrue(r.contains("your flips"));
		Assert.assertTrue(r.toLowerCase().contains("access-control-allow-origin: " + SITE));
		Assert.assertTrue("a served poll counts as an open tab", bridge.lastPollAt() > 0);
	}

	@Test
	public void aCurlGetsNothing() throws Exception
	{
		final String r = send("GET", "/flips", null, host(), null, null);
		Assert.assertEquals(403, status(r));
		Assert.assertFalse(r.contains("your flips"));
		Assert.assertEquals("and does not count as a tab", 0, bridge.lastPollAt());
	}

	@Test
	public void anotherSiteGetsNothing() throws Exception
	{
		for (String path : new String[]{"/flips", "/history", "/status", "/nav?since=1"})
		{
			final String r = send("GET", path, "https://evil.example", host(), null, null);
			Assert.assertEquals(path, 403, status(r));
			Assert.assertFalse(path, r.toLowerCase().contains("access-control-allow-origin"));
		}
	}

	/** The one that mattered: the write used to land even though the
	 *  foreign page could not read the reply. */
	@Test
	public void anotherSiteCannotWrite() throws Exception
	{
		Assert.assertEquals(403, status(send("POST", "/favorites", "https://evil.example", host(),
			"application/json", "{\"id\":1601}")));
		Assert.assertEquals(403, status(send("POST", "/favoriteLists", "https://evil.example", host(),
			"application/json", "{\"action\":\"delete\",\"listId\":\"x\"}")));
		Assert.assertEquals("a text/plain post skips the preflight; it must not skip us",
			403, status(send("POST", "/favorites", "https://evil.example", host(), "text/plain", "{\"id\":1601}")));
		Assert.assertTrue("nothing was written: " + writes, writes.isEmpty());
	}

	@Test
	public void theSiteCanWrite() throws Exception
	{
		Assert.assertEquals(200, status(send("POST", "/favorites", SITE, host(),
			"application/json", "{\"id\":1601,\"name\":\"Diamond\"}")));
		Assert.assertEquals(List.of("1601:false"), writes);
	}

	@Test
	public void theSitesWritesMustBeJson() throws Exception
	{
		Assert.assertEquals(415, status(send("POST", "/favorites", SITE, host(), "text/plain", "{\"id\":1601}")));
		Assert.assertTrue(writes.isEmpty());
	}

	@Test
	public void aRebindingHostIsRefused() throws Exception
	{
		Assert.assertEquals(403, status(send("GET", "/flips", SITE, "evil.example:" + bridge.port(), null, null)));
	}

	/** The preflight is answered for the site — including Chrome's
	 *  private-network question — and refused for anyone else. */
	@Test
	public void preflightOnlyForTheSite() throws Exception
	{
		final String ok = send("OPTIONS", "/favorites", SITE, host(), null, null);
		Assert.assertEquals(204, status(ok));
		Assert.assertTrue(ok.toLowerCase().contains("access-control-allow-private-network: true"));
		Assert.assertEquals(403, status(send("OPTIONS", "/favorites", "https://evil.example", host(), null, null)));
	}
}
