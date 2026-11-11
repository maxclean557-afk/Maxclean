package az.maxclean.browser;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * New versions of Maxclean, from the project's GitHub releases, through Tor. A downloaded APK
 * is installed only if it carries the same signing certificate as this app, and only after the
 * user confirms in Android's own installer.
 */
public final class UpdateChecker {
    private static final String LATEST = "https://api.github.com/repos/maxclean557-afk/Maxclean/releases/latest";
    private static final long DAY = 24L * 3600 * 1000;

    public static final class Release {
        public final String version;
        public final String apkUrl;
        public final String pageUrl;

        Release(String version, String apkUrl, String pageUrl) {
            this.version = version;
            this.apkUrl = apkUrl;
            this.pageUrl = pageUrl;
        }
    }

    public interface Callback<T> {
        void done(T result, String error);
    }

    public interface Progress {
        void at(int percent);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private UpdateChecker() {}

    /** True when the once-a-day check is due. */
    public static boolean due(Context c) {
        return Prefs.on(c, Prefs.UPDATE_CHECK)
                && System.currentTimeMillis() - Prefs.get(c).getLong(Prefs.UPDATE_LAST, 0) > DAY;
    }

    /**
     * Asks GitHub for the latest release. {@code socksPort} is Tor's port, or 0 to go direct
     * (only when Tor is off). Calls back on the main thread with the release if it is newer
     * than this app, or null.
     */
    public static void check(Context c, int socksPort, Callback<Release> cb) {
        Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject(new String(get(LATEST, socksPort), "UTF-8"));
                Prefs.get(app).edit().putLong(Prefs.UPDATE_LAST, System.currentTimeMillis()).apply();
                String tag = json.optString("tag_name", "").replaceFirst("^v", "");
                String want = BuildConfig.FULL ? "Maxclean-Full.apk" : "Maxclean.apk";
                String apk = null;
                JSONArray assets = json.optJSONArray("assets");
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (want.equals(a.optString("name"))) apk = a.optString("browser_download_url");
                }
                Release r = apk != null && newer(tag, BuildConfig.VERSION_NAME)
                        ? new Release(tag, apk, json.optString("html_url")) : null;
                MAIN.post(() -> cb.done(r, null));
            } catch (Exception e) {
                MAIN.post(() -> cb.done(null, e.getMessage()));
            }
        }).start();
    }

    /** Downloads the release's APK through Tor and checks it is really ours. */
    public static void download(Context c, Release r, int socksPort, Progress progress, Callback<File> cb) {
        Context app = c.getApplicationContext();
        new Thread(() -> {
            File dir = new File(app.getCacheDir(), "update");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File apk = new File(dir, "Maxclean.apk");
            try {
                Response res = request(r.apkUrl, socksPort);
                long total = res.length;
                try (InputStream in = res.body; FileOutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[64 * 1024];
                    long got = 0;
                    int last = -1;
                    for (int n; (n = in.read(buf)) > 0; ) {
                        out.write(buf, 0, n);
                        got += n;
                        int p = total > 0 ? (int) (got * 100 / total) : -1;
                        if (p != last) {
                            last = p;
                            MAIN.post(() -> progress.at(p));
                        }
                    }
                }
                String problem = verify(app, apk);
                if (problem != null) {
                    //noinspection ResultOfMethodCallIgnored
                    apk.delete();
                    MAIN.post(() -> cb.done(null, problem));
                    return;
                }
                MAIN.post(() -> cb.done(apk, null));
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
                MAIN.post(() -> cb.done(null, e.getMessage()));
            }
        }).start();
    }

    /** Opens Android's installer for a verified APK. */
    public static void install(Context c, File apk) {
        Uri uri = androidx.core.content.FileProvider.getUriForFile(c, c.getPackageName() + ".updates", apk);
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    /** Null if the APK is a newer Maxclean signed with this app's key; otherwise why not. */
    static String verify(Context c, File apk) throws Exception {
        PackageManager pm = c.getPackageManager();
        int flags = android.os.Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo theirs = pm.getPackageArchiveInfo(apk.getPath(), flags);
        PackageInfo ours = pm.getPackageInfo(c.getPackageName(), flags);
        if (theirs == null) return "файл повреждён";
        if (!c.getPackageName().equals(theirs.packageName)) return "это не Maxclean";
        if (theirs.getLongVersionCode() <= ours.getLongVersionCode()) return "эта версия не новее установленной";
        if (!Arrays.equals(certs(theirs), certs(ours))) {
            return "подпись не совпадает с официальной — файл подменён, установка отменена";
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static byte[][] certs(PackageInfo p) throws Exception {
        Signature[] s;
        if (android.os.Build.VERSION.SDK_INT >= 28 && p.signingInfo != null) {
            s = p.signingInfo.hasMultipleSigners()
                    ? p.signingInfo.getApkContentsSigners() : p.signingInfo.getSigningCertificateHistory();
        } else {
            s = p.signatures;
        }
        if (s == null) return new byte[0][];
        byte[][] out = new byte[s.length][];
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        for (int i = 0; i < s.length; i++) out[i] = sha.digest(s[i].toByteArray());
        return out;
    }

    /** "1.3.10" is newer than "1.3.9"; the " Full" suffix is ignored. */
    static boolean newer(String remote, String local) {
        String[] a = remote.split("[^0-9]+"), b = local.split(" ")[0].split("[^0-9]+");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length && !a[i].isEmpty() ? Integer.parseInt(a[i]) : 0;
            int y = i < b.length && !b[i].isEmpty() ? Integer.parseInt(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static byte[] get(String url, int socksPort) throws Exception {
        Response r = request(url, socksPort);
        try (InputStream in = r.body) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static final class Response {
        InputStream body;
        long length = -1;
    }

    /**
     * A small HTTPS GET through Tor's SOCKS port. The host name is handed to Tor unresolved, so
     * not even DNS goes outside Tor (Android's HTTP client would look it up itself first).
     */
    private static Response request(String url, int socksPort) throws Exception {
        if (socksPort <= 0) throw new java.io.IOException("Tor не подключён");
        for (int hop = 0; hop < 6; hop++) {
            URL u = new URL(url);
            if (!"https".equals(u.getProtocol())) throw new java.io.IOException("не HTTPS: " + url);
            String host = u.getHost();
            int port = u.getPort() > 0 ? u.getPort() : 443;
            java.net.Socket raw = new java.net.Socket(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", socksPort)));
            raw.connect(InetSocketAddress.createUnresolved(host, port), 60_000);
            raw.setSoTimeout(60_000);
            javax.net.ssl.SSLSocket s = (javax.net.ssl.SSLSocket)
                    ((javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault()).createSocket(raw, host, port, true);
            javax.net.ssl.SSLParameters params = s.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            s.setSSLParameters(params);
            s.startHandshake();
            String path = u.getFile().isEmpty() ? "/" : u.getFile();
            s.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nUser-Agent: Maxclean\r\n"
                    + "Accept: application/vnd.github+json, */*\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
            InputStream in = new java.io.BufferedInputStream(s.getInputStream());
            String status = line(in);
            String[] st = status.split(" ");
            int code = st.length > 1 ? Integer.parseInt(st[1]) : 0;
            String location = null, encoding = "";
            long length = -1;
            for (String h; !(h = line(in)).isEmpty(); ) {
                int colon = h.indexOf(':');
                if (colon < 0) continue;
                String k = h.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT), v = h.substring(colon + 1).trim();
                if (k.equals("location")) location = v;
                else if (k.equals("transfer-encoding")) encoding = v.toLowerCase(java.util.Locale.ROOT);
                else if (k.equals("content-length")) length = Long.parseLong(v);
            }
            if (code >= 300 && code < 400 && location != null) {
                s.close();
                url = new URL(u, location).toString();
                continue;
            }
            if (code != 200) {
                s.close();
                throw new java.io.IOException("ответ сервера " + code);
            }
            Response r = new Response();
            r.body = encoding.contains("chunked") ? new Chunked(in) : in;
            r.length = encoding.contains("chunked") ? -1 : length;
            return r;
        }
        throw new java.io.IOException("слишком много перенаправлений");
    }

    private static String line(InputStream in) throws java.io.IOException {
        StringBuilder b = new StringBuilder();
        for (int c; (c = in.read()) != -1 && c != '\n'; ) if (c != '\r') b.append((char) c);
        return b.toString();
    }

    /** Reads an HTTP "Transfer-Encoding: chunked" body. */
    private static final class Chunked extends InputStream {
        private final InputStream in;
        private long left;
        private boolean done;

        Chunked(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws java.io.IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws java.io.IOException {
            if (done) return -1;
            if (left == 0) {
                String size = line(in);
                if (size.isEmpty()) size = line(in); // the CRLF after the previous chunk
                left = Long.parseLong(size.split(";")[0].trim(), 16);
                if (left == 0) {
                    done = true;
                    return -1;
                }
            }
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }

        @Override
        public void close() throws java.io.IOException {
            in.close();
        }
    }
}
