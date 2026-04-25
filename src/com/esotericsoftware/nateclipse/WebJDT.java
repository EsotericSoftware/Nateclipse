
package com.esotericsoftware.nateclipse;

import static com.esotericsoftware.nateclipse.jdt.JdtLookup.*;
import static com.esotericsoftware.nateclipse.jdt.JdtUtils.*;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.concurrent.Executor;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.launching.JavaRuntime;

import com.esotericsoftware.nateclipse.jdt.JdtUtils.Params;
import com.esotericsoftware.nateclipse.jdt.OrganizeImportsHandler;
import com.esotericsoftware.nateclipse.jdt.SuperMethodCollector;
import com.esotericsoftware.nateclipse.utils.FileCache;
import com.esotericsoftware.nateclipse.utils.Json;
import com.esotericsoftware.nateclipse.utils.WebServer;

public class WebJDT extends WebServer {
	static final int defaultLimit = 50;

	final FileCache fileCache = new FileCache();
	final OrganizeImportsHandler organizeImports = new OrganizeImportsHandler();

	public WebJDT (int port, Executor executor) {
		super(port, executor);
	}

	public void handle (String path, Exchange exchange) throws Throwable {
		switch (path) {
		case "/java_errors" -> java_errors(exchange);
		case "/java_references" -> java_references(exchange);
		case "/java_hierarchy" -> java_hierarchy(exchange);
		case "/java_type" -> java_type(exchange);
		case "/java_members" -> java_members(exchange);
		case "/java_method" -> java_method(exchange);
		case "/java_callers" -> java_callers(exchange);
		case "/java_resolve_type" -> java_resolve_type(exchange);
		case "/java_organize_imports" -> organizeImports.handle(exchange);
		case "/java_classpath" -> java_classpath(exchange);
		case "/java_enclosing" -> java_enclosing(exchange);
		default -> exchange.response404();
		}
	}

	void java_errors (Exchange exchange) throws Throwable {
		var p = new Params(exchange);
		String projectName = p.get("project");
		int limit = p.intOpt("limit", defaultLimit);
		boolean unlimited = p.bool("unlimited");

		var root = ResourcesPlugin.getWorkspace().getRoot();

		IResource target;
		if (projectName != null) {
			var project = root.getProject(projectName);
			if (!project.exists()) {
				error(exchange, 404, "Project not found: " + projectName);
				return;
			}
			target = project;
		} else
			target = root;

		target.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
		Job.getJobManager().join(ResourcesPlugin.FAMILY_AUTO_BUILD, new NullProgressMonitor());

		var markers = target.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
		Arrays.sort(markers, (a, b) -> {
			int c = a.getResource().getFullPath().toString().compareTo(b.getResource().getFullPath().toString());
			if (c != 0) return c;
			return Integer.compare(a.getAttribute(IMarker.LINE_NUMBER, 0), b.getAttribute(IMarker.LINE_NUMBER, 0));
		});

		// Cache source per file for context lines.
		var sourceCache = new HashMap<IResource, String>();

		var json = new Json();
		json.object();
		json.array("errors");
		int total = 0;
		for (var marker : markers) {
			int severity = marker.getAttribute(IMarker.SEVERITY, -1);
			if (severity != IMarker.SEVERITY_ERROR && severity != IMarker.SEVERITY_WARNING) continue;
			total++;
			if (!unlimited && total > limit) continue; // Count but don't emit.

			var resource = marker.getResource();
			var location = resource.getLocation();
			String fp = location != null ? location.toOSString() : resource.getFullPath().toOSString();
			int line = marker.getAttribute(IMarker.LINE_NUMBER, -1);
			String message = marker.getAttribute(IMarker.MESSAGE, "");

			json.object();
			json.set("project", resource.getProject().getName());
			json.set("file", fp);
			json.set("line", line);
			json.set("severity", severity == IMarker.SEVERITY_ERROR ? "error" : "warning");
			json.set("message", message);

			// Context lines.
			if (line > 0) {
				var source = sourceCache.computeIfAbsent(resource, r -> {
					try {
						var el = JavaCore.create(r);
						if (el instanceof ICompilationUnit cu) {
							var buf = cu.getBuffer();
							if (buf != null) return buf.getContents();
						}
					} catch (Exception ignored) {
					}
					return null;
				});
				if (source != null) json.set("context", contextLines(source, line, 1, 1));
			}

			json.pop();
		}
		json.pop();
		writeLimited(json, total, limit, unlimited);
		json.pop();
		exchange.responseJson(200, json.toString());
	}

