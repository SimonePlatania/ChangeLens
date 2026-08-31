package com.simone.changelens;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.resource.StringConverter;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.ITextViewerExtension2;
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
import org.eclipse.ui.editors.text.EditorsUI;

/**
 * The code preview panel: a rounded sheet with a rainbow border and an arrow
 * pointing at the spot under the mouse.
 *
 * Its content is a copy of the editor, not raw text: the StyleRanges are taken
 * from the editor's StyledText, so colours, theme and highlighting are already
 * the right ones without running any syntax analysis again. If the editor has
 * not computed the presentation for those lines yet, it falls back to plain
 * text rather than doing extra work.
 *
 * Two things carry meaning and one is a signature. The arrow, the stripe down
 * the side and the band on the pointed line all take the accent colour, so the
 * panel says what it is about - an error, a warning, added or rewritten code -
 * without a word of text. The rainbow border says nothing: it is there so that
 * this panel is recognisable as this panel, from the corner of the eye, and it
 * is kept to the perimeter so it never competes with the code inside.
 */
final class CodePreview {

    /** Whoever pins the bubble to a line wants to know when the wheel moves it. */
    interface WheelSink {
        void scrolled(int lines);
    }

    private static final int CONTEXT = 6;
    private static final int MAX_LINES = 16;
    /** Lines on show in the overview ruler's bubble. */
    private static final int WINDOW_LINES = 2 * CONTEXT + 1;
    /**
     * Lines kept ready above and below the visible ones. This margin is what
     * makes the scrolling real scrolling: as long as the pointer stays inside
     * the loaded window nothing is recomposed, only the view moves, exactly
     * like scrolling a page.
     */
    private static final int WINDOW_MARGIN = 120;
    /**
     * The bubble is as wide as the code it shows, down to this floor. A wide
     * fixed minimum turned every preview into a panel the width of the screen,
     * whatever was in it.
     */
    private static final int MIN_WIDTH = 520;
    /** Below this width the bubble stops shrinking: narrow beats unreadable. */
    private static final int MIN_ROOM = 320;
    private static final int MAX_WIDTH = 1400;
    private static final int PADDING = 16;
    private static final int RADIUS = 14;
    /**
     * The arrow that points at the spot the panel is about: how far it reaches
     * out, how long its head is, and the half-heights of head and shaft.
     *
     * An arrow rather than a speech-bubble tail: a tail says "someone is
     * talking", an arrow says "that one, over there", which is the only thing
     * this panel has to communicate about its position.
     */
    private static final int ARROW = 24;
    private static final int ARROW_HEAD = 12;
    private static final int ARROW_HALF = 9;
    private static final int SHAFT_HALF = 2;
    /** Thickness of the border stroke. */
    private static final int BORDER = 3;
    /**
     * Longest stretch of border painted in one hue, in pixels, and how many
     * hues the sweep is quantised to. Quantising bounds the colours the shared
     * palette ends up holding, and at this step the banding is invisible.
     */
    private static final int RAINBOW_STEP = 5;
    private static final int RAINBOW_HUES = 72;
    /** Air between the end of a line and the author label, inside the bubble. */
    private static final int AUTHOR_GAP = 18;
    /** Air around the line numbers painted in the margin. */
    private static final int GUTTER_GAP = 10;
    /** Inner padding of the box wrapped around the problem message. */
    private static final int NOTICE_INSET = 10;
    /** Air between the coloured stripe and the code it stands beside. */
    private static final int ACCENT_GAP = 7;
    /** Width of the coloured stripe running down the tail side of the bubble. */
    private static final int ACCENT_WIDTH = 3;
    /** How far the surface is lifted off the editor background, 0 to 1. */
    private static final double SURFACE_LIFT = 0.055;
    /** No accent kind set: the stripe falls back to a neutral tone. */
    static final int NO_ACCENT = -1;

    private final Control anchor;
    private final ITextViewer viewer;

    private IDocument windowDocument;
    private int windowFirst = -1;
    private int windowLast = -1;
    private int gutterFirstLine = -1;
    private LensController labels;
    private int gutterPixels;
    private int windowWidth;

    private String notice;
    private int noticeSeverity;
    private int noticeHeight;
    private int accentKind = NO_ACCENT;
    /** Row of {@link #content} the bubble is pointing at, or -1 when none. */
    private int focusRow = -1;
    private Listener deactivate;

    private Shell shell;
    private WheelSink wheelSink;

    private StyledText content;
    private StyledText source;
    private Region region;
    private String shownText;
    private String shownNotice;
    private RGB shownAccent;
    private boolean pointLeft;
    private int tailY = -1;
    private Point shownSize = new Point(0, 0);

