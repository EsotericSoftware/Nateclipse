
package com.esotericsoftware.nateclipse.jdt;

import static com.esotericsoftware.nateclipse.jdt.JdtUtils.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IClassFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IOpenable;
import org.eclipse.jdt.core.ITypeRoot;
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

/** Finds the directly overridden method and any <code>super.xxx(...)</code> / <code>super(...)</code> targets inside a method
 * body, using JDT bindings so overloads, generics, and inner classes resolve accurately. Results are deduped by binding key, with
 * the directly overridden entry first. */
public class SuperMethodCollector {
	private SuperMethodCollector () {
	}

	public record SuperMethodInfo (
		String kind,
		String typeName,
		String methodName,
		String file,
		int startLine,
		int endLine,
		String source) {}

	public static ArrayList<SuperMethodInfo> collectSuperMethods (ICompilationUnit cu, IMethod method) throws JavaModelException {
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
	private static IMethodBinding findOverriddenMethod (IMethodBinding method) {
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

	private static SuperMethodInfo buildSuperInfo (IMethodBinding binding, String kind) throws JavaModelException {
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

	private static String signatureString (IMethod m) throws JavaModelException {
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
}
