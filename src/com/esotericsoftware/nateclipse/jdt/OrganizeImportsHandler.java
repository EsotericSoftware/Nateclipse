
package com.esotericsoftware.nateclipse.jdt;

import static com.esotericsoftware.nateclipse.jdt.JdtLookup.*;
import static com.esotericsoftware.nateclipse.jdt.JdtUtils.*;
import static java.nio.charset.StandardCharsets.*;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.manipulation.OrganizeImportsOperation;
import org.eclipse.jdt.core.search.TypeNameMatch;

import com.esotericsoftware.nateclipse.utils.Json;
import com.esotericsoftware.nateclipse.utils.WebServer.Exchange;

/** Handles /java_organize_imports. Serialized via an internal lock because the operation mutates the working copy and a real
 * build is used to verify single-conflict candidates.
 * <p>
 * If the first organize pass records ambiguous imports: with multiple conflicts we respond immediately; with exactly one conflict
 * we try each candidate (reset file, set explicit resolution, organize, build, check for errors) and commit the single one that
 * compiles. */
public class OrganizeImportsHandler {
	private final Object lock = new Object();

	public void handle (Exchange exchange) throws Throwable {
		synchronized (lock) {
			handle0(exchange);
		}
	}

	private void handle0 (Exchange exchange) throws Throwable {
		var p = new JdtUtils.Params(exchange);
		String filePath = p.get("file");
		String typeName = p.get("type");
		String resolveStr = p.get("resolve");

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

		var conflicts = new ArrayList<String[]>();
		organizeImports(cu, explicitResolve, conflicts);

		if (conflicts.isEmpty()) {
			respond(exchange, true, null);
			return;
		}

		if (conflicts.size() > 1) {
			respond(exchange, false, conflicts);
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
			respond(exchange, true, null);
		} else {
			ifile.setContents(new ByteArrayInputStream(originalContent), IResource.FORCE, new NullProgressMonitor());
			cu.getBuffer().setContents(new String(originalContent, UTF_8));
			respond(exchange, false, conflicts);
		}
	}

	/** Resets the file, sets explicitResolve[simpleName]=candidate, organizes imports, builds. Returns true if no errors after. */
	private boolean tryImportCandidate (IFile ifile, ICompilationUnit cu, byte[] originalContent,
		HashMap<String, String> explicitResolve, String simpleName, String candidate) throws Exception {
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

	private void organizeImports (ICompilationUnit cu, HashMap<String, String> explicitResolve, ArrayList<String[]> conflicts)
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

	private void respond (Exchange exchange, boolean organized, ArrayList<String[]> conflicts) throws Exception {
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
}
