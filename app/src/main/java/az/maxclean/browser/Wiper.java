package az.maxclean.browser;

import android.content.Context;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewDatabase;

import java.io.File;

/**
 * Removes every trace of browsing: cookies, cache, local storage, IndexedDB, form data, HTTP auth,
 * service workers and the WebView's on-disk profile. Called when a tab closes, when the app goes
 * away and on every start (in case the app was killed before it could clean up).
 */
public final class Wiper {
    private Wiper() {}

    /** Must run before the first WebView is created: deletes the WebView profile directory. */
    public static void wipeDiskBeforeWebView(Context c) {
        File dataDir = c.getDataDir();
        deleteRecursive(new File(dataDir, "app_webview"));
        deleteRecursive(new File(dataDir, "app_textures"));
        wipeCacheKeepingTor(c);
        deleteRecursive(c.getCodeCacheDir());
    }

    public static void wipeWebView(Context c, WebView view) {
        if (view != null) {
            view.stopLoading();
            view.clearHistory();
            view.clearCache(true);
            view.clearFormData();
            view.clearMatches();
            view.clearSslPreferences();
        }
        wipeShared(c);
    }

    /** Clears data shared between all tabs. */
    public static void wipeShared(Context c) {
        CookieManager cm = CookieManager.getInstance();
        cm.removeAllCookies(null);
        cm.removeSessionCookies(null);
        cm.flush();
        WebStorage.getInstance().deleteAllData();
        WebViewDatabase db = WebViewDatabase.getInstance(c);
        db.clearHttpAuthUsernamePassword();
        db.clearFormData();
        wipeCacheKeepingTor(c);
    }


    /**
     * Empties the cache directory but keeps Tor's cache: it only holds the public list of Tor
     * relays (nothing about you), and keeping it makes Tor connect much faster.
     */
    private static void wipeCacheKeepingTor(Context c) {
        File[] children = c.getCacheDir().listFiles();
        if (children == null) return;
        for (File child : children) {
            if (!child.getName().equals("TorService")) deleteRecursive(child);
        }
    }

    static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursive(child);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
