package com.simone.changelens;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.jface.text.ITextViewer;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.AbstractTextEditor;

/** Aggancia ChangeLens agli editor di testo aperti su file di un repository. */
public final class EditorStartup implements IStartup {

    private static final int RETRIES = 20;
    private static final int RETRY_DELAY = 150;

    private final Set<Object> attached = Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());
    private final Map<IWorkbenchWindow, Boolean> listening = new WeakHashMap<IWorkbenchWindow, Boolean>();

    @Override
    public void earlyStartup() {
        PlatformUI.getWorkbench().getDisplay().asyncExec(new Runnable() {
            @Override
            public void run() {
                for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
                    attach(window);
                }


                PlatformUI.getWorkbench().addWindowListener(new IWindowListener() {
                    @Override
                    public void windowOpened(IWorkbenchWindow window) {
                        attach(window);
                    }

                    @Override
                    public void windowClosed(IWorkbenchWindow window) {
                        listening.remove(window);
                    }

                    @Override
                    public void windowActivated(IWorkbenchWindow window) {
                        attach(window);
                    }

                    @Override
                    public void windowDeactivated(IWorkbenchWindow window) {
                        // niente
                    }
                });
            }
        });
    }

    private void attach(IWorkbenchWindow window) {
        if (window == null || listening.put(window, Boolean.TRUE) != null) return;
        for (IWorkbenchPage page : window.getPages()) {
            for (IEditorReference reference : page.getEditorReferences()) {
                install(reference.getEditor(false), RETRIES);
            }
        }
        window.getPartService().addPartListener(new IPartListener2() {
            private void take(IWorkbenchPartReference reference) {
                IWorkbenchPart part = reference == null ? null : reference.getPart(false);
                if (part instanceof IEditorPart) install((IEditorPart) part, RETRIES);
            }

            @Override
            public void partOpened(IWorkbenchPartReference reference) {
                take(reference);
            }

            @Override
            public void partVisible(IWorkbenchPartReference reference) {
                take(reference);
            }

            @Override
            public void partActivated(IWorkbenchPartReference reference) {
                take(reference);
            }

            @Override
            public void partInputChanged(IWorkbenchPartReference reference) {
                take(reference);
            }

            @Override
            public void partBroughtToTop(IWorkbenchPartReference reference) {
                // gia coperto da partActivated
            }

            @Override
            public void partClosed(IWorkbenchPartReference reference) {
                // EditorLens si smonta da solo quando la StyledText viene distrutta
            }

            @Override
            public void partDeactivated(IWorkbenchPartReference reference) {
                // niente
            }

            @Override
            public void partHidden(IWorkbenchPartReference reference) {
                // niente
            }
        });
    }

    private void install(final IEditorPart part, final int attempts) {
        if (attempts <= 0 || !(part instanceof AbstractTextEditor)) return;
        if (!(part.getEditorInput() instanceof FileEditorInput)) return;

        Object viewer = sourceViewer(part);
        if (viewer == null) {
            PlatformUI.getWorkbench().getDisplay().timerExec(RETRY_DELAY, new Runnable() {
                @Override
                public void run() {
                    install(part, attempts - 1);
                }
            });
            return;
        }
        if (!(viewer instanceof ITextViewer) || !attached.add(viewer)) return;

        ITextViewer text = (ITextViewer) viewer;
        if (text.getTextWidget() == null || text.getDocument() == null) {
            attached.remove(viewer);
            PlatformUI.getWorkbench().getDisplay().timerExec(RETRY_DELAY, new Runnable() {
                @Override
                public void run() {
                    install(part, attempts - 1);
                }
            });
            return;
        }
        EditorLens.install((AbstractTextEditor) part, text,
                ((FileEditorInput) part.getEditorInput()).getFile());
    }

    private static Object sourceViewer(IEditorPart part) {
        try {
            Method method = AbstractTextEditor.class.getDeclaredMethod("getSourceViewer", (Class<?>[]) null);
            method.setAccessible(true);
            return method.invoke(part, (Object[]) null);
        } catch (Exception failure) {
            Activator.log(failure);
            return null;
        }
    }
}
