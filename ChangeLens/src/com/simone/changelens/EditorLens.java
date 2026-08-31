package com.simone.changelens;

import java.lang.reflect.Method;
import java.util.Iterator;

import org.eclipse.core.resources.IFile;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.source.CompositeRuler;
import org.eclipse.jface.text.source.IVerticalRuler;
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
 * Everything ChangeLens installs on a single editor, and its complete removal.
 * One attach point and one detach point: if something fails halfway, whatever
 * had already been installed still gets taken down.
 */
final class EditorLens {

    private final ITextViewer viewer;
    private final AbstractDecoratedTextEditor decorated;

    private LensController controller;

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
            // Dragging the thumb moves the text with setTopPixel, which fires
            // no viewport event: the column has to be repainted by hand.
            scrollBar = SlimScrollBar.install(viewer, hover.strip(), new Runnable() {
                @Override
                public void run() {
                    if (column != null) column.redraw();
                }
            });
            hover.setScrollBar(scrollBar);
        } else {
            Activator.log("ChangeLens: the overview ruler is out of reach in this editor, "
                    + "so the preview bubble and the slim scroll bar are unavailable.");
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

    /** The column goes at the far right of the ruler, right against the text. */
    private void addColumn(AbstractTextEditor editor) {
        IVerticalRuler ruler = verticalRuler(editor);
        if (!(ruler instanceof CompositeRuler)) return;
        CompositeRuler composite = (CompositeRuler) ruler;
        int index = 0;
        for (Iterator<?> it = composite.getDecoratorIterator(); it.hasNext(); it.next()) index++;
        column = new ChangeRulerColumn(controller, viewer);
        composite.addDecorator(index, column);

        // Inserting a column lays the ruler out again, and columns installed by
        // other themes (DevStyle replaces the line number one) are sometimes
        // left with no room: an explicit layout, once the event turn is over,
        // puts them back in place.
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
     * Hides the native Quick Diff column in this editor only, so there are not
     * two sets of markers on the same line. Eclipse's global preference is left
     * alone, and the state is restored on detach.
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
