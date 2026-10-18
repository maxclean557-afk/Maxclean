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

    private static final int C_BG = Color.parseColor("#0E1116");
    private static final int C_PANEL = Color.parseColor("#171B22");
    private static final int C_TEXT = Color.parseColor("#E8ECF1");
    private static final int C_MUTED = Color.parseColor("#8A94A3");
    private static final int C_ACCENT = Color.parseColor("#2BD4A4");
    private static final int C_TOR = Color.parseColor("#9B6BFF");
    private static final int C_WARN = Color.parseColor("#E5484D");

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
    private final List<String> pendingLoads = new ArrayList<>();
    private String cosmeticCss = "";
    private String cookieCss = "";

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!webViewStartedInProcess) {
            // Clean up anything left on disk if the app was killed last time.
            Wiper.wipeDiskBeforeWebView(this);
            webViewStartedInProcess = true;
        }
        blocker = new AdBlocker(this);
        cosmeticCss = readAsset("filters/cosmetic.css");
        cookieCss = readAsset("filters/cookie_banners.css");
        buildUi();
        Wiper.wipeShared(this);

        tor = new TorProxy(this, torListener);
        torEnabled = Prefs.on(this, Prefs.TOR);
        applyTor(false);
        newTab(urlFromIntent(getIntent()));
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
        boolean wantTor = Prefs.on(this, Prefs.TOR);
        if (wantTor != torEnabled) {
            torEnabled = wantTor;
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
        pill.setColor(C_BG);
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

        setContentView(root);
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
    }

    private void showBanner(String text, int color) {
        banner.setText(text);
        banner.setBackgroundColor(color);
        banner.setVisibility(View.VISIBLE);
    }

    private void onBannerClick() {
        if (torEnabled && !torReady && torFailed) {
            tor.stop();
            applyTor(true); // retry
            return;
        }
        if (torReady || !torEnabled) banner.setVisibility(View.GONE);
    }

    // ---------------------------------------------------------------- Tor

    private void toggleTor() {
        torEnabled = !torEnabled;
        Prefs.get(this).edit().putBoolean(Prefs.TOR, torEnabled).apply();
        applyTor(true);
    }

    /** Called when Tor is switched on or off. Reload the page once the new route is in place. */
    private void applyTor(boolean reload) {
        updateTorBadge();
        reloadWhenRouted = reload;
        torReady = !torEnabled;
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
        public void onStarting() {
            startedAt = System.currentTimeMillis();
            showBanner("🧅 Подключаюсь к сети Tor… обычно 10–40 секунд. "
                    + "Пока Tor не готов, ничего не загружается.", C_TOR);
            container.postDelayed(this::slowCheck, 60_000);
        }

        private void slowCheck() {
            if (torEnabled && !torReady && !torFailed
                    && System.currentTimeMillis() - startedAt >= 59_000) {
                showBanner("🧅 Tor подключается дольше обычного. Возможно, ваша сеть блокирует Tor "
                        + "или интернет медленный. Продолжаю пытаться…", C_TOR);
            }
        }

        @Override
        public void onReady(int socksPort) {
            torReady = true;
            torFailed = false;
            showBanner("🧅 Tor подключён: провайдер не видит, куда вы ходите", C_TOR);
            container.postDelayed(() -> { if (torReady) banner.setVisibility(View.GONE); }, 2500);
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
        public void onOff() {
            if (torEnabled) return;
            torReady = true;
            if (reloadWhenRouted) {
                showBanner("Tor выключен — прямое соединение", C_PANEL);
                container.postDelayed(() -> { if (!torEnabled) banner.setVisibility(View.GONE); }, 2000);
            } else {
                banner.setVisibility(View.GONE);
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
        current = t;
        container.removeAllViews();
        container.addView(t.view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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
        String mode = torEnabled
                ? "<span style='color:#9B6BFF'>🧅 Tor включён — провайдер не видит, куда вы ходите</span>"
                : "<span style='color:#8A94A3'>Tor выключен — нажмите 🧅 вверху, чтобы включить</span>";
        return "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{background:#0E1116;color:#E8ECF1;font-family:sans-serif;margin:0;"
                + "display:flex;flex-direction:column;align-items:center;justify-content:center;min-height:90vh;"
                + "text-align:center;padding:0 24px}h1{font-size:40px;margin:0 0 4px;letter-spacing:1px}"
                + "h1 b{color:#2BD4A4}p{color:#8A94A3;line-height:1.5;margin:6px 0}"
                + "ul{list-style:none;padding:0;color:#8A94A3;font-size:14px;line-height:1.9}</style></head><body>"
                + "<h1><b>Max</b>clean</h1><p>Ничего не сохраняется. Ни аккаунтов, ни истории, ни следов.</p>"
                + "<p>" + mode + "</p><ul>"
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
            if (!blocker.shouldBlock(uri)) return null;
            if (!request.isForMainFrame()) {
                blocker.countBlocked();
                return AdBlocker.emptyResponse();
            }
            // A whole page on a tracker domain (e.g. an ad click redirect).
            String page = "<html><body style='background:#0E1116;color:#E8ECF1;font-family:sans-serif;padding:24px'>"
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
            progress.setProgress(p);
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
        m.getMenu().add(0, 9, 0, "🔥 Стереть всё и выйти");
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: newTab(null); break;
                case 2: closeTab(current); break;
                case 3: reloadCurrent(); break;
                case 4: if (current.view.canGoForward()) current.view.goForward(); break;
                case 5: toggleTor(); break;
                case 6: chooseEngine(); break;
                case 7: startActivity(new Intent(this, SettingsActivity.class)); break;
                case 8: startActivity(new Intent(this, CodeActivity.class)); break;
                case 9: wipeAndExit(); break;
            }
            return true;
        });
        m.show();
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
