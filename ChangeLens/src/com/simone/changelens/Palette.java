package com.simone.changelens;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.resource.StringConverter;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;

import com.simone.changelens.preferences.Preferences;

/**
 * Colours shared by every editor. One set per Display, released when the
 * Display dies: no editor allocates or destroys a Color on its own, so there
 * are no orphaned handles and no use of already disposed resources.
 */
final class Palette {

    private static final Map<Display, Palette> INSTANCES = new HashMap<Display, Palette>();

    private final Display display;
    private final Map<RGB, Color> colors = new HashMap<RGB, Color>();

    static synchronized Palette of(Display display) {
        Palette palette = INSTANCES.get(display);
        if (palette == null) {
            palette = new Palette(display);
            INSTANCES.put(display, palette);
            final Display owner = display;
            display.disposeExec(new Runnable() {
                @Override
                public void run() {
                    release(owner);
                }
            });
        }
        return palette;
    }

    private static synchronized void release(Display display) {
        Palette palette = INSTANCES.remove(display);
        if (palette != null) palette.disposeAll();
    }

    private Palette(Display display) {
        this.display = display;
    }

    Color added() {
        return preference(Preferences.ADDED_COLOR, new RGB(87, 171, 90));
    }

    Color modified() {
        return preference(Preferences.MODIFIED_COLOR, new RGB(84, 140, 205));
    }

    /**
     * The orange of the asterisked label. It does not go through the block
     * preferences: those are the colour of rewritten lines in the ruler, this
     * one flags a method with changes not yet committed.
     */
    Color attention() {
        return get(new RGB(223, 143, 53));
    }

    Color deleted() {
        return preference(Preferences.DELETED_COLOR, new RGB(199, 58, 58));
    }

    /** A block that both rewrites existing lines and adds new ones. */
    Color mixed() {
        return preference(Preferences.MIXED_COLOR, new RGB(84, 140, 205));
    }

    Color warning() {
        return get(new RGB(233, 179, 48));
    }

    Color info() {
        return get(new RGB(98, 150, 200));
    }

    Color task() {
        return get(new RGB(140, 130, 190));
    }

    Color forChange(int kind) {
        switch (kind) {
            case ChangeBlock.ADDED: return added();
            case ChangeBlock.MODIFIED: return modified();
            case ChangeBlock.MIXED: return mixed();
            default: return deleted();
        }
    }

    /** A soft colour for the author name, derived from the editor's real colours. */
    Color author(RGB foreground, RGB background) {
        return get(new RGB(
                (foreground.red + background.red * 2) / 3,
                (foreground.green + background.green * 2) / 3,
                (foreground.blue + background.blue * 2) / 3));
    }

    /** The brighter variant, used while the mouse is over the author name. */
    Color authorHover(RGB foreground, RGB background) {
        return get(new RGB(
                (foreground.red * 2 + background.red) / 3,
                (foreground.green * 2 + background.green) / 3,
                (foreground.blue * 2 + background.blue) / 3));
    }

    Color get(RGB rgb) {
        Color color = colors.get(rgb);
        if (color == null || color.isDisposed()) {
            color = new Color(display, rgb);
            colors.put(rgb, color);
        }
        return color;
    }

    /**
     * The colour configured for a kind of change, or the built-in default.
     *
     * The Activator can be gone - the workbench shutting down while an editor
     * still paints - and reading its store without checking threw right in the
     * middle of a repaint. Without preferences the defaults are perfectly good
     * colours.
     */
    private Color preference(String key, RGB fallback) {
        Activator activator = Activator.getDefault();
        RGB rgb = fallback;
        try {
            if (activator != null) {
                IPreferenceStore store = activator.getPreferenceStore();
                String value = store == null ? null : store.getString(key);
                if (value != null && !value.isEmpty()) rgb = StringConverter.asRGB(value);
            }
        } catch (Exception ignored) {
            // invalid value in the preferences: the default stands
        }
        return get(rgb);
    }

    private void disposeAll() {
        for (Color color : colors.values()) {
            if (!color.isDisposed()) color.dispose();
        }
        colors.clear();
    }
}
