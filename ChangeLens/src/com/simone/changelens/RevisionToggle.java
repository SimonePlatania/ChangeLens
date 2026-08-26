package com.simone.changelens;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;

import org.eclipse.jface.text.revisions.IRevisionRulerColumn;
import org.eclipse.jface.text.revisions.IRevisionRulerColumnExtension;
import org.eclipse.jface.text.source.CompositeRuler;
import org.eclipse.jface.text.source.IVerticalRuler;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.texteditor.AbstractDecoratedTextEditor;
import org.eclipse.ui.texteditor.AbstractTextEditor;

/**
 * Apre e chiude la colonna delle revisioni di Eclipse, la stessa che si
 * raggiunge da "Revisions" nel menu del righello: informazioni di revisione
 * visibili, colorazione per autore e nome autore in colonna. Un secondo clic
 * la richiude.
 */
final class RevisionToggle {

    private static final String SHOW_BLAME = "org.eclipse.egit.ui.team.ShowBlame";

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
        // La colorazione per autore va impostata dopo che EGit ha installato le
        // revisioni, altrimenti la colonna non e ancora pronta.
        applyRendering(revisionColumn());
        PlatformUI.getWorkbench().getDisplay().timerExec(400, new Runnable() {
            @Override
            public void run() {
                applyRendering(revisionColumn());
                applyRendering(editorColumn());
            }
        });
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
     * Chiude le revisioni. Non basta la colonna trovata nel righello: a
     * seconda di come EGit ha installato il blame, quella che tiene davvero le
     * revisioni puo essere la colonna dei numeri di riga dell'editor. Si
     * azzerano tutte quelle raggiungibili, ed e cio che fa anche "Hide
     * Revision Information" di Eclipse.
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

    /** La colonna dei numeri di riga dell'editor, che ospita le revisioni. */
    private IRevisionRulerColumn editorColumn() {
        for (String name : new String[] { "fLineNumberRulerColumn", "fLineColumn" }) {
            try {
                Field field = AbstractDecoratedTextEditor.class.getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(editor);
                if (value instanceof IRevisionRulerColumn) return (IRevisionRulerColumn) value;
            } catch (Exception ignored) {
                // campo assente in questa versione di Eclipse: si prova il prossimo
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
