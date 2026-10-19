package az.maxclean.browser;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Every protection is a switch, so the user decides what is blocked and what is allowed.
 */
public class SettingsActivity extends Activity {
    private static final int C_TEXT = Color.parseColor("#E8ECF1");
    private static final int C_MUTED = Color.parseColor("#8A94A3");
    private static final int C_ACCENT = Color.parseColor("#2BD4A4");

    private LinearLayout list;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.get(this);

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(12), dp(16), dp(32));
        scroll.addView(list);
        setContentView(scroll);

        title("Настройки Maxclean");

        header("🧅 Tor (луковая маршрутизация)");
        toggle(Prefs.TOR, "Шифровать трафик через Tor",
                "Tor встроен в Maxclean. Провайдер видит только, что вы используете Tor, но не сайты. "
                        + "Медленнее. Пока Tor подключается — ничего не грузится (защита от утечки).");

        header("🌉 Мосты Tor");
        hint("Если Tor заблокирован в вашей стране или сети, мосты маскируют подключение к нему.");
        choice(Prefs.BRIDGES, Prefs.BRIDGES_NONE, new String[][]{
                {Prefs.BRIDGES_NONE, "Без мостов", "Быстрее всего, если Tor у вас не блокируют"},
                {Prefs.BRIDGES_SNOWFLAKE, "Snowflake", "Встроенные мосты Tor Project, работают почти везде"},
                {Prefs.BRIDGES_OBFS4, "obfs4", "Встроенные мосты Tor Project"},
                {Prefs.BRIDGES_CUSTOM, "Свои мосты", "obfs4, WebTunnel или Snowflake — вставьте строки ниже"},
        });
        multiline(Prefs.BRIDGE_LINES, "Свои мосты, по одному в строке",
                "Получить: bridges.torproject.org или Telegram-бот @GetBridgesBot");

        header("🔀 Свой прокси (xray, sing-box, v2rayNG)");
        text(Prefs.PROXY_URL, "Адрес прокси",
                "Например socks5://127.0.0.1:10808. С Tor — Tor пойдёт через этот прокси. "
                        + "Без Tor — браузер пойдёт через него. Пусто — не используется.", "", false);

        header("🕵 Защита от отпечатка устройства");
        toggle(Prefs.FP_PROTECT, "Скрывать особенности телефона",
                "Одинаковые параметры железа, без данных батареи и сети, общий видеочип, "
                        + "шум в canvas. Снижает слежку по отпечатку, но не убирает её полностью");

        header("🛡 Блокировщик рекламы");
        toggle(Prefs.BLOCK_ADS, "Рекламные сети", "Google Ads, Яндекс.Директ, Criteo, Taboola и др.");
        toggle(Prefs.BLOCK_TRACKERS, "Трекеры и аналитика", "Google Analytics, Метрика, Hotjar, отпечатки браузера");
        toggle(Prefs.BLOCK_SOCIAL, "Соцсети-шпионы", "Пиксели и виджеты Facebook, VK, TikTok, Twitter");
        toggle(Prefs.BLOCK_MINERS, "Крипто-майнеры", "Скрипты, которые майнят на вашем телефоне");
        toggle(Prefs.BLOCK_POPUPS, "Всплывающие окна", "Окна, открытые без вашего нажатия");
        toggle(Prefs.HIDE_AD_BLOCKS, "Прятать пустые рекламные блоки", "Чтобы не было дыр на странице");
        toggle(Prefs.HIDE_COOKIE_BANNERS, "Прятать баннеры «Мы используем cookies»", "Сайт считает, что вы не согласились");
        toggle(Prefs.BLOCK_AUTOPLAY, "Без автозапуска видео", "Видео играет только после нажатия");

        header("🔒 Приватность");
        toggle(Prefs.COOKIES, "Разрешить cookies", "Нужны для входа на сайты. Стираются при закрытии вкладки");
        toggle(Prefs.THIRD_PARTY_COOKIES, "Сторонние cookies", "Обычно используются только для слежки");
        toggle(Prefs.JAVASCRIPT, "JavaScript", "Без него безопаснее, но многие сайты сломаются");
        toggle(Prefs.DISABLE_WEBRTC, "Отключить WebRTC", "Иначе сайт может узнать ваш настоящий IP даже через Tor");
        toggle(Prefs.GENERIC_UA, "Скрыть модель телефона", "Общий User-Agent как у всех Chrome на Android");
        toggle(Prefs.GPC, "Сигнал «Не отслеживать» (GPC/DNT)", "Законный запрет продавать ваши данные");
        toggle(Prefs.HTTPS_ONLY, "Только HTTPS", "Ссылки http:// открываются как https://");
        toggle(Prefs.NO_SCREENSHOTS, "Запретить скриншоты", "И скрыть превью в списке приложений");

        header("🔎 Свой поисковик");
        text(Prefs.CUSTOM_ENGINE, "Адрес поиска", "%s будет заменено запросом, например https://searx.be/search?q=%s",
                "", false);

        header("</> Свой код");
        toggle(Prefs.USER_SCRIPT_ON, "Включить мои скрипты и стили",
                "Редактируются в меню → «Код браузера» → «Мой скрипт»");

        TextView note = new TextView(this);
        note.setText("\nMaxclean не имеет аккаунтов, не отправляет статистику и не связывается с "
                + "разработчиком. Настройки — единственное, что хранится на телефоне.\n\n"
                + "Maxclean защищает только себя, это не VPN: другие приложения ходят в сеть напрямую. "
                + "Чтобы весь телефон шёл через Tor, используйте Orbot в режиме VPN.");
        note.setTextColor(C_MUTED);
        note.setTextSize(13);
        list.addView(note);
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void title(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(C_TEXT);
        t.setTextSize(22);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(4), 0, dp(8));
        list.addView(t);
    }

    private void header(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(C_ACCENT);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(20), 0, dp(4));
        list.addView(t);
    }

    private void toggle(String key, String label, String hint) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(C_TEXT);
        l.setTextSize(16);
        TextView h = new TextView(this);
        h.setText(hint);
        h.setTextColor(C_MUTED);
        h.setTextSize(13);
        texts.addView(l);
        texts.addView(h);
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Switch sw = new Switch(this);
        sw.setChecked(Prefs.on(this, key));
        sw.setOnCheckedChangeListener((b, checked) -> prefs.edit().putBoolean(key, checked).apply());
        row.addView(sw);
        row.setOnClickListener(v -> sw.toggle());
        list.addView(row);
    }

    private void hint(String s) {
        TextView h = new TextView(this);
        h.setText(s);
        h.setTextColor(C_MUTED);
        h.setTextSize(13);
        list.addView(h);
    }

    /** A single choice among options {value, label, hint}. */
    private void choice(String key, String def, String[][] options) {
        RadioGroup group = new RadioGroup(this);
        String current = prefs.getString(key, def);
        for (int i = 0; i < options.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(options[i][1] + "\n" + options[i][2]);
            rb.setTextColor(C_TEXT);
            rb.setTextSize(15);
            rb.setPadding(dp(8), dp(6), 0, dp(6));
            String value = options[i][0];
            group.addView(rb);
            if (value.equals(current)) rb.setChecked(true);
            rb.setOnCheckedChangeListener((b, checked) -> {
                if (checked) prefs.edit().putString(key, value).apply();
            });
        }
        list.addView(group);
    }

    private void multiline(String key, String label, String hint) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(C_TEXT);
        l.setTextSize(16);
        l.setPadding(0, dp(8), 0, 0);
        list.addView(l);

        EditText e = new EditText(this);
        e.setText(prefs.getString(key, ""));
        e.setTextColor(C_TEXT);
        e.setTextSize(12);
        e.setTypeface(Typeface.MONOSPACE);
        e.setHint("obfs4 1.2.3.4:443 FINGERPRINT cert=… iat-mode=0");
        e.setHintTextColor(C_MUTED);
        e.setMinLines(3);
        e.setGravity(Gravity.TOP | Gravity.START);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                prefs.edit().putString(key, s.toString()).apply();
            }
        });
        list.addView(e);
        hint(hint);
    }

    private void text(String key, String label, String hint, String def, boolean number) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(C_TEXT);
        l.setTextSize(16);
        l.setPadding(0, dp(8), 0, 0);
        list.addView(l);

        EditText e = new EditText(this);
        e.setText(prefs.getString(key, def));
        e.setTextColor(C_TEXT);
        e.setHint(hint);
        e.setHintTextColor(C_MUTED);
        e.setSingleLine(true);
        e.setInputType(number ? InputType.TYPE_CLASS_NUMBER
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e.setOnFocusChangeListener((View v, boolean focus) -> {
            if (!focus) prefs.edit().putString(key, e.getText().toString().trim()).apply();
        });
        e.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                prefs.edit().putString(key, s.toString().trim()).apply();
            }
        });
        list.addView(e);

        TextView h = new TextView(this);
        h.setText(hint);
        h.setTextColor(C_MUTED);
        h.setTextSize(12);
        list.addView(h);
    }
}
