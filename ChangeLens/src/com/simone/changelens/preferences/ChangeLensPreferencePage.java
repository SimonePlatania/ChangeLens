package com.simone.changelens.preferences;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.ColorFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.simone.changelens.Activator;

public final class ChangeLensPreferencePage extends FieldEditorPreferencePage
        implements IWorkbenchPreferencePage {

    public ChangeLensPreferencePage() {
        super(GRID);
        setPreferenceStore(Activator.getDefault().getPreferenceStore());
        setDescription("Indicatori Git e autori inline negli editor di testo. "
                + "Le modifiche si applicano subito agli editor aperti.");
    }

    @Override
    protected void createFieldEditors() {
        addField(new BooleanFieldEditor(Preferences.ENABLED,
                "Abilita ChangeLens", getFieldEditorParent()));
        addField(new BooleanFieldEditor(Preferences.CHANGE_MARKERS,
                "Barre delle modifiche accanto al testo", getFieldEditorParent()));
        addField(new BooleanFieldEditor(Preferences.HIDE_NATIVE_QUICK_DIFF,
                "Nascondi Quick Diff di Eclipse negli editor gestiti", getFieldEditorParent()));
        addField(new BooleanFieldEditor(Preferences.SLIM_SCROLLBAR,
                "Barra di scorrimento sottile e stondata sulla barra panoramica", getFieldEditorParent()));
        addField(new BooleanFieldEditor(Preferences.AUTHORS,
                "Mostra l'autore accanto alla dichiarazione", getFieldEditorParent()));
        addField(new BooleanFieldEditor(Preferences.AUTHOR_ICON,
                "Mostra l'icona accanto al nome", getFieldEditorParent()));
        addField(new BooleanFieldEditor(Preferences.AUTHOR_INITIALS,
                "Privacy: mostra solo le iniziali", getFieldEditorParent()));
        addField(new ColorFieldEditor(Preferences.ADDED_COLOR,
                "Righe aggiunte", getFieldEditorParent()));
        addField(new ColorFieldEditor(Preferences.MODIFIED_COLOR,
                "Righe modificate", getFieldEditorParent()));
        addField(new ColorFieldEditor(Preferences.DELETED_COLOR,
                "Righe eliminate", getFieldEditorParent()));
    }

    @Override
    public void init(IWorkbench workbench) {
        // lo store e gia impostato nel costruttore
    }
}
