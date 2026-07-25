package com.pocketge.tracker;

/**
 * A simplified proxy of pocketge.com's Analyst Rating gauge (0-100, Strong
 * Sell -> Strong Buy), scoped to what the plugin can cheaply fetch: the
 * live quote plus a 24h average (one extra whole-market API call, same
 * shape as /latest — no per-item history calls). The website's real rating
 * scores against a full recency-weighted price series; this is intentionally
 * coarser — "how does right now compare to today's typical price" — and is
 * documented as such rather than pretending full parity.
 */
public final class AnalystRating
{
	private AnalystRating() {}

	public static class Average
	{
		public long avgHighPrice;
		public long avgLowPrice;
	}

	public enum Label
	{
		STRONG_SELL("Strong Sell"),
		SELL("Sell"),
		HOLD("Hold"),
		BUY("Buy"),
		STRONG_BUY("Strong Buy");

		public final String text;
		Label(String text) { this.text = text; }
	}

	public static class Grade
	{
		public int score;   // 0 (strong sell) .. 100 (strong buy)
		public Label label;
	}

	/** How far the deviation has to swing (as a fraction of the 24h typical
	 *  price) to fully saturate the 0-100 scale. 20% below typical -> ~100,
	 *  20% above -> ~0, matched to the site's rough sensitivity. */
	private static final double SATURATION = 0.20;

	public static Grade grade(Advisor.Quote live, Average avg)
	{
		Grade g = new Grade();
		if (live == null || avg == null || live.high <= 0 || live.low <= 0
			|| avg.avgHighPrice <= 0 || avg.avgLowPrice <= 0)
		{
			g.score = 50;
			g.label = Label.HOLD;
			return g;
		}
		double liveMid = (live.high + live.low) / 2.0;
		double typicalMid = (avg.avgHighPrice + avg.avgLowPrice) / 2.0;
		double deviation = (liveMid - typicalMid) / typicalMid; // negative = below typical = cheap = buy-leaning

		double raw = 50.0 - (deviation / SATURATION) * 50.0;
		g.score = (int) Math.round(Math.max(0, Math.min(100, raw)));
		g.label = labelFor(g.score);
		return g;
	}

	private static Label labelFor(int score)
	{
		if (score >= 80) return Label.STRONG_BUY;
		if (score >= 60) return Label.BUY;
		if (score > 40) return Label.HOLD;
		if (score > 20) return Label.SELL;
		return Label.STRONG_SELL;
	}
}
