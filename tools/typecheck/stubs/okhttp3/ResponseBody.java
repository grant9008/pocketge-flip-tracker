package okhttp3;

import java.io.Closeable;
import java.io.IOException;

/**
 * Compile-only stub of okhttp3.ResponseBody.
 *
 * okhttp3/ResponseBody.kt:100 declares `abstract class ResponseBody : Closeable`
 * and :185-186 `@Throws(IOException::class) fun string(): String` — so string()
 * IS a checked-throwing call from Java, and that matters: the plugin's getJson
 * relies on it to justify its own `throws IOException`.
 */
public abstract class ResponseBody implements Closeable
{
	public String string() throws IOException
	{
		throw new UnsupportedOperationException("stub");
	}

	@Override
	public void close()
	{
	}
}
