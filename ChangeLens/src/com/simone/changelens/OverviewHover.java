package com.simone.changelens;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.source.IOverviewRuler;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseMoveListener;
import org.eclipse.swt.events.MouseTrackAdapter;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

/**
 * Passaggio del mouse sulla barra panoramica di destra: sopra una stanghetta
 * si apre il fumetto con il codice di quel punto del file, e allontanandosi
 * sparisce. Il salto alla riga al clic resta quello nativo della barra.
 */
final class OverviewHover {

    /** Pausa prima di aprire il fumetto. */
    private static final int HOVER_DELAY = 320;
    /**
     * Quanto a sinistra della barra panoramica il fumetto risponde comunque.
     * La barra e larga una decina di pixel: pretendere di restare li dentro
     * rendeva l'apertura un esercizio di mira.
     */
    private static final int HOT_ZONE = 70;

    private final LensController controller;
    private final ITextViewer viewer;

    private IOverviewRuler ruler;
    private Control control;
    private CodePreview preview;
    private SlimScrollBar scrollBar;
    private Runnable pending;
    private int shownLine = -1;
    private int wantedLine = -1;
    private boolean open;

    /**
     * Il tooltip dei marcatori della barra panoramica non passa sempre da
     * setToolTipText: quando e un hover di JFace arriva da un evento MouseHover
     * sul canvas. Un filtro sul Display puo annullarlo mettendo il tipo a
     * SWT.None, cosa che un normale listener non puo fare.
     */
    private final Listener hoverFilter = new Listener() {
        @Override
        public void handleEvent(Event event) {
            if (event.widget == control) event.type = SWT.None;
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
        control.setToolTipText(null);
        widenHotZone();
        control.getDisplay().addFilter(SWT.MouseHover, hoverFilter);
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
     * Allarga la zona sensibile al bordo destro dell'editor.
     *
     * Gli eventi del mouse arrivano solo al controllo sotto il puntatore,
     * quindi per rispondere anche fuori dalla barra bisogna ascoltare pure la
     * StyledText: quando il puntatore e nell'ultima striscia di testo si
     * traduce la sua altezza in coordinate della barra e si procede come se
     * fosse li sopra.
     */
    private void widenHotZone() {
        final StyledText widget = viewer.getTextWidget();
        if (widget == null || widget.isDisposed()) return;
        widget.addMouseMoveListener(new MouseMoveListener() {
            @Override
            public void mouseMove(MouseEvent event) {
                if (control == null || control.isDisposed() || widget.isDisposed()) return;
                if (event.x < widget.getClientArea().width - HOT_ZONE) {
                    // uscito dalla striscia: il fumetto aperto da qui si chiude
                    if (open) hideNow();
                    return;
                }
                Point onStrip = control.toControl(widget.toDisplay(event.x, event.y));
                if (onStrip.y < 0 || onStrip.y > control.getSize().y) return;
                schedule(onStrip.y);
            }
        });
        widget.addMouseTrackListener(new MouseTrackAdapter() {
            @Override
            public void mouseExit(MouseEvent event) {
                if (open) hideNow();
            }
        });
    }

    Control strip() {
        return control;
    }

    void setScrollBar(SlimScrollBar bar) {
        this.scrollBar = bar;
    }

    /**
     * Il fumetto compare dopo una breve pausa, non al primo pixel percorso, e
     * mai sopra la linguetta di scorrimento: li il mouse serve a trascinare.
     * La pausa vale solo per l'apertura: finche il fumetto resta aperto segue
     * il mouse subito, senza timer, cosi lo scorrimento e fluido.
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
            // gia aperto: nessuna attesa, si aggiorna al volo
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
     * Il canvas della barra panoramica mostra da solo il tooltip dei marcatori
     * ("Multiple markers at this line"): finirebbe sopra il fumetto, quindi lo
     * si azzera e lo si rimette solo allo smontaggio.
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

    private void show(int y) {
        int line = lineAt(y);
        if (line < 0) {
            hide();
            return;
        }
        shownLine = line;
        open = true;
        preview.showAround(controller.document(), line, viewer.getTextWidget(),
                control.toDisplay(-2, y));
    }

    private void hide() {
        shownLine = -1;
        open = false;
        if (preview != null) preview.hide();
    }

    private void hideNow() {
        wantedLine = -1;
        cancelPending();
        hide();
    }

    /**
     * La riga di documento sotto il puntatore. Il calcolo lo fa la barra
     * stessa, quindi il fumetto mostra sempre il punto giusto anche con la
     * scala compressa dei file lunghi.
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
     * La barra panoramica del viewer. Non e API pubblica, e a seconda della
     * versione di Eclipse e raggiungibile come metodo protetto o solo come
     * campo, quindi si provano entrambe le strade risalendo la gerarchia.
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
        }
        if (preview != null) preview.dispose();
        preview = null;
        control = null;
        ruler = null;
        shownLine = -1;
    }
}
