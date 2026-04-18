
package com.esotericsoftware.nateclipse.utils;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;

/** Shared classification and scoring used by the content-assist sorter and the Open Type dialog.
 * <p>
 * All "score" methods return higher-is-better ints. Callers can then compare by descending score and break ties however they like
 * (name, relevance, etc.). */
public class TypeRanking {
	// ----- Tier constants. Tweak here to adjust ordering. -----

	// Open Type dialog: rules per user spec.
	// 1. MRU types (most recent first)
	// 2. Same project as active editor (MRU-ranked types first)
	// 3. Any other workspace project (by MRU project rank)
	// 4. Workspace JARs
	// 5. JDK
	static public final int OPEN_TYPE_MRU = 100_000;
	static public final int OPEN_TYPE_SAME_PROJECT = 80_000;
	static public final int OPEN_TYPE_WORKSPACE = 60_000;
	static public final int OPEN_TYPE_WORKSPACE_JAR = 40_000;
	static public final int OPEN_TYPE_JDK = 20_000;

	// Content assist: same idea, but also prefer types declared in the current CU,
	// allow JDK types to appear above non-type proposals by giving them a non-trivial floor.
	static public final int CA_CURRENT_CU = 100_000;
	static public final int CA_SAME_PACKAGE = 90_000;
	static public final int CA_SAME_PROJECT = 80_000;
	static public final int CA_WORKSPACE = 70_000;
	static public final int CA_WORKSPACE_JAR = 50_000;
	static public final int CA_COMMON_JDK = 40_000;
	static public final int CA_JDK = 30_000;

	/** Types everyone uses constantly. Get a boost so Ctrl-Space "Str" still shows String up top. */
	static public final Set<String> COMMON_JDK_TYPES = Set.of( //
		"java.lang.String", //
		"java.lang.StringBuilder", //
		"java.lang.Integer", //
		"java.lang.Long", //
		"java.lang.Double", //
		"java.lang.Float", ////
		"java.lang.Boolean", //
		"java.lang.Byte", //
		"java.lang.Short", //
		"java.lang.Character", ////
		"java.lang.Object", //
		"java.lang.Class", //
		"java.lang.Thread", //
		"java.lang.Throwable", ////
		"java.lang.Exception", //
		"java.lang.RuntimeException", //
		"java.lang.Math", //
		"java.lang.System", //
		"java.lang.Number", ////
		"java.lang.Iterable", //
		"java.lang.Comparable", //
		"java.lang.Runnable", ////
		"java.util.List", //
		"java.util.ArrayList", //
		"java.util.LinkedList", ////
		"java.util.Map", //
		"java.util.HashMap", //
		"java.util.LinkedHashMap", //
		"java.util.TreeMap", ////
		"java.util.Set", //
		"java.util.HashSet", //
		"java.util.LinkedHashSet", //
		"java.util.TreeSet", ////
		"java.util.Collection", //
		"java.util.Collections", //
		"java.util.Arrays", ////
		"java.util.Objects", //
		"java.util.Iterator", ////
		"java.util.function.Function", //
		"java.util.function.Consumer", //
		"java.util.function.Supplier", ////
		"java.util.function.Predicate", //
		"java.util.function.BiFunction", ////
		"java.io.File", //
		"java.io.IOException", //
		"java.io.InputStream", //
		"java.io.OutputStream", ////
		"java.io.Reader", //
		"java.io.Writer", //
		"java.io.BufferedReader", //
		"java.io.BufferedWriter", ////
		"java.io.ByteArrayInputStream", //
		"java.io.ByteArrayOutputStream", ////
		"java.nio.file.Path", //
		"java.nio.file.Paths", //
		"java.nio.file.Files", ////
		"java.nio.charset.StandardCharsets", //
		"java.nio.charset.Charset", ////
		"java.time.LocalDate", //
		"java.time.LocalDateTime", //
		"java.time.Instant", //
		"java.time.Duration" //
	);

	// ----- Classification -----

	public enum Origin {
		WORKSPACE_SOURCE, WORKSPACE_JAR, JDK, UNKNOWN
	}

	public static final class Classification {
		public final Origin origin;
		public final String projectName; // may be null
		public final String fqn;

		public Classification (Origin origin, String projectName, String fqn) {
			this.origin = origin;
			this.projectName = projectName;
			this.fqn = fqn;
		}
	}

	/** Per-session cache. Keyed by FQN, scoped to the workspace since roots rarely change mid-session.
	 * <p>
	 * Invalidate via {@link #invalidate()} if the classpath changes (not currently wired; acceptable for now). */
	static private final ConcurrentHashMap<String, Classification> cache = new ConcurrentHashMap<>();

	static public void invalidate () {
		cache.clear();
	}

	static public Classification classify (String fqn) {
		if (fqn == null || fqn.isEmpty()) return new Classification(Origin.UNKNOWN, null, fqn);
		Classification cached = cache.get(fqn);
		if (cached != null) return cached;
		Classification computed = computeClassification(fqn);
		cache.put(fqn, computed);
		return computed;
	}

	static private Classification computeClassification (String fqn) {
		try {
			for (var project : org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
				if (!project.isAccessible()) continue;
				IJavaProject jp = JavaCore.create(project);
				if (jp == null || !jp.exists()) continue;
				IType type = jp.findType(fqn);
				if (type == null) continue;
				return classify(type, jp);
			}
		} catch (Exception ignored) {
		}
		return new Classification(Origin.UNKNOWN, null, fqn);
	}

