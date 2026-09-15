package okhttp3;

/**
 * Compile-only stub of okhttp3.Request.
 *
 * Builder members confirmed at okhttp3/Request.kt:136 (open class Builder),
 * :162 url(HttpUrl), :172 url(String), :198 header(String, String),
 * :287 build(). Each returns Builder so the chain type-checks.
 */
public class Request
{
	public static class Builder
	{
		public Builder url(HttpUrl url)
		{
			return this;
		}

		public Builder url(String url)
		{
			return this;
		}

		public Builder header(String name, String value)
		{
			return this;
		}

		public Request build()
		{
			throw new UnsupportedOperationException("stub");
		}
	}
}
