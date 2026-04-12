
package nateclipse;

import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.plugin.AbstractUIPlugin;

import nateclipse.utils.WebServer;
import nateclipse.utils.WebServer.WebServerSettings;

public class Activator extends AbstractUIPlugin implements IStartup {
	@Override
	public void earlyStartup () {
		var settings = new WebServerSettings();
		settings.port = 9001;
		new WebJDT(settings, WebServer.newThreadPool(15, 60, "test", true)).start();

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
