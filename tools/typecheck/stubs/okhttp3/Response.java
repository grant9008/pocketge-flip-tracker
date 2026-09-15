package okhttp3;

import java.io.Closeable;

/**
 * Compile-only stub of okhttp3.Response.
 *
 * okhttp3/Response.kt: `val isSuccessful: Boolean` (:146),
 * `@get:JvmName("code") val code: Int` (:59) and
 * `@get:JvmName("body") val body: ResponseBody?` (:78) — the JvmName
 * annotations are why Java sees code() and body() rather than getCode()/getBody().
 *
 * close() is declared WITHOUT a throws clause on purpose: upstream is
 * `override fun close()` (:301) with no @Throws, so Kotlin emits no checked
 * exception and try-with-resources over a Response does not force a catch.
 * Declaring IOException here would make code compile locally that the real
 * build accepts anyway — but the reverse mistake is the dangerous one, so it
 * matches upstream exactly.
 */
public class Response implements Closeable
{
	public boolean isSuccessful()
	{
		return false;
	}

	public int code()
	{
		return 0;
	}

	public ResponseBody body()
	{
		return null;
	}

	@Override
	public void close()
	{
	}
}
