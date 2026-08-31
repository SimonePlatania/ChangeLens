package com.simone.changelens;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.text.revisions.IRevisionRulerColumn;
import org.eclipse.jface.text.revisions.IRevisionRulerColumnExtension;
import org.eclipse.jface.text.revisions.RevisionInformation;
import org.eclipse.jface.text.source.CompositeRuler;
import org.eclipse.jface.text.source.IVerticalRuler;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.texteditor.AbstractDecoratedTextEditor;
import org.eclipse.ui.texteditor.AbstractTextEditor;

/**
 * Opens and closes Eclipse's revision column, the same one reached from
 * "Revisions" in the ruler menu: revision information on show, colouring by
 * author and the author name in the column. A second click closes it again.
 */
final class RevisionToggle {

    private static final String SHOW_BLAME = "org.eclipse.egit.ui.team.ShowBlame";
    /** How many times, and how often, EGit is checked for having installed the blame. */
    private static final int ADOPT_ATTEMPTS = 20;
    private static final int ADOPT_DELAY = 250;

    private final AbstractTextEditor editor;
    private boolean showing;

    RevisionToggle(AbstractTextEditor editor) {
        this.editor = editor;
    }

    void toggle() {
        if (showing) {
            hide();
        } else {
            show();
        }
    }

    private void show() {
        try {
            IHandlerService service = PlatformUI.getWorkbench().getActiveWorkbenchWindow()
                    .getService(IHandlerService.class);
            if (service != null) service.executeCommand(SHOW_BLAME, null);
            showing = true;
        } catch (Exception failure) {
            Activator.log(failure);
        }
        // Colouring by author has to be set after EGit has installed the
        // revisions, or the column is not ready yet.
        applyRendering(revisionColumn());
        PlatformUI.getWorkbench().getDisplay().timerExec(400, new Runnable() {
            @Override
            public void run() {
                applyRendering(revisionColumn());
                applyRendering(editorColumn());
            }
        });
        adoptRevisions(0);
    }

    /**
     * Rewrites the revisions EGit installed while leaving everything else as it
     * was.
     *
     * Replacing them outright lost the commit card that opens on hovering the
     * column, which is EGit's own and cannot be rebuilt by hand. So nothing is
     * replaced: its revisions are taken, the ranges are broken up line by line
     * - the column writes the text once per range - and the date is put before
     * the name. All the rest, colour, id and hover card, stays what EGit would
     * have shown.
     *
     * EGit installs the blame in a Job of its own, so the column is not ready
     * as soon as the command returns: it is watched at intervals until it
     * shows up.
     */
    private void adoptRevisions(final int attempt) {
        if (!showing || attempt > ADOPT_ATTEMPTS) {
            if (showing && attempt > ADOPT_ATTEMPTS) ownBlame();
            return;
        }
        RevisionInformation source = installed();
        if (source != null && !BlameRevisions.isOurs(source)) {
            RevisionInformation perLine = BlameRevisions.perLine(source);
            if (perLine != null) {
                install(perLine);
                return;
            }
        }
        if (source != null && BlameRevisions.isOurs(source)) return;
        PlatformUI.getWorkbench().getDisplay().timerExec(ADOPT_DELAY, new Runnable() {
            @Override
            public void run() {
                adoptRevisions(attempt + 1);
            }
        });
    }

    /**
     * The fallback for when EGit's revisions never arrive: the plug-in runs the
     * blame itself. The rich hover card is lost, but the date and the name are
     * on the column all the same.
     */
    private void ownBlame() {
        final IFile file = fileOf(editor);
        if (file == null) return;
        Job job = new Job("ChangeLens: revisions with dates") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                final RevisionInformation information = BlameRevisions.of(file);
                if (information == null || PlatformUI.getWorkbench().getDisplay().isDisposed()) {
                    return Status.OK_STATUS;
                }
                PlatformUI.getWorkbench().getDisplay().asyncExec(new Runnable() {
                    @Override
                    public void run() {
                        install(information);
                    }
                });
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.schedule();
    }

    /** The revisions one of the two columns is already showing. */
    private RevisionInformation installed() {
        for (IRevisionRulerColumn column : new IRevisionRulerColumn[] { revisionColumn(), editorColumn() }) {
            RevisionInformation information = installedOn(column);
            if (information != null) return information;
        }
        return null;
    }

