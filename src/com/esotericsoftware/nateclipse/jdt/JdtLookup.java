
package com.esotericsoftware.nateclipse.jdt;

import static com.esotericsoftware.nateclipse.jdt.JdtUtils.*;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.ICompilationUnit;
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
	private static final String CONSTRUCTOR_ALIAS = "<init>";
	private static final int AMBIGUOUS_TYPE_MAX_SHOWN = 25;

	private JdtLookup () {
	}

	/** Resolve a type name or {@code FQN path} selector. Returns null if it responded with an error. */
	public static IType resolveTypeOrError (Exchange exchange, String projectName, String typeName) throws Exception {
		var fileTypes = typesForFilePath(projectName, typeName);
		if (fileTypes != null) {
			if (fileTypes.size() == 1) return fileTypes.get(0);
			if (fileTypes.isEmpty()) {
				error(exchange, 404, "No Java types in file: " + typeName);
				return null;
			}
			ambiguousTypes(exchange, fileTypes);
			return null;
		}

		var types = searchTypes(projectName, typeName);
		if (types.size() == 1) return types.get(0);
		if (types.isEmpty()) {
			if (typeName.contains(".") && !typeName.contains("*") && !typeName.contains("?")) {
				var type = findType(projectName, typeName);
				if (type != null) return type;
			}
			error(exchange, 404, "Type not found: " + typeName);
			return null;
		}

		ambiguousTypes(exchange, types);
		return null;
	}

	/** Search source types by name, wildcard pattern, or {@code FQN path} selector. */
	public static ArrayList<IType> searchTypes (String projectName, String typeName) throws CoreException {
		var fileTypes = typesForFilePath(projectName, typeName);
		if (fileTypes != null) return fileTypes;

		boolean hasWildcards = typeName.contains("*") || typeName.contains("?");

		// Search before direct lookup: an FQN can name distinct source declarations.
		int matchRule = hasWildcards ? SearchPattern.R_PATTERN_MATCH | SearchPattern.R_CASE_SENSITIVE
			: SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE;
		var pattern = SearchPattern.createPattern(typeName, IJavaSearchConstants.TYPE, IJavaSearchConstants.DECLARATIONS,
			matchRule);
		var types = new ArrayList<IType>();
		var scope = searchScope(projectName);
		search(pattern, scope, sourceTypeCollector(types));

		// No hit: retry with '.' and '$' swapped so "Outer.Inner" and "Outer$Inner" are interchangeable.
		if (types.isEmpty() && !hasWildcards && (typeName.indexOf('.') >= 0 || typeName.indexOf('$') >= 0)) {
			String swapped = typeName.indexOf('$') >= 0 ? typeName.replace('$', '.') : typeName.replace('.', '$');
			var alt = SearchPattern.createPattern(swapped, IJavaSearchConstants.TYPE, IJavaSearchConstants.DECLARATIONS, matchRule);
			search(alt, scope, sourceTypeCollector(types));
		}
		if (!hasWildcards && (typeName.indexOf('.') >= 0 || typeName.indexOf('$') >= 0)) {
			// Qualified searches can hide shadowed source roots.
			String qualifiedName = typeName.replace('$', '.');
			String simpleName = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
			var candidates = new ArrayList<IType>();
			var simple = SearchPattern.createPattern(simpleName, IJavaSearchConstants.TYPE, IJavaSearchConstants.DECLARATIONS,
				matchRule);
			search(simple, scope, sourceTypeCollector(candidates));
			for (var type : candidates)
				if (type.getFullyQualifiedName('.').equals(qualifiedName) || type.getTypeQualifiedName('.').equals(qualifiedName))
					types.add(type);
			var exact = new ArrayList<IType>();
			for (var type : types)
				if (type.getFullyQualifiedName().equals(typeName) || type.getFullyQualifiedName('.').equals(typeName)) exact.add(type);
			if (!exact.isEmpty()) types = exact;
		}
		if (types.isEmpty() && typeName.contains(".") && !hasWildcards) {
			var type = findType(projectName, typeName);
			if (type != null && !type.isBinary()) types.add(type);
		}
		return deduplicateTypes(types);
	}

	private static ArrayList<IType> deduplicateTypes (ArrayList<IType> types) {
		var unique = new LinkedHashMap<String, IType>();
		for (var type : types)
			unique.putIfAbsent(type.getFullyQualifiedName() + " " + typePath(type), type);
		return new ArrayList<>(unique.values());
	}

	private static String typePath (IType type) {
		var resource = type.getResource();
		return (resource != null ? filePath(resource) : type.getPath().toOSString()).replace('\\', '/');
	}

	private static void ambiguousTypes (Exchange exchange, ArrayList<IType> types) throws Exception {
		var names = new LinkedHashMap<String, IType>();
		boolean paths = false;
		for (var type : types) {
			if (names.putIfAbsent(type.getFullyQualifiedName(), type) != null) {
				paths = true;
				break;
			}
		}
		var sb = new StringBuilder(paths ? "Ambiguous, use one of these type values:\n"
			: "Ambiguous, use fully qualified name:\n");
		int shown = Math.min(types.size(), AMBIGUOUS_TYPE_MAX_SHOWN);
		for (int i = 0; i < shown; i++) {
			if (i > 0) sb.append("\n");
			var type = types.get(i);
			sb.append(type.getFullyQualifiedName());
			if (paths) sb.append(' ').append(typePath(type));
		}
		if (types.size() > shown) sb.append("\n...+").append(types.size() - shown).append(" more");
		error(exchange, 400, sb.toString());
	}

	private static ArrayList<IType> typesForFilePath (String projectName, String typeName) throws CoreException {
		if (typeName == null) return null;
		var value = typeName.trim();
		if (!value.toLowerCase(Locale.ROOT).endsWith(".java")) return null;
		var file = new File(value);
		String qualifiedName = null;
		if (!file.isAbsolute()) {
			int separator = 0;
			while (separator < value.length() && !Character.isWhitespace(value.charAt(separator)))
				separator++;
			if (separator == value.length()) return null;
			qualifiedName = value.substring(0, separator);
			file = new File(value.substring(separator).trim());
			if (!file.isAbsolute()) return null;
		}

		var types = new ArrayList<IType>();
		if (!file.isFile()) return types;
		var ifile = ResourcesPlugin.getWorkspace().getRoot().getFileForLocation(Path.fromOSString(file.getAbsolutePath()));
		if (ifile == null || !ifile.exists()) return types;
		if (projectName != null && !projectName.isEmpty() && !ifile.getProject().getName().equals(projectName)) return types;
		var javaElement = JavaCore.create(ifile);
		if (!(javaElement instanceof ICompilationUnit cu)) return types;
		if (qualifiedName == null) return deduplicateTypes(primaryTypes(cu, file.getName()));
		for (var type : cu.getAllTypes())
			if (type.exists() && (type.getFullyQualifiedName().equals(qualifiedName)
				|| type.getFullyQualifiedName('.').equals(qualifiedName))) types.add(type);
		return deduplicateTypes(types);
	}

	private static ArrayList<IType> primaryTypes (ICompilationUnit cu, String fileName) throws JavaModelException {
		var result = new ArrayList<IType>();
		var dot = fileName.lastIndexOf('.');
		var baseName = dot >= 0 ? fileName.substring(0, dot) : fileName;
		for (var type : cu.getTypes()) {
			if (type.exists() && type.getElementName().equals(baseName)) {
				result.add(type);
				return result;
			}
		}
		for (var type : cu.getTypes())
			if (type.exists()) result.add(type);
		return result;
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
		boolean constructor = isConstructorAlias(methodName);
		if (paramTypes != null && !paramTypes.isEmpty()) {
			var parts = paramTypes.split(",");
			var sigs = new String[parts.length];
			for (int i = 0; i < parts.length; i++)
				sigs[i] = Signature.createTypeSignature(parts[i].trim(), false);
			var method = type.getMethod(constructor ? type.getElementName() : methodName, sigs);
			if (method.exists() && (!constructor || method.isConstructor())) return method;
		}
		for (var method : type.getMethods())
			if (matchesMethodName(method, methodName, constructor)) return method;
		return null;
	}

	/** Like {@link #findMethod} but walks the supertype hierarchy if no direct match is found. Returns the first match in the
	 * superclass chain, then interfaces (the order produced by {@link IType#newSupertypeHierarchy}). Constructors are not
	 * inherited; use <code>&lt;init&gt;</code> to request a constructor directly. */
	public static IMethod findMethodInHierarchy (IType type, String methodName, String paramTypes) throws JavaModelException {
		var direct = findMethod(type, methodName, paramTypes);
		if (direct != null || isConstructorAlias(methodName)) return direct;
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
		boolean constructor = isConstructorAlias(methodName);
		for (var method : type.getMethods())
			if (matchesMethodName(method, methodName, constructor)) result.add(method);
		return result;
	}

	private static boolean isConstructorAlias (String methodName) {
		return CONSTRUCTOR_ALIAS.equals(methodName);
	}

	private static boolean matchesMethodName (IMethod method, String methodName, boolean constructor) throws JavaModelException {
		return constructor ? method.isConstructor() : method.getElementName().equals(methodName);
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
