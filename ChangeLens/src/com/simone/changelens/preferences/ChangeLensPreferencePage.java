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
        setDescription("Git indicators and inline authors in text editors. "
                + "Changes take effect immediately in editors that are already open.");
    }

    @Override
    protected void createFieldEditors() {
        addField(new BooleanFieldEditor(Preferences.ENABLED,
                "Enable ChangeLens", getFieldEditorParent()));
        addField(new BooleanFieldEditor(Preferences.CHANGE_MARKERS,
                "Change bars next to the code", getFieldEditorParent()));
        addField(new BooleanFieldEditor(Preferences.HIDE_NATIVE_QUICK_DIFF,
                "Hide Eclipse's Quick Diff in managed editors", getFieldEditorParent()));
        addField(new BooleanFieldEditor(Preferences.SLIM_SCROLLBAR,
                "Thin rounded scrollbar on the overview ruler", getFieldEditorParent()));
        addField(new BooleanFieldEditor(Preferences.AUTHORS,
                "Show the author next to the declaration", getFieldEditorParent()));
        addField(new BooleanFieldEditor(Preferences.AUTHOR_ICON,
                "Show the icon next to the name", getFieldEditorParent()));
        addField(new BooleanFieldEditor(Preferences.AUTHOR_INITIALS,
                "Privacy: show initials only", getFieldEditorParent()));
        addField(new ColorFieldEditor(Preferences.ADDED_COLOR,
                "Added lines", getFieldEditorParent()));
        addField(new ColorFieldEditor(Preferences.MODIFIED_COLOR,
                "Rewritten lines", getFieldEditorParent()));
        addField(new ColorFieldEditor(Preferences.DELETED_COLOR,
                "Deleted lines", getFieldEditorParent()));
        addField(new ColorFieldEditor(Preferences.MIXED_COLOR,
                "Rewritten and added together", getFieldEditorParent()));
    }

    @Override
    public void init(IWorkbench workbench) {
        // the store is already set in the constructor
    }
}
