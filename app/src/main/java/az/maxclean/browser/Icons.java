package az.maxclean.browser;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/**
 * Full: alternative launcher icons. Each icon is an activity-alias in the Full manifest; exactly
 * one is enabled at a time. The launcher may take a few seconds to show the new icon.
 */
public final class Icons {
    private Icons() {}

    /** {value, label, hint, alias class suffix}. */
    public static final String[][] ICONS = {
            {"classic", "Классика", "Мятный щит на тёмном", ""},
            {"violet", "Фиалка", "Фиолетовый щит, как у Tor", "Violet"},
            {"light", "Светлый", "Мятный щит на белом", "Light"},
            {"mono", "Моно", "Белый щит на чёрном, ничего лишнего", "Mono"},
            {"sunset", "Закат", "Оранжевый щит на сливовом", "Sunset"},
    };

    public static void apply(Context c, String value) {
        if (!BuildConfig.FULL) return;
        PackageManager pm = c.getPackageManager();
        boolean found = false;
        for (String[] icon : ICONS) found |= icon[0].equals(value);
        for (String[] icon : ICONS) {
            boolean on = found ? icon[0].equals(value) : icon[3].isEmpty();
            ComponentName alias = new ComponentName(c, "az.maxclean.browser.Launcher" + icon[3]);
            pm.setComponentEnabledSetting(alias, on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        }
    }
}
