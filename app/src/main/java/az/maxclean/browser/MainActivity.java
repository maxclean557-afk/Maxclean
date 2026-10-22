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
    private FrameLayout appFrame;
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
        /** Small picture for the Full tab cards, in memory only. */
        Bitmap thumb;
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
        if (closeOverlay != null) {
            closeOverlay.run();
            return;
        }
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

        View content = BuildConfig.FULL ? fullLayout(root, bar) : root;
        appFrame = new FrameLayout(this);
        appFrame.addView(content);
        if (Palette.animations(this) && !splashShown) {
            splashShown = true;
            appFrame.addView(buildSplash(appFrame));
        }
        setContentView(appFrame);
        Palette.applyFont(this, appFrame);
        if (BuildConfig.FULL && !Prefs.get(this).getBoolean(Prefs.ONBOARDED, false)) {
            appFrame.postDelayed(this::showIntro, Palette.animations(this) ? 1500 : 0);
        }
        updateEngineButton();
    }

    // ---------------------------------------------------------------- Full edition interface

    /** The open sheet or overlay, closed by Back. */
    private Runnable closeOverlay;

    private GradientDrawable rounded(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private int fieldColor() {
        return pal.field;
    }

    /**
     * Shows a panel over a dimmed page. Bottom panels slide up with a little spring; the
     * returned action slides it away again.
     */
    private Runnable overlay(View panel, boolean fullHeight) {
        FrameLayout layer = new FrameLayout(this);
        View scrim = new View(this);
        scrim.setBackgroundColor(0x99000000);
        layer.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                fullHeight ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        if (fullHeight) lp.topMargin = dp(48);
        layer.addView(panel, lp);
        Palette.applyFont(this, layer);
        appFrame.addView(layer);
        boolean anim = Palette.animations(this);
        Runnable[] close = new Runnable[1];
        close[0] = () -> {
            if (closeOverlay == close[0]) closeOverlay = null;
            if (!anim) {
                appFrame.removeView(layer);
                return;
            }
            scrim.animate().alpha(0f).setDuration(200).start();
            panel.animate().translationY(panel.getHeight()).setDuration(220)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .withEndAction(() -> appFrame.removeView(layer)).start();
        };
        scrim.setOnClickListener(v -> close[0].run());
        closeOverlay = close[0];
        if (anim) {
            scrim.setAlpha(0f);
            scrim.animate().alpha(1f).setDuration(220).start();
            panel.setVisibility(View.INVISIBLE);
            panel.post(() -> {
                panel.setTranslationY(panel.getHeight());
                panel.setVisibility(View.VISIBLE);
                panel.animate().translationY(0).setDuration(380)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(0.9f)).start();
            });
        }
        return close[0];
    }

    private LinearLayout sheetPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_PANEL);
        float r = dp(28);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        panel.setBackground(bg);
        panel.setClickable(true);
        panel.setPadding(dp(18), dp(10), dp(18), dp(22));
        View handle = new View(this);
        handle.setBackground(rounded(C_MUTED, dp(3)));
        handle.setAlpha(0.5f);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(dp(40), dp(5));
        hl.gravity = Gravity.CENTER_HORIZONTAL;
        hl.bottomMargin = dp(16);
        panel.addView(handle, hl);
        return panel;
    }

    /** Full: the menu as a sheet: four big actions on top, the rest as a list. */
    private void showSheetMenu() {
        LinearLayout panel = sheetPanel();
        Runnable[] close = new Runnable[1];

        LinearLayout tiles = new LinearLayout(this);
        tiles.setOrientation(LinearLayout.HORIZONTAL);
        Object[][] big = {
                {"＋", "Вкладка", (Runnable) () -> newTab(null), false},
                {"🧅", torEnabled ? "Tor вкл" : "Tor выкл", (Runnable) this::toggleTor, torEnabled},
                {"🔎", "Поиск", (Runnable) this::chooseEngine, false},
                {"🔥", "Стереть", (Runnable) this::wipeAndExit, false},
        };
        for (Object[] b : big) {
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            boolean active = (Boolean) b[3];
            tile.setBackground(rounded(active ? C_ACCENT : fieldColor(), dp(20)));
            tile.setPadding(0, dp(14), 0, dp(12));
            TextView icon = new TextView(this);
            icon.setText((String) b[0]);
            icon.setTextSize(24);
            icon.setTextColor(active ? pal.onAccent : C_TEXT);
            icon.setGravity(Gravity.CENTER);
            TextView label = new TextView(this);
            label.setText((String) b[1]);
            label.setTextSize(12);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setTextColor(active ? pal.onAccent : C_TEXT);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, dp(6), 0, 0);
            tile.addView(icon);
            tile.addView(label);
            Runnable action = (Runnable) b[2];
            tile.setOnClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                close[0].run();
                action.run();
            });
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            tl.setMargins(dp(5), 0, dp(5), 0);
            tiles.addView(tile, tl);
        }
        panel.addView(tiles);

        Object[][] rows = {
                {"↻   Обновить", (Runnable) this::reloadCurrent},
                {"→   Вперёд", (Runnable) () -> { if (current.view.canGoForward()) current.view.goForward(); }},
                {"✕   Закрыть вкладку (стереть)", (Runnable) () -> closeTab(current)},
                {"🔍  Проверка утечек", (Runnable) this::showLeakTests},
                {"⚙   Настройки", (Runnable) () -> openScreen(SettingsActivity.class)},
                {"</>  Код браузера", (Runnable) () -> openScreen(CodeActivity.class)},
        };
        View gap = new View(this);
        panel.addView(gap, new LinearLayout.LayoutParams(1, dp(12)));
        TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        for (Object[] r : rows) {
            TextView row = new TextView(this);
            row.setText((String) r[0]);
            row.setTextSize(16);
            row.setTextColor(C_TEXT);
            row.setPadding(dp(10), dp(14), dp(10), dp(14));
            row.setBackgroundResource(ripple.resourceId);
            Runnable action = (Runnable) r[1];
            row.setOnClickListener(v -> {
                close[0].run();
                action.run();
            });
            panel.addView(row);
        }
        close[0] = overlay(panel, false);
        panel.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
    }

    /** Captures a small picture of a tab for the tab cards. Kept in memory only. */
    private void snapshot(Tab t) {
        if (t == null || t.view == null || t.view.getWidth() == 0) return;
        try {
            float s = 0.35f;
            Bitmap b = Bitmap.createBitmap(Math.max(1, (int) (t.view.getWidth() * s)),
                    Math.max(1, (int) (t.view.getHeight() * s)), Bitmap.Config.RGB_565);
            android.graphics.Canvas c = new android.graphics.Canvas(b);
            c.scale(s, s);
            c.translate(-t.view.getScrollX(), -t.view.getScrollY());
            t.view.draw(c);
            if (t.thumb != null) t.thumb.recycle();
            t.thumb = b;
        } catch (RuntimeException | OutOfMemoryError ignored) {
            // No picture: the card shows the title only.
        }
    }

    /** Full: tabs as cards. Tap opens, swipe sideways closes and wipes. */
    private void showTabCards() {
        snapshot(current);
        LinearLayout panel = sheetPanel();
        Runnable[] close = new Runnable[1];

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Вкладки · " + tabs.size());
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(C_TEXT);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView add = new TextView(this);
        add.setText("＋  Новая");
        add.setTextColor(pal.onAccent);
        add.setTypeface(Typeface.DEFAULT_BOLD);
        add.setPadding(dp(16), dp(10), dp(16), dp(10));
        add.setBackground(rounded(C_ACCENT, dp(18)));
        add.setOnClickListener(v -> {
            close[0].run();
            newTab(null);
        });
        head.addView(add);
        panel.addView(head);

        TextView hint = new TextView(this);
        hint.setText("Смахните карточку в сторону — вкладка закроется, всё её стирается.");
        hint.setTextColor(C_MUTED);
        hint.setTextSize(12);
        hint.setPadding(0, dp(6), 0, dp(12));
        panel.addView(hint);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        android.widget.GridLayout grid = new android.widget.GridLayout(this);
        grid.setColumnCount(2);
        scroll.addView(grid);
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        int cardW = (getResources().getDisplayMetrics().widthPixels - dp(36) - dp(20)) / 2;
        for (Tab t : new ArrayList<>(tabs)) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(rounded(fieldColor(), dp(18)));
            card.setClipToOutline(true);
            if (t == current) {
                GradientDrawable sel = rounded(fieldColor(), dp(18));
                sel.setStroke(dp(2), C_ACCENT);
                card.setBackground(sel);
            }
            android.widget.ImageView pic = new android.widget.ImageView(this);
            pic.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            pic.setBackgroundColor(C_BG);
            if (t.thumb != null) pic.setImageBitmap(t.thumb);
            card.addView(pic, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (cardW * 1.25f)));
            TextView name = new TextView(this);
            name.setText(t.title);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setTextColor(C_TEXT);
            name.setTextSize(13);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setPadding(dp(12), dp(10), dp(12), dp(12));
            card.addView(name);
            android.widget.GridLayout.LayoutParams gl = new android.widget.GridLayout.LayoutParams();
            gl.width = cardW;
            gl.setMargins(dp(5), dp(5), dp(5), dp(5));
            grid.addView(card, gl);

            float[] start = new float[2];
            boolean[] moved = new boolean[1];
            card.setOnTouchListener((v, e) -> {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        start[0] = e.getRawX();
                        start[1] = e.getRawY();
                        moved[0] = false;
                        v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(100).start();
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - start[0];
                        if (Math.abs(dx) > dp(8)) {
                            moved[0] = true;
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        if (moved[0]) {
                            v.setTranslationX(dx);
                            v.setAlpha(1f - Math.min(0.8f, Math.abs(dx) / v.getWidth()));
                        }
                        return true;
                    }
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL: {
                        float dx = e.getRawX() - start[0];
                        if (moved[0] && Math.abs(dx) > v.getWidth() / 3f) {
                            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                            v.animate().translationX(Math.signum(dx) * v.getWidth() * 1.5f).alpha(0f).setDuration(180)
                                    .withEndAction(() -> {
                                        closeTab(t);
                                        close[0].run();
                                        if (tabs.size() > 1) showTabCards();
                                    }).start();
                        } else if (!moved[0] && e.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                            v.animate().scaleX(1.06f).scaleY(1.06f).alpha(0f).setDuration(200)
                                    .withEndAction(() -> {
                                        close[0].run();
                                        switchTo(t);
                                    }).start();
                        } else {
                            v.animate().translationX(0).alpha(1f).scaleX(1f).scaleY(1f).setDuration(260)
                                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f)).start();
                        }
                        return true;
                    }
                    default:
                        return false;
                }
            });
        }
        close[0] = overlay(panel, true);
    }

    /** Full: three short screens on first launch about what Maxclean does. */
    private void showIntro() {
        String[][] pages = {
                {"🧅", "Tor внутри", "Ваш трафик идёт через три случайных узла сети Tor. Провайдер не видит, какие сайты вы открываете, а сайты не видят ваш адрес."},
                {"🧹", "Ничего не остаётся", "Закрыли вкладку — cookies, история и кэш стёрты. Ни аккаунтов, ни статистики, ни связи с разработчиком."},
                {"🛡", "Без рекламы и слежки", "Реклама, трекеры и майнеры блокируются ещё до загрузки. Каждый фильтр можно включить или выключить в настройках."},
        };
        FrameLayout layer = new FrameLayout(this);
        layer.setBackgroundColor(C_BG);
        layer.setClickable(true);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(32), 0, dp(32), 0);
        TextView glyph = new TextView(this);
        glyph.setTextSize(72);
        glyph.setGravity(Gravity.CENTER);
        TextView head = new TextView(this);
        head.setTextSize(30);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setTextColor(C_TEXT);
        head.setGravity(Gravity.CENTER);
        head.setPadding(0, dp(18), 0, dp(12));
        TextView body = new TextView(this);
        body.setTextSize(16);
        body.setTextColor(C_MUTED);
        body.setGravity(Gravity.CENTER);
        body.setLineSpacing(0, 1.3f);
        box.addView(glyph);
        box.addView(head);
        box.addView(body);
        layer.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        LinearLayout dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER);
        View[] dotViews = new View[pages.length];
        for (int i = 0; i < pages.length; i++) {
            View d = new View(this);
            dotViews[i] = d;
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(8), dp(8));
            dl.setMargins(dp(4), 0, dp(4), 0);
            dots.addView(d, dl);
        }
        TextView next = new TextView(this);
        next.setTextColor(pal.onAccent);
        next.setTextSize(16);
        next.setTypeface(Typeface.DEFAULT_BOLD);
        next.setGravity(Gravity.CENTER);
        next.setBackground(rounded(C_ACCENT, dp(20)));
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.addView(dots);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        nl.setMargins(dp(24), dp(22), dp(24), dp(28));
        bottom.addView(next, nl);
        layer.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        Palette.applyFont(this, layer);
        appFrame.addView(layer);

        int[] page = {0};
        Runnable show = new Runnable() {
            @Override
            public void run() {
                String[] p = pages[page[0]];
                glyph.setText(p[0]);
                head.setText(p[1]);
                body.setText(p[2]);
                next.setText(page[0] == pages.length - 1 ? "Начать" : "Далее");
                for (int i = 0; i < dotViews.length; i++) {
                    dotViews[i].setBackground(rounded(i == page[0] ? C_ACCENT : C_MUTED, dp(4)));
                    dotViews[i].setAlpha(i == page[0] ? 1f : 0.4f);
                }
                if (Palette.animations(MainActivity.this)) {
                    box.setAlpha(0f);
                    box.setTranslationX(dp(40));
                    box.animate().alpha(1f).translationX(0).setDuration(320)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f)).start();
                    glyph.setScaleX(0.5f);
                    glyph.setScaleY(0.5f);
                    glyph.animate().scaleX(1f).scaleY(1f).setDuration(480)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
                }
            }
        };
        next.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            if (page[0] < pages.length - 1) {
                page[0]++;
                show.run();
                return;
            }
            Prefs.get(this).edit().putBoolean(Prefs.ONBOARDED, true).apply();
            layer.animate().alpha(0f).setDuration(300).withEndAction(() -> appFrame.removeView(layer)).start();
        });
        show.run();
    }

    /**
     * Full: the page fills the screen, the address bar floats at the bottom as a rounded card
     * (easy to reach with a thumb), and messages float at the top.
     */
    private View fullLayout(LinearLayout root, LinearLayout bar) {
        root.removeAllViews();
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(C_BG);

        FrameLayout.LayoutParams pageLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        pageLp.bottomMargin = dp(84);
        frame.addView(container, pageLp);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(C_PANEL);
        cardBg.setCornerRadius(dp(26));
        cardBg.setStroke(1, pal.dark ? 0x22FFFFFF : 0x14000000);
        card.setBackground(cardBg);
        card.setElevation(dp(14));
        card.setClipToOutline(true);
        card.setPadding(dp(6), dp(4), dp(6), dp(4));
        bar.setBackgroundColor(Color.TRANSPARENT);
        progress.setVisibility(View.INVISIBLE);
        card.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));
        card.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        cardLp.setMargins(dp(12), 0, dp(12), dp(12));
        frame.addView(card, cardLp);

        GradientDrawable bannerBg = new GradientDrawable();
        bannerBg.setCornerRadius(dp(16));
        banner.setBackground(bannerBg);
        banner.setElevation(dp(10));
        banner.setPadding(dp(16), dp(12), dp(16), dp(12));
        FrameLayout.LayoutParams bannerLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        bannerLp.setMargins(dp(12), dp(12), dp(12), 0);
        frame.addView(banner, bannerLp);

        // A light tap of feedback on every bar button.
        for (int i = 0; i < bar.getChildCount(); i++) {
            View b = bar.getChildAt(i);
            if (b instanceof TextView && !(b instanceof EditText)) {
                b.setOnTouchListener((v, e) -> {
                    if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                        if (Palette.animations(this)) v.animate().scaleX(0.86f).scaleY(0.86f).setDuration(90).start();
                    } else if (e.getAction() == android.view.MotionEvent.ACTION_UP
                            || e.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(180)
                                .setInterpolator(new android.view.animation.OvershootInterpolator(3f)).start();
                    }
                    return false;
                });
            }
        }
        return frame;
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
        if (banner.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) banner.getBackground()).setColor(color);
        } else {
            banner.setBackgroundColor(color);
        }
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
        if (BuildConfig.FULL && current != null && current != t) snapshot(current);
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
        if (t.thumb != null) {
            t.thumb.recycle();
            t.thumb = null;
        }
        if (t.view == null) return;
        container.removeView(t.view);
        Wiper.wipeWebView(this, t.view);
        t.view.loadUrl("about:blank");
        t.view.destroy();
        t.view = null;
    }

    private void showTabs() {
        if (BuildConfig.FULL) {
            showTabCards();
            return;
        }
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

    private static String fontDataUri;

    /** Full: the living start page from assets/home.html, filled in with this session's values. */
    private String fullHomeHtml() {
        String tpl = readAsset("home.html");
        if (tpl.isEmpty()) return "";
        if (fontDataUri == null) {
            byte[] font = readAssetBytes("fonts/Manrope.ttf");
            fontDataUri = font.length == 0 ? "''"
                    : "data:font/ttf;base64," + Base64.encodeToString(font, Base64.NO_WRAP);
        }
        StringBuilder tiles = new StringBuilder();
        for (String[] link : QuickLinks.list(this)) {
            String name = android.text.TextUtils.htmlEncode(link[0]);
            String letter = android.text.TextUtils.htmlEncode(link[0].substring(0, 1).toUpperCase());
            tiles.append("<a class='tile' href='").append(android.text.TextUtils.htmlEncode(link[1]))
                    .append("'><b>").append(letter).append("</b><span>").append(name).append("</span></a>");
        }
        java.util.Date now = new java.util.Date();
        boolean bridges = !Prefs.BRIDGES_NONE.equals(Prefs.str(this, Prefs.BRIDGES, Prefs.BRIDGES_NONE));
        String torText = !torEnabled ? "Tor выключен — нажмите 🧅"
                : !torReady ? "Подключаюсь к Tor…"
                : "Tor включён · 3 узла" + (bridges ? " · мосты" : "");
        return tpl.replace("{{FONT}}", fontDataUri)
                .replace("{{BG}}", Palette.hex(C_BG)).replace("{{TEXT}}", Palette.hex(C_TEXT))
                .replace("{{MUTED}}", Palette.hex(C_MUTED)).replace("{{ACCENT}}", Palette.hex(C_ACCENT))
                .replace("{{TOR}}", Palette.hex(C_TOR))
                .replace("{{GLASS}}", pal.dark ? "rgba(255,255,255,.07)" : "rgba(255,255,255,.62)")
                .replace("{{LINE}}", pal.dark ? "rgba(255,255,255,.12)" : "rgba(20,23,28,.09)")
                .replace("{{TORCLASS}}", torEnabled && torReady ? "on" : "")
                .replace("{{TORTEXT}}", torText)
                .replace("{{TORON}}", torEnabled && torReady ? "true" : "false")
                .replace("{{TIME}}", new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(now))
                .replace("{{DATE}}", new java.text.SimpleDateFormat("EEEE, d MMMM", new java.util.Locale("ru")).format(now))
                .replace("{{TILES}}", tiles.toString());
    }

    private String homeHtml() {
        if (BuildConfig.FULL) {
            String full = fullHomeHtml();
            if (!full.isEmpty()) return full;
        }
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
            if (scheme.equals("maxclean") && "search".equals(uri.getHost())) {
                // The search box on the Full start page: search with the chosen engine.
                String q = uri.getQueryParameter("q");
                if (q != null && !q.trim().isEmpty()) submit(q);
                return true;
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
            progress.setVisibility(p >= 100 ? (BuildConfig.FULL ? View.INVISIBLE : View.GONE) : View.VISIBLE);
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
        if (BuildConfig.FULL) {
            showSheetMenu();
            return;
        }
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

    private byte[] readAssetBytes(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
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
