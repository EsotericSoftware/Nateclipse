
package com.esotericsoftware.nateclipse;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageDeclaration;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.ui.text.java.AbstractProposalSorter;
import org.eclipse.jdt.ui.text.java.ContentAssistInvocationContext;
import org.eclipse.jdt.ui.text.java.JavaContentAssistInvocationContext;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.templates.TemplateProposal;

import com.esotericsoftware.nateclipse.utils.TypeRanking;
import com.esotericsoftware.nateclipse.utils.TypeRanking.Classification;

/** Re-orders Java content-assist proposals so that:
 * <ol>
 * <li>Method-stub generators (override method, getter/setter, record accessor, method declaration) come first when present.
 * <li>Templates (eg {@code sout} -> {@code System.out.println()}) come next.
 * <li>Types declared in the current compilation unit come next.
 * <li>Then types already imported by the current compilation unit (explicit imports beat wildcards).
 * <li>Then types in the same package, same project, other workspace projects.
 * <li>Then workspace JARs.
 * <li>Then commonly-used JDK types, then the rest of the JDK.
 * </ol>
 * Non-type, non-template proposals (methods, fields, keywords) keep their relative order via relevance, but sort below scored
 * type proposals. */
public class CompletionSort extends AbstractProposalSorter {
	static private final ILog log = Platform.getLog(CompletionSort.class);

	static public final String ID = "com.esotericsoftware.nateclipse.completionSort";

	// --- Reflection helpers for extracting FQN from internal proposal classes ---

	static private final Method NONE = sentinel();

	static private Method sentinel () {
		try {
			return Object.class.getMethod("toString");
		} catch (NoSuchMethodException e) {
			throw new RuntimeException(e);
		}
	}

	static private final Map<Class<?>, Method> FQN_METHOD_CACHE = new ConcurrentHashMap<>();

	static private Method fqnMethod (Class<?> c) {
		Method m = FQN_METHOD_CACHE.get(c);
		if (m != null) return m;
		try {
			m = c.getMethod("getQualifiedTypeName");
			m.setAccessible(true);
		} catch (Exception e) {
			m = NONE;
		}
		FQN_METHOD_CACHE.put(c, m);
		return m;
	}

	// --- Per-invocation context ---

	private String activeProject;
	private String activePackage;
	private Set<String> currentCuTypes = Collections.emptySet();
	private Set<String> importedTypes = Collections.emptySet();
	private Set<String> importedPackages = Collections.emptySet();

	/** Proposal identity -> computed score. Reset per sorting pass. */
	private final Map<ICompletionProposal, Integer> scoreCache = new HashMap<>();

	@Override
	public void beginSorting (ContentAssistInvocationContext context) {
		scoreCache.clear();
		activeProject = null;
		activePackage = null;
		currentCuTypes = Collections.emptySet();
		importedTypes = Collections.emptySet();
		importedPackages = Collections.emptySet();
		try {
			if (context instanceof JavaContentAssistInvocationContext jc) {
				IJavaProject project = jc.getProject();
				if (project != null) activeProject = project.getElementName();
				ICompilationUnit cu = jc.getCompilationUnit();
				if (cu != null) {
					IPackageDeclaration[] pkgs = cu.getPackageDeclarations();
					activePackage = pkgs.length > 0 ? pkgs[0].getElementName() : "";
					Set<String> fqns = new HashSet<>();
					collectTypes(cu.getTypes(), fqns);
					currentCuTypes = fqns;
					Set<String> impTypes = new HashSet<>();
					Set<String> impPkgs = new HashSet<>();
					collectImports(cu.getImports(), impTypes, impPkgs);
					importedTypes = impTypes;
					importedPackages = impPkgs;
				}
			}
		} catch (Exception ex) {
			log.error("SmartProposalSorter.beginSorting failed", ex);
		}
	}

	static private void collectImports (IImportDeclaration[] imports, Set<String> types, Set<String> packages)
		throws org.eclipse.jdt.core.JavaModelException {
		for (IImportDeclaration imp : imports) {
			String name = imp.getElementName();
			if (name == null || name.isEmpty()) continue;
			boolean onDemand = imp.isOnDemand();
			boolean isStatic = Flags.isStatic(imp.getFlags());
			if (isStatic) {
				// `import static a.b.C.*;` -> element name is the type C.
				// `import static a.b.C.member;` -> strip trailing segment to get the type.
				if (onDemand) {
					types.add(name);
				} else {
					int dot = name.lastIndexOf('.');
					if (dot > 0) types.add(name.substring(0, dot));
				}
			} else {
				// `import a.b.*;` -> element name is the package.
				// `import a.b.C;` -> element name is the type.
				if (onDemand)
					packages.add(name);
				else
					types.add(name);
			}
		}
	}

	static private void collectTypes (IType[] types, Set<String> out) throws org.eclipse.jdt.core.JavaModelException {
		for (IType t : types) {
			out.add(t.getFullyQualifiedName('.'));
			collectTypes(t.getTypes(), out);
		}
	}