    CodePreview(Control anchor, ITextViewer viewer) {
        this.anchor = anchor;
        this.viewer = viewer;
    }

    /**
     * The StyledText offset matching a document offset.
     *
     * With folding on the two do not coincide: every folded region pulls back
     * everything that follows. Reading styles off the widget with document
     * offsets meant picking up the colouring of another part of the file, which
     * is why the bubble used to come out in colours the page never had.
     */
    private int widgetOffset(int modelOffset) {
        if (viewer instanceof ITextViewerExtension5) {
            return ((ITextViewerExtension5) viewer).modelOffset2WidgetOffset(modelOffset);
        }
        return modelOffset;
    }

    /** Preview of a change block: for deletions it shows what HEAD still has. */
    void showChange(ChangeBlock block, IDocument document, StyledText source, Point tip) {
        pointLeft = true;
        accentKind = block.kind;
        if (block.kind == ChangeBlock.DELETED) {
            show(block.original, -1, source, null, tip);
        } else {
            int first = block.startLine(document);
            show(readLines(document, first, block.endLine(document)), first, source, document, tip);
        }
    }

    /**
     * Preview of the code around a line, for the overview ruler.
     *
     * The bubble is not recomposed for every line: it keeps a slice of the page
     * {@link #WINDOW_MARGIN} lines wide on each side and, as long as the
     * pointer stays inside that slice, it only moves the view with
     * {@code setTopPixel}. Hence the continuous scrolling: it is the same
     * mechanism the editor scrolls with, not a succession of previews.
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
        // The problem message takes one line at the bottom, when there is one.
        noticeHeight = notice == null ? 0 : lineHeight + 16;
        Point size = new Point(windowWidth,
                WINDOW_LINES * lineHeight + 2 * PADDING + 4 + noticeHeight);
        layout(size, tip);
        // The view is placed so that the line being pointed at stays centred.
        int top = (line - CONTEXT - windowFirst) * lineHeight;
        int maxTop = Math.max(0, (windowLast - windowFirst + 1) * lineHeight
                - content.getClientArea().height);
        content.setTopPixel(Math.max(0, Math.min(top, maxTop)));
        focus(line - windowFirst);
        if (!shell.isVisible()) shell.setVisible(true);
        // The frame only needs repainting when what it draws changes: the
        // message at the bottom, or the accent the stripe is tinted with.
        // Otherwise the drawing is already the right one, and a redraw for
        // every line travelled showed up as a stutter.
        RGB accent = accent();
        if ((notice == null ? shownNotice != null : !notice.equals(shownNotice))
                || !accent.equals(shownAccent)) {
            shownNotice = notice;
            shownAccent = accent;
            shell.redraw();
        }
    }

    /**
     * Bands the line the bubble is pointing at.
     *
     * It goes through the widget's line background rather than through the
     * paint listener: a paint listener runs after the text is drawn, so a
     * filled band would cover the very code it is meant to point out.
     */
    private void focus(int row) {
        if (content == null || content.isDisposed()) return;
        if (row == focusRow) return;
        if (focusRow >= 0 && focusRow < content.getLineCount()) {
            content.setLineBackground(focusRow, 1, null);
        }
        focusRow = row >= 0 && row < content.getLineCount() ? row : -1;
        if (focusRow >= 0) {
            content.setLineBackground(focusRow, 1, Palette.of(anchor.getDisplay())
                    .get(mix(accent(), content.getBackground().getRGB(), 0.14)));
        }
    }

    /** The loaded slice already covers the pointed line and its context. */
    private boolean windowHolds(IDocument document, int line, int lastLine) {
        if (windowFirst < 0 || document != windowDocument) return false;
        return line - CONTEXT >= windowFirst - 1 && line + CONTEXT <= windowLast + 1
                && windowLast <= lastLine;
    }

    private void buildWindow(IDocument document, StyledText source, int line, int lastLine) {
        int first = Math.max(0, line - WINDOW_MARGIN);
        int last = Math.min(lastLine, line + WINDOW_MARGIN);
        String text = readLines(document, first, last);

        windowDocument = document;
        windowFirst = first;
        windowLast = last;
        shownText = text;
        setGutter(first, last + 1);
        // setText wipes the line backgrounds along with the text: the banded
        // line has to be forgotten here, or it would never be painted again.
        focusRow = -1;
        content.setText(text);
        applyStyles(document, source, first);
        windowWidth = widthFor(text) + gutterPixels;
    }


    void setWheelSink(WheelSink sink) {
        this.wheelSink = sink;
    }

    /**
     * The colour the bubble takes its identity from: the stripe down its side,
     * the band on the pointed line and the problem box all read from here.
     * A problem outranks a change - a red marker is the more urgent thing to
     * say about a line - and with neither, the accent is a neutral tone.
     */
    void setAccent(int changeKind) {
        this.accentKind = changeKind;
    }

