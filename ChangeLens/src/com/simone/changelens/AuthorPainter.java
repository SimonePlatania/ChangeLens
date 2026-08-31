package com.simone.changelens;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.ITextViewerExtension5;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseMoveListener;
import org.eclipse.swt.events.MouseTrackAdapter;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;

import com.simone.changelens.preferences.Preferences;

/**
 * Draws the author name after the declaration: an icon, the name, {@code +N}
 * for the other authors of the body, and {@code *} when the method has
 * uncommitted changes. The label is clickable: it opens Eclipse's revisions
 * coloured by author, and the next click closes them again.
 *
 * A declaration that does not exist in HEAD yet has no author: in its place
 * comes {@code not committed yet*}, with an icon of its own and no click.
 *
 * The painting method computes nothing and never asks for a repaint: it only
 * reads what the controller already has ready.
 */
final class AuthorPainter implements PaintListener {

    private static final int GAP = 18;

    private final LensController controller;
    private final ITextViewer viewer;
    private final StyledText widget;
    private final List<Hit> hits = new ArrayList<Hit>();
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            if (!widget.isDisposed()) widget.redraw();
        }
    };

    private RevisionToggle revisions;
    private Font font;
    private Font fontSource;
    private Hit hovered;
    private boolean painting;
    private int failures;

    private static final class Hit {
        final Rectangle bounds;
        final int line;

        Hit(Rectangle bounds, int line) {
            this.bounds = bounds;
            this.line = line;
        }
    }

    AuthorPainter(LensController controller, ITextViewer viewer) {
        this.controller = controller;
        this.viewer = viewer;
        this.widget = viewer.getTextWidget();
        widget.addPaintListener(this);
        controller.addListener(refresh);
        installMouse();
    }

    private void installMouse() {
        widget.addMouseMoveListener(new MouseMoveListener() {
            @Override
            public void mouseMove(MouseEvent event) {
                Hit hit = hitAt(event.x, event.y);
                if (hit == hovered) return;
                hovered = hit;
                widget.setCursor(hit == null ? null
                        : widget.getDisplay().getSystemCursor(SWT.CURSOR_HAND));
                widget.redraw();
            }
        });
        widget.addMouseTrackListener(new MouseTrackAdapter() {
            @Override
            public void mouseExit(MouseEvent event) {
                if (hovered == null) return;
                hovered = null;
                widget.setCursor(null);
                widget.redraw();
            }
        });
        widget.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseDown(MouseEvent event) {
                if (event.button != 1) return;
                if (hitAt(event.x, event.y) != null) toggleRevisions();
            }
        });
    }

    private Hit hitAt(int x, int y) {
        for (Hit hit : hits) {
            if (hit.bounds.contains(x, y)) return hit;
        }
        return null;
    }

    @Override
    public void paintControl(PaintEvent event) {
        if (painting || failures > 3) return;
        painting = true;
        try {
            hits.clear();
            if (enabled()) draw(event.gc);
        } catch (Throwable failure) {
            // better to lose the labels than to bring the event loop down
            failures++;
            Activator.log(failure instanceof Exception
                    ? (Exception) failure : new RuntimeException(String.valueOf(failure)));
        } finally {
            painting = false;
        }
    }

    private void draw(GC gc) {
        if (!controller.hasMethods()) return;
        IDocument document = viewer.getDocument();
        if (document == null) return;

        Display display = widget.getDisplay();
        Palette palette = Palette.of(display);
        boolean initialsOnly = preference(Preferences.AUTHOR_INITIALS);
        boolean icon = preference(Preferences.AUTHOR_ICON);

        Color idle = palette.author(widget.getForeground().getRGB(), widget.getBackground().getRGB());

        Font previous = gc.getFont();
        gc.setFont(labelFont(display));
        gc.setAntialias(SWT.ON);

        int top = widget.getTopIndex();
        int bottom = Math.min(widget.getLineCount() - 1,
                widget.getLineIndex(Math.max(0, widget.getClientArea().height - 1)) + 1);

        for (int widgetLine = top; widgetLine <= bottom; widgetLine++) {
            int line = modelLine(widgetLine);
            if (line < 0 || controller.methodAt(line) == null) continue;
            AuthorLabel label = controller.label(line);
            if (label == null || label.isPending()) continue;

            String text = label.render(initialsOnly, controller.isDirty(line));
            if (text.isEmpty()) continue;

            Point end = endOfLine(document, line);
            if (end == null) continue;

            int lineHeight = widget.getLineHeight(widget.getOffsetAtLine(widgetLine));
            Point extent = gc.textExtent(text);
            // Icon and spacing follow the text height: zoomed out, an icon of
            // fixed size would end up taller than the line itself.
            int mark = Math.max(5, Math.min(11, extent.y - 3));
            int markWidth = mark + 3;
            int x = end.x + Math.max(6, Math.min(GAP, extent.y + 4));
            int y = end.y + Math.max(0, (lineHeight - extent.y) / 2);
            boolean pinned = label.isNotCommitted();
            boolean hot = !pinned && hovered != null && hovered.line == line;
            // The icon carries the colour; the name stays grey and does not
            // compete with the code. Under the mouse the name takes the shade
            // of its icon: that is how the two are said to be one thing.
            Color ink = tint(palette, label, controller.isDirty(line), idle);
            gc.setForeground(hot ? ink : idle);

            int textX = x;
            if (icon) {
                int markY = y + Math.max(0, (extent.y - mark) / 2);
                if (pinned) {
                    drawSpark(gc, ink, x, markY, mark);
                } else {
                    drawAuthorMark(gc, ink, x, markY, mark);
                }
                textX += markWidth;
                gc.setForeground(hot ? ink : idle);
            }
            gc.drawString(text, textX, y, true);
            if (hot) {
                int underline = y + extent.y - 1;
                gc.drawLine(textX, underline, textX + extent.x, underline);
            }
            // The notice is not clickable: there is no revision to open for
            // code that does not exist in the history yet.
            if (!pinned) {
                hits.add(new Hit(new Rectangle(x - 2, end.y, markWidth + extent.x + 6, lineHeight), line));
            }
        }
        gc.setFont(previous);
    }

    /**
     * The notice icon: a small plus sign. It has to read at a glance as "not a
     * person": freshly written code has no author in the history to show.
     */
    private void drawSpark(GC gc, Color ink, int x, int y, int size) {
        Color background = gc.getBackground();
        int thickness = Math.max(1, size / 5);
        int middle = (size - thickness) / 2;
        gc.setBackground(ink);
        gc.fillRectangle(x + middle, y, thickness, size);
        gc.fillRectangle(x, y + middle, size, thickness);
        gc.setBackground(background);
    }

    /**
     * A single figure inside a circle, not the two figures side by side: those
     * belong to another IDE's lenses, and this plug-in is not that one. The
     * circle gives it a shape of its own, recognisable even at 11 pixels.
     */
    private void drawAuthorMark(GC gc, Color ink, int x, int y, int size) {
        Color background = gc.getBackground();
        int head = Math.max(2, size * 2 / 5);
        int shoulders = Math.max(3, size * 4 / 5);
        gc.setForeground(ink);
        gc.setLineWidth(1);
        gc.drawOval(x, y, size, size);
        gc.setBackground(ink);
        // head and shoulders, kept inside the circle
        gc.fillOval(x + (size - head) / 2, y + size / 5, head, head);
        gc.fillArc(x + (size - shoulders) / 2, y + size / 2, shoulders, shoulders, 0, 180);
        gc.setBackground(background);
    }

    /**
     * The colour says what kind of line it is before a word of it is read:
     * green where the body has more than one author, orange where there are
     * uncommitted changes, blue where the code is committed and untouched, grey
     * for the never-committed notice. The icon carries it, and the name only
     * while the mouse is over it.
     */
    private Color tint(Palette palette, AuthorLabel label, boolean dirtyNow, Color idle) {
        if (label.isNotCommitted()) return idle;
        if (label.additionalAuthors > 0) return palette.added();
        if (label.dirty || dirtyNow) return palette.attention();
        return palette.mixed();
    }

    private Point endOfLine(IDocument document, int line) {
        try {
            IRegion region = document.getLineInformation(line);
            int offset = widgetOffset(region.getOffset() + region.getLength());
            if (offset < 0 || offset > widget.getCharCount()) return null;
            return widget.getLocationAtOffset(offset);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * The label font: the editor's, one point smaller.
     *
     * It has to be rebuilt every time the editor changes its own, or the old
     * one survives a zoom: enlarging the page left the name small, and
     * shrinking it left the name large next to tiny code. The editor font
     * itself is the proof of the change, since zooming hands the widget a new
     * Font instance. The point is only taken off while there is room to take
     * it: below six points, with the zoom all the way out, a fixed floor would
     * have left the name twice the size of the code it belongs to.
     */
    private Font labelFont(Display display) {
        Font editorFont = widget.getFont();
        if (font != null && !font.isDisposed() && editorFont.equals(fontSource)) return font;
        FontData[] data = editorFont.getFontData();
        for (FontData item : data) {
            item.setStyle(SWT.NORMAL);
            item.setHeight(item.getHeight() > 6 ? item.getHeight() - 1 : item.getHeight());
        }
        if (font != null && !font.isDisposed()) font.dispose();
        font = new Font(display, data);
        fontSource = editorFont;
        return font;
    }

    void setRevisionToggle(RevisionToggle toggle) {
        this.revisions = toggle;
    }

    /** A click on the name opens the revisions coloured by author; the next one closes them. */
    private void toggleRevisions() {
        if (revisions != null) revisions.toggle();
    }

    private int modelLine(int widgetLine) {
        if (viewer instanceof ITextViewerExtension5) {
            return ((ITextViewerExtension5) viewer).widgetLine2ModelLine(widgetLine);
        }
        return widgetLine;
    }

    private int widgetOffset(int offset) {
        if (viewer instanceof ITextViewerExtension5) {
            return ((ITextViewerExtension5) viewer).modelOffset2WidgetOffset(offset);
        }
        IRegion visible = viewer.getVisibleRegion();
        if (visible == null) return offset;
        return offset < visible.getOffset() || offset > visible.getOffset() + visible.getLength()
                ? -1 : offset - visible.getOffset();
    }

    private static boolean preference(String key) {
        Activator activator = Activator.getDefault();
        return activator != null && activator.getPreferenceStore().getBoolean(key);
    }

    private static boolean enabled() {
        return preference(Preferences.ENABLED) && preference(Preferences.AUTHORS);
    }

    void dispose() {
        controller.removeListener(refresh);
        if (!widget.isDisposed()) {
            widget.removePaintListener(this);
            widget.setCursor(null);
        }
        if (font != null && !font.isDisposed()) font.dispose();
        font = null;
        hits.clear();
        hovered = null;
    }
}
