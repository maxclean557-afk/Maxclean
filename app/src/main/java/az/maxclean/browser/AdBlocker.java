package az.maxclean.browser;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceResponse;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Blocks requests to ad, tracker, social and miner domains. Each category can be switched off in
 * the settings. A rule like "example.com" also blocks every subdomain; "example.com/path" blocks
 * only URLs on that host starting with that path.
 */
public final class AdBlocker {
    private static final String[][] CATEGORIES = {
            {Prefs.BLOCK_ADS, "filters/ads.txt"},
            {Prefs.BLOCK_TRACKERS, "filters/trackers.txt"},
            {Prefs.BLOCK_SOCIAL, "filters/social.txt"},
            {Prefs.BLOCK_MINERS, "filters/miners.txt"},
    };

    private final Set<String>[] hosts;
    private final List<String[]>[] pathRules;
    private final boolean[] enabled = new boolean[CATEGORIES.length];
    private volatile Set<String> userHosts = new HashSet<>();
    private int blockedCount;

    @SuppressWarnings("unchecked")
    public AdBlocker(Context c) {
        hosts = new Set[CATEGORIES.length];
        pathRules = new List[CATEGORIES.length];
        for (int i = 0; i < CATEGORIES.length; i++) {
            hosts[i] = new HashSet<>();
            pathRules[i] = new ArrayList<>();
            load(c, CATEGORIES[i][1], hosts[i], pathRules[i]);
        }
        refresh(c);
    }

    private static void load(Context c, String asset, Set<String> hostSet, List<String[]> paths) {
        try (InputStream in = c.getAssets().open(asset);
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim().toLowerCase();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int slash = line.indexOf('/');
                if (slash > 0) {
                    paths.add(new String[]{line.substring(0, slash), line.substring(slash)});
                } else {
                    hostSet.add(line);
                }
            }
        } catch (IOException ignored) {
            // A missing list just means nothing is blocked for that category.
        }
    }

    /** Re-reads which categories are switched on and the user's own block list. */
    public void refresh(Context c) {
        for (int i = 0; i < CATEGORIES.length; i++) {
            enabled[i] = Prefs.on(c, CATEGORIES[i][0]);
        }
        Set<String> user = new HashSet<>();
        for (String line : Prefs.get(c).getString(Prefs.USER_BLOCKLIST, "").split("\n")) {
            line = line.trim().toLowerCase();
            if (!line.isEmpty() && !line.startsWith("#")) user.add(line);
        }
        userHosts = user;
    }

    public boolean shouldBlock(Uri uri) {
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase();
        if (matchesHost(userHosts, host)) return true;
        String path = uri.getPath() == null ? "/" : uri.getPath().toLowerCase();
        for (int i = 0; i < CATEGORIES.length; i++) {
            if (!enabled[i]) continue;
            if (matchesHost(hosts[i], host)) return true;
            for (String[] rule : pathRules[i]) {
                if ((host.equals(rule[0]) || host.endsWith("." + rule[0])) && path.startsWith(rule[1])) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matchesHost(Set<String> set, String host) {
        String h = host;
        while (true) {
            if (set.contains(h)) return true;
            int dot = h.indexOf('.');
            if (dot < 0 || dot == h.length() - 1) return false;
            h = h.substring(dot + 1);
        }
    }

    public synchronized void countBlocked() {
        blockedCount++;
    }

    public synchronized int takeBlockedCount() {
        int n = blockedCount;
        blockedCount = 0;
        return n;
    }

    public static WebResourceResponse emptyResponse() {
        return new WebResourceResponse("text/plain", "utf-8",
                new ByteArrayInputStream(new byte[0]));
    }
}
