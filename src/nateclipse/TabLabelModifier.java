
package nateclipse;

import java.lang.reflect.Field;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IURIEditorInput;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;

public class TabLabelModifier implements IPartListener2 {
	static private final ILog log = Platform.getLog(TabLabelModifier.class);

	@Override
	public void partOpened (IWorkbenchPartReference partRef) {
		if (partRef.getPart(false) instanceof IEditorPart editor && editor.getEditorInput() instanceof IURIEditorInput) {
			// When opened.
			fixTitle(partRef, editor);

			// When saved.
			editor.addPropertyListener( (source, propId) -> {
				if (propId == IEditorPart.PROP_DIRTY && !editor.isDirty()) fixTitle(partRef, editor);
			});
		}
	}

	/** Walks all CTabFolders and forces close buttons off and minimum characters up. No title matching needed. */
	static void fixAllTabFolders (Composite parent) {
		for (Control child : parent.getChildren()) {
			if (child instanceof CTabFolder folder) {
				try {
					Field field = CTabFolder.class.getDeclaredField("showClose");
					field.setAccessible(true);
					field.set(folder, false);
				} catch (Exception ignored) {
				}
				for (CTabItem item : folder.getItems())
					item.setShowClose(false);
				folder.setMinimumCharacters(100);
			} else if (child instanceof Composite) {
				fixAllTabFolders((Composite)child);
			}
		}
	}

	static void fixTitle (IWorkbenchPartReference partRef, IEditorPart editor) {
		String title = editor.getEditorInput().getName();
		String uniqueTitle = title + "___nateclipse___";
		setTitle(partRef, uniqueTitle);
		findTabFolders(partRef, uniqueTitle);
		if (title.endsWith(".java")) title = title.substring(0, title.length() - 5);
		setTitle(partRef, title);
	}

	static void setTitle (IWorkbenchPartReference partRef, String title) {
		try {
			Object model = partRef.getClass().getMethod("getModel").invoke(partRef, (Object[])null);
			model.getClass().getMethod("setLabel", String.class).invoke(model, title);
		} catch (Exception ex) {
			log.error("Unable to set tab title.", ex);
		}
	}

	static void findTabFolders (IWorkbenchPartReference partRef, String title) {
		IWorkbenchPart part = partRef.getPart(false);
		if (part != null && part.getSite() != null) {
			Composite parent = part.getSite().getShell();
			if (parent != null) findTabFolders(parent, title);
		}
	}

	static private boolean findTabFolders (Composite parent, String title) {
		for (Control child : parent.getChildren()) {
			if (child instanceof CTabFolder) {
				if (hideClose((CTabFolder)child, title)) return true;
			} else if (child instanceof Composite) {
				if (findTabFolders((Composite)child, title)) return true;
			}
		}
		return false;
	}

	static private boolean hideClose (CTabFolder tabFolder, String title) {
		for (CTabItem item : tabFolder.getItems()) {
			if (item.getText().equals(title)) {
				item.setShowClose(false);

				try {
					Field field = CTabFolder.class.getDeclaredField("showClose");
					field.setAccessible(true);
					field.set(tabFolder, false);
				} catch (Exception ex) {
					log.error("Unable to set CTabFolder#showClose false.", ex);
				}

				tabFolder.setMinimumCharacters(100);
				return true;
			}
		}
		return false;
	}
}
