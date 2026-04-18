
package com.esotericsoftware.nateclipse;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.HandlerUtil;

import com.esotericsoftware.nateclipse.utils.RankedTypeDialog;

/** Replacement for {@code org.eclipse.jdt.ui.navigate.open.type}. Opens {@link RankedTypeDialog} so our MRU/project-aware
 * ordering is applied. */
public class OpenTypeSort extends AbstractHandler {
	static private final ILog log = Platform.getLog(OpenTypeSort.class);

	@Override
	public Object execute (ExecutionEvent event) throws ExecutionException {
		try {
			IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
			if (window == null) window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
			if (window == null) return null;
			Shell shell = window.getShell();

			IJavaSearchScope scope = SearchEngine.createWorkspaceScope();
			String activeProject = findActiveProject(window);

			RankedTypeDialog dialog = new RankedTypeDialog(shell, false, PlatformUI.getWorkbench().getProgressService(), scope,
				IJavaSearchConstants.TYPE, activeProject);
			dialog.setTitle("Open Type");
			dialog.setMessage("Select a type to open:");
			if (dialog.open() != Window.OK) return null;
			Object[] result = dialog.getResult();
			if (result == null || result.length == 0) return null;
			for (Object item : result) {
				if (item instanceof IJavaElement element) {
					try {
						JavaUI.openInEditor(element);
					} catch (Exception ex) {
						log.error("Open Type: could not open " + element, ex);
					}
				}
			}
		} catch (Exception ex) {
			log.error("Open Type handler failed", ex);
		}
		return null;
	}

	static private String findActiveProject (IWorkbenchWindow window) {
		try {
			if (window.getActivePage() == null) return null;
			IEditorPart editor = window.getActivePage().getActiveEditor();
			if (editor == null) return null;
			IEditorInput input = editor.getEditorInput();
			if (input == null) return null;
			Object javaElement = input.getAdapter(IJavaElement.class);
			if (javaElement instanceof IJavaElement je) {
				IJavaProject jp = je.getJavaProject();
				if (jp != null) return jp.getElementName();
			}
			Object res = input.getAdapter(org.eclipse.core.resources.IResource.class);
			if (res instanceof org.eclipse.core.resources.IResource r && r.getProject() != null) return r.getProject().getName();
		} catch (Exception ignored) {
		}
		return null;
	}
}
