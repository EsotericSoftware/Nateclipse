
package nateclipse;

import java.lang.reflect.Field;

import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.FileEditorInput;

public class JavaTabLabelModifier implements IPartListener2 {
	@Override
	public void partOpened (IWorkbenchPartReference partRef) {
		if (partRef.getPart(false) instanceof IEditorPart editor && editor.getEditorInput() instanceof FileEditorInput) {
			String title = editor.getEditorInput().getName();
			String uniqueTitle = title + "___nateclipse___";
			setTitle(partRef, uniqueTitle);
			findTabFolders(partRef, uniqueTitle);
			if (title.endsWith(".java")) title = title.substring(0, title.length() - 5);
			setTitle(partRef, title);
		}
	}

	static public void setTitle (IWorkbenchPartReference partRef, String title) {
		try {
			Object model = partRef.getClass().getMethod("getModel").invoke(partRef, (Object[])null);
			model.getClass().getMethod("setLabel", String.class).invoke(model, title);
		} catch (Exception ex) {
			new Exception("Unable to set tab title (Nateclipse plugin).", ex).printStackTrace();
		}
	}

	static public void findTabFolders (IWorkbenchPartReference partRef, String title) {
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
					new Exception("Unable to set CTabFolder#showClose false (Nateclipse plugin).", ex).printStackTrace();
				}

				tabFolder.setMinimumCharacters(100);
				return true;
			}
		}
		return false;
	}

	@Override
	public void partActivated (IWorkbenchPartReference partRef) {
	}

	@Override
	public void partDeactivated (IWorkbenchPartReference partRef) {
	}

	@Override
	public void partBroughtToTop (IWorkbenchPartReference partRef) {
	}

	@Override
	public void partClosed (IWorkbenchPartReference partRef) {
	}

	@Override
	public void partHidden (IWorkbenchPartReference partRef) {
	}

	@Override
	public void partVisible (IWorkbenchPartReference partRef) {
	}

	@Override
	public void partInputChanged (IWorkbenchPartReference partRef) {
	}

	static public void register () {
		PlatformUI.getWorkbench().getActiveWorkbenchWindow().getPartService().addPartListener(new JavaTabLabelModifier());
	}
}
