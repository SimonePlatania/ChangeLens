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
 * Replaces the system vertical scroll bar with a rounded thumb drawn on the
 * very strip that carries the markers, with no track and no arrows: behind it
 * stays the editor background, and the change, error and warning markers live
 * alongside the thumb instead of sitting in two separate columns.
 *
 * The native bar is only hidden: on teardown it goes back exactly as it was.
 */
final class SlimScrollBar {

    private static final int THUMB_WIDTH = 9;
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
        // added last: the thumb ends up above the native markers
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

        // Without this the thumb only showed up on the first scroll: it was
        // the viewport event that had it drawn. When the editor opens, the
        // line count and the usable height are not final yet, so it paints now
        // and again once the layout has settled.
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
        RGB ink = new RGB(
                (foreground.red + background.red * 2) / 3,
                (foreground.green + background.green * 2) / 3,
                (foreground.blue + background.blue * 2) / 3);
        int opacity = hot || dragging ? 130 : 70;

        if (gc.getAdvanced()) {
            // Semi-transparent: the error and warning ticks stay readable
            // under the thumb instead of being covered by it.
            gc.setBackground(palette.get(ink));
            gc.setAlpha(opacity);
            gc.fillRoundRectangle(thumb.x, thumb.y, thumb.width, thumb.height,
                    THUMB_WIDTH, THUMB_WIDTH);
            gc.setAlpha(255);
        } else {
            // Where advanced graphics are missing (older Eclipse, drivers
            // without alpha) the transparency has to be faked: the colour is
            // blended with the background by hand in the same proportion.
            // Drawing only the outline, as it used to, came out flat and hard.
            gc.setBackground(palette.get(new RGB(
                    (ink.red * opacity + background.red * (255 - opacity)) / 255,
                    (ink.green * opacity + background.green * (255 - opacity)) / 255,
                    (ink.blue * opacity + background.blue * (255 - opacity)) / 255)));
            gc.fillRoundRectangle(thumb.x, thumb.y, thumb.width, thumb.height,
                    THUMB_WIDTH, THUMB_WIDTH);
        }
    }

    /** Repaints the thumb. Whoever opens windows over the strip puts it back on screen. */
    void repaint() {
        refresh();
    }

    /**
     * Scrolls by a few lines, the way the wheel would on the native bar. The
     * overview strip needs it: there the wheel has to move the page, not the
     * bubble.
     */
    void scrollByLines(int lines) {
        if (disposed || widget.isDisposed() || lines == 0) return;
        int step = Math.max(1, widget.getLineHeight());
        int max = Math.max(0, totalPixels() - widget.getClientArea().height);
        int top = Math.max(0, Math.min(max, widget.getTopPixel() - lines * step));
        widget.setTopPixel(top);
        // As with dragging: setTopPixel does not go through the system bar, so
        // there is no viewport event and the columns have to be told.
        if (onScroll != null) onScroll.run();
        refresh();
    }

    /** The point is on the thumb: the bubble must not appear there. */
    boolean isOnThumb(int y) {
        Rectangle thumb = thumb();
        return thumb != null && y >= thumb.y - 2 && y <= thumb.y + thumb.height + 2;
    }

    boolean isDragging() {
        return dragging;
    }

    /** Thumb geometry, in proportion to the visible share of the file. */
    private Rectangle thumb() {
        if (strip == null || strip.isDisposed() || widget.isDisposed()) return null;
        Rectangle area = strip.getClientArea();
        int total = totalPixels();
        int visible = widget.getClientArea().height;
        if (total <= 0 || visible <= 0 || visible >= total) return null;

        int track = Math.max(1, area.height - 2 * MARGIN);
        // Length in proportion to the visible share of the file, with no cap:
        // short file, long thumb; long file, short thumb. A fixed maximum
        // would have made a 200-line file and a 5000-line one look alike.
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
        // setTopPixel moves the text without going through the system bar, so
        // the viewer fires no viewport event: the columns have to be told by
        // hand, or the bars fall behind.
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
            // viewer already torn down
        }
        if (strip != null && !strip.isDisposed()) strip.removePaintListener(painter);
        if (!widget.isDisposed()) {
            ScrollBar bar = widget.getVerticalBar();
            if (bar != null && !bar.isDisposed()) bar.setVisible(nativeBarWasVisible);
        }
    }
}
