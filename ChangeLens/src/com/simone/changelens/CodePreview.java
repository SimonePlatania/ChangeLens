package com.simone.changelens;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.resource.StringConverter;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.ITextViewerExtension5;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.graphics.Region;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TypedListener;
import org.eclipse.ui.editors.text.EditorsUI;

/**
 * Il fumetto di anteprima del codice: finestrella larga, angoli stondati e una
 * punta laterale che indica il punto sotto il mouse.
 *
 * Il contenuto e una copia dell'editor, non testo grezzo: gli StyleRange
 * vengono presi dalla StyledText dell'editor, quindi colori, tema e
 * evidenziazioni sono gia quelli giusti senza rieseguire alcuna analisi
 * sintattica. Se per quelle righe l'editor non ha ancora calcolato la
 * presentazione, si ricade sul testo semplice invece di far lavoro extra.
 */
final class CodePreview {

    private static final int CONTEXT = 6;
    private static final int MAX_LINES = 16;
    /** Righe visibili nel fumetto della barra panoramica. */
    private static final int WINDOW_LINES = 2 * CONTEXT + 1;
    /**
     * Righe tenute pronte sopra e sotto quelle visibili. E questo margine che
     * rende lo scorrimento uno scorrimento vero: finche il puntatore resta
     * dentro la finestra caricata non si ricompone nulla, si sposta solo la
     * vista, esattamente come scorrere una pagina.
     */
    private static final int WINDOW_MARGIN = 120;
    private static final int MIN_WIDTH = 1240;
    private static final int MAX_WIDTH = 1900;
    private static final int PADDING = 14;
    private static final int RADIUS = 10;
    /** Sporgenza e mezza altezza della punta del fumetto. */
    private static final int TAIL = 12;
    private static final int TAIL_HALF = 9;

    private final Control anchor;
    private final ITextViewer viewer;

    private IDocument windowDocument;
    private int windowFirst = -1;
    private int windowLast = -1;
    private int windowWidth;

    private Shell shell;
    private StyledText content;
    private StyledText source;
    private Region region;
    private String shownText;
    private boolean pointLeft;
    private int tailY = -1;
    private Point shownSize = new Point(0, 0);

    CodePreview(Control anchor, ITextViewer viewer) {
        this.anchor = anchor;
        this.viewer = viewer;
    }

    /**
     * Offset nella StyledText corrispondente a un offset del documento.
     *
     * Con il folding attivo i due non coincidono: ogni regione ripiegata
     * sposta indietro tutto cio che segue. Leggere gli stili dal widget usando
     * gli offset del documento significava prendere la colorazione di un altro
     * punto del file, ed e per questo che il fumetto usciva con colori diversi
     * dalla pagina.
     */
    private int widgetOffset(int modelOffset) {
        if (viewer instanceof ITextViewerExtension5) {
            return ((ITextViewerExtension5) viewer).modelOffset2WidgetOffset(modelOffset);
        }
        return modelOffset;
    }

    /** Anteprima di un blocco di modifica: per le cancellazioni mostra HEAD. */
    void showChange(ChangeBlock block, IDocument document, StyledText source, Point tip) {
        pointLeft = true;
        if (block.kind == ChangeBlock.DELETED) {
            show(block.original, -1, source, null, tip);
        } else {
            int first = block.startLine(document);
            show(readLines(document, first, block.endLine(document)), first, source, document, tip);
        }
    }

    /**
     * Anteprima del codice attorno a una riga, per la barra panoramica.
     *
     * Il fumetto non viene ricomposto a ogni riga: tiene in memoria una fetta
     * di pagina larga {@link #WINDOW_MARGIN} righe per lato e, finche il
     * puntatore resta dentro quella fetta, si limita a spostare la vista con
     * {@code setTopPixel}. Da qui lo scorrimento continuo: e lo stesso
     * meccanismo con cui scorre l'editor, non un susseguirsi di anteprime.
     */
    void showAround(IDocument document, int line, StyledText source, Point tip) {
        if (document == null || line < 0 || anchor.isDisposed()) {
            hide();
            return;
        }
        pointLeft = false;
        create(source);

        int lastLine = document.getNumberOfLines() - 1;
        if (lastLine < 0) {
            hide();
            return;
        }
        line = Math.min(line, lastLine);
        if (!windowHolds(document, line, lastLine)) {
            buildWindow(document, source, line, lastLine);
        }
        if (windowFirst < 0) {
            hide();
            return;
        }

        int lineHeight = Math.max(1, content.getLineHeight());
        Point size = new Point(windowWidth,
                WINDOW_LINES * lineHeight + 2 * PADDING + 4);
        layout(size, tip);
        // La vista si posiziona in modo che la riga puntata resti al centro.
        int top = (line - CONTEXT - windowFirst) * lineHeight;
        int maxTop = Math.max(0, (windowLast - windowFirst + 1) * lineHeight
                - content.getClientArea().height);
        content.setTopPixel(Math.max(0, Math.min(top, maxTop)));
        if (!shell.isVisible()) shell.setVisible(true);
    }

