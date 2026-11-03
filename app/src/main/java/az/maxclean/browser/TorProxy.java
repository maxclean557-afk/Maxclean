package az.maxclean.browser;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import org.torproject.jni.TorService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import IPtProxy.Controller;
import IPtProxy.IPtProxy;
import IPtProxy.OnTransportEvents;

/**
 * Runs Tor inside Maxclean itself and routes all WebView traffic — pages, images, scripts and
 * DNS — through it. Optionally Tor goes through bridges (obfs4, Snowflake, WebTunnel) so it
 * works where Tor is blocked, and/or through the user's own proxy (xray, sing-box, v2rayNG).
 *
 * While Tor starts, the WebView is pointed at a dead proxy, so nothing can leave the phone
 * outside Tor. No proxy rule ever has a DIRECT fallback.
 */
public final class TorProxy {
    /** Port 9 (discard) on localhost: every connection fails, nothing leaks. */
    private static final String BLACKHOLE = "socks5://127.0.0.1:9";

    public interface Listener {
        void onStarting(String how);

        void onReady(int socksPort);

        void onFailed(String message);

        /** Tor is off; traffic goes direct, or through {@code proxy} if that is not empty. */
        void onOff(String proxy);
    }

    private final Context app;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Executor onMain = main::post;

    private TorService service;
    private boolean bound;
    private volatile boolean wanted;
    private volatile boolean ready;
    /** route() has run for this start: the poll and the ON broadcast may both call it. */
    private boolean routing;
    private volatile int generation;
    private Controller transports;
    private Set<String> running;
    /** Chain in front of Tor, and the one the browser uses (Tor plus the proxy after it). */
    private ProxyChain chainIn, chainOut;
    /** The bridges and proxies the running Tor was started with. */
    private String torSig;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((TorService.LocalBinder) binder).getService();
            waitForCircuit(service, generation);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            if (wanted) fail("Tor неожиданно остановился. Нажмите сюда, чтобы переподключиться.");
        }
    };

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!wanted) return;
            if (TorService.ACTION_ERROR.equals(intent.getAction())) {
                fail("Ошибка Tor: " + intent.getStringExtra(Intent.EXTRA_TEXT));
                return;
            }
            String status = intent.getStringExtra(TorService.EXTRA_STATUS);
            if (TorService.STATUS_ON.equals(status) && !ready) {
                int port = service != null ? service.getSocksPort() : -1;
                if (port <= 0) {
                    fail("Tor запустился без SOCKS-порта");
                    return;
                }
                route(port);
            }
        }
    };

    public TorProxy(Context context, Listener listener) {
        this.app = context.getApplicationContext();
        this.listener = listener;
        IntentFilter filter = new IntentFilter(TorService.ACTION_STATUS);
        filter.addAction(TorService.ACTION_ERROR);
        LocalBroadcastManager.getInstance(app).registerReceiver(statusReceiver, filter);
    }

    public static boolean isSupported() {
        return WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE);
    }

    public boolean isReady() {
        return ready;
    }

    /**
     * Asks Tor itself once a second whether it has a circuit, rather than rely only on the ON
     * broadcast: it can come before we listen, or (tor-android 0.4.9) not at all.
     */
    private void waitForCircuit(TorService s, int gen) {
        new Thread(() -> {
            while (wanted && gen == generation && !ready) {
                try {
                    String info = s.getInfo("status/circuit-established");
                    if (info != null && "1".equals(info.trim())) {
                        int port = s.getSocksPort();
                        if (port > 0) {
                            main.post(() -> {
                                if (wanted && gen == generation && !ready) route(port);
                            });
                            return;
                        }
                    }
                } catch (Exception ignored) {
                    // The control connection is not open yet.
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }).start();
    }

    public void start() {
        wanted = true;
        ready = false;
        routing = false;
        int gen = ++generation;
        if (!isSupported()) {
            fail("Ваш системный WebView слишком старый для Tor. Обновите \"Android System WebView\".");
            return;
        }
        List<String> bridges = Bridges.lines(app);
        List<ProxyChain.Hop> before = Bridges.chainBefore(app);
        ProxyChain.Hop after = Bridges.proxyAfter(app);
        if (Prefs.BRIDGES_CUSTOM.equals(Prefs.str(app, Prefs.BRIDGES, "")) && bridges.isEmpty()) {
            fail("Вы выбрали свои мосты, но не вставили ни одного. Добавьте их в Настройках.");
            return;
        }
        Set<String> needed = Bridges.transports(bridges);
        String how = bridges.isEmpty() ? "" : " через мосты (" + String.join(", ", needed) + ")";
        if (!before.isEmpty()) how += " через " + hops(before.size()) + " перед ним";
        if (after != null) how += ", с прокси после него";
        listener.onStarting(how);
        String sig = bridges + "|" + before;
        if (bound && service != null && sig.equals(torSig)) {
            // Tor still runs with the same settings (it was only switched off in the browser):
            // use it again instead of restarting it in this process.
            stopChainOut();
            TorService s = service;
            setProxy(BLACKHOLE, () -> waitForCircuit(s, gen));
            return;
        }
        shutdown();
        torSig = sig;
        // Close the door first, then start Tor.
        setProxy(BLACKHOLE, () -> BRIDGE_THREAD.execute(() -> {
            if (!wanted || gen != generation) return; // turned off or restarted meanwhile
            try {
                // Tor takes one upstream proxy: a local chain server stands in for the whole chain.
                String proxy = "";
                if (!before.isEmpty()) {
                    chainIn = new ProxyChain(before);
                    proxy = "socks5://127.0.0.1:" + chainIn.start();
                }
                Map<String, Long> ports = startTransports(needed, proxy);
                writeTorrc(Bridges.torrc(bridges, ports, proxy));
            } catch (Exception e) {
                fail("Не удалось запустить мосты: " + e.getMessage());
                return;
            }
            main.post(() -> {
                if (!wanted || gen != generation) return;
                bound = app.bindService(new Intent(app, TorService.class), connection, Context.BIND_AUTO_CREATE);
                if (!bound) fail("Не удалось запустить Tor");
            });
        }));
    }

    /**
     * Tor off in the browser. Tor itself keeps running, so switching it back on is instant: it
     * runs inside this process, and starting it again and again can bring the process down.
     */
    public void stop() {
        stopChainOut();
        wanted = false;
        ready = false;
        routing = false;
        generation++;
        if (!isSupported()) {
            listener.onOff("");
            return;
        }
        List<ProxyChain.Hop> hops = new ArrayList<>(Bridges.chainBefore(app));
        ProxyChain.Hop after = Bridges.proxyAfter(app);
        if (after != null) hops.add(after);
        if (hops.isEmpty()) {
            ProxyController.getInstance().clearProxyOverride(onMain, () -> listener.onOff(""));
            return;
        }
        // Without Tor, but through the user's own proxies (xray, sing-box…). Until the chain is
        // up the blackhole stays, so nothing goes out directly.
        String label = hops.size() == 1 ? hops.get(0).toString() : hops(hops.size());
        setProxy(BLACKHOLE, () -> new Thread(() -> {
            try {
                ProxyChain c = new ProxyChain(hops);
                int port = c.start();
                main.post(() -> {
                    if (wanted) {
                        c.stop();
                        return;
                    }
                    chainOut = c;
                    setProxy("socks5://127.0.0.1:" + port, () -> listener.onOff(label));
                });
            } catch (Exception e) {
                // The blackhole stays: better no internet than a direct connection.
                main.post(() -> listener.onOff("(прокси не запустился: " + e.getMessage() + ")"));
            }
        }).start());
    }

    private static String hops(int n) {
        return n + (n == 1 ? " прокси" : " прокси подряд");
    }

    public void release() {
        LocalBroadcastManager.getInstance(app).unregisterReceiver(statusReceiver);
        shutdown();
    }

    private void stopChainOut() {
        if (chainOut != null) chainOut.stop();
        chainOut = null;
    }

    private void shutdown() {
        torSig = null;
        if (chainIn != null) chainIn.stop();
        chainIn = null;
        stopChainOut();
        if (bound) {
            app.unbindService(connection);
            bound = false;
            service = null;
        }
        Controller c = transports;
        Set<String> r = running;
        transports = null;
        running = null;
        if (c != null && r != null) {
            // On the bridge thread, so a restart can't start bridges before these have stopped.
            BRIDGE_THREAD.execute(() -> {
                for (String t : r) {
                    try {
                        c.stop(t);
                    } catch (Exception ignored) {
                    }
                }
            });
        }
    }

    /**
     * IPtProxy makes one controller per process: asking again returns null. So it is made once
     * and kept, and its events go to whichever TorProxy started the bridges last.
     */
    private static Controller bridgeController;
    private static volatile TorProxy bridgeOwner;
    /** All bridge starts and stops, in order. */
    private static final java.util.concurrent.ExecutorService BRIDGE_THREAD =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private Map<String, Long> startTransports(Set<String> needed, String proxy) throws Exception {
        Map<String, Long> ports = new LinkedHashMap<>();
        if (needed.isEmpty()) return ports;
        bridgeOwner = this;
        if (bridgeController == null) {
            File state = new File(app.getFilesDir(), "pt_state");
            //noinspection ResultOfMethodCallIgnored
            state.mkdirs();
            bridgeController = IPtProxy.newController(state.getAbsolutePath(), false, false, "ERROR", new OnTransportEvents() {
                @Override
                public void connected(String name) {
                }

                @Override
                public void error(String name, Exception e) {
                    TorProxy owner = bridgeOwner;
                    if (owner != null) owner.fail("Мост " + name + " не работает: " + (e == null ? "" : e.getMessage()));
                }

                @Override
                public void stopped(String name, Exception e) {
                }
            });
            if (bridgeController == null) throw new IOException("IPtProxy не создал контроллер мостов");
        }
        Controller c = bridgeController;
        transports = c;
        running = needed;
        for (String t : needed) {
            c.start(t, proxy);
            ports.put(t, c.port(t));
        }
        return ports;
    }

    private void writeTorrc(String text) throws IOException {
        File rc = TorService.getTorrc(app);
        File dir = rc.getParentFile();
        //noinspection ResultOfMethodCallIgnored
        if (dir != null) dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(rc, false)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void route(int port) {
        if (routing) return;
        routing = true;
        ProxyChain.Hop after = Bridges.proxyAfter(app);
        if (after == null) {
            // socks5 makes Chromium send host names to Tor: DNS goes through Tor and .onion works.
            setProxy("socks5://127.0.0.1:" + port, () -> {
                if (!wanted) return;
                ready = true;
                listener.onReady(port);
            });
            return;
        }
        // Browser → Tor → the user's proxy → site, for sites that refuse Tor exits.
        int gen = generation;
        new Thread(() -> {
            try {
                ProxyChain c = new ProxyChain(java.util.Arrays.asList(
                        ProxyChain.parse("socks5://127.0.0.1:" + port), after));
                int local = c.start();
                main.post(() -> {
                    if (!wanted || gen != generation) {
                        c.stop();
                        return;
                    }
                    chainOut = c;
                    setProxy("socks5://127.0.0.1:" + local, () -> {
                        if (!wanted) return;
                        ready = true;
                        listener.onReady(port);
                    });
                });
            } catch (Exception e) {
                fail("Не удалось запустить прокси после Tor: " + e.getMessage());
            }
        }).start();
    }

    private void fail(String message) {
        ready = false;
        main.post(() -> {
            if (wanted) listener.onFailed(message);
        });
    }

    private void setProxy(String rule, Runnable then) {
        ProxyConfig config = new ProxyConfig.Builder().addProxyRule(rule).build();
        ProxyController.getInstance().setProxyOverride(config, onMain, then);
    }
}
