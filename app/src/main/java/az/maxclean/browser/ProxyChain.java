package az.maxclean.browser;

import android.net.Uri;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A small SOCKS5 server on 127.0.0.1 that sends every connection through a chain of proxies,
 * one after another: hop 1 → hop 2 → … → the site. Each hop is a SOCKS5 or HTTP proxy, with or
 * without a login. Host names are passed on as names, never resolved here, so DNS also travels
 * through the chain (and through Tor, when Tor is one of the hops).
 *
 * Tor itself can use only one upstream proxy; pointing it at this server gives it a whole chain.
 */
public final class ProxyChain {
    public static final int MAX_HOPS = 5;
    private static final int TIMEOUT_MS = 30_000;

    /** One proxy in the chain. */
    public static final class Hop {
        final boolean http;
        final String host;
        final int port;
        final String user;
        final String pass;

        Hop(boolean http, String host, int port, String user, String pass) {
            this.http = http;
            this.host = host;
            this.port = port;
            this.user = user;
            this.pass = pass;
        }

        @Override
        public String toString() {
            return (http ? "http://" : "socks5://") + host + ":" + port;
        }
    }

    /**
     * Parses "socks5://[user:pass@]host:port", "http://…" or a bare "host:port" (SOCKS5).
     * Returns null when the address is not usable.
     */
    public static Hop parse(String raw) {
        raw = raw == null ? "" : raw.trim();
        if (raw.isEmpty() || raw.startsWith("#")) return null;
        if (!raw.contains("://")) raw = "socks5://" + raw;
        Uri u = Uri.parse(raw);
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
        boolean http = scheme.equals("http") || scheme.equals("https");
        boolean socks = scheme.equals("socks5") || scheme.equals("socks") || scheme.equals("socks5h");
        if (!http && !socks) return null;
        if (u.getHost() == null || u.getPort() <= 0 || u.getPort() > 65535) return null;
        String user = null, pass = null;
        String info = u.getUserInfo();
        if (info != null && !info.isEmpty()) {
            int colon = info.indexOf(':');
            user = colon < 0 ? info : info.substring(0, colon);
            pass = colon < 0 ? "" : info.substring(colon + 1);
        }
        return new Hop(http, u.getHost(), u.getPort(), user, pass);
    }

    private final List<Hop> hops;
    private final Set<Socket> open = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private ServerSocket server;
    private volatile boolean running;

    public ProxyChain(List<Hop> hops) {
        this.hops = new ArrayList<>(hops);
    }

    /** Starts listening on a free local port and returns it. */
    public int start() throws IOException {
        server = new ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"));
        running = true;
        Thread t = new Thread(this::acceptLoop, "proxy-chain");
        t.setDaemon(true);
        t.start();
        return server.getLocalPort();
    }