	static public Classification classify (IType type, IJavaProject contextProject) {
		try {
			IPackageFragmentRoot root = (IPackageFragmentRoot)type.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
			if (root == null) return new Classification(Origin.UNKNOWN, null, type.getFullyQualifiedName('.'));
			return classifyRoot(root, type.getFullyQualifiedName('.'));
		} catch (Exception ex) {
			return new Classification(Origin.UNKNOWN, null, null);
		}
	}

	static public Classification classifyRoot (IPackageFragmentRoot root, String fqn) {
		try {
			IJavaProject jp = root.getJavaProject();
			String projectName = jp != null ? jp.getElementName() : null;
			if (root.getKind() == IPackageFragmentRoot.K_SOURCE)
				return new Classification(Origin.WORKSPACE_SOURCE, projectName, fqn);
			// Binary. Decide JDK vs workspace JAR based on the originating classpath entry.
			IClasspathEntry raw = root.getRawClasspathEntry();
			if (raw != null && raw.getEntryKind() == IClasspathEntry.CPE_CONTAINER) {
				IPath path = raw.getPath();
				if (path != null && path.segmentCount() > 0 && JavaRuntime.JRE_CONTAINER.equals(path.segment(0)))
					return new Classification(Origin.JDK, projectName, fqn);
			}
			return new Classification(Origin.WORKSPACE_JAR, projectName, fqn);
		} catch (Exception ex) {
			return new Classification(Origin.UNKNOWN, null, fqn);
		}
	}

	// ----- Scoring -----

	/** Score for Open Type dialog. Higher = earlier in the list. */
	static public int scoreForOpenType (Classification c, String activeProject) {
		if (c == null) return 0;
		MruTracker mru = MruTracker.get();
		int typeRank = mru.getTypeRank(c.fqn);
		int projRank = c.projectName != null ? mru.getProjectRank(c.projectName) : -1;

		// MRU types trump everything except JDK/unknown (which we still push below workspace).
		if (typeRank >= 0 && c.origin != Origin.JDK) return OPEN_TYPE_MRU + (MruTracker.MAX_TYPES - typeRank);

		switch (c.origin) {
		case WORKSPACE_SOURCE:
			if (activeProject != null && activeProject.equals(c.projectName))
				return OPEN_TYPE_SAME_PROJECT + (projRank >= 0 ? MruTracker.MAX_PROJECTS - projRank : 0);
			return OPEN_TYPE_WORKSPACE + (projRank >= 0 ? MruTracker.MAX_PROJECTS - projRank : 0);
		case WORKSPACE_JAR:
			return OPEN_TYPE_WORKSPACE_JAR + (projRank >= 0 ? MruTracker.MAX_PROJECTS - projRank : 0);
		case JDK:
			// MRU still matters within JDK bucket so frequently-opened JDK types float to top of that group.
			return OPEN_TYPE_JDK + (typeRank >= 0 ? MruTracker.MAX_TYPES - typeRank : 0);
		case UNKNOWN:
		default:
			return OPEN_TYPE_JDK - 1_000;
		}
	}

	/** Score for content assist. Higher = earlier. {@code currentCuTypes} may be empty. */
	static public int scoreForCompletion (Classification c, String activeProject, String activePackage,
		Set<String> currentCuTypes) {
		if (c == null) return CA_JDK - 5_000;
		MruTracker mru = MruTracker.get();
		int typeRank = mru.getTypeRank(c.fqn);
		int projRank = c.projectName != null ? mru.getProjectRank(c.projectName) : -1;

		// Types declared in the current compilation unit always win.
		if (currentCuTypes != null && currentCuTypes.contains(c.fqn)) return CA_CURRENT_CU;

		// Same package beats same project.
		if (c.origin == Origin.WORKSPACE_SOURCE && activeProject != null && activeProject.equals(c.projectName)) {
			// (We don't have the package of the candidate cheaply; callers can pass it if they do.)
		}

		switch (c.origin) {
		case WORKSPACE_SOURCE: {
			int base;
			if (activePackage != null && c.fqn != null) {
				int dot = c.fqn.lastIndexOf('.');
				String candidatePkg = dot > 0 ? c.fqn.substring(0, dot) : "";
				if (activePackage.equals(candidatePkg) && activeProject != null && activeProject.equals(c.projectName))
					base = CA_SAME_PACKAGE;
				else if (activeProject != null && activeProject.equals(c.projectName))
					base = CA_SAME_PROJECT;
				else
					base = CA_WORKSPACE;
			} else {
				base = CA_WORKSPACE;
			}
			if (typeRank >= 0) base += MruTracker.MAX_TYPES - typeRank;
			if (projRank >= 0) base += MruTracker.MAX_PROJECTS - projRank;
			return base;
		}
		case WORKSPACE_JAR: {
			int base = CA_WORKSPACE_JAR;
			if (typeRank >= 0) base += MruTracker.MAX_TYPES - typeRank;
			if (projRank >= 0) base += MruTracker.MAX_PROJECTS - projRank;
			return base;
		}
		case JDK: {
			int base = COMMON_JDK_TYPES.contains(c.fqn) ? CA_COMMON_JDK : CA_JDK;
			if (typeRank >= 0) base += MruTracker.MAX_TYPES - typeRank;
			return base;
		}
		case UNKNOWN:
		default:
			return CA_JDK - 5_000;
		}
	}
}
