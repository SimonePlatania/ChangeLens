package com.simone.changelens;

import java.lang.reflect.Method;
import java.util.Iterator;

import org.eclipse.core.resources.IFile;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.source.CompositeRuler;
import org.eclipse.jface.text.source.IVerticalRuler;
import org.eclipse.jface.text.source.IVerticalRulerColumn;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.ui.texteditor.AbstractDecoratedTextEditor;
import org.eclipse.ui.texteditor.AbstractTextEditor;

import com.simone.changelens.preferences.Preferences;

/**
 * Tutto cio che ChangeLens installa su un singolo editor, e la sua rimozione
 * completa. Un solo punto di aggancio e un solo punto di distacco: se qualcosa
 * fallisce a meta, quanto era gia stato installato viene comunque smontato.
 */
final class EditorLens {

    private final ITextViewer viewer;
    private final AbstractDecoratedTextEditor decorated;

    private LensController controller;
    /** La diagnostica del righello si scrive una volta per sessione, non a ogni editor. */
    private static boolean rulerLogged;

    private ChangeRulerColumn column;
    private AuthorPainter painter;
    private OverviewHover hover;
    private SlimScrollBar scrollBar;
    private IPropertyChangeListener preferences;
    private boolean quickDiffSuppressed;
    private boolean disposed;

    static EditorLens install(AbstractTextEditor editor, ITextViewer viewer, IFile file) {
        EditorLens lens = new EditorLens(viewer,
                editor instanceof AbstractDecoratedTextEditor ? (AbstractDecoratedTextEditor) editor : null);
        try {
            lens.attach(editor);
            return lens;
        } catch (Exception failure) {
            Activator.log(failure);
            lens.dispose();
            return null;
        }
    }

    private EditorLens(ITextViewer viewer, AbstractDecoratedTextEditor decorated) {
        this.viewer = viewer;
        this.decorated = decorated;
    }

