
package com.esotericsoftware.nateclipse;

import org.eclipse.core.commands.Command;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.ui.PreferenceConstants;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import com.esotericsoftware.nateclipse.utils.MruPartListener;
import com.esotericsoftware.nateclipse.utils.MruTracker;
import com.esotericsoftware.nateclipse.utils.WebServer;

public class Activator extends AbstractUIPlugin implements IStartup {
	static private final ILog log = Platform.getLog(Activator.class);

	static private Activator instance;

	static public Activator getDefault () {
		return instance;
	}

	public Activator () {
		instance = this;
	}

	@Override
	public void start (BundleContext context) throws Exception {
		super.start(context);
		// Touch the tracker so it loads immediately. Any errors are logged.
		MruTracker.get();
	}

	@Override
	public void stop (BundleContext context) throws Exception {
		try {
			MruTracker.get().saveNow();
		} catch (Exception ex) {
			log.error("MRU: failed to save on shutdown.", ex);
		}
		super.stop(context);
	}

	@Override
	public void earlyStartup () {
		new WebJDT(9001, WebServer.newThreadPool(15, 60, "test", true)).start();

		// Make the smart proposal sorter the default every startup. If the user flips it back via prefs they can, but
		// it'll be reset next launch (by design: "centralized in code").
		try {
			PreferenceConstants.getPreferenceStore().setValue(PreferenceConstants.CODEASSIST_SORTER,
				com.esotericsoftware.nateclipse.CompletionSort.ID);
		} catch (Exception ex) {
			log.error("Failed to set default content-assist sorter.", ex);
		}

		PlatformUI.getWorkbench().getDisplay().asyncExec( () -> {
			var modifier = new TabLabels();
			var mruListener = new MruPartListener();

			IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
			if (window != null && window.getShell() != null) TabLabels.fixAllTabFolders(window.getShell());

			if (window != null) {
				IWorkbenchPage page = window.getActivePage();
				if (page != null) {
					for (IWorkbenchPartReference partRef : page.getEditorReferences()) {
						modifier.partOpened(partRef);
					}
					IWorkbenchPartReference active = page.getActivePartReference();
					if (active != null) mruListener.partActivated(active);
				}
				window.getPartService().addPartListener(modifier);
				window.getPartService().addPartListener(mruListener);
			}

			// Belt-and-suspenders: also override the Open Type command programmatically so we win even if another
			// plugin's handler is more specific.
			try {
				ICommandService cs = PlatformUI.getWorkbench().getService(ICommandService.class);
				if (cs != null) {
					Command cmd = cs.getCommand("org.eclipse.jdt.ui.navigate.open.type");
					if (cmd != null) cmd.setHandler(new OpenTypeSort());
				}
			} catch (Exception ex) {
				log.error("Failed to install Open Type handler.", ex);
			}
		});
	}
}
