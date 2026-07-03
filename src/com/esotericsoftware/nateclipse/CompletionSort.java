
package com.esotericsoftware.nateclipse;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.core.ElementChangedEvent;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IElementChangedListener;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaElementDelta;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.ILocalVariable;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageDeclaration;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
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
 * <li>Proposals whose name exactly matches the typed prefix (case-sensitive), regardless of kind, so JDT's subword/camelCase
 * matching (eg {@code drawSkeletonOutline} for {@code sout}) never outranks an exact match.
 * <li>Proposals whose name matches the typed prefix ignoring case (eg {@code String} for {@code string}) — never treated as
 * exact, but still above all fuzzy matches. Ties within either group fall through to the tiers below.
 * <li>Method-stub generators (override method, getter/setter, record accessor, method declaration).
 * <li>Local variables declared <em>above</em> the cursor in the enclosing executable scope, ordered nearest-first.
 * <li>Parameters of the enclosing method/lambda/etc, in declaration order.
 * <li>Local variables declared <em>below</em> the cursor (eg in following statements), nearest-first.
 * <li>Methods and fields declared in (or inherited by) the enclosing type.
 * <li>Templates only when the typed prefix is a strong match (eg {@code sout} -> {@code sysout}).
 * <li>Type proposals, scored: types declared in the current CU > imported > same package > workspace > common JDK > rest.
 * <li>Everything else (keywords, non-matching members from elsewhere), by relevance.
 * <li>Weak template matches, so generic snippets don't bury normal Java proposals.
 * </ol>
 * <b>Why context is rebuilt lazily in {@link #compare}:</b> JDT registers this {@link AbstractProposalSorter} with the JFace
 * {@link org.eclipse.jface.text.contentassist.ContentAssistant} via {@code setSorter}, which only calls {@code compare} —
 * {@code beginSorting} / {@code endSorting} are <em>never</em> invoked along that path. So we instead refresh per-session context
 * inside {@code compare}, keyed by (active compilation unit, cursor offset) so it only recomputes once per content-assist
 * session. */
public class CompletionSort extends AbstractProposalSorter {
	static private final ILog log = Platform.getLog(CompletionSort.class);

	static public final String ID = "com.esotericsoftware.nateclipse.completionSort";

	static private final int STRONG_TEMPLATE_MATCH = 80;
	static private final int MIN_TEMPLATE_PREFIX_LENGTH = 2;

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
	/** Element names of fields and methods accessible from the {@link IType} surrounding the completion offset (declared members
	 * plus inherited members from supertypes excluding {@link Object}, plus members of any outer/declaring type for inner-class
	 * contexts). Empty when the completion is not inside a type body. */
	private Set<String> enclosingTypeMembers = Collections.emptySet();
	/** Local variable name -> source start offset, for variables declared <em>before</em> the cursor in the enclosing executable
	 * scope. Used to surface in-scope locals just below stub generators, sorted by descending position so the nearest declaration
	 * wins. */
	private Map<String, Integer> localsAbove = Collections.emptyMap();
	/** Parameter name -> source start offset, for parameters of the enclosing method. Sorted ascending (declaration order) within
	 * their tier. */
	private Map<String, Integer> parameters = Collections.emptyMap();
	/** Local variable name -> source start offset, for variables declared <em>after</em> the cursor (eg used in unfinished forward
	 * references). Sorted ascending so the nearest declaration wins. */
	private Map<String, Integer> localsBelow = Collections.emptyMap();
	private Set<String> importedTypes = Collections.emptySet();
	private Set<String> importedPackages = Collections.emptySet();
	/** Java identifier prefix immediately before the content-assist offset. Used to promote only high-confidence template
	 * matches. */
	private String activePrefix = "";

	/** Cache key for {@link #ensureContext}. Refresh only happens when this triple changes (typically once per session). The
	 * version is observed from {@link #CACHE_VERSION} so a model change forces a refresh even at the same {@code (cu, offset)}. */
	private ICompilationUnit lastCu;
	private int lastOffset = -1;
	private long lastCacheVersion = -1;

	/** Cross-session cache of enclosing-type member sets. Key is the {@link IType} handle (held weakly so closing the editor lets
	 * JDT and us GC it). The cached set survives across content-assist sessions in the same Eclipse run, so once a type's
	 * supertype hierarchy is walked the result is reused for every subsequent completion that lands in the same enclosing type —
	 * even after typing more characters (which invalidates our (cu, offset) key). Pre-populated from {@link #prewarm} so the first
	 * completion in a freshly-opened editor doesn't pay the type-hierarchy cost.
	 * <p>
	 * <b>Invalidation:</b> a {@link JavaCore} {@link IElementChangedListener} clears this map whenever any {@link IType} gets a
	 * children-list delta (member added/removed/reordered). That covers both saved and working-copy edits because we listen for
	 * both {@link ElementChangedEvent#POST_CHANGE} and {@link ElementChangedEvent#POST_RECONCILE}. */
	static private final Map<IType, Set<String>> ENCLOSING_MEMBER_CACHE = Collections.synchronizedMap(new WeakHashMap<>());

	/** Bumped every time {@link #ENCLOSING_MEMBER_CACHE} is invalidated. Per-instance per-session state observes this so even if
	 * the user re-triggers completion at the exact same {@code (cu, offset)} the model state is re-read. */
	static private final AtomicLong CACHE_VERSION = new AtomicLong();

	static {
		JavaCore.addElementChangedListener(new IElementChangedListener() {
			@Override
			public void elementChanged (ElementChangedEvent event) {
				try {
					if (hasTypeStructuralChange(event.getDelta())) {
						ENCLOSING_MEMBER_CACHE.clear();
						CACHE_VERSION.incrementAndGet();
					}
				} catch (Exception ex) {
					log.error("CompletionSort cache invalidation failed", ex);
				}
			}
		}, ElementChangedEvent.POST_CHANGE | ElementChangedEvent.POST_RECONCILE);
	}

	/** Recursively scans a Java element delta for an {@link IType} whose member list changed. We only care about adds / removes /
	 * reorders of children on a type — method bodies and field initializers don't affect our member-name set. */
	static private boolean hasTypeStructuralChange (IJavaElementDelta delta) {
		if (delta == null) return false;
		if (delta.getElement() instanceof IType && (delta.getFlags() & IJavaElementDelta.F_CHILDREN) != 0) return true;
		for (IJavaElementDelta child : delta.getAffectedChildren())
			if (hasTypeStructuralChange(child)) return true;
		return false;
	}

	// --- Debug ---
	static private final boolean DEBUG = false;
	/** Limit per-proposal logging to a handful of entries per refresh to avoid log spam. */
	private int debugProposalsLogged;

	/** Proposal identity -> computed score. Reset on each context refresh. */
	private final Map<ICompletionProposal, Integer> scoreCache = new HashMap<>();
	/** Proposal identity -> leading identifier (proposal name). Reset on each context refresh. */
	private final Map<ICompletionProposal, String> nameCache = new HashMap<>();
	/** Proposal identity -> computed tier. Reset on each context refresh. */
	private final Map<ICompletionProposal, Integer> tierCache = new HashMap<>();
	/** Proposal identity -> fuzzy match score against {@link #activePrefix}. Reset on each context refresh. */
	private final Map<ICompletionProposal, Integer> templateMatchCache = new HashMap<>();
	/** Proposal identity -> exact-match rank against {@link #activePrefix}: 0 = case-sensitive match, 1 = case-insensitive match,
	 * 2 = no match. Reset on each context refresh. */
	private final Map<ICompletionProposal, Integer> exactMatchCache = new HashMap<>();

	@Override
	public void beginSorting (ContentAssistInvocationContext context) {
		// Not invoked by JFace's setSorter path; kept only so the legacy ProposalSorterHandle.sortProposals path still works
		// if anything still uses it. The real refresh happens in compare() -> ensureContext().
		if (DEBUG)
			log.info("CompletionSort.beginSorting ENTER ctx=" + (context == null ? "null" : context.getClass().getSimpleName()));
	}

	/** Refreshes per-session context (enclosing type members, current CU types, imports, etc) if the active editor's CU or cursor
	 * offset has changed since the last refresh. Called from {@link #compare} so it works under JFace's {@code setSorter} path
	 * which never invokes {@link #beginSorting}. */
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
		long version = CACHE_VERSION.get();
		if (cu == lastCu && offset == lastOffset && version == lastCacheVersion) return;
		lastCu = cu;
		lastOffset = offset;
		lastCacheVersion = version;
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
		long t0 = DEBUG ? System.nanoTime() : 0L;
		scoreCache.clear();
		nameCache.clear();
		tierCache.clear();
		templateMatchCache.clear();
		exactMatchCache.clear();
		activeProject = null;
		activePackage = null;
		currentCuTypes = Collections.emptySet();
		enclosingTypeMembers = Collections.emptySet();
		localsAbove = Collections.emptyMap();
		parameters = Collections.emptyMap();
		localsBelow = Collections.emptyMap();
		importedTypes = Collections.emptySet();
		importedPackages = Collections.emptySet();
		activePrefix = "";
		debugProposalsLogged = 0;
		IType debugEnclosingType = null;
		boolean debugMembersFromCache = false;
		try {
			activePrefix = completionPrefix(cu, offset);
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
			IMethod enclosingMethod = null;
			IType enclosingType = null;
			while (el != null) {
				if (enclosingMethod == null && el instanceof IMethod m) enclosingMethod = m;
				if (el instanceof IType t) {
					enclosingType = t;
					break;
				}
				el = el.getParent();
			}
			if (enclosingType != null) {
				debugEnclosingType = enclosingType;
				Set<String> cached = ENCLOSING_MEMBER_CACHE.get(enclosingType);
				if (cached != null) {
					enclosingTypeMembers = cached;
					debugMembersFromCache = true;
				} else {
					enclosingTypeMembers = collectAccessibleMembers(enclosingType);
					ENCLOSING_MEMBER_CACHE.put(enclosingType, enclosingTypeMembers);
				}
			}
			collectScopeLocals(cu, offset, enclosingMethod);
		} catch (Exception ex) {
			log.error("CompletionSort.refreshFor failed", ex);
		}
		if (DEBUG) {
			long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
			log.info("CompletionSort.refreshFor: cu=" + cu.getElementName() + ", offset=" + offset //
				+ ", prefix=" + activePrefix //
				+ ", elapsedMs=" + elapsedMs //
				+ ", membersCached=" + debugMembersFromCache //
				+ ", project=" + activeProject + ", package=" + activePackage //
				+ ", enclosingType=" + (debugEnclosingType == null ? "null" : debugEnclosingType.getFullyQualifiedName('.')) //
				+ ", memberCount=" + enclosingTypeMembers.size() //
				+ ", paramCount=" + parameters.size() + " " + parameters.keySet() //
				+ ", localsAbove=" + localsAbove.keySet() //
				+ ", localsBelow=" + localsBelow.keySet());
		}
	}

	/** Pre-loads the enclosing type's accessible members into {@link #ENCLOSING_MEMBER_CACHE} so that the first content-assist
	 * invocation in {@code cu} doesn't pay the {@link IType#newSupertypeHierarchy} and supertype member iteration cost on the UI
	 * thread. Safe to call on a background thread. Best-effort: any exceptions are swallowed. */
	static public void prewarm (ICompilationUnit cu, IProgressMonitor monitor) {
		try {
			for (IType t : cu.getAllTypes()) {
				if (monitor != null && monitor.isCanceled()) return;
				if (ENCLOSING_MEMBER_CACHE.containsKey(t)) continue;
				try {
					ENCLOSING_MEMBER_CACHE.put(t, collectAccessibleMembers(t));
				} catch (JavaModelException ignored) {
				}
			}
		} catch (Exception ignored) {
		}
	}

	/** Populates {@link #parameters}, {@link #localsAbove}, and {@link #localsBelow} for the executable scope enclosing
	 * {@code offset}. Strategy:
	 * <ul>
	 * <li>Parameters come from {@link IMethod#getParameters()} — fast, no AST parse needed.
	 * <li>Locals require an AST. We parse only the source range of the enclosing method via
	 * {@link ASTParser#K_CLASS_BODY_DECLARATIONS}, which on a typical large file is roughly an order of magnitude faster than
	 * parsing the entire compilation unit ({@link ASTParser#K_COMPILATION_UNIT}).
	 * </ul>
	 * If the cursor is in an initializer, lambda, or other non-method scope, locals are skipped (the common case is method
	 * bodies). */
	private void collectScopeLocals (ICompilationUnit cu, int offset, IMethod enclosingMethod) {
		if (enclosingMethod == null) return;
		try {
			// Parameters via the Java model: avoids an AST parse, and IMethod.getParameters returns ILocalVariables with
			// proper source ranges.
			Map<String, Integer> params = new HashMap<>();
			for (ILocalVariable p : enclosingMethod.getParameters()) {
				ISourceRange r = p.getNameRange();
				params.put(p.getElementName(), Integer.valueOf(r != null ? r.getOffset() : 0));
			}
			parameters = params;

			// Locals via a method-scoped AST. Parsing just the method declaration is much cheaper than the whole CU.
			ISourceRange methodRange = enclosingMethod.getSourceRange();
			if (methodRange == null || methodRange.getLength() <= 0) return;
			String methodSrc;
			try {
				methodSrc = cu.getBuffer().getText(methodRange.getOffset(), methodRange.getLength());
			} catch (Exception ex) {
				return;
			}
			ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
			parser.setSource(methodSrc.toCharArray());
			parser.setKind(ASTParser.K_CLASS_BODY_DECLARATIONS);
			parser.setResolveBindings(false);
			parser.setStatementsRecovery(true);
			ASTNode root = parser.createAST(null);
			if (root == null) return;
			final int methodStart = methodRange.getOffset();
			final Map<String, Integer> above = new HashMap<>();
			final Map<String, Integer> below = new HashMap<>();
			final Set<String> paramNames = params.keySet();
			root.accept(new ASTVisitor() {
				@Override
				public boolean visit (VariableDeclarationFragment node) {
					put(node.getName().getIdentifier(), node.getStartPosition());
					return true;
				}

				@Override
				public boolean visit (SingleVariableDeclaration node) {
					String name = node.getName().getIdentifier();
					// The enclosing method's own parameters appear as top-level SingleVariableDeclarations; we already have
					// them via IMethod.getParameters. Anything else is a for-each / catch / pattern variable.
					if (paramNames.contains(name)) return true;
					put(name, node.getStartPosition());
					return true;
				}

				private void put (String name, int relPos) {
					int absPos = methodStart + relPos;
					Map<String, Integer> map = absPos < offset ? above : below;
					map.putIfAbsent(name, Integer.valueOf(absPos));
				}
			});
			localsAbove = above;
			localsBelow = below;
		} catch (Exception ex) {
			log.error("CompletionSort.collectScopeLocals failed", ex);
		}
	}

	/** Collects the simple names of all fields and methods accessible from {@code type}: members declared on it, members inherited
	 * from its supertypes (excluding {@link Object} so common methods like {@code toString} don't dominate), and members of any
	 * outer/declaring type (for inner and anonymous class contexts). Pure public JDT model API. */
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
		for (IField f : t.getFields())
			out.add(f.getElementName());
		for (IMethod m : t.getMethods())
			out.add(m.getElementName());
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
		// Exact name matches always come first, regardless of proposal kind: JDT's subword/camelCase matching can propose
		// eg drawSkeletonOutline for "sout", which must never outrank a proposal actually named "sout". Case-sensitive
		// matches beat case-insensitive ones. Ties within a rank fall through to the tier order below.
		int e1 = exactMatchRank(p1);
		int e2 = exactMatchRank(p2);
		if (e1 != e2) return e1 - e2;
		// Strict tiered ordering. Must be a total order or TimSort throws "Comparison method violates its general contract!".
		// 0: method-stub generators (override, getter/setter, etc).
		// 1: locals declared above the cursor (sub-sorted by source position descending = nearest first).
		// 2: parameters of the enclosing method/lambda (sub-sorted by declaration order).
		// 3: locals declared below the cursor (sub-sorted ascending = nearest first).
		// 4: methods/fields accessible from the enclosing type (declared + inherited + outer-class).
		// 5: high-confidence templates, sub-sorted by descending prefix match score.
		// 6: type proposals, sub-sorted by descending score.
		// 7: everything else, by relevance.
		// 8: weak templates, sub-sorted by descending prefix match score.
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
		if (t1 == 5 || t1 == 8) {
			int diff = templateMatchScore(p2) - templateMatchScore(p1);
			if (diff != 0) return diff;
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
				t = isStrongTemplateMatch(p) ? 5 : 8;
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

	/** Ranks how exactly the proposal's name matches the typed prefix: 0 = case-sensitive match, 1 = case-insensitive match, 2 =
	 * no match. Templates are matched by their template name rather than the display string. */
	private int exactMatchRank (ICompletionProposal p) {
		Integer cached = exactMatchCache.get(p);
		if (cached != null) return cached.intValue();
		int rank = 2;
		if (!activePrefix.isEmpty()) {
			String name = isTemplate(p) ? templateName(p) : nameOf(p);
			if (activePrefix.equals(name))
				rank = 0;
			else if (activePrefix.equalsIgnoreCase(name)) //
				rank = 1;
		}
		exactMatchCache.put(p, Integer.valueOf(rank));
		return rank;
	}

	private boolean isStrongTemplateMatch (ICompletionProposal p) {
		return templateMatchScore(p) >= STRONG_TEMPLATE_MATCH;
	}

	private int templateMatchScore (ICompletionProposal p) {
		Integer cached = templateMatchCache.get(p);
		if (cached != null) return cached.intValue();
		String prefix = activePrefix;
		int score = prefix.length() < MIN_TEMPLATE_PREFIX_LENGTH ? 0 : fuzzyTemplateScore(prefix, templateName(p));
		templateMatchCache.put(p, Integer.valueOf(score));
		return score;
	}

	private String templateName (ICompletionProposal p) {
		try {
			Method getTemplate = null;
			for (Class<?> c = p.getClass(); c != null && getTemplate == null; c = c.getSuperclass()) {
				try {
					getTemplate = c.getDeclaredMethod("getTemplate");
					getTemplate.setAccessible(true);
				} catch (NoSuchMethodException ignored) {
				}
			}
			if (getTemplate != null) {
				Object template = getTemplate.invoke(p);
				if (template != null) {
					Object name = template.getClass().getMethod("getName").invoke(template);
					if (name instanceof String s && !s.isEmpty()) return s;
				}
			}
		} catch (Exception ignored) {
		}
		return nameOf(p);
	}

	static private int fuzzyTemplateScore (String prefix, String name) {
		if (prefix == null || prefix.isEmpty() || name == null || name.isEmpty()) return 0;
		String p = prefix.toLowerCase();
		String n = name.toLowerCase();
		if (p.equals(n)) return 100;
		if (n.startsWith(p)) return 90 + Math.min(10, p.length() * 10 / Math.max(1, n.length()));
		if (n.contains(p)) return 70 + Math.min(20, p.length() * 20 / Math.max(1, n.length()));

		int pi = 0;
		int start = -1;
		int last = -1;
		int gaps = 0;
		for (int i = 0, nn = n.length(), pp = p.length(); i < nn && pi < pp; i++) {
			if (n.charAt(i) != p.charAt(pi)) continue;
			if (start < 0) start = i;
			if (last >= 0) gaps += i - last - 1;
			last = i;
			pi++;
		}
		if (pi < p.length()) return 0;
		int score = 100 - gaps * 8 - start * 4 - (n.length() - p.length()) * 2;
		return Math.max(0, Math.min(99, score));
	}

	static private String completionPrefix (ICompilationUnit cu, int offset) {
		try {
			org.eclipse.jdt.core.IBuffer buffer = cu.getBuffer();
			if (buffer == null) return "";
			int len = buffer.getLength();
			if (offset > len) offset = len;
			int start = offset;
			while (start > 0 && Character.isJavaIdentifierPart(buffer.getChar(start - 1)))
				start--;
			return start == offset ? "" : buffer.getText(start, offset - start);
		} catch (Exception ex) {
			return "";
		}
	}

	/** Cached lookup of the proposal's leading identifier (the proposal name). Empty string is used as a sentinel for "no
	 * identifier" so we can distinguish from "not yet computed" via the cache miss. */
	private String nameOf (ICompletionProposal p) {
		String cached = nameCache.get(p);
		if (cached != null) return cached;
		String name = leadingIdentifier(safeDisplay(p));
		if (name == null) name = "";
		nameCache.put(p, name);
		return name;
	}

	/** Extracts the longest leading run of Java identifier characters from {@code s}. Used to recover the proposal's member name
	 * (which JDT always places at the start of the display string, eg {@code "eyeButton : EyeButton - Foo"} or
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
