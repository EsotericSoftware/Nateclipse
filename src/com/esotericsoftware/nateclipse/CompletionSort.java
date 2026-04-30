
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
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Initializer;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
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
 * <li>Method-stub generators (override method, getter/setter, record accessor, method declaration).
 * <li>Local variables declared <em>above</em> the cursor in the enclosing executable scope, ordered nearest-first.
 * <li>Parameters of the enclosing method/lambda/etc, in declaration order.
 * <li>Local variables declared <em>below</em> the cursor (eg in following statements), nearest-first.
 * <li>Methods and fields declared in (or inherited by) the enclosing type.
 * <li>Templates (eg {@code sout} -> {@code System.out.println()}).
 * <li>Type proposals, scored: types declared in the current CU > imported > same package > workspace > common JDK > rest.
 * <li>Everything else (keywords, non-matching members from elsewhere), by relevance.
 * </ol>
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
	/** Local variable name -> source start offset, for variables declared <em>before</em> the cursor in the enclosing
	 * executable scope. Used to surface in-scope locals just below stub generators, sorted by descending position so the
	 * nearest declaration wins. */
	private Map<String, Integer> localsAbove = Collections.emptyMap();
	/** Parameter name -> source start offset, for parameters of the enclosing {@link MethodDeclaration} or
	 * {@link LambdaExpression}. Sorted ascending (declaration order) within their tier. */
	private Map<String, Integer> parameters = Collections.emptyMap();
	/** Local variable name -> source start offset, for variables declared <em>after</em> the cursor (eg used in unfinished
	 * forward references). Sorted ascending so the nearest declaration wins. */
	private Map<String, Integer> localsBelow = Collections.emptyMap();
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
	/** Proposal identity -> leading identifier (proposal name). Reset on each context refresh. */
	private final Map<ICompletionProposal, String> nameCache = new HashMap<>();
	/** Proposal identity -> computed tier. Reset on each context refresh. */
	private final Map<ICompletionProposal, Integer> tierCache = new HashMap<>();

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
		nameCache.clear();
		tierCache.clear();
		activeProject = null;
		activePackage = null;
		currentCuTypes = Collections.emptySet();
		enclosingTypeMembers = Collections.emptySet();
		localsAbove = Collections.emptyMap();
		parameters = Collections.emptyMap();
		localsBelow = Collections.emptyMap();
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
			collectScopeLocals(cu, offset);
		} catch (Exception ex) {
			log.error("CompletionSort.refreshFor failed", ex);
		}
		if (DEBUG) {
			log.info("CompletionSort.refreshFor: cu=" + cu.getElementName() + ", offset=" + offset //
				+ ", project=" + activeProject + ", package=" + activePackage //
				+ ", enclosingType="
				+ (debugEnclosingType == null ? "null" : debugEnclosingType.getFullyQualifiedName('.')) //
				+ ", memberCount=" + enclosingTypeMembers.size() //
				+ ", paramCount=" + parameters.size() + " " + parameters.keySet() //
				+ ", localsAbove=" + localsAbove.keySet() //
				+ ", localsBelow=" + localsBelow.keySet() //
				+ ", members=" + enclosingTypeMembers);
		}
	}

	/** Parses the working-copy AST of {@code cu}, finds the executable scope (method / initializer / lambda) enclosing
	 * {@code offset}, and populates {@link #parameters}, {@link #localsAbove}, and {@link #localsBelow}. The AST parse
	 * happens once per session in {@link #refreshFor}, not per proposal. */
	private void collectScopeLocals (ICompilationUnit cu, int offset) {
		try {
			ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
			parser.setSource(cu);
			parser.setKind(ASTParser.K_COMPILATION_UNIT);
			parser.setResolveBindings(false);
			parser.setStatementsRecovery(true);
			ASTNode root = parser.createAST(null);
			if (!(root instanceof org.eclipse.jdt.core.dom.CompilationUnit ast)) return;
			ASTNode at = NodeFinder.perform(ast, offset, 0);
			ASTNode scope = at;
			while (scope != null && !(scope instanceof MethodDeclaration) && !(scope instanceof Initializer)
				&& !(scope instanceof LambdaExpression)) {
				scope = scope.getParent();
			}
			if (scope == null) return;
			Map<String, Integer> params = new HashMap<>();
			Map<String, Integer> above = new HashMap<>();
			Map<String, Integer> below = new HashMap<>();
			// Parameters of the enclosing executable.
			if (scope instanceof MethodDeclaration md) {
				for (Object o : md.parameters()) {
					if (o instanceof SingleVariableDeclaration p)
						params.put(p.getName().getIdentifier(), Integer.valueOf(p.getStartPosition()));
				}
			} else if (scope instanceof LambdaExpression le) {
				for (Object o : le.parameters()) {
					if (o instanceof VariableDeclaration vd)
						params.put(vd.getName().getIdentifier(), Integer.valueOf(vd.getStartPosition()));
				}
			}
			// Locals declared anywhere inside the scope's body (visiting nested blocks; for-loop, try-with-resources,
			// catch parameters, pattern variables, etc all appear as VariableDeclarationFragment or SingleVariableDeclaration).
			final ASTNode finalScope = scope;
			scope.accept(new ASTVisitor() {
				@Override
				public boolean visit (VariableDeclarationFragment node) {
					put(node.getName().getIdentifier(), node.getStartPosition());
					return true;
				}

				@Override
				public boolean visit (SingleVariableDeclaration node) {
					// Skip the parameters of the enclosing scope itself; already collected into `params` above.
					if (node.getParent() != finalScope) put(node.getName().getIdentifier(), node.getStartPosition());
					return true;
				}

				private void put (String name, int pos) {
					Map<String, Integer> map = pos < offset ? above : below;
					map.putIfAbsent(name, Integer.valueOf(pos));
				}
			});
			parameters = params;
			localsAbove = above;
			localsBelow = below;
		} catch (Exception ex) {
			log.error("CompletionSort.collectScopeLocals failed", ex);
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
		// Strict tiered ordering. Must be a total order or TimSort throws "Comparison method violates its general contract!".
		// 0: method-stub generators (override, getter/setter, etc).
		// 1: locals declared above the cursor (sub-sorted by source position descending = nearest first).
		// 2: parameters of the enclosing method/lambda (sub-sorted by declaration order).
		// 3: locals declared below the cursor (sub-sorted ascending = nearest first).
		// 4: methods/fields accessible from the enclosing type (declared + inherited + outer-class).
		// 5: templates.
		// 6: type proposals, sub-sorted by descending score.
		// 7: everything else, by relevance.
		int t1 = tier(p1);
		int t2 = tier(p2);
		if (t1 != t2) return t1 - t2;
		if (t1 == 1 || t1 == 2 || t1 == 3) {
			Map<String, Integer> map = t1 == 1 ? localsAbove : t1 == 2 ? parameters : localsBelow;
			Integer pos1 = map.get(nameOf(p1));
			Integer pos2 = map.get(nameOf(p2));
			if (pos1 != null && pos2 != null) {
				// Tier 1 (above): nearer = larger position; descending. Tier 2/3: ascending.
				int diff = t1 == 1 ? Integer.compare(pos2.intValue(), pos1.intValue())
					: Integer.compare(pos1.intValue(), pos2.intValue());
				if (diff != 0) return diff;
			}
		}
		if (t1 == 6) {
			Integer s1 = scoreOrNull(p1);
			Integer s2 = scoreOrNull(p2);
			if (!s1.equals(s2)) return s2.intValue() - s1.intValue();
		}
		return compareByRelevance(p1, p2);
	}

	private int tier (ICompletionProposal p) {
		Integer cached = tierCache.get(p);
		if (cached != null) return cached.intValue();
		int t;
		if (isStubGenerator(p))
			t = 0;
		else {
			String name = nameOf(p);
			if (!name.isEmpty() && localsAbove.containsKey(name))
				t = 1;
			else if (!name.isEmpty() && parameters.containsKey(name))
				t = 2;
			else if (!name.isEmpty() && localsBelow.containsKey(name))
				t = 3;
			else if (!name.isEmpty() && enclosingTypeMembers.contains(name))
				t = 4;
			else if (isTemplate(p))
				t = 5;
			else
				t = scoreOrNull(p) != null ? 6 : 7;
		}
		tierCache.put(p, Integer.valueOf(t));
		if (DEBUG && debugProposalsLogged < 30) {
			debugProposalsLogged++;
			log.info("CompletionSort.tier=" + t + " name=" + nameOf(p) + " class=" + p.getClass().getSimpleName() + " display="
				+ safeDisplay(p));
		}
		return t;
	}

	/** Cached lookup of the proposal's leading identifier (the proposal name). Empty string is used as a sentinel for
	 * "no identifier" so we can distinguish from "not yet computed" via the cache miss. */
	private String nameOf (ICompletionProposal p) {
		String cached = nameCache.get(p);
		if (cached != null) return cached;
		String name = leadingIdentifier(safeDisplay(p));
		if (name == null) name = "";
		nameCache.put(p, name);
		return name;
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