	void java_references (Exchange exchange) throws Throwable {
		var p = new Params(exchange);
		String projectName = p.get("project");
		String typeName = p.require("type");
		if (typeName == null) return;
		String memberName = p.get("member");
		String paramTypes = p.get("paramTypes");
		String fileFilter = p.get("file");
		String access = p.get("access");
		int limit = p.intOpt("limit", defaultLimit);
		boolean unlimited = p.bool("unlimited");

		var type = resolveTypeOrError(exchange, projectName, typeName);
		if (type == null) return;

		var element = findMember(type, memberName, paramTypes);
		if (element == null) {
			error(exchange, 404, "Type " + type.getFullyQualifiedName() + " found, doesn't have member: " + memberName);
			return;
		}

		// Validate fileFilter: missing file is 404, else an empty result is ambiguous.
		if (fileFilter != null) {
			var found = new boolean[] {false};
			IResource target = projectName != null ? ResourcesPlugin.getWorkspace().getRoot().getProject(projectName)
				: ResourcesPlugin.getWorkspace().getRoot();
			if (target.exists()) {
				IResourceVisitor visitor = res -> {
					if (found[0]) return false;
					if (res.getType() == IResource.FILE && filePath(res).contains(fileFilter)) {
						found[0] = true;
						return false;
					}
					return true;
				};
				target.accept(visitor);
			}
			if (!found[0]) {
				error(exchange, 404, "File not found: " + fileFilter);
				return;
			}
		}

		int searchFor = IJavaSearchConstants.REFERENCES;
		if ("write".equals(access))
			searchFor = IJavaSearchConstants.WRITE_ACCESSES;
		else if ("read".equals(access)) //
			searchFor = IJavaSearchConstants.READ_ACCESSES;
		var scope = searchScope(projectName);
		var matches = new ArrayList<SearchMatch>();
		var requestor = matchCollector(matches);
		ArrayList<SearchMatch> deduped = matches;
		if (access == null) {
			// WRITE_ACCESSES includes initializers that REFERENCES misses, search both and dedupe.
			search(SearchPattern.createPattern(element, IJavaSearchConstants.WRITE_ACCESSES), scope, requestor);
			search(SearchPattern.createPattern(element, IJavaSearchConstants.REFERENCES), scope, requestor);
			// Remove duplicates, keeping order (writes first, then remaining references).
			deduped = new ArrayList<SearchMatch>();
			var dedupeSeen = new LinkedHashSet<String>();
			for (var m : matches)
				if (m.getResource() != null && dedupeSeen.add(m.getResource().getFullPath() + ":" + m.getOffset())) deduped.add(m);
		} else {
			search(SearchPattern.createPattern(element, searchFor), scope, requestor);
		}

		var json = new Json();
		json.object();
		json.array("references");
		int total = 0;
		// Dedupe by file+line so multiple references on the same line (e.g. a write + read in `x = foo.x`)
		// collapse to one visible entry, matching how users scan results.
		var seenFileLine = new LinkedHashSet<String>();
		for (var match : deduped) {
			var resource = match.getResource();
			if (resource == null) continue;
			String fp = filePath(resource);

			if (fileFilter != null && !fileFilter.isEmpty() && !fp.contains(fileFilter)) continue;

			var source = getSource(match);
			int line = source != null ? lineNumber(source, match.getOffset()) : 0;
			if (line > 0 && !seenFileLine.add(fp + ":" + line)) continue;

			total++;
			if (!unlimited && total > limit) continue;

			json.object();
			json.set("file", fp);
			if (match.getElement() instanceof IMethod enclosing) {
				json.set("enclosingType", enclosing.getDeclaringType().getFullyQualifiedName());
				json.set("enclosingMethod", enclosing.getElementName());
			} else if (match.getElement() instanceof IMember enclosing) {
				json.set("enclosingType", enclosing.getDeclaringType().getFullyQualifiedName());
			}
			if (source != null) {
				json.set("line", line);
				json.set("context", contextLine(source, match.getOffset()));
			}
			json.pop();
		}
		json.pop();
		writeLimited(json, total, limit, unlimited);
		writeWarning(json, type);
		json.pop();
		exchange.responseJson(200, json.toString());
	}