    public void stop() {
        running = false;
        closeQuietly(server);
        for (Socket s : open) closeQuietly(s);
        open.clear();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = server.accept();
                open.add(client);
                Thread t = new Thread(() -> serve(client), "proxy-chain-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    private void serve(Socket client) {
        Socket upstream = null;
        try {
            client.setSoTimeout(TIMEOUT_MS);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            // SOCKS5 greeting: we only offer "no login" to the local browser or Tor.
            if (in.read() != 5) throw new IOException("not SOCKS5");
            int methods = in.read();
            readFully(in, new byte[Math.max(0, methods)]);
            out.write(new byte[]{5, 0});

            // Request: CONNECT only.
            byte[] head = new byte[4];
            readFully(in, head);
            if (head[1] != 1) {
                out.write(new byte[]{5, 7, 0, 1, 0, 0, 0, 0, 0, 0});
                return;
            }
            String host = readAddress(in, head[3]);
            int port = ((in.read() & 0xff) << 8) | (in.read() & 0xff);

            try {
                upstream = connectThrough(host, port);
            } catch (IOException e) {
                out.write(new byte[]{5, 5, 0, 1, 0, 0, 0, 0, 0, 0}); // connection refused
                return;
            }
            out.write(new byte[]{5, 0, 0, 1, 0, 0, 0, 0, 0, 0});
            out.flush();

            client.setSoTimeout(0);
            upstream.setSoTimeout(0);
            Socket up = upstream;
            Thread back = new Thread(() -> pipe(up, client), "proxy-chain-back");
            back.setDaemon(true);
            back.start();
            pipe(client, upstream);
        } catch (IOException ignored) {
            // The browser or a hop went away; both sockets are closed below.
        } finally {
            if (upstream == null) {
                closeQuietly(client);
                open.remove(client);
            }
        }
    }

    /** Opens a connection to host:port through every hop in turn. */
    Socket connectThrough(String host, int port) throws IOException {
        Hop first = hops.get(0);
        Socket s = new Socket();
        open.add(s);
        try {
            s.connect(new InetSocketAddress(first.host, first.port), TIMEOUT_MS);
            s.setSoTimeout(TIMEOUT_MS);
            for (int i = 0; i < hops.size(); i++) {
                Hop via = hops.get(i);
                boolean last = i == hops.size() - 1;
                String nextHost = last ? host : hops.get(i + 1).host;
                int nextPort = last ? port : hops.get(i + 1).port;
                if (via.http) {
                    httpConnect(s, via, nextHost, nextPort);
                } else {
                    socksConnect(s, via, nextHost, nextPort);
                }
            }
            return s;
        } catch (IOException e) {
            closeQuietly(s);
            open.remove(s);
            throw e;
        }
    }

    private static void socksConnect(Socket s, Hop via, String host, int port) throws IOException {
        InputStream in = s.getInputStream();
        OutputStream out = s.getOutputStream();
        boolean login = via.user != null;
        out.write(login ? new byte[]{5, 2, 0, 2} : new byte[]{5, 1, 0});
        out.flush();
        byte[] choice = new byte[2];
        readFully(in, choice);
        if (choice[0] != 5) throw new IOException(via + ": not a SOCKS5 proxy");
        if (choice[1] == 2) {
            if (!login) throw new IOException(via + ": asks for a login");
            byte[] u = via.user.getBytes(StandardCharsets.UTF_8);
            byte[] p = via.pass.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream auth = new ByteArrayOutputStream();
            auth.write(1);
            auth.write(u.length);
            auth.write(u, 0, u.length);
            auth.write(p.length);
            auth.write(p, 0, p.length);
            out.write(auth.toByteArray());
            out.flush();
            byte[] ok = new byte[2];
            readFully(in, ok);
            if (ok[1] != 0) throw new IOException(via + ": wrong login");
        } else if (choice[1] != 0) {
            throw new IOException(via + ": no usable login method");
        }
        byte[] name = host.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream req = new ByteArrayOutputStream();
        req.write(new byte[]{5, 1, 0, 3}, 0, 4);
        req.write(name.length);
        req.write(name, 0, name.length);
        req.write((port >> 8) & 0xff);
        req.write(port & 0xff);
        out.write(req.toByteArray());
        out.flush();
        byte[] reply = new byte[4];
        readFully(in, reply);
        if (reply[1] != 0) throw new IOException(via + ": refused (" + reply[1] + ")");
        readAddress(in, reply[3]);
        readFully(in, new byte[2]);
    }

    private static void httpConnect(Socket s, Hop via, String host, int port) throws IOException {
        String target = (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
        StringBuilder req = new StringBuilder("CONNECT ").append(target).append(" HTTP/1.1\r\nHost: ")
                .append(target).append("\r\n");
        if (via.user != null) {
            String token = Base64.encodeToString((via.user + ":" + via.pass).getBytes(StandardCharsets.UTF_8),
                    Base64.NO_WRAP);
            req.append("Proxy-Authorization: Basic ").append(token).append("\r\n");
        }
        req.append("\r\n");
        OutputStream out = s.getOutputStream();
        out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        InputStream in = s.getInputStream();
        StringBuilder headers = new StringBuilder();
        while (!headers.toString().endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0 || headers.length() > 16384) throw new IOException(via + ": bad answer");
            headers.append((char) b);
        }
        String status = headers.substring(0, headers.indexOf("\r\n"));
        if (!status.matches("HTTP/1\\.[01] 2\\d\\d.*")) throw new IOException(via + ": " + status);
    }

    private static String readAddress(InputStream in, byte type) throws IOException {
        switch (type) {
            case 1: {
                byte[] a = new byte[4];
                readFully(in, a);
                return (a[0] & 0xff) + "." + (a[1] & 0xff) + "." + (a[2] & 0xff) + "." + (a[3] & 0xff);
            }
            case 3: {
                byte[] a = new byte[in.read() & 0xff];
                readFully(in, a);
                return new String(a, StandardCharsets.UTF_8);
            }
            case 4: {
                byte[] a = new byte[16];
                readFully(in, a);
                return InetAddress.getByAddress(a).getHostAddress();
            }
            default:
                throw new IOException("bad address type " + type);
        }
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new IOException("closed");
            off += n;
        }
    }

    private void pipe(Socket from, Socket to) {
        byte[] buf = new byte[16384];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
            // One side closed.
        } finally {
            closeQuietly(from);
            closeQuietly(to);
            open.remove(from);
            open.remove(to);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }
}