    private RGB accent() {
        Palette palette = Palette.of(anchor.getDisplay());
        if (notice != null) {
            return (noticeSeverity >= 2 ? palette.deleted()
                    : noticeSeverity == 1 ? palette.warning() : palette.info()).getRGB();
        }
        if (accentKind != NO_ACCENT) return palette.forChange(accentKind).getRGB();
        return mix(surfaceForeground(), surfaceBackground(), 0.40);
    }

    private RGB surfaceForeground() {
        return content == null || content.isDisposed()
                ? anchor.getForeground().getRGB() : content.getForeground().getRGB();
    }

    private RGB surfaceBackground() {
        return content == null || content.isDisposed()
                ? anchor.getBackground().getRGB() : content.getBackground().getRGB();
    }

    /** The bubble is on screen. */
    boolean isOpen() {
        return shell != null && !shell.isDisposed() && shell.isVisible();
    }

    /** The pointer is inside the bubble: whoever opened it must not close it. */
    boolean holdsCursor() {
        if (shell == null || shell.isDisposed() || !shell.isVisible()) return false;
        return shell.getBounds().contains(shell.getDisplay().getCursorLocation());
    }

    void hide() {
        if (shell != null && !shell.isDisposed() && shell.isVisible()) {
            shell.setVisible(false);
            shownText = null;
            focusRow = -1;
            // The loaded slice goes stale: the document can change while the
            // bubble is closed, and showing it again would be showing the past.
            // It gets rebuilt on the next opening.
            invalidateWindow();
        }
    }

    private void invalidateWindow() {
        windowDocument = null;
        windowFirst = -1;
        windowLast = -1;
    }

    void dispose() {
        if (deactivate != null && !anchor.isDisposed()) {
            Shell parent = anchor.getShell();
            if (parent != null && !parent.isDisposed()) {
                parent.removeListener(SWT.Deactivate, deactivate);
            }
        }
        deactivate = null;
        if (shell != null && !shell.isDisposed()) shell.dispose();
        shell = null;
        content = null;
        source = null;
        shownText = null;
        focusRow = -1;
        disposeRegion();
    }

    private void show(String body, int firstLine, StyledText source, IDocument document, Point tip) {
        if (anchor.isDisposed() || body == null || body.trim().isEmpty()) {
            hide();
            return;
        }
        String text = compose(body);
        create(source);
        if (!text.equals(shownText)) {
            shownText = text;
            setGutter(firstLine, firstLine + content(text));
            focusRow = -1;
            content.setText(text);
            applyStyles(document, source, firstLine);
            // this path writes into the same widget: the slice of page kept for
            // the overview ruler is no longer worth anything
            invalidateWindow();
        }
        content.setTopPixel(0);

        noticeHeight = 0;
        Point size = new Point(widthFor(text) + gutterPixels,
                content.getLineCount() * content.getLineHeight() + 2 * PADDING + 4);
        layout(size, tip);
        if (!shell.isVisible()) shell.setVisible(true);
        shell.redraw();
    }

    /**
     * Puts the panel in place, rebuilding its shape only when needed.
     *
     * The clipping Region is the expensive part of the round trip: recreating
     * it for every pixel the mouse travels is what made the scrolling stutter.
     * It depends only on the size and on the tail position, and neither changes
     * while scrolling.
     */
    private void layout(Point size, Point tip) {
        Rectangle screen = anchor.getMonitor().getClientArea();
        size.x = Math.min(size.x, Math.max(200, screen.width - 24));
        // The bubble must never cover what opened it. When there is not enough
        // room on that side the window used to be pushed back inside the
        // screen, and in doing so it rode over the scroll bar and hid it: here
        // it shrinks instead, and the bar stays in sight.
        int room = pointLeft ? screen.x + screen.width - tip.x : tip.x - screen.x;
        size.x = Math.max(MIN_ROOM, Math.min(size.x, room - 4));
        Point where = place(size, tip);
        int wantedTail = Math.max(RADIUS + ARROW_HALF,
                Math.min(size.y - RADIUS - ARROW_HALF, tip.y - where.y));

        if (region == null || !size.equals(shownSize) || wantedTail != tailY) {
            tailY = wantedTail;
            shownSize = size;
            shape(size);
            int lane = ACCENT_WIDTH + ACCENT_GAP;
            content.setBounds(pointLeft ? ARROW + PADDING + lane : PADDING, PADDING,
                    Math.max(1, size.x - ARROW - 2 * PADDING - lane),
                    Math.max(1, size.y - 2 * PADDING - noticeHeight));
        }
        shell.setBounds(where.x, where.y, size.x, size.y);
    }

