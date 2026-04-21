
package com.esotericsoftware.nateclipse.jdt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;

import com.esotericsoftware.nateclipse.utils.Json;
import com.esotericsoftware.nateclipse.utils.WebServer.Exchange;

/** Shared plumbing used by WebJDT handlers: query-param parsing, response helpers, search-engine shortcuts, and small
 * source/offset utilities. Everything here is static and stateless. */
public class JdtUtils {
	public static final SearchParticipant[] PARTICIPANTS = {SearchEngine.getDefaultSearchParticipant()};

	private JdtUtils () {
	}

	// ---- Query parameters ----

	public static class Params {
		public final Exchange exchange;
		public final HashMap<String, String> map;

		public Params (Exchange exchange) throws IOException {
			this.exchange = exchange;
			this.map = exchange.decodeQuery();
		}

		/** Null for missing or empty. */
		public String get (String name) {
			var v = map.get(name);
			return (v == null || v.isEmpty()) ? null : v;
		}

		public String getOrDefault (String name, String defaultValue) {
			var v = get(name);
			return v != null ? v : defaultValue;
		}

		/** Sends 400 and returns null when missing. Caller must return if null. */
		public String require (String name) throws Exception {
			var v = get(name);
			if (v == null) error(exchange, 400, "Missing parameter: " + name);
			return v;
		}

		public int intOpt (String name, int defaultValue) {
			var v = map.get(name);
			if (v == null || v.isEmpty()) return defaultValue;
			try {
				return Integer.parseInt(v);
			} catch (NumberFormatException e) {
				return defaultValue;
			}
		}

		public boolean bool (String name) {
			return "true".equals(map.get(name));
		}
	}

	// ---- Responses ----

	public static void error (Exchange exchange, int code, String message) throws Exception {
		exchange.responseJson(code, "{\"error\":" + Json.quote(message) + "}");
	}

	public static void writeLimited (Json json, int total, int limit, boolean unlimited) {
		json.set("total", total);
		if (!unlimited && total > limit) json.set("limited", true);
	}

	/** Writes a "warning" field when the type's file has compile errors. */
	public static void writeWarning (Json json, IType type) throws Exception {
		if (type == null) return;
		var resource = type.getResource();
		if (resource == null) return;
		for (var marker : resource.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO))
			if (marker.getAttribute(IMarker.SEVERITY, -1) == IMarker.SEVERITY_ERROR) {
				json.set("warning", "File has compile errors, results may be incomplete");
				return;
			}
	}

	// ---- Source / offset helpers ----

	public static String filePath (IResource resource) {
		var location = resource.getLocation();
		return location != null ? location.toOSString() : resource.getFullPath().toOSString();
	}

	public static int lineNumber (String source, int offset) {
		int line = 1;
		for (int i = 0; i < offset && i < source.length(); i++)
			if (source.charAt(i) == '\n') line++;
		return line;
	}

	public static String contextLine (String source, int offset) {
		if (source.isEmpty() || offset < 0 || offset >= source.length()) return "";
		int start = offset > 0 ? source.lastIndexOf('\n', offset - 1) + 1 : 0;
		int end = source.indexOf('\n', offset);
		if (end == -1) end = source.length();
		return source.substring(start, end).trim();
	}

	/** Extract lines around a 1-based line number. */
	public static String contextLines (String source, int lineNum, int before, int after) {
		var lines = source.split("\n", -1);
		int start = Math.max(0, lineNum - 1 - before);
		int end = Math.min(lines.length, lineNum + after);
		var sb = new StringBuilder();
		for (int i = start; i < end; i++) {
			if (sb.length() > 0) sb.append('\n');
			sb.append(lines[i]);
		}
		return sb.toString();
	}

	public static String getSource (SearchMatch match) throws JavaModelException {
		if (match.getElement() instanceof IJavaElement element) {
			var cu = (ICompilationUnit)element.getAncestor(IJavaElement.COMPILATION_UNIT);
			if (cu != null) {
				var buffer = cu.getBuffer();
				if (buffer != null) return buffer.getContents();
			}
		}
		return null;
	}

	// ---- Search helpers ----

	public static void search (SearchPattern pattern, IJavaSearchScope scope, SearchRequestor requestor) throws CoreException {
		new SearchEngine().search(pattern, PARTICIPANTS, scope, requestor, new NullProgressMonitor());
	}

	/** Collects accurate matches into the given list. */
	public static SearchRequestor matchCollector (ArrayList<SearchMatch> out) {
		return new SearchRequestor() {
			public void acceptSearchMatch (SearchMatch match) {
				if (match.getAccuracy() == SearchMatch.A_ACCURATE) out.add(match);
			}
		};
	}

	/** Collects accurate source-type matches (binaries excluded). */
	public static SearchRequestor sourceTypeCollector (ArrayList<IType> out) {
		return new SearchRequestor() {
			public void acceptSearchMatch (SearchMatch match) {
				if (match.getAccuracy() == SearchMatch.A_ACCURATE && match.getElement() instanceof IType t && !t.isBinary())
					out.add(t);
			}
		};
	}
}
