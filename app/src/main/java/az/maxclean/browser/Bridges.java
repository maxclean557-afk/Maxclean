package az.maxclean.browser;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tor bridges and the user's own proxy. Bridges hide the fact that you use Tor, so it also
 * works where Tor is blocked. The built-in ones come from the Tor Project (assets/bridges.txt);
 * the user can paste their own from bridges.torproject.org or the @GetBridgesBot Telegram bot.
 */
public final class Bridges {
    private Bridges() {}

    /** The bridge lines to use for the current settings, or an empty list for none. */
    public static List<String> lines(Context c) {
        String mode = Prefs.str(c, Prefs.BRIDGES, Prefs.BRIDGES_NONE);
        List<String> out = new ArrayList<>();
        if (Prefs.BRIDGES_NONE.equals(mode)) return out;
        if (Prefs.BRIDGES_CUSTOM.equals(mode)) {
            for (String line : Prefs.str(c, Prefs.BRIDGE_LINES, "").split("\n")) {
                line = clean(line);
                if (!line.isEmpty()) out.add(line);
            }
            return out;
        }
        for (String line : builtIn(c)) {
            if (line.startsWith(mode + " ")) out.add(line);
        }
        return out;
    }

    /** Pluggable transports the lines need: "obfs4", "snowflake", "webtunnel", "meek_lite". */
    public static Set<String> transports(List<String> lines) {
        Set<String> t = new LinkedHashSet<>();
        for (String line : lines) {
            String first = line.split("\\s+", 2)[0];
            // A line that starts with an address is a plain (vanilla) bridge: no transport.
            if (!first.matches("[0-9a-fA-F.:\\[\\]]+")) t.add(first);
        }
        return t;
    }

    /**
     * The torrc text for these bridges. {@code ports} maps each transport to the local port the
     * transport listens on. Without bridges, an upstream proxy is set in Tor itself; with bridges
     * the transports get it instead (Tor refuses both at once).
     */
    public static String torrc(List<String> lines, Map<String, Long> ports, String proxyUrl) {
        StringBuilder rc = new StringBuilder();
        if (!lines.isEmpty()) {
            rc.append("UseBridges 1\n");
            for (Map.Entry<String, Long> e : ports.entrySet()) {
                rc.append("ClientTransportPlugin ").append(e.getKey())
                        .append(" socks5 127.0.0.1:").append(e.getValue()).append('\n');
            }
            for (String line : lines) rc.append("Bridge ").append(line).append('\n');
        } else if (!proxyUrl.isEmpty()) {
            Uri u = Uri.parse(proxyUrl);
            String hostPort = u.getHost() + ":" + u.getPort();
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
            rc.append(scheme.startsWith("http") ? "HTTPSProxy " : "Socks5Proxy ").append(hostPort).append('\n');
        }
        return rc.toString();
    }

    /** The user's proxy chain in front of Tor (or the browser), at most {@link ProxyChain#MAX_HOPS}. */
    public static List<ProxyChain.Hop> chainBefore(Context c) {
        List<ProxyChain.Hop> hops = new ArrayList<>();
        for (String line : Prefs.str(c, Prefs.PROXY_URL, "").split("\n")) {
            ProxyChain.Hop h = ProxyChain.parse(line);
            if (h != null && hops.size() < ProxyChain.MAX_HOPS) hops.add(h);
        }
        return hops;
    }

    /** The proxy after Tor, or null. */
    public static ProxyChain.Hop proxyAfter(Context c) {
        return ProxyChain.parse(Prefs.str(c, Prefs.PROXY_AFTER, ""));
    }

    /** True when any proxy is set, so the browser must wait for it before loading anything. */
    public static boolean anyProxy(Context c) {
        return !chainBefore(c).isEmpty() || proxyAfter(c) != null;
    }

    private static String clean(String line) {
        line = line.trim();
        if (line.startsWith("Bridge ")) line = line.substring(7).trim();
        return line.startsWith("#") ? "" : line;
    }

    private static List<String> builtIn(Context c) {
        List<String> out = new ArrayList<>();
        try (InputStream in = c.getAssets().open("bridges.txt");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = clean(line);
                if (!line.isEmpty()) out.add(line);
            }
        } catch (IOException ignored) {
            // No built-in bridges: the user can still paste their own.
        }
        return out;
    }
}
