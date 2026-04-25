
package com.esotericsoftware.nateclipse.jdt;

import static com.esotericsoftware.nateclipse.jdt.JdtUtils.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchPattern;

import com.esotericsoftware.nateclipse.utils.WebServer.Exchange;

/** Type / member lookup against the JDT model. Handles unqualified and wildcard type names, nested-type dot/$ swapping,
 * ambiguous-name error responses, and the source-only workspace search scope. Stateless. */
public class JdtLookup {
	private JdtLookup () {
	}

	/** Resolve a type name that may be unqualified. Returns null if it responded with an error. */
	public static IType resolveTypeOrError (Exchange exchange, String projectName, String typeName) throws Exception {
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

	/** Search for types by simple name, FQN, or wildcard pattern. Source types only. */
	public static ArrayList<IType> searchTypes (String projectName, String typeName) throws CoreException {
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

	public static IType findType (String projectName, String qualifiedName) throws JavaModelException {
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

	public static IType findTypeExact (String projectName, String qualifiedName) throws JavaModelException {
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

	public static IMethod findMethod (IType type, String methodName, String paramTypes) throws JavaModelException {
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

	/** Like {@link #findMethod} but walks the supertype hierarchy if no direct match is found. Returns the first match in the
	 * superclass chain, then interfaces (the order produced by {@link IType#newSupertypeHierarchy}). */
	public static IMethod findMethodInHierarchy (IType type, String methodName, String paramTypes) throws JavaModelException {
		var direct = findMethod(type, methodName, paramTypes);
		if (direct != null) return direct;
		var hierarchy = type.newSupertypeHierarchy(new NullProgressMonitor());
		for (var sup : hierarchy.getAllSupertypes(type)) {
			if ("java.lang.Object".equals(sup.getFullyQualifiedName())) continue;
			var m = findMethod(sup, methodName, paramTypes);
			if (m != null) return m;
		}
		return null;
	}

	public static ArrayList<IMethod> findMethods (IType type, String methodName, String paramTypes) throws JavaModelException {
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

	public static String methodKey (IMethod m) throws JavaModelException {
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

	public static IJavaElement findMember (IType type, String memberName, String paramTypes) throws JavaModelException {
		if (memberName == null || memberName.isEmpty()) return type;
		var method = findMethod(type, memberName, paramTypes);
		if (method != null) return method;
		var field = type.getField(memberName);
		if (field.exists()) return field;
		return null;
	}

	public static IJavaSearchScope searchScope (String projectName) throws CoreException {
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
}
