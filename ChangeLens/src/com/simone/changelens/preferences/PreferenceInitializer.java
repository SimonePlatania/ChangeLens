package com.simone.changelens.preferences;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;

import com.simone.changelens.Activator;

public final class PreferenceInitializer extends AbstractPreferenceInitializer {

    @Override
    public void initializeDefaultPreferences() {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        store.setDefault(Preferences.ENABLED, true);
        store.setDefault(Preferences.CHANGE_MARKERS, true);
        store.setDefault(Preferences.AUTHORS, true);
        store.setDefault(Preferences.AUTHOR_ICON, true);
        store.setDefault(Preferences.AUTHOR_INITIALS, false);
        // Off by default: it is the one thing ChangeLens does to Eclipse's own
        // columns, and no marker is worth the risk of touching them.
        store.setDefault(Preferences.HIDE_NATIVE_QUICK_DIFF, false);
        store.setDefault(Preferences.SLIM_SCROLLBAR, true);
        store.setDefault(Preferences.ADDED_COLOR, "87,171,90");
        // Blue for rewritten lines, green for added ones: it is the reading
        // every other tool uses, and orange stayed hard to tell apart from the
        // red of deletions.
        store.setDefault(Preferences.MODIFIED_COLOR, "84,140,205");
        store.setDefault(Preferences.DELETED_COLOR, "199,58,58");
        store.setDefault(Preferences.MIXED_COLOR, "84,140,205");
    }
}