    // ----------------------------------------------------------------- text

    /**
     * The code lines alone, with no numbers in front.
     *
     * The numbers used to live in the text, and every column of the bubble came
     * out shifted by a few characters against the editor: anything reading the
     * widget's text to decorate it drew braces and parentheses detached from
     * the code. Now the bubble's text is exactly the code, and the numbers are
     * painted in the margin.
     */
    private String compose(String body) {
        String[] lines = body.split("\n", -1);
        int count = Math.min(lines.length, MAX_LINES);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) out.append('\n');
            out.append(lines[i]);
        }
        return out.toString();
    }

    /** How many lines {@link #compose} actually keeps. */
    private static int content(String text) {
        return text.split("\n", -1).length;
    }

    /**
     * Prepares the number margin: how wide it is and which line it starts from.
     * The margin is StyledText space, not text, so it shifts no column.
     */
    private void setGutter(int firstLine, int lastNumber) {
        gutterFirstLine = firstLine;
        if (firstLine < 0) {
            gutterPixels = 0;
            content.setMargins(0, 0, 0, 0);
            return;
        }
        int digits = String.valueOf(Math.max(1, lastNumber)).length();
        gutterPixels = digits * Math.max(6, averageCharWidth()) + 2 * GUTTER_GAP;
        content.setMargins(gutterPixels, 0, 0, 0);
    }

    /**
     * The line numbers, painted in the left margin and following the scroll.
     * Only the visible ones are drawn: the cost does not grow with the slice of
     * page held in memory.
     *
     * A hairline closes the margin off from the code, and the pointed line's
     * number carries the accent colour: the bubble then says which line it is
     * about even when the eye is on the numbers rather than on the band.
     */
    private void drawGutter(GC gc) {
        if (gutterPixels <= 0 || gutterFirstLine < 0 || content.isDisposed()) return;
        int lineHeight = Math.max(1, content.getLineHeight());
        int height = content.getClientArea().height;
        int first = Math.max(0, content.getTopPixel() / lineHeight);
        int last = Math.min(content.getLineCount() - 1, first + height / lineHeight + 1);

        Palette palette = Palette.of(anchor.getDisplay());
        RGB background = content.getBackground().getRGB();
        Color faded = palette.get(mix(content.getForeground().getRGB(), background, 0.38));

        gc.setForeground(palette.get(mix(content.getForeground().getRGB(), background, 0.14)));
        int rule = gutterPixels - GUTTER_GAP / 2;
        gc.drawLine(rule, 0, rule, content.getClientArea().height);

        gc.setFont(content.getFont());
        for (int i = first; i <= last; i++) {
            String number = String.valueOf(gutterFirstLine + i + 1);
            int width = gc.textExtent(number).x;
            gc.setForeground(i == focusRow ? palette.get(accent()) : faded);
            gc.drawString(number, gutterPixels - GUTTER_GAP - width, content.getLinePixel(i), true);
        }
        gc.setForeground(faded);
        drawAuthors(gc, first, last);
    }

    /**
     * The very labels that sit after the declarations in the editor, repeated
     * in the bubble on the lines they belong to: whoever is looking at the
     * preview sees whose code that is without going back to the page.
     *
     * They are not clickable here: the bubble is a read-only window that closes
     * as soon as the mouse leaves.
     */
    private void drawAuthors(GC gc, int first, int last) {
        if (labels == null || gutterFirstLine < 0) return;
        Palette palette = Palette.of(anchor.getDisplay());
        for (int i = first; i <= last; i++) {
            int line = gutterFirstLine + i;
            if (labels.methodAt(line) == null) continue;
            AuthorLabel label = labels.label(line);
            if (label == null || label.isPending()) continue;
            String text = label.render(false, labels.isDirty(line));
            if (text.isEmpty()) continue;

            String source = content.getLine(i);
            int x = gutterPixels + gc.textExtent(source).x + AUTHOR_GAP;
            gc.setForeground(authorColor(palette, label, labels.isDirty(line)));
            gc.drawString(text, x, content.getLinePixel(i), true);
        }
        gc.setForeground(palette.get(mix(
                content.getForeground().getRGB(), content.getBackground().getRGB(), 0.38)));
    }

    /** The same colour reading the labels use in the editor. */
    private Color authorColor(Palette palette, AuthorLabel label, boolean dirtyNow) {
        if (label.isNotCommitted()) {
            return palette.get(mix(content.getForeground().getRGB(),
                    content.getBackground().getRGB(), 0.55));
        }
        if (label.additionalAuthors > 0) return palette.added();
        if (label.dirty || dirtyNow) return palette.attention();
        return palette.mixed();
    }

    void setLabels(LensController labels) {
        this.labels = labels;
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

    // --------------------------------------------------------------- colours

    /**
     * Copies the colouring over from the editor, line by line. No analysis is
     * run again: the already computed StyleRanges are simply read.
     */
    private void applyStyles(IDocument document, StyledText source, int firstLine) {
        if (document == null || source == null || source.isDisposed() || firstLine < 0) return;
        ensurePresentation(document, firstLine, firstLine + content.getLineCount());
        try {
            for (int i = 0; i < content.getLineCount(); i++) {
                int line = firstLine + i;
                if (line >= document.getNumberOfLines()) break;
                IRegion region = document.getLineInformation(line);
                if (region.getLength() == 0) continue;
                // Widget offsets, not document ones: with folding on, every
                // folded region knocks them out of step, and reading styles at
                // the wrong offset gave the bubble the colouring of an entirely
                // different part of the file.
                int start = widgetOffset(region.getOffset());
                if (start < 0) continue;
                int length = Math.min(region.getLength(), source.getCharCount() - start);
                if (length <= 0) continue;

                StyleRange[] ranges = source.getStyleRanges(start, length, true);
                if (ranges == null) continue;
                int target = content.getOffsetAtLine(i);
                for (StyleRange range : ranges) {
                    StyleRange copy = (StyleRange) range.clone();
                    copy.start = target + (range.start - start);
                    if (copy.start < 0 || copy.start + copy.length > content.getCharCount()) continue;
                    content.setStyleRange(copy);
                }
            }
        } catch (Exception ignored) {
            // no presentation available for those lines: the plain text stands
        }
    }

    /**
     * Makes the editor compute the colouring of the lines the bubble is about
     * to show.
     *
     * The bubble copies styles from the editor's StyledText, but those only
     * exist where the editor has already painted: lines outside the view have
     * none, and that is where the washed-out code with white words instead of
     * their colours came from. Invalidating the presentation over that stretch
     * runs the presentation reconciler at once, within the same event turn, and
     * the styles are there by the time they are read.
     */
    private void ensurePresentation(IDocument document, int firstLine, int lastLine) {
        if (!(viewer instanceof ITextViewerExtension2) || document == null) return;
        try {
            int last = Math.min(lastLine, document.getNumberOfLines() - 1);
            if (firstLine > last) return;
            IRegion from = document.getLineInformation(firstLine);
            IRegion to = document.getLineInformation(last);
            int offset = from.getOffset();
            int length = to.getOffset() + to.getLength() - offset;
            if (length <= 0) return;
            ((ITextViewerExtension2) viewer).invalidateTextPresentation(offset, length);
        } catch (Exception ignored) {
            // no presentation to refresh: whatever is there gets copied
        }
    }

    /** Blends two colours: {@code weight} is the share of {@code a}. */
    private static RGB mix(RGB a, RGB b, double weight) {
        return new RGB(channel(a.red, b.red, weight), channel(a.green, b.green, weight),
                channel(a.blue, b.blue, weight));
    }

    private static int channel(int a, int b, double weight) {
        int value = (int) Math.round(a * weight + b * (1 - weight));
        return Math.max(0, Math.min(255, value));
    }

    // -------------------------------------------------------------- window

    private void create(StyledText source) {
        this.source = source;
        if (shell != null && !shell.isDisposed()) {
            adoptLook(source);
            return;
        }
        Display display = anchor.getDisplay();
        shell = new Shell(anchor.getShell(), SWT.ON_TOP | SWT.NO_FOCUS | SWT.NO_TRIM);
        content = new StyledText(shell, SWT.MULTI | SWT.READ_ONLY | SWT.NO_FOCUS);
        // The workbench CSS engine restyles freshly created widgets: on our
        // StyledText it put the theme's generic grey back over the editor
        // background, which is why the bubble did not look the same shade as
        // the page. These data keys keep it out.
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
        // Wheel with the pointer over the bubble: it scrolls the bubble, not
        // the page underneath. Whoever is looking at the preview is reading
        // that, and seeing the editor's text run away while reading the bubble
        // was only disorienting. This holds as long as the bubble is pinned to
        // someone who knows how to move it; for change previews, which stay put
        // on their change, the wheel keeps going to the editor.
        Listener wheel = new Listener() {
            @Override
            public void handleEvent(Event event) {
                event.doit = false;
                if (event.count == 0) return;
                if (wheelSink != null) {
                    wheelSink.scrolled(event.count);
                    return;
                }
                StyledText page = CodePreview.this.source;
                if (page == null || page.isDisposed()) return;
                int step = Math.max(1, page.getLineHeight());
                page.setTopPixel(Math.max(0, page.getTopPixel() - event.count * step));
            }
        };
        content.addListener(SWT.MouseWheel, wheel);
        shell.addListener(SWT.MouseWheel, wheel);
        content.addPaintListener(new PaintListener() {
            @Override
            public void paintControl(PaintEvent event) {
                drawGutter(event.gc);
            }
        });
        // The bubble is a window of its own: if Eclipse loses focus it would
        // stay hanging over whatever application was switched to. The listener
        // is kept so it can be taken off the editor's shell on disposal.
        deactivate = new Listener() {
            @Override
            public void handleEvent(Event event) {
                hide();
            }
        };
        anchor.getShell().addListener(SWT.Deactivate, deactivate);
    }

    /**
     * Keeps a widget out of the workbench CSS engine, which would otherwise put
     * the theme's generic colours back over the editor's. The keys differ
     * between Eclipse versions, so both are set: the unknown one is simply
     * ignored.
     */
    private static void skipTheming(org.eclipse.swt.widgets.Widget widget) {
        try {
            widget.setData("org.eclipse.e4.ui.css.disabled", Boolean.TRUE);
            widget.setData("org.eclipse.e4.ui.css.CssClassName", "ChangeLensPreview");
        } catch (Exception ignored) {
            // version without a CSS engine: there is nothing to switch off
        }
    }

    /**
     * The colour the text editor really has configured. It is the authoritative
     * source: the colour read off the widget can be the generic one the theme
     * put there, and in that case the bubble came out a different shade from
     * the page. If the preference says "use the system colour" it falls back to
     * the widget, which by then is right.
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

    /**
     * Font and colours come from the editor: the bubble is a copy of it.
     *
     * The one thing it does not copy is the exact background. The surface is
     * lifted a few percent towards the text colour, so the panel reads as a
     * sheet lying over the page instead of a hole cut into it - the same reason
     * it has a border at all, done with tone rather than with a line.
     */
    private void adoptLook(StyledText source) {
        if (source == null || source.isDisposed()) return;
        content.setFont(source.getFont());
        content.setTabs(source.getTabs());
        // Realigned on every opening, not just at creation: if the theme
        // changes, or the CSS lands later, the bubble catches up.
        Color background = editorColor("AbstractTextEditor.Color.Background", source.getBackground());
        Color foreground = editorColor("AbstractTextEditor.Color.Foreground", source.getForeground());
        if (foreground != null && !foreground.equals(content.getForeground())) {
            content.setForeground(foreground);
        }
        if (background != null && foreground != null) {
            Color surface = Palette.of(anchor.getDisplay())
                    .get(mix(foreground.getRGB(), background.getRGB(), SURFACE_LIFT));
            if (!surface.equals(content.getBackground())) {
                content.setBackground(surface);
                shell.setBackground(surface);
            }
        }
    }

    /**
     * The problem reported on the pointed line, at the foot of the bubble.
     *
     * Eclipse's overview ruler used to show the reason for an error in its own
     * tooltip, whose place the bubble has taken: without putting it back here,
     * the why of that red marker would be lost. The text sits where the code it
     * refers to is, not in a separate little window.
     */
    private void drawNotice(GC gc, Point size) {
        if (notice == null || noticeHeight <= 0) return;
        Palette palette = Palette.of(anchor.getDisplay());
        RGB accent = accent();
        RGB background = content.getBackground().getRGB();

        int lane = ACCENT_WIDTH + ACCENT_GAP;
        int left = (pointLeft ? ARROW + lane : 0) + PADDING;
        int right = size.x - PADDING - (pointLeft ? 0 : ARROW + lane);
        int top = size.y - PADDING - noticeHeight + 2;
        int height = noticeHeight - 4;
        if (right - left < 40 || height < 8) return;

        // A pill, not a boxed-in rectangle: a wash of the severity colour and a
        // faint edge. It has to lift the message off the code above without
        // weighing as much as the code itself.
        int radius = height;
        gc.setBackground(palette.get(mix(accent, background, 0.12)));
        gc.fillRoundRectangle(left, top, right - left, height, radius, radius);
        gc.setForeground(palette.get(mix(accent, background, 0.40)));
        gc.setLineWidth(1);
        gc.drawRoundRectangle(left, top, right - left, height, radius, radius);

        int dot = Math.max(5, content.getLineHeight() / 2);
        int x = left + NOTICE_INSET;
        int textY = top + Math.max(0, (height - content.getLineHeight()) / 2);
        gc.setBackground(palette.get(accent));
        gc.fillOval(x, top + (height - dot) / 2, dot, dot);

        gc.setForeground(palette.get(mix(content.getForeground().getRGB(), background, 0.80)));
        Font previous = gc.getFont();
        gc.setFont(content.getFont());
        int textX = x + dot + 8;
        gc.drawString(clip(gc, notice, right - textX - NOTICE_INSET), textX, textY, true);
        gc.setFont(previous);
    }

    /** The message must never break out of the bubble: if it does not fit, it is cut. */
    private String clip(GC gc, String text, int available) {
        if (available <= 0) return "";
        if (gc.textExtent(text).x <= available) return text;
        // Binary search, not one character at a time: on a long message that
        // loop ran hundreds of text measurements on every repaint.
        int low = 0;
        int high = text.length();
        while (low < high) {
            int middle = (low + high + 1) / 2;
            if (gc.textExtent(text.substring(0, middle) + "...").x <= available) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low <= 0 ? "..." : text.substring(0, low) + "...";
    }

    /**
     * The problem to show at the foot of the bubble, or {@code null} when that
     * line has none. It has to be set before opening it.
     */
    void setNotice(String text, int severity) {
        this.notice = text == null || text.trim().isEmpty() ? null : text.trim();
        this.noticeSeverity = severity;
    }

    private void drawFrame(GC gc) {
        Point size = shell.getSize();
        gc.setAdvanced(true);
        gc.setAntialias(SWT.ON);
        gc.setBackground(content.getBackground());
        gc.fillRectangle(0, 0, size.x, size.y);
        drawNotice(gc, size);
        drawAccent(gc, size);
        drawArrow(gc, size);
        drawRainbow(gc, outline(size));
    }

    /**
     * The stripe running down the side the bubble points from.
     *
     * It is the bubble's signature, and it carries information: its colour is
     * the accent, so the panel says at a glance whether it is showing an error,
     * a warning, or an added, rewritten or deleted stretch of code.
     */
    private void drawAccent(GC gc, Point size) {
        int top = RADIUS;
        int height = size.y - 2 * RADIUS;
        if (height < 4) return;
        // Just inside the frame edge on the tail side, one gap away from the
        // code: the same lane that layout() keeps clear for it.
        int x = pointLeft ? ARROW + PADDING : size.x - ARROW - PADDING - ACCENT_WIDTH;
        gc.setBackground(Palette.of(anchor.getDisplay()).get(accent()));
        gc.fillRoundRectangle(x, top, ACCENT_WIDTH, height, ACCENT_WIDTH, ACCENT_WIDTH);
    }

    /**
     * Clips the Shell to the bubble's outline: without this the window would
     * stay a rectangle and the tail would not show.
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

    /** Rounded rectangle plus the tail on the side facing the pointed spot. */
    private int[] outline(Point size) {
        int left = pointLeft ? ARROW : 0;
        int right = pointLeft ? size.x - 1 : size.x - 1 - ARROW;
        int bottom = size.y - 1;
        List<Integer> points = new ArrayList<Integer>();

        corner(points, left + RADIUS, RADIUS, 180, 270);
        corner(points, right - RADIUS, RADIUS, 270, 360);
        if (!pointLeft) addAll(points, arrow(size));
        corner(points, right - RADIUS, bottom - RADIUS, 0, 90);
        corner(points, left + RADIUS, bottom - RADIUS, 90, 180);
        if (pointLeft) addAll(points, arrow(size));

        int[] result = new int[points.size()];
        for (int i = 0; i < result.length; i++) result[i] = points.get(i).intValue();
        return result;
    }

    /**
     * The arrow, as the run of points that replaces a stretch of the panel edge.
     *
     * Shaft then head then shaft, so that the silhouette leaves the panel thin
     * and arrives at the target wide and pointed: the tip is the exact spot the
     * panel is about. The points come out in the order the outline is walking
     * the edge - downwards on the right side, upwards on the left - so they can
     * be spliced straight into it.
     */
    private int[] arrow(Point size) {
        int edge = pointLeft ? ARROW : size.x - 1 - ARROW;
        int out = pointLeft ? -1 : 1;
        int near = edge + out * (ARROW - ARROW_HEAD);
        int tip = edge + out * ARROW;
        int first = pointLeft ? tailY + ARROW_HALF : tailY - ARROW_HALF;
        int last = pointLeft ? tailY - ARROW_HALF : tailY + ARROW_HALF;
        int firstShaft = pointLeft ? tailY + SHAFT_HALF : tailY - SHAFT_HALF;
        int lastShaft = pointLeft ? tailY - SHAFT_HALF : tailY + SHAFT_HALF;
        return new int[] {
            edge, firstShaft,
            near, firstShaft,
            near, first,
            tip, tailY,
            near, last,
            near, lastShaft,
            edge, lastShaft,
        };
    }

    /**
     * The arrow, filled in the accent colour.
     *
     * Solid, not merely outlined: it is the one element that has to be read at a
     * glance from the corner of the eye, and its colour is the same one the
     * stripe and the banded line carry, so a red arrow and a red stripe are
     * saying the same thing about the same line.
     */
    private void drawArrow(GC gc, Point size) {
        gc.setBackground(Palette.of(anchor.getDisplay()).get(accent()));
        gc.fillPolygon(arrow(size));
    }

    /**
     * The border, painted as a hue sweep all the way round the silhouette.
     *
     * SWT cannot stroke a shape with a gradient, so the outline is walked
     * segment by segment and each short stretch is drawn in its own colour,
     * picked by how far along the perimeter it sits. Long straight edges are
     * chopped into {@link #RAINBOW_STEP} pixel pieces, otherwise a whole side
     * would come out in a single hue and the sweep would read as stripes.
     */
    private void drawRainbow(GC gc, int[] points) {
        int count = points.length / 2;
        if (count < 2) return;
        double[] lengths = new double[count];
        double perimeter = 0;
        for (int i = 0; i < count; i++) {
            int j = (i + 1) % count;
            double dx = points[2 * j] - points[2 * i];
            double dy = points[2 * j + 1] - points[2 * i + 1];
            lengths[i] = Math.sqrt(dx * dx + dy * dy);
            perimeter += lengths[i];
        }
        if (perimeter <= 0) return;

        gc.setLineWidth(BORDER);
        gc.setLineCap(SWT.CAP_ROUND);
        double travelled = 0;
        for (int i = 0; i < count; i++) {
            int j = (i + 1) % count;
            int steps = Math.max(1, (int) Math.ceil(lengths[i] / RAINBOW_STEP));
            for (int step = 0; step < steps; step++) {
                double from = (double) step / steps;
                double to = (double) (step + 1) / steps;
                gc.setForeground(hue((travelled + lengths[i] * (from + to) / 2) / perimeter));
                gc.drawLine(at(points[2 * i], points[2 * j], from), at(points[2 * i + 1], points[2 * j + 1], from),
                        at(points[2 * i], points[2 * j], to), at(points[2 * i + 1], points[2 * j + 1], to));
            }
            travelled += lengths[i];
        }
    }

    private static int at(int from, int to, double share) {
        return (int) Math.round(from + (to - from) * share);
    }

    /**
     * The colour of the border at a given point of its run, from 0 to 1.
     *
     * The pure hue is pulled a little towards the panel, and its strength
     * depends on how dark the editor is: full-strength hues sit well on a dark
     * background but glare on a light one.
     */
    private Color hue(double position) {
        RGB background = content.getBackground().getRGB();
        boolean dark = (background.red * 299 + background.green * 587 + background.blue * 114)
                / 1000 < 128;
        int step = (int) Math.floor(Math.max(0, Math.min(0.999999, position)) * RAINBOW_HUES);
        float degrees = step * 360f / RAINBOW_HUES;
        RGB pure = new RGB(degrees, dark ? 0.62f : 0.78f, dark ? 0.98f : 0.84f);
        return Palette.of(anchor.getDisplay()).get(mix(pure, background, 0.88));
    }

    private static void corner(List<Integer> points, int cx, int cy, int from, int to) {
        for (int angle = from; angle <= to; angle += 10) {
            double radians = Math.toRadians(angle);
            add(points, cx + (int) Math.round(RADIUS * Math.cos(radians)),
                    cy + (int) Math.round(RADIUS * Math.sin(radians)));
        }
    }

    private static void add(List<Integer> points, int x, int y) {
        points.add(Integer.valueOf(x));
        points.add(Integer.valueOf(y));
    }

    private static void addAll(List<Integer> points, int[] coordinates) {
        for (int i = 0; i + 1 < coordinates.length; i += 2) add(points, coordinates[i], coordinates[i + 1]);
    }

    /**
     * The width that fits behind the longest line on show.
     *
     * It follows the content: a three-line preview does not deserve a panel the
     * width of the screen, and a floor keeps a couple of short lines from
     * turning the bubble into a stub.
     */
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
        return Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, longest * charWidth
                + 2 * PADDING + ARROW + ACCENT_WIDTH + ACCENT_GAP + AUTHOR_GAP));
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

    /** Where the Shell goes so that the tail lands on the pointed spot. */
    private Point place(Point size, Point tip) {
        Rectangle screen = anchor.getMonitor().getClientArea();
        int x = pointLeft ? tip.x : tip.x - size.x;
        int y = tip.y - size.y / 2;
        x = Math.max(screen.x, Math.min(x, screen.x + screen.width - size.x));
        y = Math.max(screen.y, Math.min(y, screen.y + screen.height - size.y));
        return new Point(x, y);
    }
}
