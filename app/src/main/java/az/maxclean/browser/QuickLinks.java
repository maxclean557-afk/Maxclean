package az.maxclean.browser;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * Full: the shortcut tiles on the start page. The user edits them in Settings as
 * "Name | address" lines; they are the only thing about sites the browser keeps, and only
 * because the user typed them in.
 */
public final class QuickLinks {
    private QuickLinks() {}

    public static final int MAX = 8;

    public static final String DEFAULT = "DuckDuckGo | https://duckduckgo.com\n"
            + "Википедия | https://ru.wikipedia.org\n"
            + "Проверка Tor | https://check.torproject.org\n"
            + "Maxclean | https://github.com/maxclean557-afk/Maxclean";

    /** {name, https url} pairs; lines that are not "name | http(s) address" are skipped. */
    public static List<String[]> list(Context c) {
        List<String[]> out = new ArrayList<>();
        String raw = Prefs.get(c).getString(Prefs.QUICK_LINKS, DEFAULT);
        for (String line : raw.split("\n")) {
            int bar = line.indexOf('|');
            if (bar <= 0) continue;
            String name = line.substring(0, bar).trim();
            String url = line.substring(bar + 1).trim();
            if (!url.contains("://")) url = "https://" + url;
            if (name.isEmpty() || !(url.startsWith("https://") || url.startsWith("http://"))) continue;
            out.add(new String[]{name, url});
            if (out.size() == MAX) break;
        }
        return out;
    }
}