	void java_hierarchy (Exchange exchange) throws Throwable {
		var p = new Params(exchange);
		String projectName = p.get("project");
		String typeName = p.require("type");
		if (typeName == null) return;
		String direction = p.getOrDefault("direction", "all");
		String methodName = p.get("method");
		String methodParamTypes = p.get("paramTypes");

		var type = resolveTypeOrError(exchange, projectName, typeName);
		if (type == null) return;

		var hierarchy = type.newTypeHierarchy(new NullProgressMonitor());
		IType[] types = switch (direction) {
		case "super" -> hierarchy.getAllSupertypes(type);
		case "sub" -> hierarchy.getAllSubtypes(type);
		default -> hierarchy.getAllTypes();
		};

		var json = new Json();
		json.object();
		json.array("types");
		int emitted = 0;
		for (var t : types) {
			if (t.getFullyQualifiedName().equals("java.lang.Object")) continue;
			IMethod override = null;
			if (methodName != null) {
				override = findMethod(t, methodName, methodParamTypes);
				if (override == null) continue;
			}

			emitted++;
			json.object();
			json.set("type", t.getFullyQualifiedName());
			var resource = t.getResource();
			if (resource != null) {
				json.set("file", filePath(resource));
				var cu = t.getCompilationUnit();
				if (cu != null) {
					var buffer = cu.getBuffer();
					if (buffer != null) {
						var range = override != null ? override.getNameRange() : t.getNameRange();
						if (range != null) json.set("line", lineNumber(buffer.getContents(), range.getOffset()));
					}
				}
			}
			json.pop();
		}
		json.pop();
		writeWarning(json, type);
		json.pop();
		exchange.responseJson(200, json.toString());
	}

