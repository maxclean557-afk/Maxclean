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

import java.util.concurrent.Executor;

/**
 * Runs Tor inside Maxclean itself (no Orbot needed) and routes all WebView traffic — pages,
 * images, scripts and DNS — through it.
 *
 * While Tor starts, the WebView is pointed at a dead proxy, so nothing can leave the phone
 * outside Tor. The proxy rule never has a DIRECT fallback.
 */
public final class TorProxy {
    /** Port 9 (discard) on localhost: every connection fails, nothing leaks. */
    private static final String BLACKHOLE = "socks5://127.0.0.1:9";

    public interface Listener {
        void onStarting();

        void onReady(int socksPort);

        void onFailed(String message);

        void onOff();
    }

    private final Context app;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Executor onMain = main::post;

    private TorService service;
    private boolean bound;
    private boolean wanted;
    private boolean ready;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((TorService.LocalBinder) binder).getService();
            // Tor may already be up from before (no new ON broadcast then).
            TorService s = service;
            new Thread(() -> {
                try {
                    if ("1".equals(s.getInfo("status/circuit-established").trim())) {
                        main.post(() -> {
                            if (wanted && !ready) route(s.getSocksPort());
                        });
                    }
                } catch (Exception ignored) {
                    // Not started yet; the ON broadcast will follow.
                }
            }).start();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            if (wanted) fail("Tor неожиданно остановился. Нажмите 🧅, чтобы переподключиться.");
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
                int port = TorService.socksPort;
                if (port <= 0 && service != null) port = service.getSocksPort();
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

    public void start() {
        wanted = true;
        ready = false;
        if (!isSupported()) {
            fail("Ваш системный WebView слишком старый для Tor. Обновите \"Android System WebView\".");
            return;
        }
        listener.onStarting();
        // Close the door first, then start Tor.
        setProxy(BLACKHOLE, () -> {
            if (!bound) {
                bound = app.bindService(new Intent(app, TorService.class), connection, Context.BIND_AUTO_CREATE);
            }
            if (!bound) fail("Не удалось запустить Tor");
        });
    }

    public void stop() {
        wanted = false;
        ready = false;
        if (bound) {
            app.unbindService(connection);
            bound = false;
            service = null;
        }
        if (!isSupported()) {
            listener.onOff();
            return;
        }
        ProxyController.getInstance().clearProxyOverride(onMain, listener::onOff);
    }

    public void release() {
        LocalBroadcastManager.getInstance(app).unregisterReceiver(statusReceiver);
        if (bound) {
            app.unbindService(connection);
            bound = false;
        }
    }

    private void route(int port) {
        // socks5 makes Chromium send host names to Tor: DNS goes through Tor and .onion works.
        setProxy("socks5://127.0.0.1:" + port, () -> {
            if (!wanted) return;
            ready = true;
            listener.onReady(port);
        });
    }

    private void fail(String message) {
        ready = false;
        main.post(() -> listener.onFailed(message));
    }

    private void setProxy(String rule, Runnable then) {
        ProxyConfig config = new ProxyConfig.Builder().addProxyRule(rule).build();
        ProxyController.getInstance().setProxyOverride(config, onMain, then);
    }
}
