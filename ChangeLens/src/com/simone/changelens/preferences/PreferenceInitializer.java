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
        // Disattivata di default: e l'unica cosa che ChangeLens fa sulle colonne
        // native di Eclipse, e nessun indicatore vale il rischio di toccarle.
        store.setDefault(Preferences.HIDE_NATIVE_QUICK_DIFF, false);
        store.setDefault(Preferences.SLIM_SCROLLBAR, true);
        store.setDefault(Preferences.ADDED_COLOR, "87,171,90");
        store.setDefault(Preferences.MODIFIED_COLOR, "223,143,53");
        store.setDefault(Preferences.DELETED_COLOR, "199,58,58");
    }
}
