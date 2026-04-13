
package nateclipse.utils;

import static java.nio.charset.StandardCharsets.*;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor.AbortPolicy;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public abstract class WebServer implements HttpHandler {
	static private final boolean trace = false;
	static private final ILog log = Platform.getLog(WebServer.class);

	private final int port;
	private final Executor executor;
	protected HttpServer server;

	public WebServer (int port, Executor executor) {
		this.port = port;
		this.executor = executor;
	}

	public void start () {
		try {
			server = HttpServer.create(new InetSocketAddress(port), 0);
			server.setExecutor(executor);
			server.createContext("/", this);
			server.start();
			log.info("Listening on port: TCP " + server.getAddress().getPort());
		} catch (Throwable ex) {
			log.error("Unable to start web server: TCP " + port, ex);
		}
	}

	abstract public void handle (String path, Exchange exchange) throws Throwable;

	public void handle (HttpExchange httpExchange) throws IOException {
		var exchange = new Exchange(httpExchange);
		try {
			handle(exchange.path, exchange);
		} catch (Throwable ex) {
			if (trace || ex.getMessage() == null || ( //
			!ex.getMessage().startsWith("An established connection was aborted")
				&& !ex.getMessage().startsWith("Connection reset by peer") //
			)) {
				log.error("Error handling request: " + exchange, ex);
			}
			try {
				var writer = new StringWriter();
				ex.printStackTrace(new PrintWriter(writer));
				exchange.response(500, writer.toString());
			} catch (Throwable ignored) {
				exchange.response503();
			}
		} finally {
			if (exchange.autoClose) exchange.close();
		}
	}

	public class Exchange implements Closeable {
		public final HttpExchange http;
		public final String path;
		String body;
		public boolean autoClose = true;

		public Exchange (HttpExchange httpExchange) throws IOException {
			this.http = httpExchange;
			this.path = http.getRequestURI().getPath();

			if (trace) {
				TRACE("Request: " + this);
				TRACE("  Remote: " + http.getRemoteAddress());
				TRACE("  Headers: " + new HashMap(http.getRequestHeaders()));
				try (InputStream input = http.getRequestBody()) {
					body = new String(input.readAllBytes(), UTF_8);
				}
				if (!body.isEmpty()) TRACE("  Body: " + body);
			}
		}

		public HashMap<String, String> decodeQuery () throws IOException {
			String qs = http.getRequestURI().getQuery();
			if (qs == null) return new HashMap(0);
			HashMap<String, String> result = new HashMap();
			int last = 0, next, length = qs.length();
			while (last < length) {
				next = qs.indexOf('&', last);
				if (next == -1) next = length;
				if (next > last) {
					int eqPos = qs.indexOf('=', last);
					if (eqPos < 0 || eqPos > next)
						result.put(URLDecoder.decode(qs.substring(last, next), UTF_8), "");
					else
						result.put(URLDecoder.decode(qs.substring(last, eqPos), UTF_8),
							URLDecoder.decode(qs.substring(eqPos + 1, next), UTF_8));
				}
				last = next + 1;
			}
			return result;
		}

		public String requestBodyString () throws IOException {
			if (body != null) return body;
			try (InputStream input = http.getRequestBody()) {
				var reader = new InputStreamReader(input, UTF_8);
				var buffer = new StringBuilder(input.available());
				while (true) {
					int c = reader.read();
					if (c == -1) break;
					buffer.append((char)c);
				}
				return buffer.toString();
			}
		}

		public void responseContentType (String mimetype) {
			responseHeader("Content-Type", mimetype);
		}

		public void responseHeader (String key, String value) {
			if (trace) TRACE("Response header: " + key + " = " + value);
			http.getResponseHeaders().add(key, value);
		}

		public void response404 () throws IOException {
			response(404, "404 Not Found");
		}

		public void response503 () throws IOException {
			response(503, "503 Service Temporarily Unavailable");
		}

		public void responseJson (int code, String json) throws IOException {
			responseContentType("application/json");
			response(code, json);
		}

		public void response (int code, String body) throws IOException {
			if (trace) TRACE("Response: " + (body.startsWith(Integer.toString(code)) ? "" : code + " ") + body);
			if (body.isEmpty())
				http.sendResponseHeaders(code, -1);
			else {
				byte[] bytes = body.getBytes();
				http.sendResponseHeaders(code, bytes.length);
				http.getResponseBody().write(bytes);
			}
		}

		public void response (int code, InputStream input, int length) throws IOException {
			if (trace) TRACE("Response: " + code + " <stream: " + byteSize(length) + ">");
			http.sendResponseHeaders(code, length);
			if (length > 0) copyStream(input, http.getResponseBody(), new byte[Math.min(length, 8192)]);
		}

		public OutputStream responseStream (int code, int length) throws IOException {
			http.sendResponseHeaders(code, length);
			return http.getResponseBody();
		}

		public boolean hasResponse () {
			return http.getResponseCode() != -1;
		}

		public void close () throws IOException {
			if (!hasResponse()) http.sendResponseHeaders(204, -1); // No response.
			http.close();
		}

		public String toString () {
			return http.getRequestMethod() + " " + http.getRequestURI();
		}
	}

	static void TRACE (String message) {
		if (trace) log.info("[trace] " + message);
	}

	static void copyStream (InputStream input, OutputStream output, byte[] buffer) throws IOException {
		if (buffer.length == 0) throw new RuntimeException();
		int bytesRead;
		while ((bytesRead = input.read(buffer)) != -1)
			output.write(buffer, 0, bytesRead);
	}

	static String byteSize (long bytes) {
		if (bytes < 1024) return bytes + " B"/**/;
		int exp = (int)(Math.log(bytes) / Math.log(1024));
		return String.format(Locale.ROOT, "%d (%.1f %siB)"/**/, bytes, bytes / Math.pow(1024, exp), "kMGTPE".charAt(exp - 1));
	}

	static public ThreadPoolExecutor newThreadPool (int maxThreads, int liveSeconds, String name, boolean daemon) {
		var pool = new ThreadPoolExecutor(maxThreads, maxThreads, liveSeconds, TimeUnit.SECONDS, //
			new LinkedBlockingQueue(), //
			new ThreadFactory() {
				final AtomicInteger id = new AtomicInteger(1);

				public Thread newThread (Runnable runnable) {
					var thread = new Thread(runnable, maxThreads == 1 ? name : name + '-' + id.getAndIncrement());
					thread.setDaemon(daemon);
					return thread;
				}
			}, new AbortPolicy());
		pool.allowCoreThreadTimeOut(true);
		return pool;
	}

	static public void main (String[] args) throws Throwable {
		new WebServer(9001, newThreadPool(3, 10, "test", false)) {
			public void handle (String path, Exchange exchange) throws Throwable {
				System.out.println(path);
			}
		}.start();
	}
}