    private void attach(AbstractTextEditor editor) {
        controller = new LensController(viewer, fileOf(editor));
        painter = new AuthorPainter(controller, viewer);
        painter.setRevisionToggle(new RevisionToggle(editor));
        hover = OverviewHover.install(controller, viewer);
        if (hover != null) {
            // Trascinando la linguetta il testo si muove con setTopPixel, che non
            // emette eventi di viewport: la colonna va ridisegnata a mano.
            scrollBar = SlimScrollBar.install(viewer, hover.strip(), new Runnable() {
                @Override
                public void run() {
                    if (column != null) column.redraw();
                }
            });
            hover.setScrollBar(scrollBar);
        } else {
            Activator.log("ChangeLens: barra panoramica non raggiungibile in questo editor, "
                    + "fumetto e barra di scorrimento sottile non disponibili.");
        }
        addColumn(editor);
        suppressQuickDiff();

        preferences = new IPropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent event) {
                onPreferenceChange();
            }
        };
        Activator.getDefault().getPreferenceStore().addPropertyChangeListener(preferences);

        final StyledText widget = viewer.getTextWidget();
        widget.addListener(SWT.Dispose, new Listener() {
            @Override
            public void handleEvent(Event event) {
                dispose();
            }
        });
        Activator.getDefault().register(this);
    }

    private IFile fileOf(AbstractTextEditor editor) {
        return ((org.eclipse.ui.part.FileEditorInput) editor.getEditorInput()).getFile();
    }

    /** La colonna va all'estrema destra del righello, a ridosso del testo. */
    private void addColumn(AbstractTextEditor editor) {
        IVerticalRuler ruler = verticalRuler(editor);
        if (!(ruler instanceof CompositeRuler)) return;
        CompositeRuler composite = (CompositeRuler) ruler;
        int index = 0;
        for (Iterator<?> it = composite.getDecoratorIterator(); it.hasNext(); it.next()) index++;
        logRuler("prima", composite);
        column = new ChangeRulerColumn(controller, viewer);
        composite.addDecorator(index, column);
        logRuler("dopo", composite);

        // Inserire una colonna rimpagina il righello, e le colonne installate
        // da altri temi (DevStyle sostituisce quella dei numeri di riga) a
        // volte restano senza spazio: una rimpaginazione esplicita, a giro di
        // eventi finito, le rimette al loro posto.
        final Control control = composite.getControl();
        if (control != null && !control.isDisposed()) {
            control.getDisplay().asyncExec(new Runnable() {
                @Override
                public void run() {
                    if (control.isDisposed() || control.getParent() == null) return;
                    control.getParent().layout(true, true);
                }
            });
        }
    }

    /**
     * Scrive nel log com'e composto il righello, una volta sola per sessione.
     *
     * Serve a capire i casi in cui i numeri di riga spariscono con temi che
     * sostituiscono la colonna che li disegna: dal solo aspetto non si
     * distingue una colonna larga e vuota da una colonna assente.
     */
    private static void logRuler(String when, CompositeRuler composite) {
        if (rulerLogged) return;
        StringBuilder out = new StringBuilder("ChangeLens: colonne del righello (").append(when).append(")");
        try {
            for (Iterator<?> it = composite.getDecoratorIterator(); it.hasNext();) {
                Object decorator = it.next();
                out.append("\n  - ").append(decorator == null ? "null" : decorator.getClass().getName());
                if (decorator instanceof IVerticalRulerColumn) {
                    out.append(" larghezza=").append(((IVerticalRulerColumn) decorator).getWidth());
                }
            }
            if ("dopo".equals(when)) rulerLogged = true;
        } catch (Exception failure) {
            out.append("\n  (lettura interrotta: ").append(failure).append(')');
        }
        Activator.log(out.toString());
    }

    private static IVerticalRuler verticalRuler(AbstractTextEditor editor) {
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

    /**
     * Nasconde la colonna Quick Diff nativa solo in questo editor, cosi non ci
     * sono due serie di indicatori sulla stessa riga. La preferenza globale di
     * Eclipse non viene toccata e al distacco lo stato viene ripristinato.
     */
    private void suppressQuickDiff() {
        if (decorated == null || quickDiffSuppressed) return;
        if (!enabled() || !preference(Preferences.HIDE_NATIVE_QUICK_DIFF)) return;
        try {
            if (decorated.isChangeInformationShowing()) {
                decorated.showChangeInformation(false);
                quickDiffSuppressed = true;
            }
        } catch (Exception failure) {
            Activator.log(failure);
        }
    }

    private void restoreQuickDiff() {
        if (decorated == null || !quickDiffSuppressed) return;
        quickDiffSuppressed = false;
        try {
            if (!decorated.isChangeInformationShowing()) decorated.showChangeInformation(true);
        } catch (Exception failure) {
            Activator.log(failure);
        }
    }

    private void onPreferenceChange() {
        if (disposed) return;
        StyledText widget = viewer.getTextWidget();
        if (widget == null || widget.isDisposed()) return;
        if (enabled() && preference(Preferences.HIDE_NATIVE_QUICK_DIFF)) suppressQuickDiff();
        else restoreQuickDiff();
        if (column != null) column.redraw();
        widget.redraw();
    }

    private static boolean preference(String key) {
        Activator activator = Activator.getDefault();
        return activator != null && activator.getPreferenceStore().getBoolean(key);
    }

    private static boolean enabled() {
        return preference(Preferences.ENABLED);
    }

    void dispose() {
        if (disposed) return;
        disposed = true;
        Activator activator = Activator.getDefault();
        if (activator != null) {
            activator.unregister(this);
            if (preferences != null) activator.getPreferenceStore().removePropertyChangeListener(preferences);
        }
        preferences = null;
        restoreQuickDiff();
        if (scrollBar != null) scrollBar.dispose();
        if (hover != null) hover.dispose();
        if (painter != null) painter.dispose();
        if (controller != null) controller.dispose();
        scrollBar = null;
        hover = null;
        painter = null;
        controller = null;
        column = null;
    }
}