    /** La fetta caricata copre gia la riga puntata con il suo contorno. */
    private boolean windowHolds(IDocument document, int line, int lastLine) {
        if (windowFirst < 0 || document != windowDocument) return false;
        return line - CONTEXT >= windowFirst - 1 && line + CONTEXT <= windowLast + 1
                && windowLast <= lastLine;
    }

    private void buildWindow(IDocument document, StyledText source, int line, int lastLine) {
        int first = Math.max(0, line - WINDOW_MARGIN);
        int last = Math.min(lastLine, line + WINDOW_MARGIN);
        int gutter = String.valueOf(last + 1).length();
        String text = composeAll(readLines(document, first, last), first, gutter);

        windowDocument = document;
        windowFirst = first;
        windowLast = last;
        shownText = text;
        content.setText(text);
        applyStyles(document, source, first, gutter);
        windowWidth = widthFor(text);
    }

    /** Come {@link #compose}, ma senza il tetto di righe: la fetta va tutta. */
    private String composeAll(String body, int firstLine, int gutter) {
        String[] lines = body.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            out.append(pad(firstLine + i + 1, gutter)).append("  ").append(lines[i]);
        }
        return out.toString();
    }

    void hide() {
        if (shell != null && !shell.isDisposed() && shell.isVisible()) {
            shell.setVisible(false);
            shownText = null;
            // La fetta caricata invecchia: il documento puo cambiare mentre il
            // fumetto e chiuso, e mostrarla di nuovo sarebbe mostrare il
            // passato. Alla prossima apertura si ricostruisce.
            invalidateWindow();
        }
    }

    private void invalidateWindow() {
        windowDocument = null;
        windowFirst = -1;
        windowLast = -1;
    }

    void dispose() {
        if (shell != null && !shell.isDisposed()) shell.dispose();
        shell = null;
        content = null;
        source = null;
        shownText = null;
        disposeRegion();
    }

    private void show(String body, int firstLine, StyledText source, IDocument document, Point tip) {
        if (anchor.isDisposed() || body == null || body.trim().isEmpty()) {
            hide();
            return;
        }
        int gutter = gutterWidth(body, firstLine);
        String text = compose(body, firstLine, gutter);
        create(source);
        if (!text.equals(shownText)) {
            shownText = text;
            content.setText(text);
            applyStyles(document, source, firstLine, gutter);
            // questo percorso scrive nello stesso widget: la fetta di pagina
            // tenuta per la barra panoramica non vale piu
            invalidateWindow();
        }
        content.setTopPixel(0);

        Point size = new Point(widthFor(text),
                content.getLineCount() * content.getLineHeight() + 2 * PADDING + 4);
        layout(size, tip);
        if (!shell.isVisible()) shell.setVisible(true);
        shell.redraw();
    }

    /**
     * Mette la finestrella al suo posto, rifacendo la sagoma solo se serve.
     *
     * La Region di ritaglio e la parte cara del giro: ricrearla a ogni pixel
     * percorso dal mouse e cio che rendeva lo scorrimento a scatti. Dipende
     * solo dalla dimensione e dalla posizione della punta, che durante lo
     * scorrimento non cambiano.
     */
    private void layout(Point size, Point tip) {
        Rectangle screen = anchor.getMonitor().getClientArea();
        size.x = Math.min(size.x, Math.max(200, screen.width - 24));
        Point where = place(size, tip);
        int wantedTail = Math.max(RADIUS + TAIL_HALF,
                Math.min(size.y - RADIUS - TAIL_HALF, tip.y - where.y));

        if (region == null || !size.equals(shownSize) || wantedTail != tailY) {
            tailY = wantedTail;
            shownSize = size;
            shape(size);
            content.setBounds(pointLeft ? TAIL + PADDING : PADDING, PADDING,
                    size.x - TAIL - 2 * PADDING, size.y - 2 * PADDING);
        }
        shell.setBounds(where.x, where.y, size.x, size.y);
    }

    // ---------------------------------------------------------------- testo

    private String compose(String body, int firstLine, int gutter) {
        String[] lines = body.split("\n", -1);
        int count = Math.min(lines.length, MAX_LINES);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) out.append('\n');
            if (gutter > 0) out.append(pad(firstLine + i + 1, gutter)).append("  ");
            out.append(lines[i]);
        }
        if (lines.length > count) {
            out.append('\n').append("... altre ").append(lines.length - count).append(" righe");
        }
        return out.toString();
    }

    private static int gutterWidth(String body, int firstLine) {
        if (firstLine < 0) return 0;
        int count = Math.min(body.split("\n", -1).length, MAX_LINES);
        return String.valueOf(firstLine + count).length();
    }

    private static String pad(int number, int width) {
        StringBuilder out = new StringBuilder(String.valueOf(number));
        while (out.length() < width) out.insert(0, ' ');
        return out.toString();
    }

    private static String readLines(IDocument document, int from, int to) {
        if (document == null || from < 0) return "";
        StringBuilder out = new StringBuilder();
        int last = Math.min(to, document.getNumberOfLines() - 1);
        for (int line = from; line <= last; line++) {
            try {
                IRegion region = document.getLineInformation(line);
                if (out.length() > 0) out.append('\n');
                out.append(document.get(region.getOffset(), region.getLength()));
            } catch (BadLocationException ignored) {
                break;
            }
        }
        return out.toString();
    }

    // --------------------------------------------------------------- colori

    /**
     * Ricopia la colorazione dall'editor, riga per riga, spostando gli offset
     * per tenere conto del numero di riga aggiunto davanti. Nessuna analisi
     * viene rieseguita: si leggono gli StyleRange gia calcolati.
     */
    private void applyStyles(IDocument document, StyledText source, int firstLine, int gutter) {
        dimGutter(gutter);
        if (document == null || source == null || source.isDisposed() || firstLine < 0) return;
        int prefix = gutter > 0 ? gutter + 2 : 0;
        try {
            for (int i = 0; i < content.getLineCount(); i++) {
                int line = firstLine + i;
                if (line >= document.getNumberOfLines()) break;
                IRegion region = document.getLineInformation(line);
                if (region.getLength() == 0) continue;
                // Offset del widget, non del documento: con il folding attivo
                // ogni regione ripiegata li disallinea, e leggere gli stili
                // all'offset sbagliato dava al fumetto la colorazione di
                // tutt'altro punto del file.
                int start = widgetOffset(region.getOffset());
                if (start < 0) continue;
                int length = Math.min(region.getLength(), source.getCharCount() - start);
                if (length <= 0) continue;

                StyleRange[] ranges = source.getStyleRanges(start, length, true);
                if (ranges == null) continue;
                int target = content.getOffsetAtLine(i) + prefix;
                for (StyleRange range : ranges) {
                    StyleRange copy = (StyleRange) range.clone();
                    copy.start = target + (range.start - start);
                    if (copy.start < 0 || copy.start + copy.length > content.getCharCount()) continue;
                    content.setStyleRange(copy);
                }
            }
        } catch (Exception ignored) {
            // presentazione non disponibile per quelle righe: resta il testo
        }
    }

    /** I numeri di riga vanno in secondo piano, come nell'editor. */
    private void dimGutter(int gutter) {
        if (gutter <= 0) return;
        Color dim = Palette.of(anchor.getDisplay()).get(blend(
                content.getForeground().getRGB(), content.getBackground().getRGB()));
        for (int i = 0; i < content.getLineCount(); i++) {
            try {
                content.setStyleRange(new StyleRange(content.getOffsetAtLine(i), gutter, dim, null));
            } catch (Exception ignored) {
                // riga fuori dal testo: nessuno stile da applicare
            }
        }
    }

    private static RGB blend(RGB a, RGB b) {
        return mix(a, b, 0.5);
    }

    /** Mescola due colori: {@code weight} e la quota di {@code a}. */
    private static RGB mix(RGB a, RGB b, double weight) {
        return new RGB(channel(a.red, b.red, weight), channel(a.green, b.green, weight),
                channel(a.blue, b.blue, weight));
    }

    private static int channel(int a, int b, double weight) {
        int value = (int) Math.round(a * weight + b * (1 - weight));
        return Math.max(0, Math.min(255, value));
    }

    // -------------------------------------------------------------- finestra

    private void create(StyledText source) {
        this.source = source;
        if (shell != null && !shell.isDisposed()) {
            adoptLook(source);
            return;
        }
        Display display = anchor.getDisplay();
        shell = new Shell(anchor.getShell(), SWT.ON_TOP | SWT.NO_FOCUS | SWT.NO_TRIM);
        content = new StyledText(shell, SWT.MULTI | SWT.READ_ONLY | SWT.NO_FOCUS);
        // Il motore CSS del workbench ristilizza i widget nuovi appena creati:
        // sulla nostra StyledText rimetteva il grigio generico del tema al
        // posto dello sfondo dell'editor, ed e per questo che il fumetto non
        // sembrava della stessa tinta della pagina. Questi dati lo escludono.
        skipTheming(shell);
        skipTheming(content);
        content.setCaret(null);
        content.setEditable(false);
        content.setCursor(display.getSystemCursor(SWT.CURSOR_ARROW));
        adoptLook(source);
        shell.addPaintListener(new PaintListener() {
            @Override
            public void paintControl(PaintEvent event) {
                drawFrame(event.gc);
            }
        });
        content.addPaintListener(new PaintListener() {
            @Override
            public void paintControl(PaintEvent event) {
                replayDecorations(event.gc);
            }
        });
    }

    /**
     * Ripete sul fumetto i disegni che gli altri plugin fanno sull'editor:
     * linee di indentazione, righelli, evidenziazioni. I loro PaintListener
     * sono registrati sulla StyledText dell'editor, non sulla nostra, quindi
     * senza questo passaggio il fumetto mostrerebbe solo la colorazione
     * sintattica e non sarebbe una copia fedele della pagina.
     *
     * Non viene creato nulla e non viene ricalcolato nulla: si riusano gli
     * stessi listener gia in memoria, invocati su un widget di poche righe.
     * Il costo e quello di ridisegnare una finestrella, non l'editor.
     */
    private void replayDecorations(GC gc) {
        if (source == null || source.isDisposed() || content == null || content.isDisposed()) return;
        Listener[] listeners = source.getListeners(SWT.Paint);
        if (listeners == null) return;
        for (Listener listener : listeners) {
            PaintListener painter = paintListenerOf(listener);
            // il nostro pittore degli autori disegnerebbe nomi fuori posto:
            // nel fumetto il testo ha altre righe e altri offset
            if (painter == null || painter instanceof AuthorPainter) continue;
            Event event = new Event();
            event.widget = content;
            event.display = content.getDisplay();
            event.gc = gc;
            Rectangle area = content.getClientArea();
            event.x = area.x;
            event.y = area.y;
            event.width = area.width;
            event.height = area.height;
            try {
                painter.paintControl(new PaintEvent(event));
            } catch (Throwable ignored) {
                // decoratore altrui che non gradisce un widget diverso:
                // si salta, il fumetto resta comunque leggibile
            }
        }
    }

    private static PaintListener paintListenerOf(Listener listener) {
        if (!(listener instanceof TypedListener)) return null;
        Object typed = ((TypedListener) listener).getEventListener();
        return typed instanceof PaintListener ? (PaintListener) typed : null;
    }

    /**
     * Esclude un widget dal motore CSS del workbench, che altrimenti gli
     * rimetterebbe i colori generici del tema sopra quelli dell'editor. Le
     * chiavi cambiano fra le versioni di Eclipse, quindi si mettono entrambe:
     * quella ignota viene semplicemente ignorata.
     */
    private static void skipTheming(org.eclipse.swt.widgets.Widget widget) {
        try {
            widget.setData("org.eclipse.e4.ui.css.disabled", Boolean.TRUE);
            widget.setData("org.eclipse.e4.ui.css.CssClassName", "ChangeLensPreview");
        } catch (Exception ignored) {
            // versione senza motore CSS: non c'e nulla da disattivare
        }
    }

    /**
     * Il colore che l'editor di testo ha davvero configurato. E la sorgente
     * autorevole: il colore letto dal widget puo essere quello generico messo
     * dal tema, e in quel caso il fumetto usciva di una tinta diversa dalla
     * pagina. Se la preferenza dice "usa il colore di sistema" si torna al
     * widget, che a quel punto e giusto.
     */
    private Color editorColor(String key, Color fallback) {
        try {
            IPreferenceStore store = EditorsUI.getPreferenceStore();
            if (store == null || store.getBoolean(key + ".SystemDefault")) return fallback;
            String value = store.getString(key);
            if (value == null || value.isEmpty()) return fallback;
            return Palette.of(anchor.getDisplay()).get(StringConverter.asRGB(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    /** Font e colori sono quelli dell'editor: il fumetto ne e una copia. */
    private void adoptLook(StyledText source) {
        if (source == null || source.isDisposed()) return;
        content.setFont(source.getFont());
        content.setTabs(source.getTabs());
        // Riallineati a ogni apertura, non solo alla creazione: se il tema
        // cambia, o se il CSS e passato dopo, il fumetto si rimette in pari.
        Color background = editorColor("AbstractTextEditor.Color.Background", source.getBackground());
        Color foreground = editorColor("AbstractTextEditor.Color.Foreground", source.getForeground());
        if (background != null && !background.equals(content.getBackground())) {
            content.setBackground(background);
            shell.setBackground(background);
        }
        if (foreground != null && !foreground.equals(content.getForeground())) {
            content.setForeground(foreground);
        }
    }

    private void drawFrame(GC gc) {
        Point size = shell.getSize();
        gc.setAdvanced(true);
        gc.setAntialias(SWT.ON);
        gc.setBackground(content.getBackground());
        gc.fillRectangle(0, 0, size.x, size.y);
        // Bordo tirato verso il colore del testo: sul tema scuro un blend a meta
        // spariva contro lo sfondo dell'editor e il fumetto non si distingueva.
        gc.setForeground(Palette.of(anchor.getDisplay()).get(mix(
                content.getForeground().getRGB(), content.getBackground().getRGB(), 0.55)));
        gc.setLineWidth(2);
        gc.drawPolygon(outline(size));
    }

    /**
     * Ritaglia la Shell sulla sagoma del fumetto: senza questo la finestra
     * resterebbe un rettangolo e la punta non si vedrebbe.
     */
    private void shape(Point size) {
        disposeRegion();
        region = new Region(anchor.getDisplay());
        region.add(outline(size));
        shell.setRegion(region);
    }

    private void disposeRegion() {
        if (region != null && !region.isDisposed()) region.dispose();
        region = null;
    }

    /** Rettangolo stondato piu la punta sul lato rivolto al punto indicato. */
    private int[] outline(Point size) {
        int left = pointLeft ? TAIL : 0;
        int right = pointLeft ? size.x - 1 : size.x - 1 - TAIL;
        int bottom = size.y - 1;
        List<Integer> points = new ArrayList<Integer>();

        corner(points, left + RADIUS, RADIUS, 180, 270);
        corner(points, right - RADIUS, RADIUS, 270, 360);
        if (!pointLeft) {
            add(points, right, tailY - TAIL_HALF);
            add(points, right + TAIL, tailY);
            add(points, right, tailY + TAIL_HALF);
        }
        corner(points, right - RADIUS, bottom - RADIUS, 0, 90);
        corner(points, left + RADIUS, bottom - RADIUS, 90, 180);
        if (pointLeft) {
            add(points, left, tailY + TAIL_HALF);
            add(points, left - TAIL, tailY);
            add(points, left, tailY - TAIL_HALF);
        }

        int[] result = new int[points.size()];
        for (int i = 0; i < result.length; i++) result[i] = points.get(i).intValue();
        return result;
    }

    private static void corner(List<Integer> points, int cx, int cy, int from, int to) {
        for (int angle = from; angle <= to; angle += 15) {
            double radians = Math.toRadians(angle);
            add(points, cx + (int) Math.round(RADIUS * Math.cos(radians)),
                    cy + (int) Math.round(RADIUS * Math.sin(radians)));
        }
    }

    private static void add(List<Integer> points, int x, int y) {
        points.add(Integer.valueOf(x));
        points.add(Integer.valueOf(y));
    }

    /** Larghezza che sta dietro alla riga piu lunga del testo mostrato. */
    private int widthFor(String text) {
        int longest = 0;
        int current = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                longest = Math.max(longest, current);
                current = 0;
            } else {
                current += text.charAt(i) == '\t' ? 4 : 1;
            }
        }
        longest = Math.max(longest, current);
        int charWidth = Math.max(6, averageCharWidth());
        return Math.min(MAX_WIDTH,
                Math.max(MIN_WIDTH, longest * charWidth + 2 * PADDING + TAIL + 16));
    }

    private int averageCharWidth() {
        GC gc = new GC(content);
        try {
            gc.setFont(content.getFont());
            return gc.getFontMetrics().getAverageCharWidth();
        } finally {
            gc.dispose();
        }
    }

    /** Posizione della Shell perche la punta cada sul punto indicato. */
    private Point place(Point size, Point tip) {
        Rectangle screen = anchor.getMonitor().getClientArea();
        int x = pointLeft ? tip.x : tip.x - size.x;
        int y = tip.y - size.y / 2;
        x = Math.max(screen.x, Math.min(x, screen.x + screen.width - size.x));
        y = Math.max(screen.y, Math.min(y, screen.y + screen.height - size.y));
        return new Point(x, y);
    }
}
