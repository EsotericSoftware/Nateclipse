
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
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageDeclaration;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jdt.ui.text.java.AbstractProposalSorter;
import org.eclipse.jdt.ui.text.java.ContentAssistInvocationContext;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.templates.TemplateProposal;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.esotericsoftware.nateclipse.utils.TypeRanking;
import com.esotericsoftware.nateclipse.utils.TypeRanking.Classification;

/** Re-orders Java content-assist proposals so that:
 * <ol>
 * <li>Method-stub generators (override method, getter/setter, record accessor, method declaration) come first when present.
 * <li>Methods and fields declared in (or inherited by) the type enclosing the completion offset come next.
 * <li>Templates (eg {@code sout} -> {@code System.out.println()}) come next.
 * <li>Types declared in the current compilation unit come next.
 * <li>Then types already imported by the current compilation unit (explicit imports beat wildcards).
 * <li>Then types in the same package, same project, other workspace projects.
 * <li>Then workspace JARs.
 * <li>Then commonly-used JDK types, then the rest of the JDK.
 * </ol>
 * Non-type, non-template proposals (methods, fields, keywords) that aren't members of the enclosing type keep their relative
 * order via relevance, but sort below scored type proposals.
 * <p>
 * <b>Why context is rebuilt lazily in {@link #compare}:</b> JDT registers this {@link AbstractProposalSorter} with the JFace
 * {@link org.eclipse.jface.text.contentassist.ContentAssistant} via {@code setSorter}, which only calls {@code compare} —
 * {@code beginSorting} / {@code endSorting} are <em>never</em> invoked along that path. So we instead refresh per-session
 * context inside {@code compare}, keyed by (active compilation unit, cursor offset) so it only recomputes once per
 * content-assist session. */
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

	// --- Per-session context, refreshed lazily on first compare() call per content-assist session ---

	private String activeProject;
	private String activePackage;
	private Set<String> currentCuTypes = Collections.emptySet();
	/** Element names of fields and methods accessible from the {@link IType} surrounding the completion offset (declared
	 * members plus inherited members from supertypes excluding {@link Object}, plus members of any outer/declaring type for
	 * inner-class contexts). Empty when the completion is not inside a type body. */
	private Set<String> enclosingTypeMembers = Collections.emptySet();
	private Set<String> importedTypes = Collections.emptySet();
	private Set<String> importedPackages = Collections.emptySet();

	/** Cache key for {@link #ensureContext}. Refresh only happens when this pair changes (typically once per session). */
	private ICompilationUnit lastCu;
	private int lastOffset = -1;

	// --- Debug ---
	static private final boolean DEBUG = true;
	/** Limit per-proposal logging to a handful of entries per refresh to avoid log spam. */
	private int debugProposalsLogged;

	/** Proposal identity -> computed score. Reset on each context refresh. */
	private final Map<ICompletionProposal, Integer> scoreCache = new HashMap<>();

	@Override
	public void beginSorting (ContentAssistInvocationContext context) {
		// Not invoked by JFace's setSorter path; kept only so the legacy ProposalSorterHandle.sortProposals path still works
		// if anything still uses it. The real refresh happens in compare() -> ensureContext().
		if (DEBUG) log.info("CompletionSort.beginSorting ENTER ctx="
			+ (context == null ? "null" : context.getClass().getSimpleName()));
	}

	/** Refreshes per-session context (enclosing type members, current CU types, imports, etc) if the active editor's CU or
	 * cursor offset has changed since the last refresh. Called from {@link #compare} so it works under JFace's
	 * {@code setSorter} path which never invokes {@link #beginSorting}. */
	private void ensureContext () {
		IEditorPart editor = activeEditor();
		if (editor == null) return;
		IJavaElement input = JavaUI.getEditorInputJavaElement(editor.getEditorInput());
		if (!(input instanceof ICompilationUnit cu)) return;
		ISelectionProvider sp = editor.getSite() == null ? null : editor.getSite().getSelectionProvider();
		if (sp == null) return;
		ISelection sel = sp.getSelection();
		if (!(sel instanceof ITextSelection ts)) return;
		int offset = ts.getOffset();
		if (cu == lastCu && offset == lastOffset) return;
		lastCu = cu;
		lastOffset = offset;
		refreshFor(cu, offset);
	}

	static private IEditorPart activeEditor () {
		try {
			IWorkbench wb = PlatformUI.getWorkbench();
			if (wb == null) return null;
			IWorkbenchWindow win = wb.getActiveWorkbenchWindow();
			if (win == null) return null;
			IWorkbenchPage page = win.getActivePage();
			return page == null ? null : page.getActiveEditor();
		} catch (Exception e) {
			return null;
		}
	}

	private void refreshFor (ICompilationUnit cu, int offset) {
		scoreCache.clear();
		activeProject = null;
		activePackage = null;
		currentCuTypes = Collections.emptySet();
		enclosingTypeMembers = Collections.emptySet();
		importedTypes = Collections.emptySet();
		importedPackages = Collections.emptySet();
		debugProposalsLogged = 0;
		IType debugEnclosingType = null;
		try {
			IJavaProject project = cu.getJavaProject();
			if (project != null) activeProject = project.getElementName();
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
			IJavaElement el = cu.getElementAt(offset);
			while (el != null && !(el instanceof IType)) el = el.getParent();
			if (el instanceof IType type) {
				debugEnclosingType = type;
				enclosingTypeMembers = collectAccessibleMembers(type);
			}
		} catch (Exception ex) {
			log.error("CompletionSort.refreshFor failed", ex);
		}
		if (DEBUG) {
			log.info("CompletionSort.refreshFor: cu=" + cu.getElementName() + ", offset=" + offset //
				+ ", project=" + activeProject + ", package=" + activePackage //
				+ ", enclosingType="
				+ (debugEnclosingType == null ? "null" : debugEnclosingType.getFullyQualifiedName('.')) //
				+ ", memberCount=" + enclosingTypeMembers.size() //
				+ ", members=" + enclosingTypeMembers);
		}
	}

	/** Collects the simple names of all fields and methods accessible from {@code type}: members declared on it, members
	 * inherited from its supertypes (excluding {@link Object} so common methods like {@code toString} don't dominate), and
	 * members of any outer/declaring type (for inner and anonymous class contexts). Pure public JDT model API. */
	static private Set<String> collectAccessibleMembers (IType type) throws JavaModelException {
		Set<String> names = new HashSet<>();
		// Members of the type itself plus its enclosing type chain (handles inner / anonymous classes).
		for (IType t = type; t != null; t = t.getDeclaringType()) {
			addDeclaredMembers(t, names);
			// Supertype-inherited members for this level. newSupertypeHierarchy() can be expensive but runs once per
			// completion session in beginSorting, not per proposal.
			try {
				ITypeHierarchy h = t.newSupertypeHierarchy(null);
				for (IType s : h.getAllSupertypes(t)) {
					if ("java.lang.Object".equals(s.getFullyQualifiedName())) continue;
					addDeclaredMembers(s, names);
				}
			} catch (JavaModelException ignored) {
			}
		}
		return names;
	}

	static private void addDeclaredMembers (IType t, Set<String> out) throws JavaModelException {
		for (IField f : t.getFields()) out.add(f.getElementName());
		for (IMethod m : t.getMethods()) out.add(m.getElementName());
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
		// State is kept across sorting passes and refreshed lazily by ensureContext() when the (cu, offset) key changes.
	}

	@Override
	public int compare (ICompletionProposal p1, ICompletionProposal p2) {
		ensureContext();
		// Strict tiered ordering, must be a total order or TimSort throws
		// "Comparison method violates its general contract!".
		// Tier 0: method-stub generators (override method, getter/setter, etc) - most useful in class body context.
		// Tier 1: methods/fields declared in the current compilation unit's types.
		// Tier 2: templates (eg "sout" -> "System.out.println()").
		// Tier 3: type proposals, ordered by descending score.
		// Tier 4: everything else (methods, fields, keywords), ordered by relevance.
		int t1 = tier(p1);
		int t2 = tier(p2);
		if (t1 != t2) return t1 - t2;
		if (t1 == 3) {
			Integer s1 = scoreOrNull(p1);
			Integer s2 = scoreOrNull(p2);
			// Both are tier 3, so both have non-null scores.
			if (!s1.equals(s2)) return s2.intValue() - s1.intValue();
		}
		return compareByRelevance(p1, p2);
	}

	private int tier (ICompletionProposal p) {
		int t;
		if (isStubGenerator(p))
			t = 0;
		else if (isCurrentCuMember(p))
			t = 1;
		else if (isTemplate(p))
			t = 2;
		else
			t = scoreOrNull(p) != null ? 3 : 4;
		if (DEBUG && debugProposalsLogged < 30) {
			debugProposalsLogged++;
			String d = safeDisplay(p);
			String name = leadingIdentifier(d);
			log.info("CompletionSort.tier=" + t + " name=" + name + " classMatch="
				+ (name != null && enclosingTypeMembers.contains(name)) + " class=" + p.getClass().getSimpleName() + " display="
				+ d);
		}
		return t;
	}

	/** True if {@code p}'s leading identifier matches the name of a field or method accessible from the {@link IType} that
	 * encloses the completion offset. The enclosing type and its accessible members are precomputed in
	 * {@link #ensureContext}, so this avoids any reflection into JDT internals or fragile parsing of the proposal's
	 * display-string qualifier. */
	private boolean isCurrentCuMember (ICompletionProposal p) {
		if (enclosingTypeMembers.isEmpty()) return false;
		String name = leadingIdentifier(safeDisplay(p));
		return name != null && enclosingTypeMembers.contains(name);
	}

	/** Extracts the longest leading run of Java identifier characters from {@code s}. Used to recover the proposal's
	 * member name (which JDT always places at the start of the display string, eg {@code "eyeButton : EyeButton - Foo"} or
	 * {@code "setBounds(int, int, int, int) - Foo"}). */
	static private String leadingIdentifier (String s) {
		if (s == null || s.isEmpty()) return null;
		int len = s.length();
		if (!Character.isJavaIdentifierStart(s.charAt(0))) return null;
		int i = 1;
		while (i < len && Character.isJavaIdentifierPart(s.charAt(i)))
			i++;
		return s.substring(0, i);
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
