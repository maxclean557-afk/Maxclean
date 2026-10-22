package az.maxclean.browser;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.Window;

/**
 * The colours of the interface. Lite always uses the original dark look; Full lets the user pick
 * a light, dark or system theme and an accent colour.
 */
public final class Palette {
    /** {value, label, accent colour} — the accent choices in Full. */
    public static final String[][] ACCENTS = {
            {"mint", "Мята", "#2BD4A4"},
            {"violet", "Фиалка", "#9B6BFF"},
            {"ocean", "Океан", "#3D8BFF"},
            {"amber", "Янтарь", "#FF9F1C"},
            {"rose", "Роза", "#FF5C8A"},
    };

    /** {value, label, hint}. */
    public static final String[][] THEMES = {
            {"system", "Как в системе", "Светлая днём, тёмная ночью — как настроен телефон"},
            {"dark", "Тёмная", "Бережёт глаза и батарею"},
            {"light", "Светлая", "Чистый белый интерфейс"},
    };

    public final boolean dark;
    public final int bg, panel, field, text, muted, accent, onAccent, tor, warn;

    private Palette(boolean dark, int accent) {
        this.dark = dark;
        this.accent = accent;
        this.onAccent = luminance(accent) > 0.55 ? Color.parseColor("#0E1116") : Color.WHITE;
        this.tor = Color.parseColor("#9B6BFF");
        this.warn = Color.parseColor("#E5484D");
        if (dark) {
            bg = Color.parseColor("#0E1116");
            panel = Color.parseColor("#171B22");
            field = Color.parseColor("#0E1116");
            text = Color.parseColor("#E8ECF1");
            muted = Color.parseColor("#8A94A3");
        } else {
            bg = Color.parseColor("#F6F7F9");
            panel = Color.parseColor("#FFFFFF");
            field = Color.parseColor("#EDEFF3");
            text = Color.parseColor("#14171C");
            muted = Color.parseColor("#5E6673");
        }
    }

    public static Palette of(Context c) {
        if (!BuildConfig.FULL) return new Palette(true, Color.parseColor("#2BD4A4"));
        String theme = Prefs.str(c, Prefs.THEME, "system");
        boolean dark = theme.equals("dark") || (theme.equals("system")
                && (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES);
        String key = Prefs.str(c, Prefs.ACCENT, "mint");
        int accent = Color.parseColor(ACCENTS[0][2]);
        for (String[] a : ACCENTS) if (a[0].equals(key)) accent = Color.parseColor(a[2]);
        return new Palette(dark, accent);
    }

    public static boolean animations(Context c) {
        return BuildConfig.FULL && Prefs.on(c, Prefs.ANIMATIONS);
    }

    /** Sets the platform theme (dialogs, switches) and the system bars to match. Call before setContentView. */
    public void applyTo(Activity a) {
        a.setTheme(dark ? android.R.style.Theme_Material_NoActionBar
                : android.R.style.Theme_Material_Light_NoActionBar);
        Window w = a.getWindow();
        w.setStatusBarColor(panel);
        w.setNavigationBarColor(bg);
        int flags = 0;
        if (!dark) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 27) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        w.getDecorView().setSystemUiVisibility(flags);
    }

    public static String hex(int color) {
        return String.format("#%06X", 0xFFFFFF & color);
    }

    private static double luminance(int c) {
        return (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0;
    }
}
