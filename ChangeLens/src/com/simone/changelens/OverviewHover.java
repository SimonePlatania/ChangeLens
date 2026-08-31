package com.simone.changelens;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import java.util.Iterator;

import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IAnnotationModelExtension2;
import org.eclipse.jface.text.source.IOverviewRuler;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseMoveListener;
import org.eclipse.swt.events.MouseTrackAdapter;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

/**
 * Mouse over the overview ruler on the right: hovering a marker opens the
 * bubble with the code from that point of the file, and moving away dismisses
 * it. Clicking still jumps to the line the way the native ruler does.
 */
final class OverviewHover {

    /** Pause before the bubble opens. */
    private static final int HOVER_DELAY = 320;
    /** Extra pixels given to the overview strip, to make it an easier target. */
    private static final int STRIP_GROWTH = 5;
    /** How far the bubble stays off the strip, so it never covers it. */
    private static final int STRIP_CLEARANCE = 10;

    private final LensController controller;
    private final ITextViewer viewer;

    private IOverviewRuler ruler;
    private Control control;
    private CodePreview preview;
    private SlimScrollBar scrollBar;
    private Runnable pending;
    private int shownLine = -1;
    private Point tip;
    private int wantedLine = -1;
    private int noticeLine = -1;
    private boolean open;

    /**
     * The marker tooltip of the overview ruler does not always go through
     * setToolTipText: when it is a JFace hover it arrives as a MouseHover event
     * on the canvas. A Display filter can cancel it by setting the type to
     * SWT.None, which an ordinary listener cannot do.
     */
    private final Listener hoverFilter = new Listener() {
        @Override
        public void handleEvent(Event event) {
            if (event.widget == control) event.type = SWT.None;
        }
    };

    /**
     * With the bubble open the wheel belongs to it: it moves the bubble and
     * nothing else, and the editor page stays on the line it was on.
     *
     * A Display filter is needed because the bubble is a focusless window: on
     * Windows the wheel goes to whoever holds focus, that is the editor, and a
     * listener on the bubble's own window would never see an event.
     */
    private final Listener wheelFilter = new Listener() {
        @Override
        public void handleEvent(Event event) {
            if (preview == null || !preview.isOpen() || event.count == 0) return;
            event.doit = false;
            event.type = SWT.None;
            scrollPreview(event.count);
        }
    };

    static OverviewHover install(LensController controller, ITextViewer viewer) {
        OverviewHover hover = new OverviewHover(controller, viewer);
        return hover.attach() ? hover : null;
    }

    private OverviewHover(LensController controller, ITextViewer viewer) {
        this.controller = controller;
        this.viewer = viewer;
    }

    private boolean attach() {
        ruler = overviewRuler();
        if (ruler == null) return false;
        control = ruler.getControl();
        if (control == null || control.isDisposed()) return false;

        preview = new CodePreview(control, viewer);
        preview.setLabels(controller);
        preview.setWheelSink(new CodePreview.WheelSink() {
            @Override
            public void scrolled(int lines) {
                scrollPreview(lines);
            }
        });
        control.setToolTipText(null);
        widenHotZone();
        widenStrip();
        control.getDisplay().addFilter(SWT.MouseHover, hoverFilter);
        control.getDisplay().addFilter(SWT.MouseWheel, wheelFilter);
        control.addMouseTrackListener(new MouseTrackAdapter() {
            @Override
            public void mouseExit(MouseEvent event) {
                hideNow();
            }
        });
        control.addMouseMoveListener(new MouseMoveListener() {
            @Override
            public void mouseMove(MouseEvent event) {
                schedule(event.y);
            }
        });
        // Wheel over the strip: here the page scrolls. The bubble stays pinned
        // to the point of the ruler under the pointer, which has not moved.
        // Off the strip, that is over the bubble, it is the bubble that
        // scrolls and the page that stays put.
        control.addListener(SWT.MouseWheel, new Listener() {
            @Override
            public void handleEvent(Event event) {
                event.doit = false;
                if (event.count == 0) return;
                if (scrollBar != null) {
                    scrollBar.scrollByLines(event.count);
                    return;
                }
                StyledText widget = viewer.getTextWidget();
                if (widget == null || widget.isDisposed()) return;
                int step = Math.max(1, widget.getLineHeight());
                widget.setTopPixel(Math.max(0, widget.getTopPixel() - event.count * step));
            }
        });
        control.addListener(SWT.MouseDown, new Listener() {
            @Override
            public void handleEvent(Event event) {
                hideNow();
            }
        });
        control.addListener(SWT.Dispose, new Listener() {
            @Override
            public void handleEvent(Event event) {
                dispose();
            }
        });
        return true;
    }

    /**
     * The bubble only opens from the strip on the right, and closes as soon as
     * the pointer leaves it.
     *
     * Mouse events only reach the control under the pointer, and the strip
     * knows nothing of what happens over the editor: noticing that the mouse
     * has gone means listening to the StyledText as well.
     */
    private void widenHotZone() {
        final StyledText widget = viewer.getTextWidget();
        if (widget == null || widget.isDisposed()) return;
        widget.addMouseMoveListener(new MouseMoveListener() {
            @Override
            public void mouseMove(MouseEvent event) {
                if (open) hideNow();
            }
        });
        widget.addMouseTrackListener(new MouseTrackAdapter() {
            @Override
            public void mouseExit(MouseEvent event) {
                if (open) hideNow();
            }
        });
    }

