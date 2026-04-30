
package com.esotericsoftware.nateclipse.utils;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPartReference;

import com.esotericsoftware.nateclipse.CompletionSort;

/** Feeds {@link MruTracker} from editor activation events. */
public class MruPartListener implements IPartListener2 {
	static private final ILog log = Platform.getLog(MruPartListener.class);

	/** Editors we've already scheduled completion-cache pre-warm for. Held weakly so closing the editor lets entries go. We dedupe
	 * across activate/open/bringToTop events so a single editor isn't pre-warmed multiple times. */
	static private final Set<ICompilationUnit> prewarmed = Collections
		.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

	@Override
	public void partActivated (IWorkbenchPartReference partRef) {
		bumpFrom(partRef);
		prewarmCompletion(partRef);
	}

	@Override
	public void partOpened (IWorkbenchPartReference partRef) {
		bumpFrom(partRef);
		prewarmCompletion(partRef);
	}

	@Override
	public void partBroughtToTop (IWorkbenchPartReference partRef) {
		bumpFrom(partRef);
		prewarmCompletion(partRef);
	}

	/** Schedules a low-priority background {@link Job} to pre-load the {@link CompletionSort} member cache for the compilation
	 * unit behind {@code partRef}. The expensive work is {@link IType#newSupertypeHierarchy} and the supertype member iteration;
	 * doing it here means it's already done by the time the user hits Ctrl+Space. */
	static private void prewarmCompletion (IWorkbenchPartReference partRef) {
		try {
			if (!(partRef.getPart(false) instanceof IEditorPart editor)) return;
			if (!(editor.getEditorInput() instanceof IFileEditorInput fileInput)) return;
			IFile file = fileInput.getFile();
			if (file == null) return;
			IJavaElement el = JavaCore.create(file);
			if (!(el instanceof ICompilationUnit cu)) return;
			if (!prewarmed.add(cu)) return;
			Job job = Job.create("Nateclipse: pre-warming completion caches", monitor -> {
				try {
					CompletionSort.prewarm(cu, monitor);
				} catch (Exception ex) {
					log.error("Completion pre-warm failed.", ex);
				}
				return Status.OK_STATUS;
			});
			job.setSystem(true);
			job.setPriority(Job.DECORATE);
			job.schedule();
		} catch (Exception ex) {
			log.error("Failed to schedule completion pre-warm.", ex);
		}
	}

	static private void bumpFrom (IWorkbenchPartReference partRef) {
		try {
			if (!(partRef.getPart(false) instanceof IEditorPart editor)) return;
			IEditorInput input = editor.getEditorInput();
			if (!(input instanceof IFileEditorInput fileInput)) return;
			IFile file = fileInput.getFile();
			if (file == null) return;
			IResource resource = file;
			String projectName = resource.getProject() != null ? resource.getProject().getName() : null;
			IJavaElement element = JavaCore.create(file);
			if (element instanceof ITypeRoot typeRoot) {
				IType primary = typeRoot.findPrimaryType();
				if (primary != null) {
					MruTracker.get().bumpType(primary.getFullyQualifiedName('.'), projectName);
					return;
				}
			}
			// Non-Java file in a project: only bump the project.
			if (projectName != null) {
				IJavaProject jp = JavaCore.create(resource.getProject());
				if (jp != null && jp.exists()) MruTracker.get().bumpProject(projectName);
			}
		} catch (Exception ex) {
			log.error("MRU: failed to bump from part.", ex);
		}
	}
}
