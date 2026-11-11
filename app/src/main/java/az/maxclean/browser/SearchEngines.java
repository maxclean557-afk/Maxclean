package az.maxclean.browser;

import android.net.Uri;

/**
 * Search engines for the menu next to the address bar. "%s" is replaced with the query.
 */
public final class SearchEngines {
    public static final String[] NAMES = {
            "Maxclean (анонимный)",
            "DuckDuckGo",
            "Google",
            "Яндекс",
            "Bing",
            "Yahoo",
            "Brave Search",
            "Startpage",
            "Ecosia",
            "Qwant",
            "Mojeek",
            "Mail.ru",
            "Baidu",
            "Свой (из настроек)",
    };

    public static final String[] URLS = {
            null, // Maxclean, see maxcleanUrl()
            "https://duckduckgo.com/?q=%s",
            "https://www.google.com/search?q=%s",
            "https://yandex.ru/search/?text=%s",
            "https://www.bing.com/search?q=%s",
            "https://search.yahoo.com/search?p=%s",
            "https://search.brave.com/search?q=%s",
            "https://www.startpage.com/do/search?q=%s",
            "https://www.ecosia.org/search?q=%s",
            "https://www.qwant.com/?q=%s",
            "https://www.mojeek.com/search?q=%s",
            "https://go.mail.ru/search?q=%s",
            "https://www.baidu.com/s?wd=%s",
            null, // custom
    };

    /**
     * The order shown in the menu: private engines first, the ones that collect the most at the
     * end. Saved choices are indexes into NAMES, so NAMES itself never changes order.
     */
    public static final int[] MENU_ORDER = {0, 1, 6, 7, 10, 9, 8, 2, 4, 5, 3, 11, 12, 13};

    /** Shown after the name in the menu. */
    public static String note(int engine) {
        switch (engine) {
            case 0: return "  — через .onion, без JavaScript";
            case 2: case 3: case 11: case 12: return "  — собирает много данных";
            default: return "";
        }
    }

    public static final int MAXCLEAN = 0;
    public static final int CUSTOM = NAMES.length - 1;

    /** Short label for the button next to the address bar. */
    public static final String[] SHORT = {
            "MC", "DDG", "G", "Я", "B", "Y!", "Br", "SP", "Ec", "Qw", "Mj", "@", "百", "★"
    };

    private SearchEngines() {}

    /**
     * The native Maxclean engine: DuckDuckGo's JavaScript-free HTML version with no tracking
     * parameters. Through Tor it uses DuckDuckGo's official .onion address, so the query never
     * leaves the Tor network.
     */
    private static String maxcleanUrl(boolean tor) {
        return tor
                ? "https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/html/?q=%s"
                : "https://html.duckduckgo.com/html/?q=%s";
    }

    public static String buildUrl(int engine, String query, boolean tor, String custom) {
        String template;
        if (engine == MAXCLEAN || engine < 0 || engine >= URLS.length) {
            template = maxcleanUrl(tor);
        } else if (engine == CUSTOM) {
            template = (custom != null && custom.contains("%s")) ? custom : maxcleanUrl(tor);
        } else {
            template = URLS[engine];
        }
        return template.replace("%s", Uri.encode(query));
    }
}
