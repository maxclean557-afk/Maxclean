package az.maxclean.browser;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.os.Process;
import android.text.InputType;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String HOME = "maxclean://home";
    private static boolean webViewStartedInProcess;
    private static boolean splashShown;
    private android.animation.ObjectAnimator torPulse;

    private Palette pal;
    private int C_BG;
    private int C_PANEL;
    private int C_TEXT;
    private int C_MUTED;
    private int C_ACCENT;
    private int C_TOR;
    private int C_WARN;

    /** One open tab. Everything it holds lives in memory and dies with it. */
    private static final class Tab {
        WebView view;
        String title = "Новая вкладка";
        final List<ScriptHandler> scripts = new ArrayList<>();
    }

    private final List<Tab> tabs = new ArrayList<>();
    private Tab current;

    private FrameLayout container;
    private EditText urlBar;
    private TextView engineButton;
    private TextView tabsButton;
    private TextView torBadge;
    private TextView banner;
    private ProgressBar progress;

    private AdBlocker blocker;
    private TorProxy tor;
    private boolean torEnabled;
    private boolean torReady;
    private boolean torFailed;
    private boolean reloadWhenRouted;
    private String networkSignature;
    private final List<String> pendingLoads = new ArrayList<>();
    private String cosmeticCss = "";
    private String cookieCss = "";
    private String fingerprintJs = "";
    private static final long FP_SEED = new java.security.SecureRandom().nextInt() & 0xffffffffL;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        pal = Palette.of(this);
        pal.applyTo(this);
        C_BG = pal.bg; C_PANEL = pal.panel; C_TEXT = pal.text; C_MUTED = pal.muted;
        C_ACCENT = pal.accent; C_TOR = pal.tor; C_WARN = pal.warn;
        super.onCreate(savedInstanceState);
        if (!webViewStartedInProcess) {
            // Clean up anything left on disk if the app was killed last time.
            Wiper.wipeDiskBeforeWebView(this);
            webViewStartedInProcess = true;
        }
        blocker = new AdBlocker(this);
        cosmeticCss = readAsset("filters/cosmetic.css");
        cookieCss = readAsset("filters/cookie_banners.css");
        fingerprintJs = readAsset("fingerprint.js");
        buildUi();
        Wiper.wipeShared(this);

        tor = new TorProxy(this, torListener);
        torEnabled = Prefs.on(this, Prefs.TOR);
        networkSignature = Prefs.networkSignature(this);
        applyTor(false);
        newTab(urlFromIntent(getIntent()));
        warnIfWebViewOutdated();
    }

    /**
     * The browser engine is the system WebView, updated by Google Play. An old one has known
     * security holes, so say so instead of staying silent.
     */
    private void warnIfWebViewOutdated() {
        android.content.pm.PackageInfo p = WebViewCompat.getCurrentWebViewPackage(this);
        if (p == null || p.versionName == null) return;
        int major;
        try {
            major = Integer.parseInt(p.versionName.split("\\.")[0]);
        } catch (NumberFormatException e) {
            return;
        }
        // Chromium ships a new major version about every 4 weeks; 130 came out in October 2024.
        long weeks = (System.currentTimeMillis() - 1728950400000L) / (7L * 24 * 3600 * 1000);
        int expected = 130 + (int) (weeks / 4);
        if (major < expected - 12) {
            container.postDelayed(() -> showBanner("⚠ Движок браузера (Android System WebView " + major
                    + ") устарел больше чем на год, в нём есть известные уязвимости. "
                    + "Обновите «Android System WebView» в Google Play.", C_WARN), 4000);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String url = urlFromIntent(intent);
        if (url != null) newTab(url);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Prefs.on(this, Prefs.NO_SCREENSHOTS)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        // Settings may have changed while we were away.
        blocker.refresh(this);
        for (Tab t : tabs) configure(t);
        updateEngineButton();
        String sig = Prefs.networkSignature(this);
        if (!sig.equals(networkSignature)) {
            // Tor, bridges or the proxy changed in Settings: set the route up again.
            networkSignature = sig;
            torEnabled = Prefs.on(this, Prefs.TOR);
            applyTor(true);
        }
    }

    @Override
    public void onBackPressed() {
        if (current != null && current.view.canGoBack()) {
            current.view.goBack();
        } else if (tabs.size() > 1) {
            closeTab(current);
        } else {
            wipeAndExit();
        }
    }

    @Override
    protected void onDestroy() {
        for (Tab t : new ArrayList<>(tabs)) destroyTab(t);
        Wiper.wipeShared(this);
        tor.release();
        super.onDestroy();
        if (isFinishing()) {
            // Kill the process so nothing stays in RAM either.
            Process.killProcess(Process.myPid());
        }
    }

    // ---------------------------------------------------------------- UI

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private TextView barButton(String text) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(C_TEXT);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(dp(40));
        b.setPadding(dp(8), 0, dp(8), 0);
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        b.setBackgroundResource(tv.resourceId);
        return b;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(C_PANEL);
        bar.setPadding(dp(4), dp(6), dp(4), dp(6));

        engineButton = barButton("MC");
        engineButton.setTextColor(C_ACCENT);
        engineButton.setOnClickListener(v -> chooseEngine());
        bar.addView(engineButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));

        urlBar = new EditText(this);
        urlBar.setSingleLine(true);
        urlBar.setHint("Поиск или адрес");
        urlBar.setHintTextColor(C_MUTED);
        urlBar.setTextColor(C_TEXT);
        urlBar.setTextSize(15);
        urlBar.setSelectAllOnFocus(true);
        urlBar.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        // Keep the keyboard from learning what you type.
        urlBar.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(pal.field);
        if (BuildConfig.FULL) {
            // Full: the field lights up in the accent colour while you type.
            pill.setStroke(dp(2), Color.TRANSPARENT);
            urlBar.setOnFocusChangeListener((v, focus) -> pill.setStroke(dp(2), focus ? C_ACCENT : Color.TRANSPARENT));
        }
        pill.setCornerRadius(dp(20));
        urlBar.setBackground(pill);
        urlBar.setPadding(dp(14), 0, dp(14), 0);
        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                submit(urlBar.getText().toString());
                return true;
            }
            return false;
        });
        bar.addView(urlBar, new LinearLayout.LayoutParams(0, dp(40), 1));

        torBadge = barButton("🧅");
        torBadge.setTextSize(16);
        torBadge.setOnClickListener(v -> toggleTor());
        bar.addView(torBadge, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));

        tabsButton = barButton("1");
        GradientDrawable box = new GradientDrawable();
        box.setStroke(dp(2), C_TEXT);
        box.setCornerRadius(dp(5));
        tabsButton.setBackground(box);
        tabsButton.setMinWidth(0);
        tabsButton.setPadding(0, 0, 0, 0);
        tabsButton.setTextSize(13);
        tabsButton.setOnClickListener(v -> showTabs());
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(28), dp(26));
        tlp.setMargins(dp(6), 0, dp(6), 0);
        bar.addView(tabsButton, tlp);

        TextView menu = barButton("⋮");
        menu.setTextSize(22);
        menu.setOnClickListener(this::showMenu);
        bar.addView(menu, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));

        root.addView(bar);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(C_ACCENT));
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        banner = new TextView(this);
        banner.setTextColor(Color.WHITE);
        banner.setTextSize(13);
        banner.setPadding(dp(12), dp(8), dp(12), dp(8));
        banner.setVisibility(View.GONE);
        banner.setOnClickListener(v -> onBannerClick());
        root.addView(banner);

        container = new FrameLayout(this);
        root.addView(container, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        if (Palette.animations(this) && !splashShown) {
            splashShown = true;
            FrameLayout frame = new FrameLayout(this);
            frame.addView(root);
            frame.addView(buildSplash(frame));
            setContentView(frame);
        } else {
            setContentView(root);
        }
        updateEngineButton();
    }

    private void updateEngineButton() {
        int e = Prefs.get(this).getInt(Prefs.ENGINE, SearchEngines.MAXCLEAN);
        if (e < 0 || e >= SearchEngines.SHORT.length) e = SearchEngines.MAXCLEAN;
        engineButton.setText(SearchEngines.SHORT[e]);
    }

    private void updateTabsButton() {
        tabsButton.setText(String.valueOf(tabs.size()));
    }

    private void updateTorBadge() {
        torBadge.setAlpha(torEnabled ? 1f : 0.35f);
        pulseTorBadge(torEnabled && !torReady);
    }

    /** Full: the onion breathes while Tor connects. */
    private void pulseTorBadge(boolean on) {
        if (torPulse != null) {
            torPulse.cancel();
            torPulse = null;
            torBadge.setScaleX(1f);
            torBadge.setScaleY(1f);
        }
        if (!on || !Palette.animations(this)) return;
        torPulse = android.animation.ObjectAnimator.ofPropertyValuesHolder(torBadge,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.18f),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.18f));
        torPulse.setDuration(700);
        torPulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        torPulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        torPulse.start();
    }

    private void showBanner(String text, int color) {
        boolean wasHidden = banner.getVisibility() != View.VISIBLE;
        banner.setText(text);
        banner.setBackgroundColor(color);
        // Readable on any background: dark text only on the light theme's panel.
        banner.setTextColor(color == C_PANEL ? C_TEXT : Color.WHITE);
        banner.setVisibility(View.VISIBLE);
        if (torEnabled && !torReady && color == C_TOR) pulseTorBadge(true);
        if (!torEnabled || torReady) pulseTorBadge(false);
        if (wasHidden && Palette.animations(this)) {
            banner.setAlpha(0f);
            banner.setTranslationY(-dp(16));
            banner.animate().alpha(1f).translationY(0).setDuration(260)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        }
    }

    private void hideBanner() {
        if (banner.getVisibility() != View.VISIBLE) return;
        if (!Palette.animations(this)) {
            banner.setVisibility(View.GONE);
            return;
        }
        banner.animate().alpha(0f).translationY(-dp(16)).setDuration(200)
                .withEndAction(() -> {
                    banner.setVisibility(View.GONE);
                    banner.setAlpha(1f);
                    banner.setTranslationY(0);
                }).start();
    }

    /** Full: the shield grows in, the name appears, then the whole screen fades into the browser. */
    private View buildSplash(FrameLayout parent) {
        LinearLayout splash = new LinearLayout(this);
        splash.setOrientation(LinearLayout.VERTICAL);
        splash.setGravity(Gravity.CENTER);
        splash.setBackgroundColor(C_BG);
        splash.setClickable(true);
        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher_fg);
        splash.addView(logo, new LinearLayout.LayoutParams(dp(160), dp(160)));
        TextView name = new TextView(this);
        name.setText("Maxclean");
        name.setTextColor(C_TEXT);
        name.setTextSize(30);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setLetterSpacing(0.04f);
        splash.addView(name);
        TextView tag = new TextView(this);
        tag.setText("анонимность без следов");
        tag.setTextColor(C_MUTED);
        tag.setTextSize(14);
        tag.setPadding(0, dp(6), 0, 0);
        splash.addView(tag);

        logo.setScaleX(0.4f);
        logo.setScaleY(0.4f);
        logo.setAlpha(0f);
        name.setAlpha(0f);
        name.setTranslationY(dp(12));
        tag.setAlpha(0f);
        logo.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(520)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start();
        name.animate().alpha(1f).translationY(0).setStartDelay(260).setDuration(380).start();
        tag.animate().alpha(1f).setStartDelay(420).setDuration(380).start();
        splash.animate().alpha(0f).scaleX(1.06f).scaleY(1.06f).setStartDelay(1150).setDuration(380)
                .withEndAction(() -> parent.removeView(splash)).start();
        return splash;
    }

    private void onBannerClick() {
        if (torEnabled && !torReady && torFailed) {
            applyTor(true); // retry
            return;
        }
        if (torReady || !torEnabled) hideBanner();
    }

    // ---------------------------------------------------------------- Tor

    private void toggleTor() {
        torEnabled = !torEnabled;
        Prefs.get(this).edit().putBoolean(Prefs.TOR, torEnabled).apply();
        networkSignature = Prefs.networkSignature(this);
        applyTor(true);
    }

    /** Called when Tor is switched on or off. Reload the page once the new route is in place. */
    private void applyTor(boolean reload) {
        updateTorBadge();
        reloadWhenRouted = reload;
        // With the user's own proxy nothing loads until it is in place either.
        torReady = !torEnabled && !Bridges.anyProxy(this);
        torFailed = false;
        // Wipe everything made under the other network identity.
        Wiper.wipeShared(this);
        if (torEnabled) {
            tor.start();
        } else {
            tor.stop();
        }
    }

    private final TorProxy.Listener torListener = new TorProxy.Listener() {
        private long startedAt;

        @Override
        public void onStarting(String how) {
            startedAt = System.currentTimeMillis();
            showBanner("🧅 Подключаюсь к сети Tor" + how + "… обычно 10–60 секунд. "
                    + "Пока Tor не готов, ничего не загружается.", C_TOR);
            container.postDelayed(this::slowCheck, 60_000);
        }

        private void slowCheck() {
            if (torEnabled && !torReady && !torFailed
                    && System.currentTimeMillis() - startedAt >= 59_000) {
                boolean bridges = !Prefs.BRIDGES_NONE.equals(Prefs.str(MainActivity.this, Prefs.BRIDGES, Prefs.BRIDGES_NONE));
                showBanner(bridges
                        ? "🧅 Tor подключается дольше обычного. Попробуйте другой тип мостов в Настройках → Мосты Tor."
                        : "🧅 Tor подключается дольше обычного. Похоже, ваша сеть блокирует Tor: включите мосты "
                        + "(Snowflake) в Настройках → Мосты Tor.", C_TOR);
            }
        }

        @Override
        public void onReady(int socksPort) {
            torReady = true;
            torFailed = false;
            showBanner("🧅 Tor подключён: провайдер не видит, куда вы ходите", C_TOR);
            container.postDelayed(() -> { if (torReady) hideBanner(); }, 2500);
            afterRouteChange();
        }

        @Override
        public void onFailed(String message) {
            if (!torEnabled) return;
            torReady = false;
            torFailed = true;
            showBanner(message + "\nНажмите сюда, чтобы попробовать снова.", C_WARN);
        }

        @Override
        public void onOff(String proxy) {
            if (torEnabled) return;
            torReady = true;
            if (reloadWhenRouted) {
                showBanner(proxy.isEmpty() ? "Tor выключен — прямое соединение"
                        : "Tor выключен — через ваш прокси " + proxy, C_PANEL);
                container.postDelayed(() -> { if (!torEnabled) hideBanner(); }, 2000);
            } else {
                hideBanner();
            }
            afterRouteChange();
        }
    };

    private void afterRouteChange() {
        flushPending();
        if (reloadWhenRouted && current != null) reloadCurrent();
        reloadWhenRouted = false;
    }

    private void reloadCurrent() {
        String url = current.view.getUrl();
        if (url == null || url.startsWith(HOME)) {
            load(HOME); // the home page shows the Tor state
        } else {
            current.view.reload();
        }
    }

    private void flushPending() {
        if (!torReady) return;
        List<String> copy = new ArrayList<>(pendingLoads);
        pendingLoads.clear();
        for (String url : copy) load(url);
    }

    // ---------------------------------------------------------------- tabs

    private void newTab(String url) {
        Tab t = new Tab();
        t.view = createWebView(t);
        tabs.add(t);
        switchTo(t);
        load(url == null ? HOME : url);
        updateTabsButton();
    }

    private void switchTo(Tab t) {
        boolean animate = current != t && Palette.animations(this);
        current = t;
        container.removeAllViews();
        container.addView(t.view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (animate) {
            // Full: the tab rises in from slightly smaller, like a card being picked up.
            t.view.setAlpha(0f);
            t.view.setScaleX(0.96f);
            t.view.setScaleY(0.96f);
            t.view.setTranslationY(dp(18));
            t.view.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0).setDuration(280)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        }
        showUrl(t.view.getUrl());
    }

    private void closeTab(Tab t) {
        int idx = tabs.indexOf(t);
        destroyTab(t);
        Toast.makeText(this, "Вкладка закрыта, все данные стёрты", Toast.LENGTH_SHORT).show();
        if (tabs.isEmpty()) {
            newTab(null);
        } else {
            switchTo(tabs.get(Math.max(0, Math.min(idx, tabs.size() - 1))));
        }
        updateTabsButton();
    }

    private void destroyTab(Tab t) {
        tabs.remove(t);
        if (t.view == null) return;
        container.removeView(t.view);
        Wiper.wipeWebView(this, t.view);
        t.view.loadUrl("about:blank");
        t.view.destroy();
        t.view = null;
    }

    private void showTabs() {
        String[] titles = new String[tabs.size()];
        for (int i = 0; i < tabs.size(); i++) {
            Tab t = tabs.get(i);
            titles[i] = (t == current ? "▶ " : "    ") + t.title;
        }
        new AlertDialog.Builder(this)
                .setTitle("Вкладки (" + tabs.size() + ")")
                .setItems(titles, (d, which) -> switchTo(tabs.get(which)))
                .setPositiveButton("+ Новая", (d, w) -> newTab(null))
                .setNeutralButton("Закрыть текущую", (d, w) -> closeTab(current))
                .show();
    }

    // ---------------------------------------------------------------- WebView

    private WebView createWebView(Tab tab) {
        WebView w = new WebView(this);
        w.setBackgroundColor(C_BG);
        w.setWebViewClient(new Client(tab));
        w.setWebChromeClient(new Chrome(tab));
        w.setDownloadListener((url, ua, cd, mime, len) -> Toast.makeText(this,
                "Загрузки отключены: файл оставил бы след на телефоне", Toast.LENGTH_LONG).show());
        tab.view = w;
        configure(tab);
        return w;
    }

    /** Applies the current settings to a tab. Safe to call repeatedly. */
    @SuppressLint("SetJavaScriptEnabled")
    private void configure(Tab tab) {
        WebView w = tab.view;
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(Prefs.on(this, Prefs.JAVASCRIPT));
        s.setDomStorageEnabled(true); // lives for the session only, wiped on close
        s.setGeolocationEnabled(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSaveFormData(false);
        s.setSafeBrowsingEnabled(false); // would send visited URLs (hashed) to Google
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(!Prefs.on(this, Prefs.BLOCK_POPUPS));
        s.setMediaPlaybackRequiresUserGesture(Prefs.on(this, Prefs.BLOCK_AUTOPLAY));
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setUserAgentString(Prefs.on(this, Prefs.GENERIC_UA) ? genericUserAgent() : null);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(Prefs.on(this, Prefs.COOKIES));
        cm.setAcceptThirdPartyCookies(w, Prefs.on(this, Prefs.THIRD_PARTY_COOKIES));

        installScripts(tab);
    }

    /** Same user agent as everyone else on Chrome for Android: no device model, no WebView mark. */
    private String genericUserAgent() {
        String ua = WebSettings.getDefaultUserAgent(this);
        ua = ua.replaceAll("Android [^;)]*;[^)]*\\)", "Android 10; K)");
        ua = ua.replace("; wv", "").replace(" Version/4.0", "");
        return ua;
    }

    private String buildPageScript() {
        StringBuilder js = new StringBuilder("(function(){");
        if (Prefs.on(this, Prefs.DISABLE_WEBRTC)) {
            // WebRTC can reveal your real IP even through Tor.
            js.append("try{['RTCPeerConnection','webkitRTCPeerConnection','RTCDataChannel',"
                    + "'RTCSessionDescription','RTCIceCandidate'].forEach(function(k){"
                    + "try{Object.defineProperty(window,k,{value:undefined,writable:false,configurable:false});}catch(e){}});"
                    + "}catch(e){}");
        }
        if (Prefs.on(this, Prefs.FP_PROTECT) && !fingerprintJs.isEmpty()) {
            // The function is in assets/fingerprint.js; the seed changes on every start.
            js.append("try{(").append(fingerprintJs.trim().replaceAll("^(//[^\\n]*\\n)+", ""))
                    .append(")(").append(FP_SEED).append(",").append(Disguise.profileJs(this))
                    .append(");}catch(e){}");
        }
        js.append(Disguise.geoJs(this));
        if (Prefs.on(this, Prefs.GPC)) {
            js.append("try{Object.defineProperty(navigator,'globalPrivacyControl',{value:true});"
                    + "Object.defineProperty(navigator,'doNotTrack',{value:'1'});}catch(e){}");
        }
        StringBuilder css = new StringBuilder();
        if (Prefs.on(this, Prefs.HIDE_AD_BLOCKS)) css.append(cosmeticCss).append('\n');
        if (Prefs.on(this, Prefs.HIDE_COOKIE_BANNERS)) css.append(cookieCss).append('\n');
        boolean userOn = Prefs.on(this, Prefs.USER_SCRIPT_ON);
        if (userOn) css.append(Prefs.get(this).getString(Prefs.USER_CSS, "")).append('\n');
        if (css.toString().trim().length() > 0) {
            String b64 = Base64.encodeToString(css.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            js.append("try{var css=decodeURIComponent(escape(atob('").append(b64).append("')));")
                    .append("var add=function(){if(document.getElementById('__maxclean_css'))return;")
                    .append("var s=document.createElement('style');s.id='__maxclean_css';s.textContent=css;")
                    .append("(document.head||document.documentElement).appendChild(s);};")
                    .append("if(document.documentElement)add();")
                    .append("document.addEventListener('DOMContentLoaded',add);}catch(e){}");
        }
        if (userOn) {
            String userJs = Prefs.get(this).getString(Prefs.USER_JS, "");
            if (!userJs.trim().isEmpty()) {
                js.append("try{\n").append(userJs).append("\n}catch(e){console.error('Maxclean user script',e);}");
            }
        }
        js.append("})();");
        return js.toString();
    }

    private void installScripts(Tab tab) {
        for (ScriptHandler h : tab.scripts) h.remove();
        tab.scripts.clear();
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            // Runs before any page script, in every frame.
            tab.scripts.add(WebViewCompat.addDocumentStartJavaScript(
                    tab.view, buildPageScript(), Collections.singleton("*")));
        }
    }

    private void injectFallback(WebView view) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            view.evaluateJavascript(buildPageScript(), null);
        }
    }

    private Map<String, String> privacyHeaders() {
        Map<String, String> h = new HashMap<>();
        if (Prefs.on(this, Prefs.GPC)) {
            h.put("Sec-GPC", "1");
            h.put("DNT", "1");
        }
        return h;
    }

    private void load(String url) {
        if (current == null) return;
        if (HOME.equals(url)) {
            current.view.loadDataWithBaseURL(HOME, homeHtml(), "text/html", "utf-8", null);
            current.title = "Maxclean";
            showUrl(null);
            return;
        }
        if (!torReady) {
            // Kill switch: nothing leaves the phone until Tor is up.
            pendingLoads.add(url);
            urlBar.setText(url);
            return;
        }
        Uri u = Uri.parse(url);
        if ("http".equalsIgnoreCase(u.getScheme()) && u.getHost() != null
                && !u.getHost().endsWith(".onion") && Prefs.on(this, Prefs.HTTPS_ONLY)) {
            url = u.buildUpon().scheme("https").build().toString();
        }
        current.view.loadUrl(url, privacyHeaders());
    }

    private static final Pattern LOOKS_LIKE_HOST =
            Pattern.compile("^[\\w-]+(\\.[\\w-]+)+(:\\d+)?(/.*)?$");

    private void submit(String input) {
        input = input.trim();
        if (input.isEmpty()) return;
        hideKeyboard();
        String url;
        if (input.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) {
            url = input;
        } else if (!input.contains(" ") && LOOKS_LIKE_HOST.matcher(input).matches()) {
            String host = input.split("[/:]")[0];
            // Most .onion sites have no TLS certificate; Tor already encrypts end to end.
            url = (host.endsWith(".onion") ? "http://" : "https://") + input;
        } else {
            int engine = Prefs.get(this).getInt(Prefs.ENGINE, SearchEngines.MAXCLEAN);
            url = SearchEngines.buildUrl(engine, input, torEnabled,
                    Prefs.get(this).getString(Prefs.CUSTOM_ENGINE, ""));
        }
        load(url);
        current.view.requestFocus();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
        urlBar.clearFocus();
    }

    private void showUrl(String url) {
        if (urlBar.hasFocus()) return;
        if (url == null || url.startsWith(HOME) || url.equals("about:blank")) {
            urlBar.setText("");
        } else {
            urlBar.setText(url);
        }
    }

    private String homeHtml() {
        String bg = Palette.hex(C_BG), text = Palette.hex(C_TEXT), muted = Palette.hex(C_MUTED);
        String accent = Palette.hex(C_ACCENT), tor = Palette.hex(C_TOR), panel = Palette.hex(C_PANEL);
        String mode = torEnabled
                ? "<span style='color:" + tor + "'>🧅 Tor включён — провайдер не видит, куда вы ходите</span>"
                : "<span style='color:" + muted + "'>Tor выключен — нажмите 🧅 вверху, чтобы включить</span>";
        // Full: the start page rises in piece by piece and the shield breathes.
        String motion = Palette.animations(this)
                ? ".in{opacity:0;transform:translateY(14px);animation:up .7s cubic-bezier(.2,.8,.2,1) forwards}"
                + ".d1{animation-delay:.08s}.d2{animation-delay:.18s}.d3{animation-delay:.28s}.d4{animation-delay:.38s}"
                + "@keyframes up{to{opacity:1;transform:none}}"
                + ".logo{animation:breathe 3.2s ease-in-out infinite}"
                + "@keyframes breathe{50%{transform:scale(1.04)}}"
                + "@media (prefers-reduced-motion:reduce){.in,.logo{animation:none;opacity:1;transform:none}}"
                : "";
        String card = BuildConfig.FULL
                ? "ul{background:" + panel + ";border-radius:18px;padding:14px 22px;box-shadow:0 8px 30px rgba(0,0,0,.18)}"
                : "";
        return "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{background:" + bg + ";color:" + text + ";font-family:sans-serif;margin:0;"
                + "display:flex;flex-direction:column;align-items:center;justify-content:center;min-height:90vh;"
                + "text-align:center;padding:0 24px}h1{font-size:40px;margin:0 0 4px;letter-spacing:1px}"
                + "h1 b{color:" + accent + "}p{color:" + muted + ";line-height:1.5;margin:6px 0}"
                + "ul{list-style:none;padding:0;color:" + muted + ";font-size:14px;line-height:1.9}"
                + card + motion + "</style></head><body>"
                + (BuildConfig.FULL ? "<svg class='logo in' width='84' height='84' viewBox='0 0 108 108'>"
                + "<path d='M54,22 L78,31 L78,51 C78,68 67,80 54,85 C41,80 30,68 30,51 L30,31 Z' fill='" + accent + "'/>"
                + "<path d='M42,64 L42,42 L47,42 L54,53 L61,42 L66,42 L66,64 L61,64 L61,51 L54,61 L47,51 L47,64 Z' fill='"
                + bg + "'/></svg>" : "")
                + "<h1 class='in d1'><b>Max</b>clean</h1><p class='in d2'>Ничего не сохраняется. Ни аккаунтов, ни истории, ни следов.</p>"
                + "<p class='in d3'>" + mode + "</p><ul class='in d4'>"
                + "<li>🍪 Cookies живут только пока открыта вкладка</li>"
                + "<li>🛡 Реклама и трекеры блокируются</li>"
                + "<li>🧹 Закрыли вкладку — всё стёрто</li></ul></body></html>";
    }

    private final class Client extends WebViewClient {
        private final Tab tab;

        Client(Tab tab) {
            this.tab = tab;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (!request.isForMainFrame() && "http".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null && !uri.getHost().endsWith(".onion")
                    && Prefs.on(MainActivity.this, Prefs.HTTPS_ONLY)) {
                // Unencrypted pieces of a page could be read or changed on the way (a Tor exit
                // node, public Wi-Fi). Only HTTPS, except .onion, which Tor encrypts itself.
                blocker.countBlocked();
                return AdBlocker.emptyResponse();
            }
            if (!blocker.shouldBlock(uri)) return null;
            if (!request.isForMainFrame()) {
                blocker.countBlocked();
                return AdBlocker.emptyResponse();
            }
            // A whole page on a tracker domain (e.g. an ad click redirect).
            String page = "<html><body style='background:" + Palette.hex(C_BG) + ";color:" + Palette.hex(C_TEXT) + ";font-family:sans-serif;padding:24px'>"
                    + "<h3>Maxclean заблокировал этот адрес</h3><p>" + uri.getHost()
                    + " — рекламный или следящий домен. Отключить можно в Настройках.</p></body></html>";
            return new WebResourceResponse("text/html", "utf-8",
                    new ByteArrayInputStream(page.getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (scheme.equals("http") && Prefs.on(MainActivity.this, Prefs.HTTPS_ONLY)
                    && uri.getHost() != null && !uri.getHost().endsWith(".onion")) {
                view.loadUrl(uri.buildUpon().scheme("https").build().toString(), privacyHeaders());
                return true;
            }
            if (scheme.equals("http") || scheme.equals("https") || scheme.equals("about")
                    || scheme.equals("data") || scheme.equals("blob") || scheme.equals("javascript")) {
                return false;
            }
            // intent://, market://, tel: and the like would hand your visit to another app.
            Toast.makeText(MainActivity.this, "Внешняя ссылка заблокирована: " + scheme + ":",
                    Toast.LENGTH_SHORT).show();
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (tab == current) showUrl(url);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            injectFallback(view);
            if (tab == current) {
                showUrl(url);
                int blocked = blocker.takeBlockedCount();
                if (blocked > 0) {
                    Toast.makeText(MainActivity.this, "🛡 Заблокировано: " + blocked, Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private final class Chrome extends WebChromeClient {
        private final Tab tab;

        Chrome(Tab tab) {
            this.tab = tab;
        }

        @Override
        public void onProgressChanged(WebView view, int p) {
            if (tab != current) return;
            progress.setProgress(p, Palette.animations(MainActivity.this));
            progress.setVisibility(p >= 100 ? View.GONE : View.VISIBLE);
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            tab.title = (title == null || title.isEmpty()) ? "Без названия" : title;
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            request.deny(); // camera, microphone, MIDI, protected media IDs
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
            cb.invoke(origin, false, false);
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean userGesture, Message resultMsg) {
            if (Prefs.on(MainActivity.this, Prefs.BLOCK_POPUPS) && !userGesture) {
                Toast.makeText(MainActivity.this, "Всплывающее окно заблокировано", Toast.LENGTH_SHORT).show();
                return false;
            }
            Tab t = new Tab();
            t.view = createWebView(t);
            tabs.add(t);
            switchTo(t);
            updateTabsButton();
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(t.view);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public void onCloseWindow(WebView window) {
            for (Tab t : tabs) {
                if (t.view == window) {
                    closeTab(t);
                    return;
                }
            }
        }
    }

    // ---------------------------------------------------------------- menu

    private void showMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Новая вкладка");
        m.getMenu().add(0, 2, 0, "Закрыть вкладку (стереть)");
        m.getMenu().add(0, 3, 0, "Обновить");
        m.getMenu().add(0, 4, 0, "Вперёд");
        m.getMenu().add(0, 5, 0, torEnabled ? "🧅 Выключить Tor" : "🧅 Включить Tor");
        m.getMenu().add(0, 6, 0, "🔎 Поисковик…");
        m.getMenu().add(0, 7, 0, "⚙ Настройки");
        m.getMenu().add(0, 8, 0, "</> Код браузера");
        m.getMenu().add(0, 10, 0, "🔍 Проверка утечек");
        m.getMenu().add(0, 9, 0, "🔥 Стереть всё и выйти");
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: newTab(null); break;
                case 2: closeTab(current); break;
                case 3: reloadCurrent(); break;
                case 4: if (current.view.canGoForward()) current.view.goForward(); break;
                case 5: toggleTor(); break;
                case 6: chooseEngine(); break;
                case 7: openScreen(SettingsActivity.class); break;
                case 8: openScreen(CodeActivity.class); break;
                case 9: wipeAndExit(); break;
                case 10: showLeakTests(); break;
            }
            return true;
        });
        m.show();
    }

    private void openScreen(Class<?> screen) {
        startActivity(new Intent(this, screen));
        if (Palette.animations(this)) overridePendingTransition(R.anim.mc_enter, R.anim.mc_hold);
    }

    private void chooseEngine() {
        int selected = Prefs.get(this).getInt(Prefs.ENGINE, SearchEngines.MAXCLEAN);
        new AlertDialog.Builder(this)
                .setTitle("Искать через")
                .setSingleChoiceItems(SearchEngines.NAMES, selected, (d, which) -> {
                    if (which == SearchEngines.CUSTOM
                            && !Prefs.get(this).getString(Prefs.CUSTOM_ENGINE, "").contains("%s")) {
                        Toast.makeText(this, "Сначала задайте свой поисковик в Настройках",
                                Toast.LENGTH_LONG).show();
                    }
                    Prefs.get(this).edit().putInt(Prefs.ENGINE, which).apply();
                    updateEngineButton();
                    d.dismiss();
                    String text = urlBar.getText().toString().trim();
                    if (!text.isEmpty() && !text.contains("://")) submit(text);
                })
                .show();
    }

    /** Independent sites that show what a page can see: your IP, DNS servers, fingerprint. */
    private void showLeakTests() {
        String[][] tests = {
                {"Tor и IP-адрес", "https://check.torproject.org/"},
                {"DNS-утечки", "https://www.dnsleaktest.com/"},
                {"IP, WebRTC и DNS вместе", "https://browserleaks.com/ip"},
                {"Отпечаток браузера", "https://coveryourtracks.eff.org/"},
        };
        String[] names = new String[tests.length];
        for (int i = 0; i < tests.length; i++) names[i] = tests[i][0];
        new AlertDialog.Builder(this)
                .setTitle("Что видят сайты? Через Tor там будет не ваш адрес")
                .setItems(names, (d, which) -> newTab(tests[which][1]))
                .show();
    }

    private void wipeAndExit() {
        for (Tab t : new ArrayList<>(tabs)) destroyTab(t);
        Wiper.wipeShared(this);
        finishAndRemoveTask();
    }

    // ---------------------------------------------------------------- helpers

    private String urlFromIntent(Intent intent) {
        if (intent == null) return null;
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            return intent.getDataString();
        }
        if (Intent.ACTION_WEB_SEARCH.equals(intent.getAction())) {
            String q = intent.getStringExtra("query");
            if (q != null) {
                return SearchEngines.buildUrl(Prefs.get(this).getInt(Prefs.ENGINE, 0), q,
                        Prefs.on(this, Prefs.TOR), Prefs.get(this).getString(Prefs.CUSTOM_ENGINE, ""));
            }
        }
        return null;
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (IOException e) {
            return "";
        }
    }
}
