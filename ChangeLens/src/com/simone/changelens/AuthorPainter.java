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
 * Disegna il nome dell'autore in coda alla dichiarazione: icona a due
 * sagome, nome, {@code +N} per gli altri autori del corpo e {@code *} se il
 * metodo ha modifiche non ancora committate. L'etichetta e cliccabile: apre le
 * revisioni di Eclipse colorate per autore, e al clic successivo le chiude.
 *
 * Il metodo di disegno non calcola nulla e non richiede mai un ridisegno:
 * legge solo cio che il controller ha gia pronto.
 */
final class AuthorPainter implements PaintListener {

    private static final int GAP = 18;
    private static final int ICON_WIDTH = 13;

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
            // meglio perdere le etichette che far cadere il loop degli eventi
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
        Color active = palette.authorHover(widget.getForeground().getRGB(), widget.getBackground().getRGB());

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
            int x = end.x + GAP;
            int y = end.y + Math.max(0, (lineHeight - extent.y) / 2);
            boolean hot = hovered != null && hovered.line == line;
            Color ink = hot ? active : idle;
            gc.setForeground(ink);

            int textX = x;
            if (icon) {
                drawPeople(gc, ink, x, y + (extent.y - 8) / 2);
                textX += ICON_WIDTH;
            }
            gc.drawString(text, textX, y, true);
            if (hot) {
                int underline = y + extent.y - 1;
                gc.drawLine(textX, underline, textX + extent.x, underline);
            }
            hits.add(new Hit(new Rectangle(x - 2, end.y, ICON_WIDTH + extent.x + 6, lineHeight), line));
        }
        gc.setFont(previous);
    }

    /**
     * Due sagome stilizzate, la stessa icona usata dalle lens degli IDE.
     *
     * Sagome piene, non contorni: a 1px l'antialias scaricava il tratto e
     * l'icona finiva piu chiara del nome pur avendo lo stesso colore. Il
     * riempimento le tiene esattamente sulla tinta dell'autore.
     */
    private void drawPeople(GC gc, Color ink, int x, int y) {
        Color background = gc.getBackground();
        gc.setBackground(ink);
        // figura dietro, leggermente piu piccola e alzata
        gc.fillOval(x, y + 1, 4, 4);
        gc.fillArc(x - 1, y + 4, 7, 8, 0, 180);
        // Stacco nel colore dell'editor: piene, le due sagome si toccano e
        // diventerebbero una macchia sola.
        gc.setBackground(widget.getBackground());
        gc.fillOval(x + 4, y - 1, 7, 7);
        gc.fillArc(x + 2, y + 4, 11, 11, 0, 180);
        // figura davanti
        gc.setBackground(ink);
        gc.fillOval(x + 5, y, 5, 5);
        gc.fillArc(x + 3, y + 5, 9, 9, 0, 180);
        gc.setBackground(background);
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

    private Font labelFont(Display display) {
        if (font != null && !font.isDisposed()) return font;
        FontData[] data = widget.getFont().getFontData();
        for (FontData item : data) {
            item.setStyle(SWT.NORMAL);
            item.setHeight(Math.max(6, item.getHeight() - 2));
        }
        font = new Font(display, data);
        return font;
    }

    void setRevisionToggle(RevisionToggle toggle) {
        this.revisions = toggle;
    }

    /** Il clic sul nome apre le revisioni colorate per autore; il clic dopo le chiude. */
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
