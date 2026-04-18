
package com.esotericsoftware.nateclipse.utils;

import java.util.Comparator;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jdt.internal.ui.dialogs.FilteredTypesSelectionDialog;
import org.eclipse.jface.operation.IRunnableContext;
import org.eclipse.swt.widgets.Shell;

import com.esotericsoftware.nateclipse.utils.TypeRanking.Classification;
import com.esotericsoftware.nateclipse.utils.TypeRanking.Origin;

/** {@link FilteredTypesSelectionDialog} subclass that re-ranks candidates using {@link TypeRanking}.
 * <p>
 * This reaches into an internal JDT UI class; the access is tolerated via "discouraged reference" warnings. */
@SuppressWarnings("restriction")
public class RankedTypeDialog extends FilteredTypesSelectionDialog {
	static private final ILog log = Platform.getLog(RankedTypeDialog.class);

	private final String activeProject;

	public RankedTypeDialog (Shell shell, boolean multi, IRunnableContext context, IJavaSearchScope scope, int elementKinds,
		String activeProject) {
		super(shell, multi, context, scope, elementKinds);
		this.activeProject = activeProject;
	}

	@Override
	protected Comparator<?> getItemsComparator () {
		return new TypeNameMatchComparator(activeProject);
	}

	/** Comparator works on {@link TypeNameMatch} items (what FilteredTypesSelectionDialog produces), but we accept {@link Object}
	 * defensively because FilteredItemsSelectionDialog passes items through this comparator in a few places. */
	static private final class TypeNameMatchComparator implements Comparator<Object> {
		private final String activeProject;

		TypeNameMatchComparator (String activeProject) {
			this.activeProject = activeProject;
		}

		@Override
		public int compare (Object a, Object b) {
			int sa = scoreOf(a);
			int sb = scoreOf(b);
			if (sa != sb) return sb - sa;
			// Stable, deterministic tiebreaker by FQN so the dialog doesn't consider different items as duplicates.
			return nameOf(a).compareToIgnoreCase(nameOf(b));
		}

		private int scoreOf (Object o) {
			if (!(o instanceof TypeNameMatch match)) return 0;
			try {
				Classification c = classifyMatch(match);
				return TypeRanking.scoreForOpenType(c, activeProject);
			} catch (Exception ex) {
				log.error("Open Type: failed to score " + nameOf(o), ex);
				return 0;
			}
		}

		static private Classification classifyMatch (TypeNameMatch match) {
			String fqn = match.getFullyQualifiedName();
			// Prefer classifying via the root if we can; IType may be null for unresolved matches.
			IType type = match.getType();
			IPackageFragmentRoot root = null;
			IJavaProject jp = null;
			if (type != null) {
				root = (IPackageFragmentRoot)type.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
				jp = type.getJavaProject();
			}
			if (root == null) {
				// TypeNameMatch#getPackageFragmentRoot() exists on recent JDT; guarded via reflection for safety.
				try {
					Object r = match.getClass().getMethod("getPackageFragmentRoot").invoke(match);
					if (r instanceof IPackageFragmentRoot) root = (IPackageFragmentRoot)r;
				} catch (Exception ignored) {
				}
			}
			if (root != null) return TypeRanking.classifyRoot(root, fqn);
			// Last resort: classify by FQN (expensive, falls back to workspace scan).
			Classification byFqn = TypeRanking.classify(fqn);
			if (byFqn.origin != Origin.UNKNOWN) return byFqn;
			String project = jp != null ? jp.getElementName() : null;
			return new Classification(Origin.UNKNOWN, project, fqn);
		}

		static private String nameOf (Object o) {
			if (o instanceof TypeNameMatch m) {
				String fqn = m.getFullyQualifiedName();
				return fqn != null ? fqn : "";
			}
			return o != null ? o.toString() : "";
		}
	}
}
