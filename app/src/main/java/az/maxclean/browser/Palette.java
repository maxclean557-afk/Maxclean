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
            {"ruby", "Рубин", "#FF4D4F"},
            {"lime", "Лайм", "#A3E635"},
            {"sky", "Небо", "#38BDF8"},
            {"gold", "Золото", "#F5C542"},
            {"coral", "Коралл", "#FF7A59"},
            {"lavender", "Лаванда", "#C4B5FD"},
            {"snow", "Снег", "#E8ECF1"},
    };
    /** The accent value that means "the colour in {@link Prefs#ACCENT_CUSTOM}". */
    public static final String CUSTOM = "custom";

    /** {value, label, hint}. */
    public static final String[][] THEMES = {
            {"system", "Как в системе", "Светлая днём, тёмная ночью — как настроен телефон"},
            {"dark", "Тёмная", "Бережёт глаза и батарею"},
            {"amoled", "Чёрная AMOLED", "Настоящий чёрный: на AMOLED-экране почти не тратит батарею"},
            {"light", "Светлая", "Чистый белый интерфейс"},
    };

    public final boolean dark;
    public final int bg, panel, field, text, muted, accent, onAccent, tor, warn;

    private Palette(boolean dark, int accent) {
        this(dark, false, accent);
    }

    private Palette(boolean dark, boolean amoled, int accent) {
        this.dark = dark;
        this.accent = accent;
        this.onAccent = luminance(accent) > 0.55 ? Color.parseColor("#0E1116") : Color.WHITE;
        this.tor = Color.parseColor("#9B6BFF");
        this.warn = Color.parseColor("#E5484D");
        if (amoled) {
            bg = Color.BLACK;
            panel = Color.parseColor("#0B0D10");
            field = Color.BLACK;
            text = Color.parseColor("#E8ECF1");
            muted = Color.parseColor("#8A94A3");
        } else if (dark) {
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
        boolean amoled = theme.equals("amoled");
        boolean dark = amoled || theme.equals("dark") || (theme.equals("system")
                && (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES);
        String key = Prefs.str(c, Prefs.ACCENT, "mint");
        int accent = Color.parseColor(ACCENTS[0][2]);
        for (String[] a : ACCENTS) if (a[0].equals(key)) accent = Color.parseColor(a[2]);
        if (key.equals(CUSTOM)) accent = parse(Prefs.str(c, Prefs.ACCENT_CUSTOM, ""), accent);
        return new Palette(dark, amoled, accent);
    }

    /** A colour the user typed, "#RRGGBB" or "RRGGBB"; {@code def} if it is not one. */
    public static int parse(String s, int def) {
        s = s == null ? "" : s.trim();
        if (!s.startsWith("#")) s = "#" + s;
        if (!s.matches("#[0-9A-Fa-f]{6}")) return def;
        return Color.parseColor(s);
    }

    public static boolean isDark(int color) {
        return luminance(color) < 0.5;
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

    private static android.graphics.Typeface regular, bold;

    /** Full: sets the Manrope font on every text in a view tree (bold text gets the bold weight). */
    public static void applyFont(Context c, View v) {
        if (!BuildConfig.FULL) return;
        if (regular == null) {
            try {
                regular = new android.graphics.Typeface.Builder(c.getAssets(), "fonts/Manrope.ttf")
                        .setFontVariationSettings("'wght' 500").build();
                bold = new android.graphics.Typeface.Builder(c.getAssets(), "fonts/Manrope.ttf")
                        .setFontVariationSettings("'wght' 750").build();
            } catch (RuntimeException e) {
                return; // keep the system font
            }
            if (regular == null || bold == null) return;
        }
        if (v instanceof android.widget.TextView) {
            android.widget.TextView t = (android.widget.TextView) v;
            android.graphics.Typeface cur = t.getTypeface();
            t.setTypeface(cur != null && cur.isBold() ? bold : regular);
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) applyFont(c, g.getChildAt(i));
        }
    }

    public static String hex(int color) {
        return String.format("#%06X", 0xFFFFFF & color);
    }

    private static double luminance(int c) {
        return (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0;
    }
}