    /**
     * The column does not expose the revisions it was handed: its
     * RevisionPainter holds them, and that is where they are read from.
     */
    private RevisionInformation installedOn(IRevisionRulerColumn column) {
        if (column == null) return null;
        try {
            for (Class<?> type = column.getClass(); type != null; type = type.getSuperclass()) {
                Field painterField;
                try {
                    painterField = type.getDeclaredField("fRevisionPainter");
                } catch (NoSuchFieldException missing) {
                    continue;
                }
                painterField.setAccessible(true);
                Object painter = painterField.get(column);
                if (painter == null) return null;
                Field info = painter.getClass().getDeclaredField("fRevisionInfo");
                info.setAccessible(true);
                Object value = info.get(painter);
                return value instanceof RevisionInformation ? (RevisionInformation) value : null;
            }
        } catch (Exception ignored) {
            // different internals in this version: fall back to our own blame
        }
        return null;
    }

    private void install(RevisionInformation information) {
        if (!showing) return;
        for (IRevisionRulerColumn column : new IRevisionRulerColumn[] { revisionColumn(), editorColumn() }) {
            if (column == null) continue;
            try {
                column.setRevisionInformation(information);
            } catch (Exception failure) {
                Activator.log(failure);
            }
            applyRendering(column);
        }
    }

    private static IFile fileOf(AbstractTextEditor editor) {
        Object input = editor.getEditorInput();
        return input instanceof IFileEditorInput ? ((IFileEditorInput) input).getFile() : null;
    }

    private void applyRendering(IRevisionRulerColumn column) {
        if (!(column instanceof IRevisionRulerColumnExtension)) return;
        try {
            IRevisionRulerColumnExtension revisions = (IRevisionRulerColumnExtension) column;
            revisions.setRevisionRenderingMode(IRevisionRulerColumnExtension.AUTHOR);
            revisions.showRevisionAuthor(true);
            revisions.showRevisionId(false);
        } catch (Exception failure) {
            Activator.log(failure);
        }
    }

    /**
     * Closes the revisions. The column found in the ruler is not enough:
     * depending on how EGit installed the blame, the one really holding the
     * revisions can be the editor's line number column. Every reachable one is
     * cleared, which is what Eclipse's own "Hide Revision Information" does.
     */
    private void hide() {
        showing = false;
        clear(revisionColumn());
        clear(editorColumn());
    }

    private void clear(IRevisionRulerColumn column) {
        if (column == null) return;
        try {
            if (column instanceof IRevisionRulerColumnExtension) {
                ((IRevisionRulerColumnExtension) column).showRevisionAuthor(false);
                ((IRevisionRulerColumnExtension) column).showRevisionId(false);
            }
            column.setRevisionInformation(null);
        } catch (Exception failure) {
            Activator.log(failure);
        }
    }

    /** The editor's line number column, the one that hosts the revisions. */
    private IRevisionRulerColumn editorColumn() {
        for (String name : new String[] { "fLineNumberRulerColumn", "fLineColumn" }) {
            try {
                Field field = AbstractDecoratedTextEditor.class.getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(editor);
                if (value instanceof IRevisionRulerColumn) return (IRevisionRulerColumn) value;
            } catch (Exception ignored) {
                // field absent in this Eclipse version: try the next one
            }
        }
        return null;
    }

    boolean isShowing() {
        return showing;
    }

    private IRevisionRulerColumn revisionColumn() {
        IVerticalRuler ruler = verticalRuler();
        if (!(ruler instanceof CompositeRuler)) return null;
        for (Iterator<?> it = ((CompositeRuler) ruler).getDecoratorIterator(); it.hasNext();) {
            Object column = it.next();
            if (column instanceof IRevisionRulerColumn) return (IRevisionRulerColumn) column;
        }
        return null;
    }

    private IVerticalRuler verticalRuler() {
        try {
            Method method = AbstractTextEditor.class.getDeclaredMethod("getVerticalRuler", (Class<?>[]) null);
            method.setAccessible(true);
            Object value = method.invoke(editor, (Object[]) null);
            return value instanceof IVerticalRuler ? (IVerticalRuler) value : null;
        } catch (Exception failure) {
            Activator.log(failure);
            return null;
        }
    }
}