	void java_type (Exchange exchange) throws Throwable {
		var p = new Params(exchange);
		String projectName = p.get("project");
		String typeName = p.require("type");
		if (typeName == null) return;
		int limit = p.intOpt("limit", 500);
		// `lines=true` forces buffer loads for per-type line numbers; only the java_type pi tool sets it.
		boolean includeLines = p.bool("lines");

		var types = searchTypes(projectName, typeName);
		if (types.isEmpty()) {
			error(exchange, 404, "Type not found: " + typeName);
			return;
		}

		var json = new Json();
		json.object();
		json.array("matches");
		boolean single = types.size() == 1;
		if (single) {
			// Single-match: emit full source and location info, as before. Callers (java_type tool) always want the body.
			var t = types.get(0);
			json.object();
			json.set("type", t.getFullyQualifiedName());
			var resource = t.getResource();
			if (resource != null) json.set("file", filePath(resource));

			FileCache.Entry cached = resource instanceof IFile f ? fileCache.get(f) : null;
			if (cached != null) {
				var range = t.getSourceRange();
				if (range != null && range.getOffset() >= 0 && range.getLength() > 0) {
					int startLine = FileCache.lineFor(cached.lineOffsets, range.getOffset());
					String typeSource = cached.source.substring(range.getOffset(), range.getOffset() + range.getLength());
					String[] typeLines = typeSource.split("\n", -1);
					int totalLines = typeLines.length;
					int shownLines = Math.min(limit, totalLines);

					String shownSource;
					if (shownLines < totalLines) {
						var sb = new StringBuilder();
						for (int i = 0; i < shownLines; i++) {
							if (i > 0) sb.append('\n');
							sb.append(typeLines[i]);
						}
						shownSource = sb.toString();
						json.set("truncated", true);
					} else
						shownSource = typeSource;

					json.set("line", startLine);
					json.set("endLine", startLine + shownLines - 1);
					json.set("totalLines", totalLines);
					json.set("source", shownSource);
				}
			}
			json.pop();
		} else {
			// Multi-match: dedupe types to their owning file and emit one entry per file. `lines` (parallel to `types`) is only
			// included when the caller opted in, because computing it requires loading each file's buffer.
			var byFile = new LinkedHashMap<IResource, ArrayList<IType>>();
			for (var t : types) {
				var r = t.getResource();
				if (r == null) continue;
				byFile.computeIfAbsent(r, k -> new ArrayList<>()).add(t);
			}
			for (var entry : byFile.entrySet()) {
				var resource = entry.getKey();
				var typesInFile = entry.getValue();
				json.object();
				json.set("file", filePath(resource));
				json.array("types");
				for (var t : typesInFile)
					json.value(t.getFullyQualifiedName());
				json.pop();
				if (includeLines && resource instanceof IFile f) {
					var cached = fileCache.get(f);
					if (cached != null) {
						json.array("lines");
						for (var t : typesInFile) {
							var nameRange = t.getNameRange();
							int line = nameRange != null && nameRange.getOffset() >= 0
								? FileCache.lineFor(cached.lineOffsets, nameRange.getOffset())
								: 0;
							json.value(line);
						}
						json.pop();
					}
				}
				json.pop();
			}
		}
		json.pop();
		if (single) writeWarning(json, types.get(0));
		json.pop();
		exchange.responseJson(200, json.toString());
	}

	void java_members (Exchange exchange) throws Throwable {
		var p = new Params(exchange);
		String projectName = p.get("project");
		String typeName = p.require("type");
		if (typeName == null) return;

		var type = resolveTypeOrError(exchange, projectName, typeName);
		if (type == null) return;

		var hierarchy = type.newTypeHierarchy(new NullProgressMonitor());
		var allTypes = new ArrayList<IType>();
		allTypes.add(type);
		for (var superType : hierarchy.getAllSupertypes(type))
			if (!superType.getFullyQualifiedName().equals("java.lang.Object")) allTypes.add(superType);

		var json = new Json();
		json.object();
		json.array("entries");
		for (var t : allTypes) {
			json.object();
			json.set("type", t.getFullyQualifiedName());
			if (t.isInterface()) json.set("isInterface", true);
			var resource = t.getResource();
			if (resource != null) json.set("file", filePath(resource));

			String source = null;
			var cu = t.getCompilationUnit();
			if (cu != null) {
				var buffer = cu.getBuffer();
				if (buffer != null) source = buffer.getContents();
			}

			json.array("fields");
			for (var field : t.getFields()) {
				json.object();
				json.set("name", field.getElementName());
				json.set("type", Signature.toString(field.getTypeSignature()));
				var flags = Flags.toString(field.getFlags());
				if (!flags.isEmpty()) json.set("flags", flags);
				if (source != null) {
					var range = field.getSourceRange();
					if (range != null) json.set("line", lineNumber(source, range.getOffset()));
				}
				json.pop();
			}
			json.pop();

			json.array("methods");
			for (var method : t.getMethods()) {
				json.object();
				json.set("name", method.getElementName());
				if (!method.isConstructor()) json.set("returnType", Signature.toString(method.getReturnType()));
				var pt = method.getParameterTypes();
				var pn = method.getParameterNames();
				var params = new StringBuilder();
				for (int i = 0; i < pt.length; i++) {
					if (i > 0) params.append(", ");
					params.append(Signature.toString(pt[i]));
					params.append(' ');
					params.append(pn[i]);
				}
				json.set("parameters", params.toString());
				var flags = Flags.toString(method.getFlags());
				if (!flags.isEmpty()) json.set("flags", flags);
				if (source != null) {
					var range = method.getSourceRange();
					if (range != null) json.set("line", lineNumber(source, range.getOffset()));
				}
				json.pop();
			}
			json.pop();
			json.pop();
		}
		json.pop();
		writeWarning(json, type);
		json.pop();
		exchange.responseJson(200, json.toString());
	}

