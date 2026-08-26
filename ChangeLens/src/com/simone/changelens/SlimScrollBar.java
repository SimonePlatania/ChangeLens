package com.simone.changelens;

import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.IViewportListener;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseMoveListener;
import org.eclipse.swt.events.MouseTrackAdapter;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Scrollable;

import com.simone.changelens.preferences.Preferences;

/**
 * Sostituisce la barra di scorrimento verticale di sistema con una linguetta
 * stondata disegnata sulla stessa striscia dei segnaposto, senza binario ne
 * frecce: dietro resta lo sfondo dell'editor, e gli indicatori di modifica,
 * errore e warning convivono con la linguetta invece di stare in due colonne
 * separate.
 *
 * La barra nativa viene solo nascosta: allo smontaggio torna esattamente
 * com'era.
 */
final class SlimScrollBar {

    private static final int THUMB_WIDTH = 7;
    private static final int MIN_THUMB = 70;
    private static final int MARGIN = 3;

    private final ITextViewer viewer;
    private final StyledText widget;
    private final Scrollable strip;

    private final PaintListener painter = new PaintListener() {
        @Override
        public void paintControl(PaintEvent event) {
            try {
                paint(event.gc);
            } catch (Exception failure) {
                Activator.log(failure);
            }
        }
    };
    private final IViewportListener viewport = new IViewportListener() {
        @Override
        public void viewportChanged(int verticalOffset) {
            refresh();
        }
    };

    private Runnable onScroll;
    private boolean nativeBarWasVisible;
    private boolean hot;
    private boolean dragging;
    private int grabOffset;
    private boolean disposed;

    static SlimScrollBar install(ITextViewer viewer, Control strip, Runnable onScroll) {
        if (viewer == null || !(strip instanceof Scrollable) || strip.isDisposed()) return null;
        StyledText widget = viewer.getTextWidget();
        if (widget == null || widget.isDisposed()) return null;
        SlimScrollBar bar = new SlimScrollBar(viewer, widget, (Scrollable) strip);
        bar.onScroll = onScroll;
        bar.attach();
        return bar;
    }

    private SlimScrollBar(ITextViewer viewer, StyledText widget, Scrollable strip) {
        this.viewer = viewer;
        this.widget = widget;
        this.strip = strip;
    }

