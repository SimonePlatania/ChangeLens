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
 * Colori condivisi da tutti gli editor. Un solo set per Display, liberato
 * quando il Display muore: nessun editor alloca o distrugge Color per conto
 * proprio, quindi non esistono handle orfani ne uso di risorse gia disposte.
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
        return preference(Preferences.MODIFIED_COLOR, new RGB(223, 143, 53));
    }

    Color deleted() {
        return preference(Preferences.DELETED_COLOR, new RGB(199, 58, 58));
    }

    /** Blocco che modifica righe esistenti e ne aggiunge di nuove. */
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

    /** Colore tenue per il nome autore, calcolato sui colori reali dell'editor. */
    Color author(RGB foreground, RGB background) {
        return get(new RGB(
                (foreground.red + background.red * 2) / 3,
                (foreground.green + background.green * 2) / 3,
                (foreground.blue + background.blue * 2) / 3));
    }

    /** Variante piu accesa, usata quando il mouse e sopra il nome autore. */
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

    private Color preference(String key, RGB fallback) {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        RGB rgb = fallback;
        try {
            String value = store.getString(key);
            if (value != null && !value.isEmpty()) rgb = StringConverter.asRGB(value);
        } catch (Exception ignored) {
            // valore non valido nelle preferenze: si resta sul default
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