    /**
     * Widens the overview strip by a few pixels.
     *
     * The width is a private field of OverviewRuler, read by the viewer layout
     * on every pass: changing it and laying out again is the only way to give
     * the strip a more comfortable target. If the field is not there (a
     * different Eclipse version) the original width stands: a few pixels of
     * comfort are lost, not the bubble.
     */
    private void widenStrip() {
        if (control == null || control.isDisposed()) return;
        try {
            Field width = ruler.getClass().getDeclaredField("fWidth");
            width.setAccessible(true);
            int current = width.getInt(ruler);
            if (current > 0 && current < 24) width.setInt(ruler, current + STRIP_GROWTH);
        } catch (Exception ignored) {
            // width out of reach: the native one stands
            return;
        }
        Composite parent = control.getParent();
        if (parent != null && !parent.isDisposed()) parent.layout(true, true);
    }

    Control strip() {
        return control;
    }

    void setScrollBar(SlimScrollBar bar) {
        this.scrollBar = bar;
    }

    /**
     * The bubble appears after a short pause, not on the first pixel travelled,
     * and never over the scroll thumb: there the mouse is for dragging. The
     * pause applies to the opening only: while the bubble stays open it follows
     * the mouse at once, with no timer, so the scrolling stays fluid.
     */
    private void schedule(final int y) {
        if (control == null || control.isDisposed()) return;
        muteNativeTip();
        if (scrollBar != null && (scrollBar.isDragging() || scrollBar.isOnThumb(y))) {
            cancelPending();
            hide();
            return;
        }
        int line = lineAt(y);
        if (line < 0) {
            wantedLine = -1;
            cancelPending();
            hide();
            return;
        }
        if (open) {
            // already open: no waiting, it updates on the fly
            wantedLine = line;
            show(y);
            return;
        }
        if (line == wantedLine && pending != null) return;
        wantedLine = line;
        cancelPending();
        pending = new Runnable() {
            @Override
            public void run() {
                pending = null;
                if (control != null && !control.isDisposed()) show(y);
            }
        };
        control.getDisplay().timerExec(HOVER_DELAY, pending);
    }

    /**
     * The overview ruler canvas shows the marker tooltip on its own ("Multiple
     * markers at this line"): it would land on top of the bubble, so it is
     * cleared and only put back on teardown.
     */
    private void muteNativeTip() {
        if (control == null || control.isDisposed()) return;
        if (control.getToolTipText() != null) control.setToolTipText(null);
    }

    private void cancelPending() {
        if (pending == null || control == null || control.isDisposed()) return;
        control.getDisplay().timerExec(-1, pending);
        pending = null;
    }

    /**
     * What the bubble has to say about a line: the problem reported on it, and
     * the kind of change it belongs to.
     *
     * On the overview ruler Eclipse's tooltip used to give the reason for the
     * red marker; the bubble has taken its place, and without this the only
     * thing left of an error would be its colour. Among several reports on the
     * same line the most severe one wins.
     */
    private void describe(int line) {
        // The annotation model is only queried when the line changes: doing it
        // for every pixel travelled, on a file full of markers, is what made
        // the bubble stutter while being dragged.
        if (line == noticeLine) return;
        noticeLine = line;
        preview.setAccent(changeKindAt(line));
        String text = null;
        int severity = 0;
        try {
            IAnnotationModel model = viewer instanceof ISourceViewer
                    ? ((ISourceViewer) viewer).getAnnotationModel() : null;
            IDocument document = controller.document();
            if (model != null && document != null) {
                Iterator<Annotation> annotations = annotationsOn(model, document, line);
                while (annotations.hasNext()) {
                    Annotation annotation = annotations.next();
                    if (annotation == null || annotation.isMarkedDeleted()) continue;
                    int rank = rankOf(annotation.getType());
                    if (rank <= severity) continue;
                    String message = annotation.getText();
                    if (message == null || message.trim().isEmpty()) continue;
                    Position position = model.getPosition(annotation);
                    if (position == null) continue;
                    if (document.getLineOfOffset(position.getOffset()) != line) continue;
                    severity = rank;
                    text = message;
                }
            }
        } catch (Exception ignored) {
            // annotation model unavailable: the bubble goes without a notice
        }
        preview.setNotice(text, severity);
    }

    /**
     * The kind of change covering that line, or {@link CodePreview#NO_ACCENT}
     * where the line is unchanged. It is what tints the bubble's stripe, so a
     * preview taken over a marker already says what kind of marker it is.
     */
    private int changeKindAt(int line) {
        IDocument document = controller.document();
        if (document == null) return CodePreview.NO_ACCENT;
        ChangeBlock block = controller.snapshot().at(document, line);
        return block == null ? CodePreview.NO_ACCENT : block.kind;
    }

