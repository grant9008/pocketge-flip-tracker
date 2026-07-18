package com.pocketge.tracker;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Tiny opt-in HTTP listener bound to 127.0.0.1 ONLY. pocketge.com open in
 * the local browser polls GET /flips to show this session's trades — the
 * Flipping-Copilot experience without a cloud account: data never leaves
 * the machine. CORS is restricted to the PocketGE origins, and the
 * Private-Network-Access preflight header is answered so Chromium allows
 * the https page -> localhost fetch.
 */
public class LocalBridgeServer
{
	private static final String[] ALLOWED_ORIGINS = {
		"https://pocketge.com",
		"https://www.pocketge.com",
		"http://localhost:8901" // local dev of the site
	};

	private final Gson gson = new Gson();
	private HttpServer server;

	public void start(int port, Supplier<Map<String, Object>> payload) throws IOException
	{
		stop();
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
		server.createContext("/flips", ex -> handle(ex, payload));
		server.createContext("/status", ex -> handle(ex, () -> {
			Map<String, Object> m = new HashMap<>();
			m.put("ok", true);
			m.put("plugin", "pocketge-flip-tracker");
			return m;
		}));
		server.setExecutor(null);
		server.start();
	}

	private void handle(HttpExchange ex, Supplier<Map<String, Object>> payload) throws IOException
	{
		String origin = ex.getRequestHeaders().getFirst("Origin");
		String allow = null;
		if (origin != null)
		{
			for (String o : ALLOWED_ORIGINS)
			{
				if (o.equals(origin))
				{
					allow = o;
					break;
				}
			}
		}
		if (allow != null)
		{
			ex.getResponseHeaders().set("Access-Control-Allow-Origin", allow);
			ex.getResponseHeaders().set("Vary", "Origin");
		}
		if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod()))
		{
			ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
			ex.getResponseHeaders().set("Access-Control-Allow-Headers", "*");
			/* Chromium Private Network Access: an https page fetching a
			   private address must be explicitly allowed. */
			ex.getResponseHeaders().set("Access-Control-Allow-Private-Network", "true");
			ex.sendResponseHeaders(204, -1);
			ex.close();
			return;
		}
		byte[] body = gson.toJson(payload.get()).getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		ex.sendResponseHeaders(200, body.length);
		try (OutputStream os = ex.getResponseBody())
		{
			os.write(body);
		}
	}

	public void stop()
	{
		if (server != null)
		{
			server.stop(0);
			server = null;
		}
	}

	/** Build the /flips payload from tracker state. Static so the plugin
	 *  can also reuse it for future export features. */
	public static Map<String, Object> payload(long sessionProfit, List<Flip> flips, List<TradeFill> fills)
	{
		Map<String, Object> m = new HashMap<>();
		m.put("sessionProfit", sessionProfit);
		m.put("flips", flips);
		m.put("fills", fills);
		m.put("generatedAt", System.currentTimeMillis());
		return m;
	}
}