	void java_method (Exchange exchange) throws Throwable {
		var p = new Params(exchange);
		String projectName = p.get("project");
		String typeName = p.require("type");
		if (typeName == null) return;
		String methodName = p.require("method");
		if (methodName == null) return;
		String paramTypes = p.get("paramTypes");

		var type = resolveTypeOrError(exchange, projectName, typeName);
		if (type == null) return;

		var method = findMethodInHierarchy(type, methodName, paramTypes);
		if (method == null) {
			error(exchange, 404, "Type " + type.getFullyQualifiedName() + " found, doesn't have method: " + methodName);
			return;
		}

		var declaringType = method.getDeclaringType();
		boolean inherited = declaringType != null && !declaringType.getFullyQualifiedName().equals(type.getFullyQualifiedName());

		var cu = method.getCompilationUnit();
		if (cu == null) {
			error(exchange, 404, "No source available");
			return;
		}
		var buffer = cu.getBuffer();
		if (buffer == null) {
			error(exchange, 404, "No source available");
			return;
		}

		var source = buffer.getContents();
		var range = method.getSourceRange();
		if (range == null) {
			error(exchange, 404, "No source range available");
			return;
		}

		int startLine = lineNumber(source, range.getOffset());
		String methodSource = source.substring(range.getOffset(), range.getOffset() + range.getLength());

		var supers = SuperMethodCollector.collectSuperMethods(cu, method);

		var json = new Json();
		json.object();
		json.set("type", declaringType != null ? declaringType.getFullyQualifiedName() : type.getFullyQualifiedName());
		json.set("method", method.getElementName());
		if (inherited) json.set("inheritedBy", type.getFullyQualifiedName());
		var resource = cu.getResource();
		if (resource != null) json.set("file", filePath(resource));
		int endLine = startLine + methodSource.split("\n", -1).length - 1;
		json.set("line", startLine);
		json.set("endLine", endLine);
		json.set("source", methodSource);
		if (!supers.isEmpty()) {
			json.array("supers");
			for (var info : supers) {
				json.object();
				if (info.kind() != null) json.set("kind", info.kind());
				json.set("type", info.typeName());
				json.set("method", info.methodName());
				if (info.file() != null) json.set("file", info.file());
				if (info.startLine() > 0) json.set("line", info.startLine());
				if (info.endLine() > 0) json.set("endLine", info.endLine());
				json.set("source", info.source());
				json.pop();
			}
			json.pop();
		}
		writeWarning(json, type);
		json.pop();
		exchange.responseJson(200, json.toString());
	}

