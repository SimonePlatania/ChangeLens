package com.simone.changelens;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

public final class Activator extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "com.simone.changelens";

    private static volatile Activator instance;

    private final Set<EditorLens> lenses = Collections.newSetFromMap(
            new WeakHashMap<EditorLens, Boolean>());

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        instance = this;
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        EditorLens[] open;
        synchronized (lenses) {
            open = lenses.toArray(new EditorLens[lenses.size()]);
        }
        for (EditorLens lens : open) {
            try {
                lens.dispose();
            } catch (Exception failure) {
                log(failure);
            }
        }
        instance = null;
        super.stop(context);
    }

    public static Activator getDefault() {
        return instance;
    }

    void register(EditorLens lens) {
        synchronized (lenses) {
            lenses.add(lens);
        }
    }

    void unregister(EditorLens lens) {
        synchronized (lenses) {
            lenses.remove(lens);
        }
    }

    public static void log(String message) {
        Activator current = instance;
        if (current != null && message != null) {
            current.getLog().log(new Status(IStatus.WARNING, PLUGIN_ID, message));
        }
    }

    public static void log(Throwable error) {
        Activator current = instance;
        if (current == null || error == null) return;
        String message = error.getMessage() == null ? error.toString() : error.getMessage();
        current.getLog().log(new Status(IStatus.ERROR, PLUGIN_ID, message, error));
    }
}