	@Override
	public void endSorting () {
		scoreCache.clear();
		activeProject = null;
		activePackage = null;
		currentCuTypes = Collections.emptySet();
		importedTypes = Collections.emptySet();
		importedPackages = Collections.emptySet();
	}

	@Override
	public int compare (ICompletionProposal p1, ICompletionProposal p2) {
		// Strict tiered ordering, must be a total order or TimSort throws
		// "Comparison method violates its general contract!".
		// Tier 0: method-stub generators (override method, getter/setter, etc) - most useful in class body context.
		// Tier 1: templates (eg "sout" -> "System.out.println()").
		// Tier 2: type proposals, ordered by descending score.
		// Tier 3: everything else (methods, fields, keywords), ordered by relevance.
		int t1 = tier(p1);
		int t2 = tier(p2);
		if (t1 != t2) return t1 - t2;
		if (t1 == 2) {
			Integer s1 = scoreOrNull(p1);
			Integer s2 = scoreOrNull(p2);
			// Both are tier 2, so both have non-null scores.
			if (!s1.equals(s2)) return s2.intValue() - s1.intValue();
		}
		return compareByRelevance(p1, p2);
	}

	private int tier (ICompletionProposal p) {
		if (isStubGenerator(p)) return 0;
		if (isTemplate(p)) return 1;
		return scoreOrNull(p) != null ? 2 : 3;
	}

	static private boolean isStubGenerator (ICompletionProposal p) {
		if (p == null) return false;
		// Internal JDT proposals (org.eclipse.jdt.internal.ui.text.java.*) that synthesize a method body
		// or class member when invoked. These are highly contextual, only offered inside a class/anonymous
		// class body, and the user almost always wants them above generic templates.
		String cn = p.getClass().getName();
		return cn.endsWith(".OverrideCompletionProposal") //
			|| cn.endsWith(".MethodDeclarationCompletionProposal") //
			|| cn.endsWith(".GetterSetterCompletionProposal") //
			|| cn.endsWith(".RecordAccessorCompletionProposal");
	}

	static private boolean isTemplate (ICompletionProposal p) {
		if (p == null) return false;
		if (p instanceof TemplateProposal) return true;
		// Fallback: some template proposals may not extend jface's TemplateProposal directly
		// (eg JDT's own org.eclipse.jdt.internal.ui.text.template.contentassist.TemplateProposal
		// and PostfixTemplateProposal).
		String cn = p.getClass().getName();
		return cn.endsWith("TemplateProposal") || cn.contains(".template.");
	}

	private Integer scoreOrNull (ICompletionProposal p) {
		if (scoreCache.containsKey(p)) return scoreCache.get(p);
		String fqn = getFqn(p);
		Integer score;
		if (fqn == null) {
			score = null;
		} else {
			Classification c = TypeRanking.classify(fqn);
			score = Integer.valueOf(
				TypeRanking.scoreForCompletion(c, activeProject, activePackage, currentCuTypes, importedTypes, importedPackages));
		}
		scoreCache.put(p, score);
		return score;
	}

	static private String getFqn (ICompletionProposal p) {
		if (p == null) return null;
		Method m = fqnMethod(p.getClass());
		if (m != NONE) {
			try {
				Object r = m.invoke(p);
				if (r instanceof String s && !s.isEmpty()) return s;
			} catch (Exception ignored) {
			}
		}
		// Fallback: parse "SimpleName - package.name" shaped display strings.
		// Only use this if the display string has a dash separator AND the simple name has no parentheses
		// (those indicate method proposals).
		String d;
		try {
			d = p.getDisplayString();
		} catch (Exception e) {
			return null;
		}
		if (d == null) return null;
		int dash = d.lastIndexOf(" - ");
		if (dash <= 0) return null;
		String simple = d.substring(0, dash).trim();
		if (simple.indexOf('(') >= 0 || simple.indexOf(':') >= 0) return null;
		int angle = simple.indexOf('<');
		if (angle >= 0) simple = simple.substring(0, angle).trim();
		String pkg = d.substring(dash + 3).trim();
		// Strip trailing annotations like "(default package)" or " (via ...)"
		int space = pkg.indexOf(' ');
		if (space > 0) pkg = pkg.substring(0, space);
		if (pkg.isEmpty() || simple.isEmpty()) return null;
		return pkg + "." + simple;
	}

	static private int compareByRelevance (ICompletionProposal p1, ICompletionProposal p2) {
		int r1 = relevance(p1);
		int r2 = relevance(p2);
		if (r1 != r2) return r2 - r1;
		String d1 = safeDisplay(p1);
		String d2 = safeDisplay(p2);
		return d1.compareToIgnoreCase(d2);
	}

	static private int relevance (ICompletionProposal p) {
		if (p instanceof org.eclipse.jdt.ui.text.java.IJavaCompletionProposal jp) return jp.getRelevance();
		return 0;
	}

	static private String safeDisplay (ICompletionProposal p) {
		try {
			String d = p.getDisplayString();
			return d != null ? d : "";
		} catch (Exception e) {
			return "";
		}
	}
}
