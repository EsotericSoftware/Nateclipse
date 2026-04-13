
package nateclipse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.Executor;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.manipulation.OrganizeImportsOperation;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.jdt.core.search.TypeNameMatch;

import nateclipse.utils.Json;
import nateclipse.utils.WebServer;

public class WebJDT extends WebServer {
	private final Object organizeImportsLock = new Object();

	public WebJDT (WebServerSettings settings, Executor executor) {
		super(settings, executor);
	}

	public boolean handle (String path, Exchange exchange) throws Throwable {
		if (path.equals("/jdt_errors"))
			jdt_errors(exchange);
		else if (path.equals("/jdt_references"))
			jdt_references(exchange);
		else if (path.equals("/jdt_hierarchy"))
			jdt_hierarchy(exchange);
		else if (path.equals("/jdt_search_type"))
			jdt_search_type(exchange);
		else if (path.equals("/jdt_members"))
			jdt_members(exchange);
		else if (path.equals("/jdt_organize_imports"))
			jdt_organize_imports(exchange);
		else if (path.equals("/jdt_classpath"))
			jdt_classpath(exchange);
		else
			exchange.response404();
		return true;
	}

	void jdt_errors (Exchange exchange) throws Throwable {
		var query = exchange.decodeQuery();
		String projectName = query.get("project");

		var root = ResourcesPlugin.getWorkspace().getRoot();

		// Determine scope.
		IResource target;
		if (projectName != null && !projectName.isEmpty()) {
			var project = root.getProject(projectName);
			if (!project.exists()) {
				exchange.responseJson(404, "{\"error\":\"Project not found: " + Json.quote(projectName) + "\"}");
				return;
			}
			target = project;
		} else
			target = root;

		// Refresh to pick up external filesystem changes.
		target.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());

		// Wait for auto-build to finish.
		Job.getJobManager().join(ResourcesPlugin.FAMILY_AUTO_BUILD, new NullProgressMonitor());

