
package nateclipse;

import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.plugin.AbstractUIPlugin;

public class Activator extends AbstractUIPlugin implements IStartup {
	@Override
	public void earlyStartup () {
		PlatformUI.getWorkbench().getDisplay().asyncExec( () -> {
			var modifier = new TabLabelModifier();

			// Existing editors.
			IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
			if (window != null) {
				IWorkbenchPage page = window.getActivePage();
				if (page != null) {
					for (IWorkbenchPartReference partRef : page.getEditorReferences())
						modifier.partOpened(partRef);
				}
				window.getPartService().addPartListener(modifier);
			}
		});
	}
}
