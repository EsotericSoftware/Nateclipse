
package com.esotericsoftware.nateclipse;

import static java.nio.charset.StandardCharsets.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.concurrent.Executor;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IClassFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IOpenable;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.manipulation.OrganizeImportsOperation;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jdt.launching.JavaRuntime;

import com.esotericsoftware.nateclipse.utils.FileCache;
import com.esotericsoftware.nateclipse.utils.Json;
import com.esotericsoftware.nateclipse.utils.WebServer;

public class WebJDT extends WebServer {
	static final int defaultLimit = 50;

	private final Object organizeImportsLock = new Object();
	final FileCache fileCache = new FileCache();

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
		case "/java_organize_imports" -> java_organize_imports(exchange);
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

	/** Search for types by simple name, FQN, or wildcard pattern. Source types only. */
	ArrayList<IType> searchTypes (String projectName, String typeName) throws CoreException {
		boolean hasWildcards = typeName.contains("*") || typeName.contains("?");

		// FQN exact match: direct lookup first.
		if (typeName.contains(".") && !hasWildcards) {
			var type = findType(projectName, typeName);
			if (type != null && !type.isBinary()) {
				var types = new ArrayList<IType>();
				types.add(type);
				return types;
			}
		}

		int matchRule = hasWildcards ? SearchPattern.R_PATTERN_MATCH | SearchPattern.R_CASE_SENSITIVE
			: SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE;
		var pattern = SearchPattern.createPattern(typeName, IJavaSearchConstants.TYPE, IJavaSearchConstants.DECLARATIONS,
			matchRule);
		var types = new ArrayList<IType>();
		search(pattern, searchScope(projectName), sourceTypeCollector(types));
		return types;
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

		var method = findMethod(type, methodName, paramTypes);
		if (method == null) {
			error(exchange, 404, "Type " + type.getFullyQualifiedName() + " found, doesn't have method: " + methodName);
			return;
		}

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

		var supers = collectSuperMethods(cu, method);

		var json = new Json();
		json.object();
		json.set("type", type.getFullyQualifiedName());
		json.set("method", method.getElementName());
		var resource = type.getResource();
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

	/** Collects directly overridden method plus any super methods invoked in the method body. Order: directly overridden first,
	 * then any additional super.xxx(...) / super(...) targets in source order. Deduped by binding key. Uses JDT bindings for
	 * accurate resolution across overloads, generics, and inner classes. */
	ArrayList<SuperMethodInfo> collectSuperMethods (ICompilationUnit cu, IMethod method) throws JavaModelException {
		var results = new ArrayList<SuperMethodInfo>();

		var parser = ASTParser.newParser(AST.getJLSLatest());
		parser.setSource(cu);
		parser.setResolveBindings(true);
		parser.setBindingsRecovery(true);
		parser.setStatementsRecovery(true);
		var ast = (org.eclipse.jdt.core.dom.CompilationUnit)parser.createAST(new NullProgressMonitor());
		if (ast == null) return results;

		var nameRange = method.getNameRange();
		if (nameRange == null) return results;
		ASTNode node = NodeFinder.perform(ast, nameRange.getOffset(), nameRange.getLength());
		while (node != null && !(node instanceof MethodDeclaration))
			node = node.getParent();
		if (!(node instanceof MethodDeclaration methodDecl)) return results;
		var binding = methodDecl.resolveBinding();

		var seen = new LinkedHashMap<String, IMethodBinding>();
		var kinds = new HashMap<String, String>();

		// Directly overridden method (not applicable to constructors).
		if (binding != null && !binding.isConstructor()) {
			var overridden = findOverriddenMethod(binding);
			if (overridden != null) {
				var key = overridden.getKey();
				if (key != null) {
					seen.putIfAbsent(key, overridden);
					kinds.putIfAbsent(key, "overrides");
				}
			}
		}

		// Super calls inside the method body. Recurse into lambdas (same super) but not into nested types.
		methodDecl.accept(new ASTVisitor() {
			public boolean visit (SuperMethodInvocation n) {
				var b = n.resolveMethodBinding();
				if (b != null) {
					b = b.getMethodDeclaration();
					var key = b.getKey();
					if (key != null) {
						seen.putIfAbsent(key, b);
						kinds.putIfAbsent(key, "super");
					}
				}
				return true;
			}

			public boolean visit (SuperConstructorInvocation n) {
				var b = n.resolveConstructorBinding();
				if (b != null) {
					b = b.getMethodDeclaration();
					var key = b.getKey();
					if (key != null) {
						seen.putIfAbsent(key, b);
						kinds.putIfAbsent(key, "super");
					}
				}
				return true;
			}

			public boolean visit (TypeDeclaration n) {
				return false;
			}

			public boolean visit (AnonymousClassDeclaration n) {
				return false;
			}

			public boolean visit (EnumDeclaration n) {
				return false;
			}

			public boolean visit (RecordDeclaration n) {
				return false;
			}
		});

		for (var entry : seen.entrySet()) {
			var info = buildSuperInfo(entry.getValue(), kinds.getOrDefault(entry.getKey(), "super"));
			if (info != null) results.add(info);
		}
		return results;
	}

	/** Returns the direct super method a method overrides, preferring the superclass chain, then interfaces (BFS). */
	IMethodBinding findOverriddenMethod (IMethodBinding method) {
		var declaring = method.getDeclaringClass();
		if (declaring == null) return null;
		// Superclass chain first.
		for (var sc = declaring.getSuperclass(); sc != null; sc = sc.getSuperclass()) {
			for (var m : sc.getDeclaredMethods())
				if (method.overrides(m)) return m;
		}
		// Then interfaces (BFS across declaring type's interfaces and those of its superclass chain).
		var visited = new HashSet<String>();
		var queue = new ArrayDeque<ITypeBinding>();
		for (var iface : declaring.getInterfaces())
			queue.add(iface);
		for (var sc = declaring.getSuperclass(); sc != null; sc = sc.getSuperclass())
			for (var iface : sc.getInterfaces())
				queue.add(iface);
		while (!queue.isEmpty()) {
			var iface = queue.poll();
			if (iface == null) continue;
			var key = iface.getKey();
			if (key != null && !visited.add(key)) continue;
			for (var m : iface.getDeclaredMethods())
				if (method.overrides(m)) return m;
			for (var parent : iface.getInterfaces())
				queue.add(parent);
		}
		return null;
	}

	SuperMethodInfo buildSuperInfo (IMethodBinding binding, String kind) throws JavaModelException {
		var element = binding.getJavaElement();
		if (!(element instanceof IMethod superMethod)) return null;
		var range = superMethod.getSourceRange();
		var declaring = superMethod.getDeclaringType();

		String cuSource = null;
		String path = null;
		var root = (ITypeRoot)superMethod.getAncestor(IJavaElement.COMPILATION_UNIT);
		if (root == null) root = (ITypeRoot)superMethod.getAncestor(IJavaElement.CLASS_FILE);
		if (root instanceof IOpenable openable) {
			var buffer = openable.getBuffer();
			if (buffer != null) cuSource = buffer.getContents();
		}
		if (root != null) {
			var res = root.getResource();
			if (res != null)
				path = filePath(res);
			else if (root instanceof IClassFile) path = root.getPath().toOSString();
		}

		String typeName = declaring != null ? declaring.getFullyQualifiedName() : binding.getDeclaringClass().getQualifiedName();
		String methodName = superMethod.getElementName();
		if (cuSource != null && range != null && range.getOffset() >= 0 && range.getLength() > 0) {
			int startLine = lineNumber(cuSource, range.getOffset());
			String src = cuSource.substring(range.getOffset(), range.getOffset() + range.getLength());
			int endLine = startLine + src.split("\n", -1).length - 1;
			return new SuperMethodInfo(kind, typeName, methodName, path, startLine, endLine, src);
		}
		// No source available (e.g. binary with no attachment). Fall back to signature-only.
		var src = superMethod.getSource();
		return new SuperMethodInfo(kind, typeName, methodName, path, 0, 0, src != null ? src : signatureString(superMethod));
	}

	String signatureString (IMethod m) throws JavaModelException {
		var sb = new StringBuilder();
		int flags = m.getFlags();
		if (Flags.isPublic(flags))
			sb.append("public ");
		else if (Flags.isProtected(flags))
			sb.append("protected ");
		else if (Flags.isPrivate(flags)) sb.append("private ");
		if (Flags.isStatic(flags)) sb.append("static ");
		if (Flags.isAbstract(flags)) sb.append("abstract ");
		if (!m.isConstructor()) sb.append(Signature.getSignatureSimpleName(m.getReturnType())).append(' ');
		sb.append(m.getElementName()).append('(');
		var params = m.getParameterTypes();
		var names = m.getParameterNames();
		for (int i = 0; i < params.length; i++) {
			if (i > 0) sb.append(", ");
			sb.append(Signature.getSignatureSimpleName(params[i]));
			if (i < names.length) sb.append(' ').append(names[i]);
		}
		sb.append(");");
		return sb.toString();
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

	void java_organize_imports (Exchange exchange) throws Throwable {
		synchronized (organizeImportsLock) {
			java_organize_imports0(exchange);
		}
	}

	void java_organize_imports0 (Exchange exchange) throws Throwable {
		var p = new Params(exchange);
		String filePath = p.get("file");
		String typeName = p.get("type");
		String resolveStr = p.get("resolve");
		String preferStr = p.getOrDefault("prefer", "com.esotericsoftware,com.badlogic");

		// Resolve type to file if provided.
		if (typeName != null) {
			var type = resolveTypeOrError(exchange, p.get("project"), typeName);
			if (type == null) return;
			var resource = type.getResource();
			if (resource == null) {
				error(exchange, 404, "No source file");
				return;
			}
			filePath = filePath(resource);
		}

		if (filePath == null) {
			error(exchange, 400, "Missing parameter: file or type");
			return;
		}

		var ipath = Path.fromOSString(filePath);
		var ifile = ResourcesPlugin.getWorkspace().getRoot().getFileForLocation(ipath);
		if (ifile == null || !ifile.exists()) {
			error(exchange, 404, "File not found");
			return;
		}
		var javaElement = JavaCore.create(ifile);
		if (!(javaElement instanceof ICompilationUnit cu)) {
			error(exchange, 400, "Not a Java source file");
			return;
		}

		ifile.refreshLocal(IResource.DEPTH_ZERO, new NullProgressMonitor());
		Job.getJobManager().join(ResourcesPlugin.FAMILY_AUTO_BUILD, new NullProgressMonitor());

		for (var marker : ifile.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO)) {
			if (marker.getAttribute(IMarker.SEVERITY, -1) == IMarker.SEVERITY_ERROR
				&& marker.getAttribute(IMarker.MESSAGE, "").contains("Syntax error")) {
				error(exchange, 400, "Syntax errors, fix before organizing imports");
				return;
			}
		}

		var explicitResolve = new HashMap<String, String>();
		if (resolveStr != null) {
			for (var pair : resolveStr.split(",")) {
				var kv = pair.split(":", 2);
				if (kv.length == 2) explicitResolve.put(kv[0].trim(), kv[1].trim());
			}
		}

		var prefer = preferStr.split(",");
		for (int i = 0; i < prefer.length; i++)
			prefer[i] = prefer[i].trim();

		var conflicts = new ArrayList<String[]>();
		organizeImports(cu, explicitResolve, conflicts);

		if (conflicts.isEmpty()) {
			respondOrganizeImports(exchange, true, null);
			return;
		}

		if (conflicts.size() > 1) {
			respondOrganizeImports(exchange, false, conflicts);
			return;
		}

		// Exactly 1 conflict: try ALL candidates with a real build.
		var conflict = conflicts.get(0);
		String simpleName = conflict[0];

		byte[] originalContent;
		try (var in = ifile.getContents()) {
			originalContent = in.readAllBytes();
		}

		var working = new ArrayList<String>();
		for (int i = 1; i < conflict.length; i++) {
			var candidate = conflict[i];
			if (tryImportCandidate(ifile, cu, originalContent, explicitResolve, simpleName, candidate)) {
				working.add(candidate);
				if (working.size() > 1) break;
			}
		}

		if (working.size() == 1) {
			tryImportCandidate(ifile, cu, originalContent, explicitResolve, simpleName, working.get(0));
			respondOrganizeImports(exchange, true, null);
		} else {
			ifile.setContents(new ByteArrayInputStream(originalContent), IResource.FORCE, new NullProgressMonitor());
			cu.getBuffer().setContents(new String(originalContent, UTF_8));
			respondOrganizeImports(exchange, false, conflicts);
		}
	}

	/** Resets the file, sets explicitResolve[simpleName]=candidate, organizes imports, builds. Returns true if no errors after. */
	boolean tryImportCandidate (IFile ifile, ICompilationUnit cu, byte[] originalContent, HashMap<String, String> explicitResolve,
		String simpleName, String candidate) throws Exception {
		ifile.setContents(new ByteArrayInputStream(originalContent), IResource.FORCE, new NullProgressMonitor());
		cu.getBuffer().setContents(new String(originalContent, UTF_8));
		explicitResolve.put(simpleName, candidate);

		var workingCopy = cu.getWorkingCopy(new NullProgressMonitor());
		try {
			var op = new OrganizeImportsOperation(workingCopy, null, true, false, true, (openChoices, ranges) -> {
				var results = new TypeNameMatch[openChoices.length];
				for (int i = 0; i < openChoices.length; i++) {
					var choices = openChoices[i];
					String name = choices[0].getSimpleTypeName();
					if (explicitResolve.containsKey(name)) {
						String wanted = explicitResolve.get(name);
						for (var choice : choices)
							if (choice.getFullyQualifiedName().equals(wanted)) {
								results[i] = choice;
								break;
							}
					}
					if (results[i] == null) results[i] = choices[0];
				}
				return results;
			});
			op.run(new NullProgressMonitor());
			workingCopy.commitWorkingCopy(true, new NullProgressMonitor());
		} finally {
			workingCopy.discardWorkingCopy();
		}

		ifile.refreshLocal(IResource.DEPTH_ZERO, new NullProgressMonitor());
		Job.getJobManager().join(ResourcesPlugin.FAMILY_AUTO_BUILD, new NullProgressMonitor());
		for (var m : ifile.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO))
			if (m.getAttribute(IMarker.SEVERITY, -1) == IMarker.SEVERITY_ERROR) return false;
		return true;
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

	void organizeImports (ICompilationUnit cu, HashMap<String, String> explicitResolve, ArrayList<String[]> conflicts)
		throws Exception {

		if (!explicitResolve.isEmpty()) {
			var source = cu.getSource();
			var sb = new StringBuilder(source.length());
			for (var line : source.split("\n", -1)) {
				var trimmed = line.trim();
				if (trimmed.startsWith("import ") && !trimmed.startsWith("import static ")) {
					var fqn = trimmed.substring(7, trimmed.endsWith(";") ? trimmed.length() - 1 : trimmed.length()).trim();
					int dot = fqn.lastIndexOf('.');
					var simpleName = dot >= 0 ? fqn.substring(dot + 1) : fqn;
					if (explicitResolve.containsKey(simpleName)) continue;
				}
				if (sb.length() > 0) sb.append('\n');
				sb.append(line);
			}
			cu.getBuffer().setContents(sb.toString());
		}

		var workingCopy = cu.getWorkingCopy(new NullProgressMonitor());
		try {
			var op = new OrganizeImportsOperation(workingCopy, null, true, false, true, (openChoices, ranges) -> {
				var results = new TypeNameMatch[openChoices.length];
				for (int i = 0; i < openChoices.length; i++) {
					var choices = openChoices[i];
					String name = choices[0].getSimpleTypeName();
					if (explicitResolve.containsKey(name)) {
						String wanted = explicitResolve.get(name);
						for (var choice : choices)
							if (choice.getFullyQualifiedName().equals(wanted)) {
								results[i] = choice;
								break;
							}
					}
					if (results[i] == null) {
						var conflict = new String[choices.length + 1];
						conflict[0] = name;
						for (int j = 0; j < choices.length; j++)
							conflict[j + 1] = choices[j].getFullyQualifiedName();
						conflicts.add(conflict);
					}
				}
				if (!conflicts.isEmpty()) return null;
				return results;
			});
			try {
				op.run(new NullProgressMonitor());
			} catch (OperationCanceledException ignored) {
			}
			if (conflicts.isEmpty()) workingCopy.commitWorkingCopy(true, new NullProgressMonitor());
		} finally {
			workingCopy.discardWorkingCopy();
		}
	}

	int candidatePriority (String fqn, String[] prefer) {
		for (int i = 0; i < prefer.length; i++)
			if (fqn.startsWith(prefer[i] + ".")) return i;
		return prefer.length;
	}

	void respondOrganizeImports (Exchange exchange, boolean organized, ArrayList<String[]> conflicts) throws Exception {
		var json = new Json();
		json.object();
		json.set("organized", organized);
		if (conflicts != null && !conflicts.isEmpty()) {
			json.array("conflicts");
			for (var conflict : conflicts) {
				json.object();
				json.set("type", conflict[0]);
				json.array("choices");
				for (int i = 1; i < conflict.length; i++)
					json.value(conflict[i]);
				json.pop();
				json.pop();
			}
			json.pop();
		}
		json.pop();
		exchange.responseJson(200, json.toString());
	}

	// ---- General helpers ----

	void refreshAndBuild (IResource target) throws Exception {
		target.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
		Job.getJobManager().join(ResourcesPlugin.FAMILY_AUTO_BUILD, new NullProgressMonitor());
	}

	IResource resolveTarget (String projectName) {
		var root = ResourcesPlugin.getWorkspace().getRoot();
		if (projectName != null && !projectName.isEmpty()) {
			var project = root.getProject(projectName);
			return project.exists() ? project : null;
		}
		return root;
	}

	/** Resolve a type name that may be unqualified. Returns null if it responded with an error. */
	IType resolveTypeOrError (Exchange exchange, String projectName, String typeName) throws Exception {
		boolean hasWildcards = typeName.contains("*") || typeName.contains("?");

		// FQN without wildcards: try direct lookup first (fast path for the common case).
		if (typeName.contains(".") && !hasWildcards) {
			var type = findType(projectName, typeName);
			if (type != null) return type;
			// Fall through to search: handles unqualified nested references like "Outer.Inner".
		}

		// Search: exact or pattern match.
		int matchRule = hasWildcards ? SearchPattern.R_PATTERN_MATCH | SearchPattern.R_CASE_SENSITIVE
			: SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE;
		var pattern = SearchPattern.createPattern(typeName, IJavaSearchConstants.TYPE, IJavaSearchConstants.DECLARATIONS,
			matchRule);
		var scope = searchScope(projectName);
		var types = new ArrayList<IType>();
		search(pattern, scope, sourceTypeCollector(types));

		// No hit: retry with '.' and '$' swapped so "Outer.Inner" and "Outer$Inner" are interchangeable.
		if (types.isEmpty() && !hasWildcards && (typeName.indexOf('.') >= 0 || typeName.indexOf('$') >= 0)) {
			String swapped = typeName.indexOf('$') >= 0 ? typeName.replace('$', '.') : typeName.replace('.', '$');
			var alt = SearchPattern.createPattern(swapped, IJavaSearchConstants.TYPE, IJavaSearchConstants.DECLARATIONS, matchRule);
			search(alt, scope, sourceTypeCollector(types));
		}

		if (types.size() == 1) return types.get(0);
		if (types.isEmpty()) {
			error(exchange, 404, "Type not found: " + typeName);
			return null;
		}

		// Multiple matches: list FQNs.
		var sb = new StringBuilder("Ambiguous, use fully qualified name:\n");
		for (int i = 0; i < types.size(); i++) {
			if (i > 0) sb.append("\n");
			sb.append(types.get(i).getFullyQualifiedName());
		}
		error(exchange, 400, sb.toString());
		return null;
	}

	IType findType (String projectName, String qualifiedName) throws JavaModelException {
		// Try as-is first; exact match always wins.
		var type = findTypeExact(projectName, qualifiedName);
		if (type != null) return type;

		// Fallback: replace '.' with '$' from the right to handle nested types written with dot notation
		// (e.g. "Outer.Inner" -> "Outer$Inner"). Only succeeds if exactly one candidate resolves.
		if (qualifiedName.indexOf('.') >= 0) {
			var matches = new LinkedHashMap<String, IType>();
			var sb = new StringBuilder(qualifiedName);
			for (int i = sb.length() - 1; i >= 0; i--) {
				if (sb.charAt(i) != '.') continue;
				sb.setCharAt(i, '$');
				var candidate = findTypeExact(projectName, sb.toString());
				if (candidate != null) matches.putIfAbsent(candidate.getFullyQualifiedName(), candidate);
			}
			if (matches.size() == 1) return matches.values().iterator().next();
		}
		return null;
	}

	IType findTypeExact (String projectName, String qualifiedName) throws JavaModelException {
		var root = ResourcesPlugin.getWorkspace().getRoot();
		if (projectName != null && !projectName.isEmpty()) {
			var project = root.getProject(projectName);
			if (!project.exists()) return null;
			return JavaCore.create(project).findType(qualifiedName);
		}
		for (var jp : JavaCore.create(root).getJavaProjects()) {
			var t = jp.findType(qualifiedName);
			if (t != null) return t;
		}
		return null;
	}

	IMethod findMethod (IType type, String methodName, String paramTypes) throws JavaModelException {
		if (paramTypes != null && !paramTypes.isEmpty()) {
			var parts = paramTypes.split(",");
			var sigs = new String[parts.length];
			for (int i = 0; i < parts.length; i++)
				sigs[i] = Signature.createTypeSignature(parts[i].trim(), false);
			var method = type.getMethod(methodName, sigs);
			if (method.exists()) return method;
		}
		for (var method : type.getMethods())
			if (method.getElementName().equals(methodName)) return method;
		return null;
	}

	ArrayList<IMethod> findMethods (IType type, String methodName, String paramTypes) throws JavaModelException {
		var result = new ArrayList<IMethod>();
		if (paramTypes != null && !paramTypes.isEmpty()) {
			var method = findMethod(type, methodName, paramTypes);
			if (method != null) result.add(method);
			return result;
		}
		for (var method : type.getMethods())
			if (method.getElementName().equals(methodName)) result.add(method);
		return result;
	}

	String methodKey (IMethod m) throws JavaModelException {
		var sb = new StringBuilder();
		sb.append(m.getElementName()).append('(');
		var params = m.getParameterTypes();
		for (int i = 0; i < params.length; i++) {
			if (i > 0) sb.append(", ");
			sb.append(Signature.getSignatureSimpleName(params[i]));
		}
		sb.append(')');
		return sb.toString();
	}

	IJavaElement findMember (IType type, String memberName, String paramTypes) throws JavaModelException {
		if (memberName == null || memberName.isEmpty()) return type;
		var method = findMethod(type, memberName, paramTypes);
		if (method != null) return method;
		var field = type.getField(memberName);
		if (field.exists()) return field;
		return null;
	}

	IJavaSearchScope searchScope (String projectName) throws CoreException {
		// SOURCES | REFERENCED_PROJECTS: every endpoint here is source-oriented (references, callers, type declarations live in
		// source). Including APPLICATION_LIBRARIES / SYSTEM_LIBRARIES would force SearchEngine to walk every type in every JAR on
		// the classpath (JDK, all dependencies) for wildcard patterns like "*" or "Foo*", costing many seconds on larger
		// workspaces, only to have us filter the binaries out in the requestor.
		int mask = IJavaSearchScope.SOURCES | IJavaSearchScope.REFERENCED_PROJECTS;
		if (projectName != null && !projectName.isEmpty()) {
			var project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
			if (project.exists()) return SearchEngine.createJavaSearchScope(new IJavaElement[] {JavaCore.create(project)}, mask);
		}
		// createWorkspaceScope() includes binaries and has no mask overload; build the equivalent source-only scope ourselves
		// by enumerating open Java projects.
		var projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
		var elements = new ArrayList<IJavaElement>(projects.length);
		for (var p : projects) {
			if (!p.isOpen()) continue;
			if (!p.hasNature(JavaCore.NATURE_ID)) continue;
			var jp = JavaCore.create(p);
			if (jp != null) elements.add(jp);
		}
		return SearchEngine.createJavaSearchScope(elements.toArray(new IJavaElement[0]), mask);
	}

	String filePath (IResource resource) {
		var location = resource.getLocation();
		return location != null ? location.toOSString() : resource.getFullPath().toOSString();
	}

	int lineNumber (String source, int offset) {
		int line = 1;
		for (int i = 0; i < offset && i < source.length(); i++)
			if (source.charAt(i) == '\n') line++;
		return line;
	}

	String contextLine (String source, int offset) {
		if (source.isEmpty() || offset < 0 || offset >= source.length()) return "";
		int start = offset > 0 ? source.lastIndexOf('\n', offset - 1) + 1 : 0;
		int end = source.indexOf('\n', offset);
		if (end == -1) end = source.length();
		return source.substring(start, end).trim();
	}

	/** Extract lines around a 1-based line number. */
	String contextLines (String source, int lineNum, int before, int after) {
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

	String getSource (SearchMatch match) throws JavaModelException {
		if (match.getElement() instanceof IJavaElement element) {
			var cu = (ICompilationUnit)element.getAncestor(IJavaElement.COMPILATION_UNIT);
			if (cu != null) {
				var buffer = cu.getBuffer();
				if (buffer != null) return buffer.getContents();
			}
		}
		return null;
	}

	static void error (Exchange exchange, int code, String message) throws Exception {
		exchange.responseJson(code, "{\"error\":" + Json.quote(message) + "}");
	}

	// ---- Query parameters ----

	static class Params {
		final Exchange exchange;
		final HashMap<String, String> map;

		Params (Exchange exchange) throws IOException {
			this.exchange = exchange;
			this.map = exchange.decodeQuery();
		}

		/** Null for missing or empty. */
		String get (String name) {
			var v = map.get(name);
			return (v == null || v.isEmpty()) ? null : v;
		}

		String getOrDefault (String name, String defaultValue) {
			var v = get(name);
			return v != null ? v : defaultValue;
		}

		/** Sends 400 and returns null when missing. Caller must return if null. */
		String require (String name) throws Exception {
			var v = get(name);
			if (v == null) error(exchange, 400, "Missing parameter: " + name);
			return v;
		}

		int intOpt (String name, int defaultValue) {
			var v = map.get(name);
			if (v == null || v.isEmpty()) return defaultValue;
			try {
				return Integer.parseInt(v);
			} catch (NumberFormatException e) {
				return defaultValue;
			}
		}

		boolean bool (String name) {
			return "true".equals(map.get(name));
		}
	}

	// ---- Search helpers ----

	static final SearchParticipant[] PARTICIPANTS = {SearchEngine.getDefaultSearchParticipant()};

	static void search (SearchPattern pattern, IJavaSearchScope scope, SearchRequestor requestor) throws CoreException {
		new SearchEngine().search(pattern, PARTICIPANTS, scope, requestor, new NullProgressMonitor());
	}

	/** Collects accurate matches into the given list. */
	static SearchRequestor matchCollector (ArrayList<SearchMatch> out) {
		return new SearchRequestor() {
			public void acceptSearchMatch (SearchMatch match) {
				if (match.getAccuracy() == SearchMatch.A_ACCURATE) out.add(match);
			}
		};
	}

	/** Collects accurate source-type matches (binaries excluded). */
	static SearchRequestor sourceTypeCollector (ArrayList<IType> out) {
		return new SearchRequestor() {
			public void acceptSearchMatch (SearchMatch match) {
				if (match.getAccuracy() == SearchMatch.A_ACCURATE && match.getElement() instanceof IType t && !t.isBinary())
					out.add(t);
			}
		};
	}

	// ---- Response helpers ----

	static void writeLimited (Json json, int total, int limit, boolean unlimited) {
		json.set("total", total);
		if (!unlimited && total > limit) json.set("limited", true);
	}

	/** Writes a "warning" field when the type's file has compile errors. */
	static void writeWarning (Json json, IType type) throws Exception {
		if (type == null) return;
		var resource = type.getResource();
		if (resource == null) return;
		for (var marker : resource.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO))
			if (marker.getAttribute(IMarker.SEVERITY, -1) == IMarker.SEVERITY_ERROR) {
				json.set("warning", "File has compile errors, results may be incomplete");
				return;
			}
	}

	record CallerRow (String file, int line, String context, String enclosingType, String enclosingMethod, String overload) {}

	record SuperMethodInfo (
		String kind,
		String typeName,
		String methodName,
		String file,
		int startLine,
		int endLine,
		String source) {}
}