	void java_callers (Exchange exchange) throws Throwable {
		var p = new Params(exchange);
		String projectName = p.get("project");
		String typeName = p.require("type");
		if (typeName == null) return;
		String methodName = p.require("method");
		if (methodName == null) return;
		String paramTypes = p.get("paramTypes");
		int limit = p.intOpt("limit", defaultLimit);
		boolean unlimited = p.bool("unlimited");

		var type = resolveTypeOrError(exchange, projectName, typeName);
		if (type == null) return;

		var methods = findMethods(type, methodName, paramTypes);
		if (methods.isEmpty()) {
			error(exchange, 404, "Type " + type.getFullyQualifiedName() + " found, doesn't have method: " + methodName);
			return;
		}

		var scope = searchScope(projectName);

		// Per-overload search so each match is attributed to one signature. Counts are full counts even when truncated.
		var overloadCounts = new LinkedHashMap<String, Integer>();
		var callers = new ArrayList<CallerRow>();
		for (var method : methods) {
			String overloadKey = methodKey(method);
			overloadCounts.putIfAbsent(overloadKey, 0);
			var matches = new ArrayList<SearchMatch>();
			search(SearchPattern.createPattern(method, IJavaSearchConstants.REFERENCES), scope, matchCollector(matches));

			// Dedupe by file+line within this overload: `foo(); foo();` produces two offsets we collapse.
			var seenFileLine = new LinkedHashSet<String>();
			for (var match : matches) {
				var resource = match.getResource();
				if (resource == null) continue;
				String fp = filePath(resource);
				var source = getSource(match);
				int line = source != null ? lineNumber(source, match.getOffset()) : 0;
				if (line > 0 && !seenFileLine.add(fp + ":" + line)) continue;

				String enclosingType = null, enclosingMethod = null;
				if (match.getElement() instanceof IMethod caller) {
					enclosingType = caller.getDeclaringType().getFullyQualifiedName();
					enclosingMethod = caller.getElementName();
				}
				callers.add(new CallerRow(fp, line, source != null ? contextLine(source, match.getOffset()) : null, enclosingType,
					enclosingMethod, overloadKey));
				overloadCounts.merge(overloadKey, 1, Integer::sum);
			}
		}

		boolean multipleOverloads = overloadCounts.size() > 1;
		int total = callers.size();

		var json = new Json();
		json.object();
		json.array("callers");
		int shown = 0;
		boolean limited = false;
		for (var c : callers) {
			if (!unlimited && shown >= limit) {
				limited = true;
				break;
			}
			shown++;
			json.object();
			json.set("file", c.file());
			if (c.enclosingType() != null) json.set("enclosingType", c.enclosingType());
			if (c.enclosingMethod() != null) json.set("enclosingMethod", c.enclosingMethod());
			if (c.line() > 0) json.set("line", c.line());
			if (c.context() != null) json.set("context", c.context());
			if (multipleOverloads) json.set("overload", c.overload());
			json.pop();
		}
		json.pop();
		if (multipleOverloads) {
			json.array("overloads");
			for (var e : overloadCounts.entrySet()) {
				json.object();
				json.set("signature", e.getKey());
				json.set("count", e.getValue());
				json.pop();
			}
			json.pop();
		}
		json.set("total", total);
		if (limited) json.set("limited", true);
		writeWarning(json, type);
		json.pop();
		exchange.responseJson(200, json.toString());
	}

	void java_resolve_type (Exchange exchange) throws Throwable {
		var p = new Params(exchange);
		var type = resolveTypeOrError(exchange, p.get("project"), p.get("type"));
		if (type == null) return;
		var resource = type.getResource();
		var json = new Json();
		json.object();
		json.set("type", type.getFullyQualifiedName());
		if (resource != null) json.set("file", filePath(resource));
		json.pop();
		exchange.responseJson(200, json.toString());
	}

	void java_classpath (Exchange exchange) throws Throwable {
		var p = new Params(exchange);
		String projectsParam = p.require("projects");
		if (projectsParam == null) return;

		var projectNames = new ArrayList<String>();
		for (var token : projectsParam.split(",")) {
			var name = token.trim();
			if (!name.isEmpty()) projectNames.add(name);
		}
		if (projectNames.isEmpty()) {
			error(exchange, 400, "Missing parameter: projects");
			return;
		}

		var seen = new LinkedHashSet<String>();
		for (var projectName : projectNames) {
			var project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
			if (!project.exists()) {
				error(exchange, 404, "Project not found: " + projectName);
				return;
			}
			var javaProject = JavaCore.create(project);
			var paths = JavaRuntime.computeDefaultRuntimeClassPath(javaProject);
			for (var path : paths)
				seen.add(path);
		}

		var file = File.createTempFile("cp-" + projectNames.get(0) + "-", ".txt");
		file.deleteOnExit();
		try (var writer = new FileWriter(file)) {
			writer.write("-cp");
			writer.write(System.lineSeparator());
			var sb = new StringBuilder();
			for (var path : seen) {
				if (sb.length() > 0) sb.append(File.pathSeparatorChar);
				sb.append(path);
			}
			writer.write(sb.toString());
		}

		var json = new Json();
		json.object();
		json.set("file", file.getAbsolutePath().replace('\\', '/'));
		json.pop();
		exchange.responseJson(200, json.toString());
	}

