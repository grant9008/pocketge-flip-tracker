package com.pocketge.tracker;

/**
 * Every pocketge.com URL the plugin opens, built in one place and tagged so
 * the visit can be told apart from someone typing the address in.
 *
 * <h2>Why the tagging is needed at all</h2>
 * The plugin opens links through {@code LinkBrowser.browse}, which hands the
 * URL to the desktop's browser. A browser launched that way sends no
 * referrer — so without a marker in the URL itself, every click from the
 * sidebar lands in analytics as "Direct / (none)", indistinguishable from
 * organic traffic. There is no website-side trick that recovers it; the
 * information simply never arrives.
 *
 * <h2>Why a class instead of four string literals</h2>
 * There were four of these scattered across three panels and the plugin, all
 * spelling out the same host. Tagging them individually is exactly the kind
 * of thing that goes stale the first time a fifth link is added and someone
 * forgets the suffix — and a missing tag is invisible, because the link still
 * works perfectly and just quietly reports as organic.
 *
 * <h2>A note on the parameters</h2>
 * {@code utm_source}/{@code utm_medium} identify the plugin;
 * {@code utm_content} says WHICH control was clicked, so the chart button and
 * the toolbar globe can be told apart. Standard names, because GA4 knows them
 * — though on pocketge.com they also need the site to read them explicitly:
 * it sets {@code send_page_view:false} and builds {@code page_location} by
 * hand, so nothing would ever see the real query string otherwise. See the
 * campaign block in index.html.
 */
final class PocketGeLinks
{
	private static final String BASE = "https://pocketge.com/";
	private static final String TAGS = "utm_source=runelite&utm_medium=plugin&utm_content=";

	private PocketGeLinks()
	{
	}

	/** The site's front page, opened from {@code where} (a stable, lowercase
	 *  identifier for the control — it becomes utm_content). */
	static String home(String where)
	{
		return BASE + "?" + TAGS + where;
	}

	/**
	 * One item's page. {@code encodedName} must already be URL-encoded — the
	 * caller does that, because it also has to turn "+" into "%20" for names
	 * with spaces.
	 *
	 * The tags go AFTER q, so the parameter the site actually reads stays
	 * first and stays readable in the address bar.
	 */
	static String item(String encodedName, String where)
	{
		return BASE + "?q=" + encodedName + "&" + TAGS + where;
	}
}