    /**
     * The annotations touching that line.
     *
     * When the model can filter by region it is asked to: walking every
     * annotation in the file - errors, occurrences, spelling, folds - for each
     * pointed line was work wasted on every mouse move.
     */
    private static Iterator<Annotation> annotationsOn(IAnnotationModel model, IDocument document,
            int line) throws Exception {
        if (model instanceof IAnnotationModelExtension2) {
            IRegion region = document.getLineInformation(line);
            return ((IAnnotationModelExtension2) model).getAnnotationIterator(
                    region.getOffset(), Math.max(1, region.getLength()), true, true);
        }
        return model.getAnnotationIterator();
    }

    /** Errors and warnings only: bookmarks, tasks and occurrences are beside the point. */
    private static int rankOf(String type) {
        if (type == null) return 0;
        String kind = type.toLowerCase();
        if (kind.contains("error")) return 2;
        if (kind.contains("warning")) return 1;
        return 0;
    }

    private void show(int y) {
        int line = lineAt(y);
        if (line < 0) {
            hide();
            return;
        }
        shownLine = line;
        boolean wasOpen = open;
        open = true;
        describe(line);
        tip = control.toDisplay(-STRIP_CLEARANCE, y);
        preview.showAround(controller.document(), line, viewer.getTextWidget(), tip);
        // The bubble is a window above everything: where it appears the strip
        // loses its drawing until it is asked to redo it. Doing that at the
        // opening is enough: afterwards the strip stays uncovered, and
        // repainting it on every move only bought stutter.
        if (!wasOpen && scrollBar != null) scrollBar.repaint();
    }

    /**
     * Wheel with the pointer over the bubble: the window travels with the text
     * instead of staying nailed to the line it was opened from.
     */
    private void scrollPreview(int lines) {
        IDocument document = controller.document();
        if (!open || document == null || shownLine < 0 || tip == null) return;
        int last = document.getNumberOfLines() - 1;
        int target = Math.max(0, Math.min(last, shownLine - lines));
        if (target == shownLine) return;
        shownLine = target;
        wantedLine = target;
        describe(target);
        tip = control.toDisplay(-STRIP_CLEARANCE, stripY(target, last));
        preview.showAround(document, target, viewer.getTextWidget(), tip);
    }

    /**
     * The height on the strip a document line maps to.
     *
     * Scrolling with the wheel, the bubble has to rise and fall the way it does
     * when following the mouse: were it to stay where it was opened, one would
     * end up reading the bottom of the file with the bubble still hanging at
     * the top.
     */
    private int stripY(int line, int lastLine) {
        if (control == null || control.isDisposed() || lastLine <= 0) return 0;
        int height = Math.max(1, control.getSize().y);
        return (int) Math.max(0, Math.min(height - 1, (long) height * line / lastLine));
    }

    private void hide() {
        shownLine = -1;
        noticeLine = -1;
        open = false;
        if (preview != null) preview.hide();
    }

    /** Closes with no delay. The bubble lives only while the mouse is on the strip. */
    private void hideNow() {
        wantedLine = -1;
        cancelPending();
        hide();
    }

    /**
     * The document line under the pointer. The ruler itself does the maths, so
     * the bubble always shows the right spot even on the compressed scale of a
     * long file.
     */
    private int lineAt(int y) {
        try {
            int line = ruler.toDocumentLineNumber(y);
            return line >= 0 ? line : -1;
        } catch (Exception ignored) {
            return -1;
        }
    }

    /**
     * The viewer's overview ruler. It is not public API, and depending on the
     * Eclipse version it is reachable as a protected method or only as a field,
     * so both routes are tried walking up the hierarchy.
     */
    private IOverviewRuler overviewRuler() {
        if (!(viewer instanceof ISourceViewer)) return null;
        for (Class<?> type = viewer.getClass(); type != null; type = type.getSuperclass()) {
            IOverviewRuler ruler = byMethod(type);
            if (ruler != null) return ruler;
            ruler = byField(type);
            if (ruler != null) return ruler;
            if (type == SourceViewer.class) break;
        }
        return null;
    }

    private IOverviewRuler byMethod(Class<?> type) {
        try {
            Method method = type.getDeclaredMethod("getOverviewRuler", (Class<?>[]) null);
            method.setAccessible(true);
            Object value = method.invoke(viewer, (Object[]) null);
            return value instanceof IOverviewRuler ? (IOverviewRuler) value : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private IOverviewRuler byField(Class<?> type) {
        try {
            Field field = type.getDeclaredField("fOverviewRuler");
            field.setAccessible(true);
            Object value = field.get(viewer);
            return value instanceof IOverviewRuler ? (IOverviewRuler) value : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    void dispose() {
        if (control != null && !control.isDisposed()) {
            control.getDisplay().removeFilter(SWT.MouseHover, hoverFilter);
            control.getDisplay().removeFilter(SWT.MouseWheel, wheelFilter);
        }
        if (preview != null) preview.dispose();
        preview = null;
        control = null;
        ruler = null;
        shownLine = -1;
    }
}
