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
    private Palette pal;
    private int C_TEXT;
    private int C_MUTED;
    private int C_ACCENT;

    private LinearLayout list;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        pal = Palette.of(this);
        pal.applyTo(this);
        C_TEXT = pal.text; C_MUTED = pal.muted; C_ACCENT = pal.accent;
        super.onCreate(savedInstanceState);
        prefs = Prefs.get(this);

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(12), dp(16), dp(32));
        scroll.addView(list);
        scroll.setBackgroundColor(pal.bg);
        setContentView(scroll);
        scroll.post(() -> Palette.applyFont(this, scroll));

        title(BuildConfig.FULL ? "Настройки Maxclean Full" : "Настройки Maxclean");

        header("⚡ Режим защиты");
        boolean max = !Prefs.on(this, Prefs.JAVASCRIPT) && !Prefs.on(this, Prefs.COOKIES);
        hint(max ? "Сейчас: максимальная защита." : "Сейчас: обычный режим — сайты работают как обычно.");
        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        presets.setPadding(0, dp(8), 0, 0);
        presets.addView(presetButton("🛡 Максимальная", true, max),
                new LinearLayout.LayoutParams(0, dp(48), 1));
        View gap = new View(this);
        presets.addView(gap, new LinearLayout.LayoutParams(dp(10), 1));
        presets.addView(presetButton("↺ Обычный", false, !max),
                new LinearLayout.LayoutParams(0, dp(48), 1));
        list.addView(presets);

        if (BuildConfig.FULL) {
            header("🎨 Оформление");
            hint("Главный экран меняется сразу. Панели и кнопки браузера — при следующем запуске.");
            choice(Prefs.THEME, "system", Palette.THEMES);
            label("Цвет акцента");
            String[][] accents = new String[Palette.ACCENTS.length + 1][];
            for (int i = 0; i < Palette.ACCENTS.length; i++) {
                String[] a = Palette.ACCENTS[i];
                accents[i] = new String[]{a[0], a[1], a[2], a[2]};
            }
            accents[accents.length - 1] = new String[]{Palette.CUSTOM, "Свой", "", ""};
            swatches(Prefs.ACCENT, "mint", accents);
            text(Prefs.ACCENT_CUSTOM, "Свой цвет акцента", "HEX, например #FF6B00 — когда выбран «Свой»", "", false);

            header("🏠 Главный экран");
            hint("Настройте как хочется: фон, цвета, часы, поиск. Вернитесь на главный экран — всё уже там.");
            label("Фон");
            choice(Prefs.HOME_SCENE, "orbs", HomeStyle.SCENES);
            label("Цвета свечения");
            swatches(Prefs.HOME_GLOW, "accent", HomeStyle.GLOWS);
            text(Prefs.HOME_GLOW1, "Свой первый цвет", "HEX, например #FF2BD6 — когда выбраны «Свои цвета»", "", false);
            text(Prefs.HOME_GLOW2, "Свой второй цвет", "HEX, например #00F0FF", "", false);
            label("Основа фона");
            String[][] bases = new String[HomeStyle.BASES.length][];
            for (int i = 0; i < bases.length; i++) {
                String[] b = HomeStyle.BASES[i];
                bases[i] = new String[]{b[0], b[1], b[2], b[2]};
            }
            swatches(Prefs.HOME_BASE, "theme", bases);
            text(Prefs.HOME_BASE_CUSTOM, "Свой цвет основы", "HEX, например #101820 — когда выбран «Свой цвет»", "", false);
            label("Часы");
            choice(Prefs.HOME_CLOCK, "big", HomeStyle.CLOCKS);
            label("Строка поиска");
            choice(Prefs.HOME_SEARCH, "rounded", HomeStyle.SEARCHES);
            toggle(Prefs.HOME_GLOBE, "Глобус сети Tor", "Вращается, когда Tor включён — рисует путь через 3 узла");
            toggle(Prefs.HOME_DATE, "Дата", "День недели и число под часами");
            toggle(Prefs.HOME_TILES, "Плитки сайтов", "Быстрые ссылки под строкой поиска");
            header("🖼 Иконка приложения");
            hint("Рабочий стол может обновить иконку через несколько секунд.");
            String[][] icons = new String[Icons.ICONS.length][];
            for (int i = 0; i < icons.length; i++) {
                icons[i] = new String[]{Icons.ICONS[i][0], Icons.ICONS[i][1], Icons.ICONS[i][2]};
            }
            choice(Prefs.ICON, "classic", icons, v -> Icons.apply(this, v));
            toggle(Prefs.ANIMATIONS, "Анимации", "Заставка, плавные вкладки, меню и баннеры");
            header("⭐ Плитки на стартовой странице");
            multilineDefault(Prefs.QUICK_LINKS, QuickLinks.DEFAULT, "Свои сайты, по одному в строке: Название | адрес",
                    "До 8 плиток. Это единственное, что браузер хранит о сайтах, и только потому, что вы сами их вписали.",
                    "Новости | https://example.com");
        } else {
            hint("Хотите темы, цвета, иконки и анимации? Скачайте Maxclean Full со страницы релизов "
                    + "на GitHub: она ставится поверх этой версии, настройки сохранятся.");
        }

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
                "Получить: bridges.torproject.org или Telegram-бот @GetBridgesBot",
                "obfs4 1.2.3.4:443 FINGERPRINT cert=… iat-mode=0");

        header("🔀 Прокси: xray, sing-box, v2rayNG");
        multiline(Prefs.PROXY_URL, "Цепочка прокси перед Tor, по одному в строке (до 5)",
                "Соединение идёт по очереди: 1-й → 2-й → … → Tor. Без Tor через эту цепочку идёт "
                        + "сам браузер. Каждый прокси замедляет связь, а первый видит ваш настоящий IP: "
                        + "анонимность даёт Tor, прокси нужны, чтобы обойти блокировки.",
                "socks5://127.0.0.1:10808\nhttp://логин:пароль@1.2.3.4:8080");
        text(Prefs.PROXY_AFTER, "Прокси после Tor",
                "Для сайтов, которые не пускают Tor. Осторожно: этот прокси видит всё, что вы "
                        + "открываете, и выходной адрес у вас всегда один — анонимности меньше.", "", false);

        header("🕵 Защита от отпечатка устройства");
        toggle(Prefs.FP_PROTECT, "Скрывать особенности телефона",
                "Одинаковые параметры железа, без данных батареи и сети, общий видеочип, "
                        + "шум в canvas. Снижает слежку по отпечатку, но не убирает её полностью");
        hint("Профиль устройства — каким телефоном браузер представляется сайтам. Все значения "
                + "в профиле согласованы между собой: случайные значения выделяли бы вас из толпы.");
        String[][] profiles = new String[Disguise.PROFILES.length][];
        for (int i = 0; i < profiles.length; i++) profiles[i] = Disguise.PROFILES[i];
        choice(Prefs.DEVICE_PROFILE, "generic", profiles);

        header("📍 Местоположение для сайтов");
        hint("Сайты, которые спрашивают, где вы, получат выбранное место. Страну по IP при этом "
                + "определяет выходной узел Tor, а не эта настройка.");
        String[][] places = new String[Disguise.PLACES.length][];
        for (int i = 0; i < places.length; i++) {
            String[] p = Disguise.PLACES[i];
            places[i] = new String[]{p[0], p[1], p[2].isEmpty()
                    ? (p[0].equals("custom") ? "Впишите ниже: широта, долгота" : "Сайты получат отказ")
                    : p[2] + ", " + p[3]};
        }
        choice(Prefs.FAKE_GEO, "off", places);
        text(Prefs.FAKE_GEO_CUSTOM, "Свои координаты", "Например 41.0082, 28.9784", "", false);

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
        toggle(Prefs.FAKE_TIMEZONE, "Часовой пояс UTC",
                "Сайты не узнают ваш пояс, как в Tor Browser. Время на сайтах будет по UTC");
        toggle(Prefs.GENERIC_UA, "Скрыть модель телефона", "Общий User-Agent как у всех Chrome на Android");
        toggle(Prefs.GPC, "Сигнал «Не отслеживать» (GPC/DNT)", "Законный запрет продавать ваши данные");
        toggle(Prefs.HTTPS_ONLY, "Только HTTPS", "Ссылки http:// открываются как https://");
        toggle(Prefs.NO_SCREENSHOTS, "Запретить скриншоты", "И скрыть превью в списке приложений");

        header("⬆ Обновления");
        toggle(Prefs.UPDATE_CHECK, "Проверять обновления",
                "Раз в сутки спрашивает GitHub через Tor, нет ли новой версии. Скачивание и установка — "
                        + "только по вашему нажатию и только если подпись совпадает с официальной");

        header("🔎 Свой поисковик");
        text(Prefs.CUSTOM_ENGINE, "Адрес поиска", "%s будет заменено запросом, например https://searx.be/search?q=%s",
                "", false);

        header("</> Свой код");
        toggle(Prefs.USER_SCRIPT_ON, "Включить мои скрипты и стили",
                "Редактируются в меню → «Код браузера» → «Мой скрипт»");

        TextView note = new TextView(this);
        note.setText("\nMaxclean не имеет аккаунтов, не отправляет статистику и не связывается с "
                + "разработчиком. Единственный свой запрос — раз в сутки спросить GitHub через Tor, нет ли "
                + "обновления (выключается выше). Настройки — единственное, что хранится на телефоне.\n\n"
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
        sw.setThumbTintList(new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}}, new int[]{C_ACCENT, C_MUTED}));
        sw.setChecked(Prefs.on(this, key));
        sw.setOnCheckedChangeListener((b, checked) -> prefs.edit().putBoolean(key, checked).apply());
        row.addView(sw);
        row.setOnClickListener(v -> sw.toggle());
        list.addView(row);
    }

    private TextView presetButton(String text, boolean max, boolean active) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(14));
        if (active) {
            bg.setColor(C_ACCENT);
            b.setTextColor(pal.onAccent);
        } else {
            bg.setColor(pal.panel);
            bg.setStroke(dp(1), C_MUTED & 0x55FFFFFF);
            b.setTextColor(C_TEXT);
        }
        b.setBackground(bg);
        b.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setTitle(max ? "Максимальная защита" : "Обычный режим")
                .setMessage(max
                        ? "Включится всё сразу: Tor, все блокировки, защита от отпечатка, часовой пояс UTC, "
                        + "только HTTPS, без WebRTC и скриншотов, сайтам — отказ в местоположении.\n\n"
                        + "Выключатся JavaScript и cookies. Так безопаснее всего, но многие сайты "
                        + "не будут работать, а войти в аккаунт не получится."
                        : "JavaScript и cookies снова включатся, сайты будут работать как обычно. "
                        + "Tor, блокировщик и остальная защита останутся как есть.")
                .setPositiveButton("Включить", (d, w) -> {
                    applyPreset(max);
                    recreate();
                })
                .setNegativeButton("Отмена", null)
                .show());
        return b;
    }

    private void applyPreset(boolean max) {
        SharedPreferences.Editor e = prefs.edit();
        e.putBoolean(Prefs.JAVASCRIPT, !max).putBoolean(Prefs.COOKIES, !max);
        if (max) {
            for (String k : new String[]{Prefs.TOR, Prefs.FP_PROTECT, Prefs.FAKE_TIMEZONE, Prefs.BLOCK_ADS,
                    Prefs.BLOCK_TRACKERS, Prefs.BLOCK_SOCIAL, Prefs.BLOCK_MINERS, Prefs.BLOCK_POPUPS,
                    Prefs.HIDE_AD_BLOCKS, Prefs.HIDE_COOKIE_BANNERS, Prefs.BLOCK_AUTOPLAY, Prefs.DISABLE_WEBRTC,
                    Prefs.GENERIC_UA, Prefs.GPC, Prefs.HTTPS_ONLY, Prefs.NO_SCREENSHOTS}) {
                e.putBoolean(k, true);
            }
            e.putBoolean(Prefs.THIRD_PARTY_COOKIES, false).putBoolean(Prefs.USER_SCRIPT_ON, false);
            e.putString(Prefs.FAKE_GEO, "off").putString(Prefs.DEVICE_PROFILE, "generic");
        }
        e.apply();
    }

    private void label(String s) {
        TextView l = new TextView(this);
        l.setText(s);
        l.setTextColor(C_TEXT);
        l.setTextSize(16);
        l.setTypeface(Typeface.DEFAULT_BOLD);
        l.setPadding(0, dp(14), 0, dp(4));
        list.addView(l);
    }

    /**
     * Colour circles, four in a row: {value, label, colour 1, colour 2}. Empty colours: "accent"
     * shows the accent and Tor colours, "theme" the theme's background, "custom" a rainbow.
     */
    private void swatches(String key, String def, String[][] options) {
        String current = prefs.getString(key, def);
        java.util.List<View> rings = new java.util.ArrayList<>();
        LinearLayout row = null;
        for (int i = 0; i < options.length; i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, dp(6), 0, dp(6));
                list.addView(row);
            }
            String[] o = options[i];
            int[] colors;
            if (!o[2].isEmpty()) {
                colors = new int[]{Color.parseColor(o[2]), Color.parseColor(o[3])};
            } else if (o[0].equals(Palette.CUSTOM)) {
                colors = new int[]{0xFFFF4D4F, 0xFFF5C542, 0xFF2BD4A4, 0xFF3D8BFF, 0xFF9B6BFF, 0xFFFF4D4F};
            } else if (o[0].equals("theme")) {
                colors = new int[]{pal.bg, pal.panel};
            } else {
                colors = new int[]{pal.accent, pal.tor};
            }
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            View dot = new View(this);
            android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable(
                    colors.length > 2 ? android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT
                            : android.graphics.drawable.GradientDrawable.Orientation.TL_BR, colors);
            d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dot.setBackground(d);
            dot.setTag(o[0]);
            cell.addView(dot, new LinearLayout.LayoutParams(dp(46), dp(46)));
            TextView name = new TextView(this);
            name.setText(o[1]);
            name.setTextColor(C_MUTED);
            name.setTextSize(11.5f);
            name.setGravity(Gravity.CENTER);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setPadding(0, dp(5), 0, 0);
            cell.addView(name);
            rings.add(dot);
            String value = o[0];
            cell.setOnClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                prefs.edit().putString(key, value).apply();
                markSwatches(rings, value);
            });
            row.addView(cell, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        }
        // Keep the last row's circles the same width as the full rows.
        int rest = options.length % 4;
        if (rest != 0) {
            for (int i = rest; i < 4; i++) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        }
        markSwatches(rings, current);
    }

    private void markSwatches(java.util.List<View> rings, String selected) {
        for (View dot : rings) {
            android.graphics.drawable.GradientDrawable d = (android.graphics.drawable.GradientDrawable) dot.getBackground();
            boolean on = selected.equals(dot.getTag());
            // The text colour stands out on every swatch, the accent one included.
            d.setStroke(on ? dp(3) : dp(1), on ? C_TEXT : (C_MUTED & 0x55FFFFFF));
        }
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
        choice(key, def, options, null);
    }

    private void choice(String key, String def, String[][] options, java.util.function.Consumer<String> onPick) {
        RadioGroup group = new RadioGroup(this);
        String current = prefs.getString(key, def);
        for (int i = 0; i < options.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(options[i][1] + "\n" + options[i][2]);
            rb.setTextColor(C_TEXT);
            rb.setButtonTintList(android.content.res.ColorStateList.valueOf(C_ACCENT));
            rb.setTextSize(15);
            rb.setPadding(dp(8), dp(6), 0, dp(6));
            String value = options[i][0];
            group.addView(rb);
            if (value.equals(current)) rb.setChecked(true);
            rb.setOnCheckedChangeListener((b, checked) -> {
                if (!checked) return;
                prefs.edit().putString(key, value).apply();
                if (onPick != null) onPick.accept(value);
            });
        }
        list.addView(group);
    }

    private void multiline(String key, String label, String hint, String example) {
        multilineDefault(key, "", label, hint, example);
    }

    private void multilineDefault(String key, String def, String label, String hint, String example) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(C_TEXT);
        l.setTextSize(16);
        l.setPadding(0, dp(8), 0, 0);
        list.addView(l);

        EditText e = new EditText(this);
        e.setText(prefs.getString(key, def));
        e.setTextColor(C_TEXT);
        e.setTextSize(12);
        e.setTypeface(Typeface.MONOSPACE);
        e.setHint(example);
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

    @Override
    public void finish() {
        super.finish();
        if (Palette.animations(this)) overridePendingTransition(R.anim.mc_return, R.anim.mc_leave);
    }
}