    private void attach() {
        ScrollBar bar = widget.getVerticalBar();
        if (bar != null && !bar.isDisposed()) {
            nativeBarWasVisible = bar.isVisible();
            bar.setVisible(false);
        }
        // aggiunto per ultimo: la linguetta finisce sopra i segnaposto nativi
        strip.addPaintListener(painter);
        viewer.addViewportListener(viewport);

        strip.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseDown(MouseEvent event) {
                Rectangle thumb = thumb();
                if (thumb == null || !thumb.contains(event.x, event.y)) return;
                dragging = true;
                grabOffset = event.y - thumb.y;
            }

            @Override
            public void mouseUp(MouseEvent event) {
                dragging = false;
            }
        });
        strip.addMouseMoveListener(new MouseMoveListener() {
            @Override
            public void mouseMove(MouseEvent event) {
                if (dragging) {
                    scrollTo(event.y - grabOffset);
                    return;
                }
                Rectangle thumb = thumb();
                boolean over = thumb != null && thumb.contains(event.x, event.y);
                if (over != hot) {
                    hot = over;
                    refresh();
                }
            }
        });
        strip.addMouseTrackListener(new MouseTrackAdapter() {
            @Override
            public void mouseExit(MouseEvent event) {
                if (!hot) return;
                hot = false;
                refresh();
            }
        });
        strip.addListener(SWT.Dispose, new Listener() {
            @Override
            public void handleEvent(Event event) {
                dispose();
            }
        });
        widget.addListener(SWT.Resize, new Listener() {
            @Override
            public void handleEvent(Event event) {
                refresh();
            }
        });

        // Senza questo la linguetta compariva solo al primo scorrimento: era
        // l'evento di viewport a farla disegnare. All'apertura dell'editor il
        // conteggio righe e l'altezza utile non sono ancora definitivi, quindi
        // si ridisegna subito e di nuovo a impaginazione avvenuta.
        refresh();
        widget.getDisplay().asyncExec(new Runnable() {
            @Override
            public void run() {
                refresh();
            }
        });
        widget.getDisplay().timerExec(300, new Runnable() {
            @Override
            public void run() {
                refresh();
            }
        });
    }

    private void paint(GC gc) {
        if (disposed || !enabled()) return;
        Rectangle thumb = thumb();
        if (thumb == null) return;

        RGB foreground = widget.getForeground().getRGB();
        RGB background = widget.getBackground().getRGB();
        Palette palette = Palette.of(strip.getDisplay());
        gc.setAdvanced(true);
        gc.setAntialias(SWT.ON);
        gc.setBackground(palette.get(new RGB(
                (foreground.red + background.red * 2) / 3,
                (foreground.green + background.green * 2) / 3,
                (foreground.blue + background.blue * 2) / 3)));

        if (gc.getAdvanced()) {
            // Semitrasparente: le tacche di errori e warning restano leggibili
            // sotto la linguetta invece di essere coperte.
            gc.setAlpha(hot || dragging ? 130 : 70);
            gc.fillRoundRectangle(thumb.x, thumb.y, thumb.width, thumb.height,
                    THUMB_WIDTH, THUMB_WIDTH);
            gc.setAlpha(255);
        } else {
            // Senza grafica avanzata l'alpha viene ignorato: si disegna solo il
            // contorno, cosi la linguetta non nasconde comunque nulla.
            gc.setForeground(gc.getBackground());
            gc.drawRoundRectangle(thumb.x, thumb.y, thumb.width - 1, thumb.height - 1,
                    THUMB_WIDTH, THUMB_WIDTH);
        }
    }

    /** Il punto e sulla linguetta: li il fumetto non deve comparire. */
    boolean isOnThumb(int y) {
        Rectangle thumb = thumb();
        return thumb != null && y >= thumb.y - 2 && y <= thumb.y + thumb.height + 2;
    }

    boolean isDragging() {
        return dragging;
    }

    /** Geometria della linguetta, in proporzione alla porzione di file visibile. */
    private Rectangle thumb() {
        if (strip == null || strip.isDisposed() || widget.isDisposed()) return null;
        Rectangle area = strip.getClientArea();
        int total = totalPixels();
        int visible = widget.getClientArea().height;
        if (total <= 0 || visible <= 0 || visible >= total) return null;

        int track = Math.max(1, area.height - 2 * MARGIN);
        // Lunghezza proporzionale alla porzione di file visibile, senza tetto:
        // file corto, linguetta lunga; file lungo, linguetta corta. Un massimo
        // fisso avrebbe fatto sembrare uguali un file di 200 righe e uno di
        // 5000.
        int height = Math.max(MIN_THUMB, (int) ((long) track * visible / total));
        height = Math.min(height, track);
        int top = MARGIN + (int) ((long) (track - height) * widget.getTopPixel()
                / Math.max(1, total - visible));
        int width = Math.min(THUMB_WIDTH, Math.max(4, area.width - 2));
        int x = area.x + Math.max(0, (area.width - width) / 2);
        return new Rectangle(x, area.y + top, width, height);
    }

    private int totalPixels() {
        int lines = widget.getLineCount();
        int height = widget.getLineHeight();
        return lines * height;
    }

    private void scrollTo(int thumbTop) {
        Rectangle area = strip.getClientArea();
        Rectangle thumb = thumb();
        if (thumb == null) return;
        int total = totalPixels();
        int visible = widget.getClientArea().height;
        int track = Math.max(1, area.height - 2 * MARGIN - thumb.height);
        int clamped = Math.max(0, Math.min(track, thumbTop - MARGIN));
        widget.setTopPixel((int) ((long) clamped * (total - visible) / track));
        // setTopPixel sposta il testo senza passare dalla barra di sistema,
        // quindi il viewer non emette alcun evento di viewport: le colonne
        // vanno avvisate a mano, o le barre restano indietro.
        if (onScroll != null) onScroll.run();
        refresh();
    }

    private void refresh() {
        if (disposed || strip == null || strip.isDisposed()) return;
        strip.redraw();
        strip.update();
    }

    private static boolean enabled() {
        Activator activator = Activator.getDefault();
        return activator != null
                && activator.getPreferenceStore().getBoolean(Preferences.ENABLED)
                && activator.getPreferenceStore().getBoolean(Preferences.SLIM_SCROLLBAR);
    }

    void dispose() {
        if (disposed) return;
        disposed = true;
        try {
            viewer.removeViewportListener(viewport);
        } catch (Exception ignored) {
            // viewer gia smontato
        }
        if (strip != null && !strip.isDisposed()) strip.removePaintListener(painter);
        if (!widget.isDisposed()) {
            ScrollBar bar = widget.getVerticalBar();
            if (bar != null && !bar.isDisposed()) bar.setVisible(nativeBarWasVisible);
        }
    }
}