	/** Given (file, 1-based lines) pairs, returns the innermost enclosing named type (and method, if any) for each line. Anonymous
	 * and local types are skipped so matches inside lambdas / anonymous inner classes report the outer named method. Lines outside
	 * any named type (package/import/top-of-file) are omitted from the response.
	 * <p>
	 * Batch mode: request body is one line per file in the form <code>&lt;filepath&gt;\t&lt;line1&gt;,&lt;line2&gt;,...</code>.
	 * Enclosures in the response are tagged with their file so the caller can re-associate them.
	 * <p>
	 * Back-compat single-file mode: <code>?file=...&amp;lines=1,2,3</code> without a body still works; enclosures omit the file
	 * field. */
	void java_enclosing (Exchange exchange) throws Throwable {
		// Collect (file, lines-csv) pairs from either the POST body or fall back to query params for single-file callers.
		var requests = new ArrayList<String[]>();
		String body = exchange.requestBodyString();
		boolean batched = body != null && !body.isEmpty();
		if (batched) {
			for (var entryLine : body.split("\n")) {
				if (entryLine.isEmpty()) continue;
				int tab = entryLine.indexOf('\t');
				if (tab <= 0 || tab == entryLine.length() - 1) continue;
				requests.add(new String[] {entryLine.substring(0, tab), entryLine.substring(tab + 1)});
			}
		} else {
			var p = new Params(exchange);
			String fp = p.get("file");
			String linesStr = p.get("lines");
			if (fp != null && linesStr != null) requests.add(new String[] {fp, linesStr});
		}

		var json = new Json();
		json.object();
		json.array("enclosures");

		var workspaceRoot = ResourcesPlugin.getWorkspace().getRoot();
		for (var req : requests) {
			String fp = req[0];
			String linesStr = req[1];
			var ifile = workspaceRoot.getFileForLocation(Path.fromOSString(fp));
			if (ifile == null || !ifile.exists()) continue;
			if (!(JavaCore.create(ifile) instanceof ICompilationUnit cu)) continue;
			var cached = fileCache.get(ifile);
			if (cached == null) continue;
			int lineCount = cached.lineOffsets.length;

			for (var token : linesStr.split(",")) {
				int line;
				try {
					line = Integer.parseInt(token.trim());
				} catch (NumberFormatException ignored) {
					continue;
				}
				if (line < 1 || line > lineCount) continue;
				int offset = cached.lineOffsets[line - 1];
				IMethod method = null;
				IType type = null;
				for (IJavaElement el = cu.getElementAt(offset); el != null; el = el.getParent()) {
					if (el instanceof IMethod m && method == null) {
						var dt = m.getDeclaringType();
						if (dt != null && !dt.isAnonymous() && !dt.isLocal()) {
							method = m;
							type = dt;
							break;
						}
					} else if (el instanceof IType t) {
						if (!t.isAnonymous() && !t.isLocal()) {
							type = t;
							break;
						}
					}
				}
				if (type == null) continue;
				json.object();
				if (batched) json.set("file", fp);
				json.set("line", line);
				json.set("type", type.getFullyQualifiedName());
				if (method != null) json.set("method", method.getElementName());
				json.pop();
			}
		}

		json.pop();
		json.pop();
		exchange.responseJson(200, json.toString());
	}

	record CallerRow (String file, int line, String context, String enclosingType, String enclosingMethod, String overload) {}
}
