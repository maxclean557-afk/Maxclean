package az.maxclean.browser;

import android.content.Context;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sites allowed to run JavaScript while it is off everywhere else (like NoScript in Tor Browser).
 * Stored as one domain per line in {@link Prefs#JS_SITES}; a domain also covers its subdomains,
 * so "youtube.com" allows m.youtube.com too.
 */
final class JsSites {
    private JsSites() {}

    /** "https://www.Example.com/x" → "example.com"; null for pages without a web host. */
    static String siteOf(String url) {
        if (url == null) return null;
        Uri u = Uri.parse(url);
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return null;
        String host = u.getHost();
        if (host == null || host.isEmpty()) return null;
        host = host.toLowerCase(Locale.ROOT);
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    static List<String> list(Context c) {
        List<String> out = new ArrayList<>();
        for (String line : Prefs.str(c, Prefs.JS_SITES, "").split("\\s+")) {
            String d = line.trim().toLowerCase(Locale.ROOT);
            if (d.startsWith("www.")) d = d.substring(4);
            if (!d.isEmpty() && !out.contains(d)) out.add(d);
        }
        return out;
    }

    /** The listed domain that covers this host, or null. */
    private static String match(List<String> sites, String host) {
        for (String d : sites) {
            if (host.equals(d) || host.endsWith("." + d)) return d;
        }
        return null;
    }

    static boolean allowed(Context c, String url) {
        String host = siteOf(url);
        return host != null && match(list(c), host) != null;
    }

    /** Whether a page at this address may run JavaScript: on everywhere, or this site is listed. */
    static boolean jsFor(Context c, String url) {
        return Prefs.on(c, Prefs.JAVASCRIPT) || allowed(c, url);
    }

    /** Adds the page's site, or removes the entry that covers it. Returns the new state. */
    static boolean toggle(Context c, String url) {
        String host = siteOf(url);
        if (host == null) return false;
        List<String> sites = list(c);
        String hit = match(sites, host);
        if (hit != null) sites.remove(hit); else sites.add(host);
        Prefs.get(c).edit().putString(Prefs.JS_SITES, String.join("\n", sites)).apply();
        return hit == null;
    }
}
