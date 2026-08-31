package com.simone.changelens;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.jobs.Job;

import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextListener;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.ITextViewerExtension5;
import org.eclipse.jface.text.IViewportListener;
import org.eclipse.jface.text.TextEvent;
import org.eclipse.jface.text.source.CompositeRuler;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IVerticalRulerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseMoveListener;
import org.eclipse.swt.events.MouseTrackAdapter;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Path;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.IHandlerService;

import com.simone.changelens.preferences.Preferences;

/**
 * The column between the line numbers and the text that carries the change
 * markers: one continuous rounded bar as tall as the block it stands for, from
 * its first line to its last.
 *
 * The geometry is recomputed on every paint from the Positions anchored to the
 * document, and the column repaints on every viewer scroll event: that is what
 * keeps the bars nailed to their code instead of letting them slide with the
 * view.
 *
 * Where two bars of different colours meet, the touching ends fade into each
 * other instead of butting up as two flat blocks: the seam then reads as one
 * region changing nature rather than as an arbitrary cut.
 */
final class ChangeRulerColumn implements IVerticalRulerColumn {

    /**
     * The column is as wide as the space between the line number and the code:
     * it is derived from the editor's average character width, so it stays in
     * proportion at any font and any zoom level.
     */
    private static final int FALLBACK_WIDTH = 38;
    private static final int DELETION_HEIGHT = 6;
    /** Breathing room between the start of the lane and the bar. */
    private static final int BAR_GAP = 3;
    /**
     * Space between the separator and the first column of code. The change bar
     * lives in there, halfway between the ruler and the text.
     */
    private static final int CODE_GAP = 14;
    /** EGit's "Commit" command, the same one behind Team &gt; Commit. */
    private static final String COMMIT_COMMAND = "org.eclipse.egit.ui.team.Commit";
    /** EGit's Git Staging view, and the preference node its settings live in. */
    private static final String STAGING_VIEW = "org.eclipse.egit.ui.StagingView";
    private static final String EGIT_UI = "org.eclipse.egit.ui";
    /** How much the bar grows when the mouse is over it. */
    private static final int HOT_GROWTH = 2;
    /** Tallest fade drawn where two bars of different colours meet. */
    private static final int BLEND = 12;
    /** Two bars are neighbours when their ends are this close, in pixels. */
    private static final int TOUCH = 2;

