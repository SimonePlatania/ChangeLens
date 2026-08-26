package com.simone.changelens;

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
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseMoveListener;
import org.eclipse.swt.events.MouseTrackAdapter;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

import com.simone.changelens.preferences.Preferences;

/**
 * Colonna a sinistra del testo con gli indicatori di modifica: una barra
 * continua e stondata alta esattamente quanto il blocco cambiato, dalla riga
 * di inizio alla riga di fine.
 *
 * La geometria viene ricalcolata a ogni disegno a partire dalle Position
 * ancorate al documento, e la colonna si ridisegna su ogni evento di
 * scorrimento del viewer: e questo che tiene le barre ferme sul loro codice
 * invece di farle scivolare con la vista.
 */
final class ChangeRulerColumn implements IVerticalRulerColumn {

    /**
     * La colonna e larga quanto lo spazio fra numero di riga e codice: si
     * ricava dalla larghezza media del carattere dell'editor, cosi resta
     * proporzionata a qualunque font e a qualunque zoom.
     */
    private static final int FALLBACK_WIDTH = 38;
    private static final int DELETION_HEIGHT = 6;
    /** Spazio fra i numeri di riga e la barretta delle modifiche. */
    private static final int BAR_X = 8;
    /** Spazio fra il separatore e la prima colonna di codice. */
    private static final int CODE_GAP = 12;
    /** Pausa prima di aprire il fumetto. */
    private static final int HOVER_DELAY = 320;

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
            // durante una modifica il ridisegno resta accodato: forzarlo qui
            // significherebbe disegnare a documento ancora in movimento
            redrawLater();
        }
    };

    private Canvas canvas;
    private StyledText widget;
    private CodePreview preview;
    private ChangeBlock shown;
    private Runnable pending;
    private Font cachedFont;
    private int cachedWidth;

    ChangeRulerColumn(LensController controller, ITextViewer viewer) {
        this.controller = controller;
        this.viewer = viewer;
    }

    @Override
    public Control createControl(CompositeRuler parentRuler, Composite parent) {
        widget = parentRuler.getTextViewer().getTextWidget();
        canvas = new Canvas(parent, SWT.NO_BACKGROUND);
        preview = new CodePreview(canvas, viewer);

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
                hidePreview();
            }
        });
        canvas.addMouseMoveListener(new MouseMoveListener() {
            @Override
            public void mouseMove(MouseEvent event) {
                schedule(event.y);
            }
        });
        canvas.addListener(SWT.Dispose, new Listener() {
            @Override
            public void handleEvent(Event event) {
                detach();
            }
        });

        // Senza questi tre agganci la colonna non saprebbe di dover ridisegnare
        // quando la vista scorre, e le barre resterebbero dov'erano.
        viewer.addViewportListener(viewport);
        viewer.addTextListener(text);
        widget.addControlListener(new ControlAdapter() {
            @Override
            public void controlResized(ControlEvent event) {
                redraw();
            }
        });
        controller.addListener(refresh);
        return canvas;
    }

    @Override
    public Control getControl() {
        return canvas;
    }

    @Override
    public int getWidth() {
        return columnWidth();
    }

    /**
     * Ridisegno immediato, non accodato.
     *
     * Con il solo {@code redraw()} il disegno arriva al giro di eventi
     * successivo, cioe dopo che il testo si e gia spostato: e quel ritardo di
     * un fotogramma che si vede come barra che scivola durante lo scorrimento.
     * L'{@code update()} la riporta in pari nello stesso evento, come fanno le
     * colonne native di Eclipse.
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
            // Come negli IDE moderni: il separatore, poi la barretta, poi un
            // margine vero prima del codice. Due caratteri di larghezza.
            cachedWidth = Math.max(34, Math.min(64, charWidth * 5));
        } finally {
            gc.dispose();
        }
        return cachedWidth;
    }

    /**
     * La riga verticale sottile fra numeri di riga e codice.
     * E il separatore che si vede negli IDE moderni: chiude visivamente la
     * colonna dei numeri e da un margine pulito al testo.
     */
    private void drawSeparator(GC gc, Rectangle area, Palette palette) {
        RGB foreground = widget.getForeground().getRGB();
        RGB background = widget.getBackground().getRGB();
        gc.setForeground(palette.get(new RGB(
                (foreground.red + background.red * 4) / 5,
                (foreground.green + background.green * 4) / 5,
                (foreground.blue + background.blue * 4) / 5)));
        gc.setLineWidth(1);
        int x = area.x + columnWidth() - CODE_GAP;
        gc.drawLine(x, area.y, x, area.y + area.height);
    }

    private int barWidth() {
        return Math.max(3, Math.min(5, columnWidth() / 8));
    }

    private static boolean markers() {
        Activator activator = Activator.getDefault();
        return activator != null
                && activator.getPreferenceStore().getBoolean(Preferences.CHANGE_MARKERS);
    }

    @Override
    public void setFont(Font font) {
        // la colonna non disegna testo
    }

    @Override
    public void setModel(IAnnotationModel model) {
        // gli indicatori vengono dal diff Git, non dal modello annotazioni
    }

    private void paint(GC gc) {
        Rectangle area = canvas.getClientArea();
        gc.setBackground(widget.getBackground());
        gc.fillRectangle(area);
        if (widget == null || widget.isDisposed()) return;

        gc.setAntialias(SWT.ON);
        Palette palette = Palette.of(canvas.getDisplay());

        // Il separatore e indipendente da Git: c'e sempre, anche su progetti
        // senza repository e su file senza alcuna modifica.
        if (enabled()) drawSeparator(gc, area, palette);
        if (!markers()) return;

        IDocument document = controller.document();
        GitSnapshot snapshot = controller.snapshot();
        if (document == null || snapshot.isEmpty()) return;

        int bar = barWidth();
        for (ChangeBlock block : snapshot.blocks) {
            int[] span = span(document, block);
            if (span == null || span[1] < area.y - DELETION_HEIGHT
                    || span[0] > area.y + area.height + DELETION_HEIGHT) {
                continue;
            }
            gc.setBackground(palette.forChange(block.kind));
            if (block.kind == ChangeBlock.DELETED) {
                gc.fillRoundRectangle(BAR_X, span[0] - DELETION_HEIGHT / 2,
                        bar + 4, DELETION_HEIGHT, 4, 4);
            } else {
                int height = Math.max(bar, span[1] - span[0]);
                gc.fillRoundRectangle(BAR_X, span[0], bar, height, bar, bar);
            }
        }
    }

    /**
     * Pixel di inizio e di fine del blocco nella colonna.
     * Il calcolo passa sempre dalle righe correnti del documento, mai da
     * numeri di riga memorizzati: dopo una modifica o uno scorrimento il
     * risultato e ancora quello giusto.
     */
    private int[] span(IDocument document, ChangeBlock block) {
        if (!block.isValid()) return null;
        int startWidget = widgetLine(document, block.startLine(document));
        int endWidget = widgetLine(document, block.endLine(document));
        if (startWidget < 0 || endWidget < 0) return null;
        // getLinePixel e gia relativo all'area client della StyledText: e la
        // stessa coordinata che usano le colonne native di Eclipse.
        int top = widget.getLinePixel(startWidget);
        int bottom = widget.getLinePixel(endWidget + 1);
        return new int[] { top, Math.max(top, bottom) };
    }

    /** Breve pausa prima di aprire il fumetto, non al primo pixel percorso. */
    private void schedule(final int y) {
        ChangeBlock block = enabled() && markers() ? blockAt(y) : null;
        if (block == shown) return;
        cancelPending();
        if (block == null) {
            hidePreview();
            return;
        }
        pending = new Runnable() {
            @Override
            public void run() {
                pending = null;
                if (canvas != null && !canvas.isDisposed()) showPreviewAt(y);
            }
        };
        canvas.getDisplay().timerExec(HOVER_DELAY, pending);
    }

    private void cancelPending() {
        if (pending == null || canvas == null || canvas.isDisposed()) return;
        canvas.getDisplay().timerExec(-1, pending);
        pending = null;
    }

    private void showPreviewAt(int y) {
        ChangeBlock block = enabled() ? blockAt(y) : null;
        if (block == null) {
            hidePreview();
            return;
        }
        shown = block;
        IDocument document = controller.document();
        Rectangle bounds = canvas.getBounds();
        preview.showChange(block, document, widget, canvas.toDisplay(bounds.width + 2, y));
    }

    private void hidePreview() {
        shown = null;
        cancelPending();
        if (preview != null) preview.hide();
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
        try {
            viewer.removeViewportListener(viewport);
            viewer.removeTextListener(text);
        } catch (Exception ignored) {
            // il viewer puo essere gia stato smontato
        }
        if (preview != null) preview.dispose();
        preview = null;
        shown = null;
    }

    private static boolean enabled() {
        Activator activator = Activator.getDefault();
        return activator != null && activator.getPreferenceStore().getBoolean(Preferences.ENABLED);
    }
}
