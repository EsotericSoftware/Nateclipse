
package com.esotericsoftware.nateclipse.utils;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
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

/** Feeds {@link MruTracker} from editor activation events. */
public class MruPartListener implements IPartListener2 {
	static private final ILog log = Platform.getLog(MruPartListener.class);

	@Override
	public void partActivated (IWorkbenchPartReference partRef) {
		bumpFrom(partRef);
	}

	@Override
	public void partOpened (IWorkbenchPartReference partRef) {
		bumpFrom(partRef);
	}

	@Override
	public void partBroughtToTop (IWorkbenchPartReference partRef) {
		bumpFrom(partRef);
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