    private final LensController controller;
    private final ITextViewer viewer;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            redrawLater();
        }
    };
    private final IViewportListener viewport = new IViewportListener() {
        @Override
        public void viewportChanged(int verticalOffset) {
            redraw();
        }
    };
    private final ITextListener text = new ITextListener() {
        @Override
        public void textChanged(TextEvent event) {
            // while an edit is in flight the repaint stays queued: forcing it
            // here would mean painting a document still in motion
            redrawLater();
        }
    };

    private Canvas canvas;
    private StyledText widget;
    private ChangeBlock hot;
    private Font cachedFont;
    private int cachedWidth;

    /** One bar ready to be drawn: where it sits, what colour it carries. */
    private static final class Bar {
        final ChangeBlock block;
        final int top;
        final int bottom;
        final int kind;
        final boolean deletion;

        Bar(ChangeBlock block, int top, int bottom, int kind, boolean deletion) {
            this.block = block;
            this.top = top;
            this.bottom = bottom;
            this.kind = kind;
            this.deletion = deletion;
        }
    }

    ChangeRulerColumn(LensController controller, ITextViewer viewer) {
        this.controller = controller;
        this.viewer = viewer;
    }

    @Override
    public Control createControl(CompositeRuler parentRuler, Composite parent) {
        widget = parentRuler.getTextViewer().getTextWidget();
        canvas = new Canvas(parent, SWT.NO_BACKGROUND);
        canvas.addPaintListener(new PaintListener() {
            @Override
            public void paintControl(PaintEvent event) {
                try {
                    paint(event.gc);
                } catch (Exception failure) {
                    Activator.log(failure);
                }
            }
        });
        canvas.addMouseTrackListener(new MouseTrackAdapter() {
            @Override
            public void mouseExit(MouseEvent event) {
                setHot(null);
            }
        });
        canvas.addMouseMoveListener(new MouseMoveListener() {
            @Override
            public void mouseMove(MouseEvent event) {
                setHot(enabled() && markers() ? blockAt(event.y) : null);
            }
        });
        // Click on a bar: its lines are staged and the commit dialog opens. The
        // gesture fires on release, not on press: opening the dialog under a
        // button that is still down made the release land on a button of the
        // freshly shown dialog and dismissed it instantly.
        canvas.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseUp(MouseEvent event) {
                if (event.button != 1 || !enabled() || !markers()) return;
                final ChangeBlock block = blockAt(event.y);
                if (block == null) return;
                canvas.getDisplay().asyncExec(new Runnable() {
                    @Override
                    public void run() {
                        commit(block);
                    }
                });
            }
        });
        canvas.addListener(SWT.Dispose, new Listener() {
            @Override
            public void handleEvent(Event event) {
                detach();
            }
        });

        // Without these three hooks the column would not know it has to repaint
        // when the view scrolls, and the bars would stay where they were.
        viewer.addViewportListener(viewport);
        viewer.addTextListener(text);
        widget.addControlListener(resize);
        controller.addListener(refresh);
        return canvas;
    }

    private final ControlAdapter resize = new ControlAdapter() {
        @Override
        public void controlResized(ControlEvent event) {
            redraw();
        }
    };

    @Override
    public Control getControl() {
        return canvas;
    }

    @Override
    public int getWidth() {
        return columnWidth();
    }

    /**
     * Immediate repaint, not a queued one.
     *
     * With {@code redraw()} alone the painting lands on the next event loop
     * turn, that is after the text has already moved: that one frame of delay
     * is what one sees as a bar sliding during a scroll. The {@code update()}
     * brings it back in step within the same event, the way Eclipse's own
     * columns do.
     */
    @Override
    public void redraw() {
        if (canvas == null || canvas.isDisposed()) return;
        canvas.redraw();
        canvas.update();
    }

    private void redrawLater() {
        if (canvas != null && !canvas.isDisposed()) canvas.redraw();
    }

    private int columnWidth() {
        if (widget == null || widget.isDisposed()) return FALLBACK_WIDTH;
        if (cachedWidth > 0 && widget.getFont().equals(cachedFont)) return cachedWidth;
        GC gc = new GC(widget);
        try {
            gc.setFont(widget.getFont());
            int charWidth = gc.getFontMetrics().getAverageCharWidth();
            cachedFont = widget.getFont();
            // As in modern IDEs: the separator, then the bar, then a real
            // margin before the code. Two characters wide.
            cachedWidth = Math.max(34, Math.min(64, charWidth * 5));
        } finally {
            gc.dispose();
        }
        return cachedWidth;
    }

    private int barWidth() {
        return Math.max(3, Math.min(5, columnWidth() / 8));
    }

    /**
     * Where the marker lane begins: the gap between the separator Eclipse draws
     * after the line numbers and the first column of code.
     */
    private int laneX() {
        return columnWidth() - CODE_GAP;
    }

    /**
     * The bar sits right next to the code, past Eclipse's separator, the way
     * IDEs that put the indicator between the ruler and the text do. With the
     * bar at the far left of the column it ended up before the line numbers,
     * far from the code it refers to.
     */
    private int barX() {
        return laneX() + BAR_GAP;
    }

    private static boolean markers() {
        Activator activator = Activator.getDefault();
        return activator != null
                && activator.getPreferenceStore().getBoolean(Preferences.CHANGE_MARKERS);
    }

    @Override
    public void setFont(Font font) {
        // the column draws no text
    }

    @Override
    public void setModel(IAnnotationModel model) {
        // the markers come from the Git diff, not from the annotation model
    }

    private void paint(GC gc) {
        if (widget == null || widget.isDisposed() || canvas == null || canvas.isDisposed()) return;
        Rectangle area = canvas.getClientArea();
        gc.setBackground(widget.getBackground());
        gc.fillRectangle(area);

        gc.setAdvanced(true);
        gc.setAntialias(SWT.ON);
        if (!markers()) return;

        IDocument document = controller.document();
        GitSnapshot snapshot = controller.snapshot();
        if (document == null || snapshot.isEmpty()) return;

        List<Bar> bars = bars(document, snapshot, area);
        Palette palette = Palette.of(canvas.getDisplay());
        int bar = barWidth();
        for (int i = 0; i < bars.size(); i++) {
            Bar current = bars.get(i);
            int grow = current.block == hot ? HOT_GROWTH : 0;
            int x = area.x + barX() - grow;
            Color ink = palette.forChange(current.kind);
            if (current.deletion) {
                gc.setBackground(ink);
                gc.fillRoundRectangle(x, current.top - DELETION_HEIGHT / 2 - grow,
                        bar + 4 + 2 * grow, DELETION_HEIGHT + 2 * grow, 4, 4);
                continue;
            }
            int width = bar + 2 * grow;
            int height = Math.max(bar, current.bottom - current.top) + 2 * grow;
            fill(gc, x, current.top - grow, width, height, ink,
                    neighbour(palette, bars, i, -1), neighbour(palette, bars, i, +1));
        }
    }

    /**
     * The colour of the bar that touches this one on the given side, or
     * {@code null} when nothing touches it or the neighbour carries the same
     * colour. Only a different colour is worth fading into: two bars of the
     * same kind are one region and must stay one solid stroke.
     */
    private Color neighbour(Palette palette, List<Bar> bars, int index, int direction) {
        int other = index + direction;
        if (other < 0 || other >= bars.size()) return null;
        Bar bar = bars.get(index);
        Bar next = bars.get(other);
        if (bar.deletion || next.deletion || next.kind == bar.kind) return null;
        int distance = direction < 0 ? bar.top - next.bottom : next.top - bar.bottom;
        return distance >= -TOUCH && distance <= TOUCH ? palette.forChange(next.kind) : null;
    }

    /**
     * Draws one bar, fading its ends into the colours of whatever it touches.
     *
     * The fade is clipped to the bar's own rounded outline, so the rounded ends
     * survive: filling a plain gradient rectangle over them would square the
     * corners off again. Where advanced graphics are missing the bar is still
     * drawn, just flat.
     */
    private void fill(GC gc, int x, int y, int width, int height, Color ink, Color above,
            Color below) {
        if (above == null && below == null || !gc.getAdvanced()) {
            gc.setBackground(ink);
            gc.fillRoundRectangle(x, y, width, height, width, width);
            return;
        }
        Rectangle clip = gc.getClipping();
        Path outline = null;
        try {
            outline = pill(gc, x, y, width, height);
            gc.setBackground(ink);
            gc.fillPath(outline);

            int fade = Math.min(BLEND, Math.max(2, height / 2));
            gc.setClipping(outline);
            if (above != null) {
                gc.setForeground(above);
                gc.setBackground(ink);
                gc.fillGradientRectangle(x, y, width, fade, true);
            }
            if (below != null) {
                gc.setForeground(ink);
                gc.setBackground(below);
                gc.fillGradientRectangle(x, y + height - fade, width, fade, true);
            }
        } catch (Exception failure) {
            // no path support on this platform: the flat bar is still correct
            gc.setBackground(ink);
            gc.fillRoundRectangle(x, y, width, height, width, width);
        } finally {
            // back to the damaged area the paint event handed us, not to the
            // whole canvas: widening the clip here would let later bars paint
            // over parts nobody asked to be repainted
            gc.setClipping(clip);
            if (outline != null && !outline.isDisposed()) outline.dispose();
        }
    }

    /**
     * The bar outline: a rectangle with fully rounded ends, as one closed
     * subpath. Two arcs and a rectangle would overlap, and under the even-odd
     * fill rule the overlap would come out hollow.
     */
    private Path pill(GC gc, int x, int y, int width, int height) {
        Path path = new Path(gc.getDevice());
        float diameter = Math.min(width, height);
        path.addArc(x, y, width, diameter, 0, 180);
        path.addArc(x, y + height - diameter, width, diameter, 180, 180);
        path.close();
        return path;
    }

    /**
     * The visible bars, in the order they appear down the column. Sorting them
     * is what lets a bar know what it touches: neighbours are looked up by
     * index instead of by scanning the whole snapshot again for every end.
     */
    private List<Bar> bars(IDocument document, GitSnapshot snapshot, Rectangle area) {
        List<Bar> bars = new ArrayList<Bar>();
        for (ChangeBlock block : snapshot.blocks) {
            int[] span = span(document, block);
            if (span == null || span[1] < area.y - DELETION_HEIGHT
                    || span[0] > area.y + area.height + DELETION_HEIGHT) {
                continue;
            }
            // Inside a fold there is no telling what kind of change is in
            // there - additions, rewrites and deletions all land on the same
            // line - so the colour falls back to the one for rewrites.
            boolean folded = span[2] == 1;
            int kind = folded ? ChangeBlock.MODIFIED : block.kind;
            bars.add(new Bar(block, span[0], Math.max(span[0], span[1]), kind,
                    block.kind == ChangeBlock.DELETED && !folded));
        }
        sort(bars);
        return bars;
    }

    /** Insertion sort by top edge: the list is short and nearly ordered already. */
    private static void sort(List<Bar> bars) {
        for (int i = 1; i < bars.size(); i++) {
            Bar bar = bars.get(i);
            int j = i - 1;
            while (j >= 0 && bars.get(j).top > bar.top) {
                bars.set(j + 1, bars.get(j));
                j--;
            }
            bars.set(j + 1, bar);
        }
    }

    /**
     * Where the block sits on screen, and whether it ended up inside a fold.
     *
     * Folded code has no lines on screen: its changes used to disappear along
     * with it, and someone looking at a collapsed line had no way of knowing
     * something inside had changed. When the block's lines are hidden, the bar
     * settles on the header line of the fold, the one holding them all.
     */
    private int[] span(IDocument document, ChangeBlock block) {
        if (!block.isValid()) return null;
        int startLine = block.startLine(document);
        int endLine = block.kind == ChangeBlock.DELETED ? startLine : block.endLine(document);
        int startWidget = widgetLine(document, startLine);
        int endWidget = widgetLine(document, endLine);
        boolean folded = false;
        if (startWidget < 0) {
            startWidget = visibleAbove(document, startLine);
            folded = true;
        }
        if (endWidget < 0) {
            endWidget = visibleAbove(document, endLine);
            folded = true;
        }
        if (startWidget < 0 || endWidget < 0) return null;
        if (endWidget < startWidget) endWidget = startWidget;
        // getLinePixel is already relative to the StyledText client area: it is
        // the same coordinate space Eclipse's own columns work in.
        int top = widget.getLinePixel(startWidget);
        int bottom = widget.getLinePixel(endWidget + 1);
        return new int[] { top, Math.max(top, bottom), folded ? 1 : 0 };
    }

    /** The first visible line walking up from a hidden one: the fold's header. */
    private int visibleAbove(IDocument document, int modelLine) {
        for (int line = modelLine; line >= 0; line--) {
            int widgetLine = widgetLine(document, line);
            if (widgetLine >= 0) return widgetLine;
        }
        return -1;
    }

    /**
     * The bar under the pointer. Only the drawing changes - a couple of pixels
     * more, and a hand instead of the arrow - so repainting when it changes is
     * enough, no need to do it for every pixel travelled.
     */
    private void setHot(ChangeBlock block) {
        if (block == hot) return;
        hot = block;
        if (canvas != null && !canvas.isDisposed()) {
            canvas.setCursor(block == null ? null
                    : canvas.getDisplay().getSystemCursor(SWT.CURSOR_HAND));
            canvas.redraw();
        }
    }

    /**
     * Stages only the lines of the bar, then opens the commit dialog.
     *
     * Committing "that block" means bringing only its lines into the index: the
     * rest of the file stays out, and the dialog opens already showing what one
     * meant to commit.
     *
     * The dialog only opens if the staging actually happened. Opening it
     * regardless was the worst behaviour available: the commit window appearing
     * is what tells the user their block went in, so on a refusal they would
     * have gone ahead and committed something else entirely, with nothing
     * anywhere saying otherwise.
     *
     * The Git work runs in a Job. It writes a blob and takes the index lock,
     * and this plug-in's whole claim is that it never blocks the editor.
     */
    private void commit(ChangeBlock block) {
        final IDocument document = controller.document();
        if (document == null || !block.isValid()) return;
        int start = block.startLine(document);
        int end = block.kind == ChangeBlock.DELETED ? start : block.endLine(document);
        final IFile file = controller.file();
        final String text = document.get();
        final int from = start;
        final int to = end;
        Job job = new Job("ChangeLens: staging the block") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                final PartialStage.Result result = PartialStage.stage(file, text, from, to);
                if (canvas == null || canvas.isDisposed()) return Status.OK_STATUS;
                canvas.getDisplay().asyncExec(new Runnable() {
                    @Override
                    public void run() {
                        status(result.message);
                        // A refusal also goes to the log. The status line is
                        // easy to miss, and "the click does nothing" with no
                        // trace anywhere is the hardest thing to diagnose.
                        if (!result.staged) Activator.log(result.message);
                        else openCommitUI();
                    }
                });
                return Status.OK_STATUS;
            }
        };
        job.setUser(false);
        job.schedule();
    }

    /**
     * Says what happened, in the status line of the active editor.
     *
     * A refusal has to land somewhere the user is already looking; the log is
     * where things go to be found by whoever already suspects them.
     */
    private void status(String message) {
        if (message == null) return;
        try {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            IWorkbenchPage page = window == null ? null : window.getActivePage();
            IEditorPart editor = page == null ? null : page.getActiveEditor();
            IEditorSite site = editor == null ? null : editor.getEditorSite();
            IActionBars bars = site == null ? null : site.getActionBars();
            if (bars != null && bars.getStatusLineManager() != null) {
                bars.getStatusLineManager().setMessage(message);
                return;
            }
        } catch (Exception ignored) {
            // no status line reachable from here: the log has to do
        }
        Activator.log(message);
    }

    /**
     * Brings up whatever EGit is configured to commit with, without letting it
     * undo the staging first.
     *
     * The Team &gt; Commit command cannot be used when the Git Staging view is
     * the configured target. On that branch, and with both preferences at their
     * defaults, the handler calls its own auto-stage before showing the view:
     * it runs Add to Index over the file in scope, which stages the whole file
     * from the working tree. On an unsaved buffer that puts the file back to
     * what is on disk and the block just staged disappears; on a saved one it
     * stages every other change in the file too. Either way the click would
     * have promised one block and delivered something else.
     *
     * Showing the view directly skips the handler and that auto-stage with it.
     * When the old commit dialog is the configured target the command is used
     * as before: on that branch it stages nothing of its own.
     */
    private void openCommitUI() {
        try {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            IWorkbenchPage page = window == null ? null : window.getActivePage();
            if (page != null && stagingViewPreferred()) {
                page.showView(STAGING_VIEW);
                return;
            }
        } catch (Exception failure) {
            // view not available in this installation: the command still works
            Activator.log(failure);
        }
        try {
            IHandlerService service = PlatformUI.getWorkbench().getActiveWorkbenchWindow()
                    .getService(IHandlerService.class);
            if (service != null) service.executeCommand(COMMIT_COMMAND, null);
        } catch (Exception failure) {
            Activator.log(failure);
        }
    }

    /**
     * EGit's own "always use the staging view" preference, read straight from
     * its preference node so that no dependency on its UI bundle is needed. Its
     * default is what EGit itself defaults to.
     */
    private static boolean stagingViewPreferred() {
        try {
            return Platform.getPreferencesService()
                    .getBoolean(EGIT_UI, "always_use_staging_view", true, null);
        } catch (Exception ignored) {
            return true;
        }
    }

    private ChangeBlock blockAt(int y) {
        IDocument document = controller.document();
        GitSnapshot snapshot = controller.snapshot();
        if (document == null || snapshot.isEmpty() || widget == null || widget.isDisposed()) {
            return null;
        }
        for (ChangeBlock block : snapshot.blocks) {
            int[] span = span(document, block);
            if (span == null) continue;
            if (block.kind == ChangeBlock.DELETED) {
                if (y >= span[0] - DELETION_HEIGHT && y <= span[0] + DELETION_HEIGHT) return block;
            } else if (y >= span[0] && y < span[1]) {
                return block;
            }
        }
        return null;
    }

    private int widgetLine(IDocument document, int modelLine) {
        if (modelLine < 0 || modelLine >= document.getNumberOfLines()) return -1;
        if (viewer instanceof ITextViewerExtension5) {
            return ((ITextViewerExtension5) viewer).modelLine2WidgetLine(modelLine);
        }
        return modelLine;
    }

    private void detach() {
        controller.removeListener(refresh);
        if (widget != null && !widget.isDisposed()) widget.removeControlListener(resize);
        try {
            viewer.removeViewportListener(viewport);
            viewer.removeTextListener(text);
        } catch (Exception ignored) {
            // the viewer may already have been torn down
        }
    }

    private static boolean enabled() {
        Activator activator = Activator.getDefault();
        return activator != null && activator.getPreferenceStore().getBoolean(Preferences.ENABLED);
    }
}
