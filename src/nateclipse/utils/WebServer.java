
package nateclipse.utils;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor.AbortPolicy;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public abstract class WebServer implements HttpHandler {
	static public final boolean trace = true;
	static private final Charset UTF_8 = StandardCharsets.UTF_8;

	private final WebServerSettings settings;
	private final Executor executor;
	private final HashMap<String, String> mimetypes = new HashMap();
	protected HttpServer server;

	public WebServer (WebServerSettings settings, Executor executor) {
		this.settings = settings;
		this.executor = executor;

		mimetypes.put("txt", "text/plain");
		mimetypes.put("html", "text/html");
		mimetypes.put("css", "text/css");
		mimetypes.put("json", "application/json");
		mimetypes.put("png", "image/png");
		mimetypes.put("jpg", "image/jpeg");
		mimetypes.put("gif", "image/gif");
		mimetypes.put("js", "text/javascript");
	}

	public void start () {
		try {
			server = HttpServer.create(new InetSocketAddress(settings.port), 0);
			server.setExecutor(executor);
			server.createContext("/", this);
			server.start();
			INFO("Listening on port: TCP " + server.getAddress().getPort());
		} catch (Throwable ex) {
			ERROR("Unable to start web server: TCP " + settings.port, ex);
		}
	}

	abstract public boolean handle (String path, Exchange exchange) throws Throwable;

	public File root (String path) throws IOException {
		return new File(settings.root, path.replace("..", "xxx"));
	}

	public void handle (HttpExchange httpExchange) throws IOException {
		var exchange = new Exchange(httpExchange);
		try {
			if (handle(exchange.path, exchange)) return;
			if (settings.root != null && serveFile(exchange, root(exchange.path))) return;
			exchange.response404();
		} catch (Throwable ex) {
			if (trace || ex.getMessage() == null || ( //
			!ex.getMessage().startsWith("An established connection was aborted")
				&& !ex.getMessage().startsWith("Connection reset by peer") //
			)) {
				ERROR("Error handling request: " + exchange, ex);
			}
			exchange.response503();
		} finally {
			if (exchange.autoClose) exchange.close();
		}
	}

	public boolean serveFile (Exchange exchange, File file) throws IOException {
		if (!file.exists()) return false;
		String mimetype = mimetypes.get(extension(file));
		if (mimetype == null) {
			ERROR("Unknown mimetype: " + file + " [" + exchange.http.getRemoteAddress() + "]");
			return false;
		}
		if (mimetype.isEmpty()) return false;
		try (InputStream input = new FileInputStream(file)) {
			exchange.responseContentType(mimetype);
			exchange.response(200, input, (int)file.length());
			return true;
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

		public void close () throws IOException {
			if (http.getResponseCode() == -1) http.sendResponseHeaders(204, -1); // No response.
			http.close();
		}

		public String toString () {
			return http.getRequestMethod() + " " + http.getRequestURI();
		}
	}

	static void ERROR (String message, Throwable ex) {
	}

	static void ERROR (String message) {
	}

	static void WARN (String message) {
	}

	static void INFO (String message) {
	}

	static void TRACE (String message) {
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

	static String extension (File file) {
		String name = file.getName();
		int dotIndex = name.lastIndexOf('.');
		if (dotIndex == -1) return "";
		return name.substring(dotIndex + 1);
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

	static public class WebServerSettings {
		public int port;
		public String root;
	}

	static public void main (String[] args) throws Throwable {
		var settings = new WebServerSettings();
		settings.port = 9001;
		settings.root = ".";
		new WebServer(settings, newThreadPool(3, 10, "test", false)) {
			public boolean handle (String path, Exchange exchange) throws Throwable {
				System.out.println(path);
				return false;
			}
		}.start();
	}
}