		// Collect problem markers.
		var markers = target.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);

		// Sort by file path, then line number.
		Arrays.sort(markers, (a, b) -> {
			int c = a.getResource().getFullPath().toString().compareTo(b.getResource().getFullPath().toString());
			if (c != 0) return c;
			return Integer.compare(a.getAttribute(IMarker.LINE_NUMBER, 0), b.getAttribute(IMarker.LINE_NUMBER, 0));
		});

		// Response.
		var json = new Json();
		json.array();
		for (var marker : markers) {
			int severity = marker.getAttribute(IMarker.SEVERITY, -1);
			if (severity != IMarker.SEVERITY_ERROR && severity != IMarker.SEVERITY_WARNING) continue;

			var resource = marker.getResource();
			var location = resource.getLocation();
			String filePath = location != null ? location.toOSString() : resource.getFullPath().toOSString();
			int line = marker.getAttribute(IMarker.LINE_NUMBER, -1);
			String message = marker.getAttribute(IMarker.MESSAGE, "");

			json.object();
			json.set("file", filePath);
			json.set("line", line);
			json.set("severity", severity == IMarker.SEVERITY_ERROR ? "error" : "warning");
			json.set("message", message);
			json.pop();
		}
		json.pop();
		exchange.responseJson(200, json.toString());
	}

	void jdt_references (Exchange exchange) throws Throwable {
		var query = exchange.decodeQuery();
		String projectName = query.get("project");
		String typeName = query.get("type");
		String memberName = query.get("member");
		String paramTypes = query.get("paramTypes");

		if (typeName == null || typeName.isEmpty()) {
			exchange.responseJson(400, "{\"error\":\"Missing parameter: type\"}");
			return;
		}

		var type = findType(projectName, typeName);
		if (type == null) {
			exchange.responseJson(404, "{\"error\":\"Type not found: " + Json.quote(typeName) + "\"}");
			return;
		}

		var element = findMember(type, memberName, paramTypes);
		if (element == null) {
			exchange.responseJson(404, "{\"error\":\"Member not found: " + Json.quote(memberName) + "\"}");
			return;
		}

		var pattern = SearchPattern.createPattern(element, IJavaSearchConstants.REFERENCES);
		var scope = searchScope(projectName);
		var matches = new ArrayList<SearchMatch>();
		new SearchEngine().search(pattern, new SearchParticipant[] {SearchEngine.getDefaultSearchParticipant()}, scope,
			new SearchRequestor() {
				public void acceptSearchMatch (SearchMatch match) {
					if (match.getAccuracy() == SearchMatch.A_ACCURATE) matches.add(match);
				}
			}, new NullProgressMonitor());

		var json = new Json();
		json.array();
		for (var match : matches) {
			var resource = match.getResource();
			if (resource == null) continue;
			json.object();
			json.set("file", filePath(resource));
			if (match.getElement() instanceof IMethod enclosing) {
				json.set("enclosingType", enclosing.getDeclaringType().getFullyQualifiedName());
				json.set("enclosingMethod", enclosing.getElementName());
			}
			var source = getSource(match);
			if (source != null) {
				json.set("line", lineNumber(source, match.getOffset()));
				json.set("context", contextLine(source, match.getOffset()));
			}
			json.pop();
		}
		json.pop();
		exchange.responseJson(200, json.toString());
	}

	void jdt_hierarchy (Exchange exchange) throws Throwable {
		var query = exchange.decodeQuery();
		String projectName = query.get("project");
		String typeName = query.get("type");
		String direction = query.getOrDefault("direction", "sub");
		String methodName = query.get("method");
		String methodParamTypes = query.get("paramTypes");

		if (typeName == null || typeName.isEmpty()) {
			exchange.responseJson(400, "{\"error\":\"Missing parameter: type\"}");
			return;
		}

		var type = findType(projectName, typeName);
		if (type == null) {
			exchange.responseJson(404, "{\"error\":\"Type not found: " + Json.quote(typeName) + "\"}");
			return;
		}

		var hierarchy = type.newTypeHierarchy(new NullProgressMonitor());
		IType[] types = switch (direction) {
		case "super" -> hierarchy.getAllSupertypes(type);
		case "all" -> hierarchy.getAllTypes();
		default -> hierarchy.getAllSubtypes(type);
		};

		var json = new Json();
		json.array();
		for (var t : types) {
			// If method specified, filter to types that override it.
			IMethod override = null;
			if (methodName != null && !methodName.isEmpty()) {
				override = findMethod(t, methodName, methodParamTypes);
				if (override == null) continue;
			}

			json.object();
			json.set("type", t.getFullyQualifiedName());
			var resource = t.getResource();
			if (resource != null) {
				json.set("file", filePath(resource));
				var cu = t.getCompilationUnit();
				if (cu != null) {
					var buffer = cu.getBuffer();
					if (buffer != null) {
						// Point to the override method or the type declaration.
						var range = override != null ? override.getNameRange() : t.getNameRange();
						if (range != null) json.set("line", lineNumber(buffer.getContents(), range.getOffset()));
					}
				}
			}
			json.pop();
		}
		json.pop();
		exchange.responseJson(200, json.toString());
	}

	void jdt_search_type (Exchange exchange) throws Throwable {
		var query = exchange.decodeQuery();
		String projectName = query.get("project");
		String name = query.get("name");

		if (name == null || name.isEmpty()) {
			exchange.responseJson(400, "{\"error\":\"Missing parameter: name\"}");
			return;
		}

		var pattern = SearchPattern.createPattern(name, IJavaSearchConstants.TYPE, IJavaSearchConstants.DECLARATIONS,
			SearchPattern.R_PATTERN_MATCH | SearchPattern.R_CASE_SENSITIVE);
		var scope = searchScope(projectName);
		var matches = new ArrayList<SearchMatch>();
		new SearchEngine().search(pattern, new SearchParticipant[] {SearchEngine.getDefaultSearchParticipant()}, scope,
			new SearchRequestor() {
				public void acceptSearchMatch (SearchMatch match) {
					if (match.getAccuracy() == SearchMatch.A_ACCURATE) matches.add(match);
				}
			}, new NullProgressMonitor());

		var json = new Json();
		json.array();
		for (var match : matches) {
			if (!(match.getElement() instanceof IType t)) continue;
			json.object();
			json.set("type", t.getFullyQualifiedName());
			var resource = match.getResource();
			if (resource != null) {
				json.set("file", filePath(resource));
				var cu = t.getCompilationUnit();
				if (cu != null) {
					var buffer = cu.getBuffer();
					if (buffer != null) json.set("line", lineNumber(buffer.getContents(), match.getOffset()));
				}
			}
			json.pop();
		}
		json.pop();
		exchange.responseJson(200, json.toString());
	}

	void jdt_members (Exchange exchange) throws Throwable {
		var query = exchange.decodeQuery();
		String projectName = query.get("project");
		String typeName = query.get("type");

		if (typeName == null || typeName.isEmpty()) {
			exchange.responseJson(400, "{\"error\":\"Missing parameter: type\"}");
			return;
		}

		var type = findType(projectName, typeName);
		if (type == null) {
			exchange.responseJson(404, "{\"error\":\"Type not found: " + Json.quote(typeName) + "\"}");
			return;
		}

		// Collect types: self + supertypes (excluding Object).
		var hierarchy = type.newTypeHierarchy(new NullProgressMonitor());
		var allTypes = new ArrayList<IType>();
		allTypes.add(type);
		for (var superType : hierarchy.getAllSupertypes(type))
			if (!superType.getFullyQualifiedName().equals("java.lang.Object")) allTypes.add(superType);

		var json = new Json();
		json.array();
		for (var t : allTypes) {
			json.object();
			json.set("type", t.getFullyQualifiedName());
			var resource = t.getResource();
			if (resource != null) json.set("file", filePath(resource));

			// Get source for line numbers.
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
				var paramTypes = method.getParameterTypes();
				var paramNames = method.getParameterNames();
				var params = new StringBuilder();
				for (int i = 0; i < paramTypes.length; i++) {
					if (i > 0) params.append(", ");
					params.append(Signature.toString(paramTypes[i]));
					params.append(' ');
					params.append(paramNames[i]);
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
		exchange.responseJson(200, json.toString());
	}

	void jdt_organize_imports (Exchange exchange) throws Throwable {
		synchronized (organizeImportsLock) {
			jdt_organize_imports0(exchange);
		}
	}

	void jdt_organize_imports0 (Exchange exchange) throws Throwable {
		var query = exchange.decodeQuery();
		String filePath = query.get("file");
		String resolveStr = query.get("resolve");
		String preferStr = query.getOrDefault("prefer", "com.esotericsoftware,com.badlogic");

		if (filePath == null || filePath.isEmpty()) {
			exchange.responseJson(400, "{\"error\":\"Missing parameter: file\"}");
			return;
		}

		// Find the compilation unit.
		var ipath = Path.fromOSString(filePath);
		var ifile = ResourcesPlugin.getWorkspace().getRoot().getFileForLocation(ipath);
		if (ifile == null || !ifile.exists()) {
			exchange.responseJson(404, "{\"error\":\"File not found\"}");
			return;
		}
		var javaElement = JavaCore.create(ifile);
		if (!(javaElement instanceof ICompilationUnit cu)) {
			exchange.responseJson(400, "{\"error\":\"Not a Java source file\"}");
			return;
		}

		// Refresh and build.
		ifile.refreshLocal(IResource.DEPTH_ZERO, new NullProgressMonitor());
		Job.getJobManager().join(ResourcesPlugin.FAMILY_AUTO_BUILD, new NullProgressMonitor());

		// Check for fatal syntax errors.
		for (var marker : ifile.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO)) {
			if (marker.getAttribute(IMarker.SEVERITY, -1) == IMarker.SEVERITY_ERROR
				&& marker.getAttribute(IMarker.MESSAGE, "").contains("Syntax error")) {
				exchange.responseJson(400, "{\"error\":\"File has syntax errors, fix before organizing imports\"}");
				return;
			}
		}

		// Parse explicit resolutions: "Array:com.badlogic.gdx.utils.Array,List:java.util.List"
		var explicitResolve = new HashMap<String, String>();
		if (resolveStr != null && !resolveStr.isEmpty()) {
			for (var pair : resolveStr.split(",")) {
				var kv = pair.split(":", 2);
				if (kv.length == 2) explicitResolve.put(kv[0].trim(), kv[1].trim());
			}
		}

		var prefer = preferStr.split(",");
		for (int i = 0; i < prefer.length; i++)
			prefer[i] = prefer[i].trim();

		// Run organize imports, collecting any ambiguous types as conflicts.
		var conflicts = new ArrayList<String[]>();
		organizeImports(cu, explicitResolve, conflicts);

		if (conflicts.isEmpty()) {
			respondOrganizeImports(exchange, true, null);
			return;
		}

		// Multiple conflicts: return all for the caller to resolve.
		if (conflicts.size() > 1) {
			respondOrganizeImports(exchange, false, conflicts);
			return;
		}

		// Exactly 1 conflict: try ALL candidates with a real build.
		// Accept only if exactly one gives zero errors (100% safe).
		var conflict = conflicts.get(0);
		String simpleName = conflict[0];

		var candidates = new ArrayList<String>();
		for (int i = 1; i < conflict.length; i++)
			candidates.add(conflict[i]);

		var working = new ArrayList<String>();

		// Save original file content to restore between attempts.
		byte[] originalContent;
		try (var in = ifile.getContents()) {
			originalContent = in.readAllBytes();
		}

		for (var candidate : candidates) {
			// Restore original before each attempt (disk + CU buffer).
			ifile.setContents(new java.io.ByteArrayInputStream(originalContent), IResource.FORCE, new NullProgressMonitor());
			cu.getBuffer().setContents(new String(originalContent, java.nio.charset.StandardCharsets.UTF_8));

			// Run organize imports with a non-cancelling chooser: resolve explicits, pick the brute-force candidate, pick first
			// option for anything unexpected.
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
						if (results[i] == null) results[i] = choices[0]; // Never cancel, pick first.
					}
					return results;
				});
				op.run(new NullProgressMonitor());
				workingCopy.commitWorkingCopy(true, new NullProgressMonitor());
			} finally {
				workingCopy.discardWorkingCopy();
			}

			// Real build, then check errors on this file.
			ifile.refreshLocal(IResource.DEPTH_ZERO, new NullProgressMonitor());
			Job.getJobManager().join(ResourcesPlugin.FAMILY_AUTO_BUILD, new NullProgressMonitor());
			boolean hasErrors = false;
			for (var m : ifile.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO))
				if (m.getAttribute(IMarker.SEVERITY, -1) == IMarker.SEVERITY_ERROR) {
					hasErrors = true;
					break;
				}

			if (!hasErrors) {
				working.add(candidate);
				if (working.size() > 1) break; // Ambiguous, stop early.
			}
		}

		if (working.size() == 1) {
			// Re-apply the one working candidate.
			ifile.setContents(new java.io.ByteArrayInputStream(originalContent), IResource.FORCE, new NullProgressMonitor());
			cu.getBuffer().setContents(new String(originalContent, java.nio.charset.StandardCharsets.UTF_8));
			explicitResolve.put(simpleName, working.get(0));
			var workingCopy2 = cu.getWorkingCopy(new NullProgressMonitor());
			try {
				var op2 = new OrganizeImportsOperation(workingCopy2, null, true, false, true, (openChoices, ranges) -> {
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
				op2.run(new NullProgressMonitor());
				workingCopy2.commitWorkingCopy(true, new NullProgressMonitor());
			} finally {
				workingCopy2.discardWorkingCopy();
			}
			respondOrganizeImports(exchange, true, null);
		} else {
			// 0 or 2+ candidates worked. Restore original, return conflict.
			ifile.setContents(new java.io.ByteArrayInputStream(originalContent), IResource.FORCE, new NullProgressMonitor());
			cu.getBuffer().setContents(new String(originalContent, java.nio.charset.StandardCharsets.UTF_8));
			respondOrganizeImports(exchange, false, conflicts);
		}
	}

	void organizeImports (ICompilationUnit cu, HashMap<String, String> explicitResolve, ArrayList<String[]> conflicts)
		throws Exception {

		// Remove existing imports that match explicit resolve names, so the operation re-adds the correct ones.
		if (!explicitResolve.isEmpty()) {
			var source = cu.getSource();
			var sb = new StringBuilder(source.length());
			for (var line : source.split("\n", -1)) {
				var trimmed = line.trim();
				if (trimmed.startsWith("import ") && !trimmed.startsWith("import static ")) {
					var fqn = trimmed.substring(7, trimmed.endsWith(";") ? trimmed.length() - 1 : trimmed.length()).trim();
					int dot = fqn.lastIndexOf('.');
					var simpleName = dot >= 0 ? fqn.substring(dot + 1) : fqn;
					if (explicitResolve.containsKey(simpleName)) continue; // Skip this import.
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

	void jdt_classpath (Exchange exchange) throws Throwable {
		var query = exchange.decodeQuery();
		String projectName = query.get("project");

		if (projectName == null || projectName.isEmpty()) {
			exchange.responseJson(400, "{\"error\":\"Missing parameter: project\"}");
			return;
		}

		var project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
		if (!project.exists()) {
			exchange.responseJson(404, "{\"error\":\"Project not found\"}");
			return;
		}

		var javaProject = JavaCore.create(project);
		var paths = org.eclipse.jdt.launching.JavaRuntime.computeDefaultRuntimeClassPath(javaProject);

		// Dedupe and write to temp file.
		var seen = new java.util.LinkedHashSet<String>();
		for (var path : paths) seen.add(path);
		var file = java.io.File.createTempFile("classpath-" + projectName + "-", ".txt");
		file.deleteOnExit();
		try (var writer = new java.io.FileWriter(file)) {
			writer.write("-cp");
			writer.write(System.lineSeparator());
			var sb = new StringBuilder();
			for (var path : seen) {
				if (sb.length() > 0) sb.append(java.io.File.pathSeparatorChar);
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

	IType findType (String projectName, String qualifiedName) throws JavaModelException {
		var root = ResourcesPlugin.getWorkspace().getRoot();
		if (projectName != null && !projectName.isEmpty()) {
			var project = root.getProject(projectName);
			if (!project.exists()) return null;
			return JavaCore.create(project).findType(qualifiedName);
		}
		for (var jp : JavaCore.create(root).getJavaProjects()) {
			var type = jp.findType(qualifiedName);
			if (type != null) return type;
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

	IJavaElement findMember (IType type, String memberName, String paramTypes) throws JavaModelException {
		if (memberName == null || memberName.isEmpty()) return type;
		var method = findMethod(type, memberName, paramTypes);
		if (method != null) return method;
		var field = type.getField(memberName);
		if (field.exists()) return field;
		return null;
	}

	IJavaSearchScope searchScope (String projectName) throws JavaModelException {
		if (projectName != null && !projectName.isEmpty()) {
			var project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
			if (project.exists()) return SearchEngine.createJavaSearchScope(new IJavaElement[] {JavaCore.create(project)});
		}
		return SearchEngine.createWorkspaceScope();
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
}
