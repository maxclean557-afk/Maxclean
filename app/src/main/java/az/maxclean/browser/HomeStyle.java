package az.maxclean.browser;

import android.content.Context;
import android.graphics.Color;

/**
 * Full: how the start page looks. Every choice is the user's: the scene behind it, the two
 * colours that glow, the colour underneath, the clock, the search field, and what is shown.
 */
public final class HomeStyle {
    /** {value, label, hint}. */
    public static final String[][] SCENES = {
            {"orbs", "Огни", "Мягкие пятна света медленно плывут по экрану"},
            {"aurora", "Северное сияние", "Цветные ленты переливаются, как на небе"},
            {"gradient", "Градиент", "Спокойный переход из одного цвета в другой"},
            {"stars", "Звёздное небо", "Мерцающие звёзды двух цветов"},
            {"solid", "Однотонный", "Только цвет основы, ничего лишнего"},
    };

    /** {value, label, colour 1, colour 2}; "accent" takes the accent and the Tor violet. */
    public static final String[][] GLOWS = {
            {"accent", "Как акцент", "", ""},
            {"aurora", "Сияние", "#2BD4A4", "#3D8BFF"},
            {"sunset", "Закат", "#FF7A45", "#FF3D7F"},
            {"cosmos", "Космос", "#9B6BFF", "#3D5AFE"},
            {"neon", "Неон", "#FF2BD6", "#00F0FF"},
            {"forest", "Лес", "#2ECC71", "#0E9F8E"},
            {"fire", "Огонь", "#FF4D4F", "#FFB020"},
            {"ocean", "Океан", "#00B4D8", "#0077FF"},
            {"candy", "Конфета", "#FF8FB1", "#B892FF"},
            {"gold", "Золото", "#F5C542", "#FF8A00"},
            {"ice", "Лёд", "#A5F3FC", "#E0E7FF"},
            {"mono", "Графит", "#8A94A3", "#C9D1DB"},
            {Palette.CUSTOM, "Свои цвета", "", ""},
    };

    /** {value, label, colour}; "theme" follows the light or dark theme. */
    public static final String[][] BASES = {
            {"theme", "По теме", ""},
            {"black", "Чёрный", "#000000"},
            {"night", "Ночь", "#0A1028"},
            {"plum", "Слива", "#1A0B1F"},
            {"pine", "Хвоя", "#071A14"},
            {"wine", "Вино", "#1F0A10"},
            {"snow", "Снег", "#F4F6FA"},
            {"sand", "Песок", "#F6EFE4"},
            {Palette.CUSTOM, "Свой цвет", ""},
    };

    public static final String[][] CLOCKS = {
            {"big", "Крупные", "Жирные цифры на весь экран"},
            {"thin", "Тонкие", "Лёгкие изящные цифры"},
            {"glow", "Неон", "Светятся цветом свечения"},
            {"off", "Без часов", "Только поиск и плитки"},
    };

    public static final String[][] SEARCHES = {
            {"rounded", "Скруглённый", "Стеклянная панель с мягкими углами"},
            {"pill", "Капсула", "Полностью круглые края"},
            {"line", "Линия", "Только тонкая черта снизу"},
    };

    public final int base, text, muted, glow1, glow2;
    public final boolean dark;
    public final String scene, clock, search;
    public final boolean globe, date, tiles;

    private HomeStyle(Context c, Palette pal) {
        scene = pick(c, Prefs.HOME_SCENE, SCENES);
        clock = pick(c, Prefs.HOME_CLOCK, CLOCKS);
        search = pick(c, Prefs.HOME_SEARCH, SEARCHES);
        globe = Prefs.on(c, Prefs.HOME_GLOBE);
        date = Prefs.on(c, Prefs.HOME_DATE);
        tiles = Prefs.on(c, Prefs.HOME_TILES);

        String b = Prefs.str(c, Prefs.HOME_BASE, "theme");
        int baseColor = pal.bg;
        for (String[] o : BASES) if (o[0].equals(b) && !o[2].isEmpty()) baseColor = Color.parseColor(o[2]);
        if (b.equals(Palette.CUSTOM)) baseColor = Palette.parse(Prefs.str(c, Prefs.HOME_BASE_CUSTOM, ""), pal.bg);
        base = baseColor;
        dark = Palette.isDark(base);
        text = Color.parseColor(dark ? "#E8ECF1" : "#14171C");
        muted = Color.parseColor(dark ? "#9AA4B2" : "#5E6673");

        String g = Prefs.str(c, Prefs.HOME_GLOW, "accent");
        int g1 = pal.accent, g2 = pal.tor;
        for (String[] o : GLOWS) {
            if (o[0].equals(g) && !o[2].isEmpty()) {
                g1 = Color.parseColor(o[2]);
                g2 = Color.parseColor(o[3]);
            }
        }
        if (g.equals(Palette.CUSTOM)) {
            g1 = Palette.parse(Prefs.str(c, Prefs.HOME_GLOW1, ""), g1);
            g2 = Palette.parse(Prefs.str(c, Prefs.HOME_GLOW2, ""), g2);
        }
        glow1 = g1;
        glow2 = g2;
    }

    public static HomeStyle of(Context c, Palette pal) {
        return new HomeStyle(c, pal);
    }

    /** Everything that changes the start page, to notice when it needs drawing again. */
    public static String signature(Context c) {
        StringBuilder s = new StringBuilder();
        for (String k : new String[]{Prefs.THEME, Prefs.ACCENT, Prefs.ACCENT_CUSTOM, Prefs.HOME_SCENE,
                Prefs.HOME_GLOW, Prefs.HOME_GLOW1, Prefs.HOME_GLOW2, Prefs.HOME_BASE, Prefs.HOME_BASE_CUSTOM,
                Prefs.HOME_CLOCK, Prefs.HOME_SEARCH, Prefs.QUICK_LINKS}) {
            s.append(Prefs.str(c, k, "")).append('|');
        }
        s.append(Prefs.on(c, Prefs.HOME_GLOBE)).append(Prefs.on(c, Prefs.HOME_DATE)).append(Prefs.on(c, Prefs.HOME_TILES));
        return s.toString();
    }

    /** Classes on &lt;body&gt; that switch the page's CSS. */
    public String bodyClasses() {
        return "scene-" + scene + " clock-" + clock + " search-" + search
                + (globe ? "" : " no-globe") + (date ? "" : " no-date") + (tiles ? "" : " no-tiles")
                + (dark ? " dark" : " light");
    }

    private static String pick(Context c, String key, String[][] options) {
        String v = Prefs.str(c, key, options[0][0]);
        for (String[] o : options) if (o[0].equals(v)) return v;
        return options[0][0];
    }
}
