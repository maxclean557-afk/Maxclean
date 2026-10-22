package az.maxclean.browser;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Shows the browser's complete source code (bundled into the app at build time) and lets the
 * user edit their own code that runs inside Maxclean: a JavaScript and a CSS file injected into
 * every page, and their own block list.
 */
public class CodeActivity extends Activity {
    private Palette pal;
    private int C_BG;
    private int C_PANEL;
    private int C_TEXT;
    private int C_MUTED;
    private int C_ACCENT;

    private static final String[][] EDITABLE = {
            {Prefs.USER_JS, "✎ Мой скрипт (JavaScript на каждой странице)",
                    "// Пример: сделать все страницы тёмными\n"
                            + "// document.documentElement.style.filter = 'invert(1) hue-rotate(180deg)';\n"},
            {Prefs.USER_CSS, "✎ Мой стиль (CSS на каждой странице)",
                    "/* Пример: крупный шрифт везде */\n/* body { font-size: 120% !important; } */\n"},
            {Prefs.USER_BLOCKLIST, "✎ Мой список блокировки (домены)",
                    "# Один домен на строку, поддомены тоже блокируются\n# example-ads.com\n"},
    };

    private LinearLayout root;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        pal = Palette.of(this);
        pal.applyTo(this);
        C_BG = pal.bg; C_PANEL = pal.panel; C_TEXT = pal.text; C_MUTED = pal.muted; C_ACCENT = pal.accent;
        super.onCreate(savedInstanceState);
        showIndex();
    }

    @Override
    public void onBackPressed() {
        if (root != null && root.getTag() != null) {
            showIndex();
        } else {
            super.onBackPressed();
        }
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private LinearLayout newScreen(String title, Object tag) {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setTag(tag);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(C_TEXT);
        t.setTextSize(18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setBackgroundColor(C_PANEL);
        t.setPadding(dp(16), dp(14), dp(16), dp(14));
        root.addView(t);
        setContentView(root);
        LinearLayout screen = root;
        screen.post(() -> Palette.applyFont(this, screen));
        return root;
    }

    private void showIndex() {
        newScreen("</> Код браузера", null);
        ScrollView scroll = new ScrollView(this);
        LinearLayout items = new LinearLayout(this);
        items.setOrientation(LinearLayout.VERTICAL);
        items.setPadding(dp(16), dp(8), dp(16), dp(24));
        scroll.addView(items);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        section(items, "Ваш код — работает сразу, без пересборки");
        for (String[] e : EDITABLE) {
            item(items, e[1], C_ACCENT, v -> showEditor(e[0], e[1], e[2]));
        }
        TextView hint = new TextView(this);
        hint.setText("Скрипт и стиль включаются в Настройках → «Свой код».");
        hint.setTextColor(C_MUTED);
        hint.setTextSize(12);
        items.addView(hint);

        section(items, "Исходный код Maxclean — весь, без секретов");
        TextView note = new TextView(this);
        note.setText("Чтобы изменить сам браузер: скопируйте файлы, поправьте их и соберите APK "
                + "в Android Studio (проект Maxclean). Код открыт полностью — в нём нет ни слежки, "
                + "ни связи с разработчиком.");
        note.setTextColor(C_MUTED);
        note.setTextSize(12);
        note.setPadding(0, 0, 0, dp(8));
        items.addView(note);
        List<String> files = new ArrayList<>();
        listAssets("source", files);
        listAssets("filters", files);
        files.add("fingerprint.js");
        files.add("bridges.txt");
        for (String f : files) {
            item(items, f.replaceFirst("^source/", ""), C_TEXT, v -> showSource(f));
        }
    }

    private void section(LinearLayout parent, String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(C_MUTED);
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(16), 0, dp(6));
        parent.addView(t);
    }

    private void item(LinearLayout parent, String label, int color, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(color);
        t.setTextSize(15);
        t.setPadding(0, dp(10), 0, dp(10));
        t.setOnClickListener(click);
        parent.addView(t);
    }

    private void listAssets(String dir, List<String> out) {
        try {
            String[] children = getAssets().list(dir);
            if (children == null || children.length == 0) {
                out.add(dir); // a file
                return;
            }
            for (String c : children) listAssets(dir + "/" + c, out);
        } catch (IOException ignored) {
        }
    }

    private void showSource(String asset) {
        newScreen(asset.replaceFirst("^source/", ""), asset);
        String code = readAsset(asset);

        Button copy = new Button(this);
        copy.setText("Копировать весь файл");
        copy.setOnClickListener(v -> copyToClipboard(code));
        root.addView(copy);

        TextView view = new TextView(this);
        view.setText(code);
        view.setTextColor(C_TEXT);
        view.setTextSize(12);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        view.setPadding(dp(12), dp(8), dp(12), dp(24));
        view.setHorizontallyScrolling(true);

        HorizontalScrollView h = new HorizontalScrollView(this);
        h.addView(view);
        ScrollView v = new ScrollView(this);
        v.addView(h);
        root.addView(v, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
    }

    private void showEditor(String key, String title, String template) {
        newScreen(title, key);

        EditText editor = new EditText(this);
        editor.setText(Prefs.get(this).getString(key, template));
        editor.setTextColor(C_TEXT);
        editor.setTextSize(13);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setBackgroundColor(C_BG);
        editor.setPadding(dp(12), dp(8), dp(12), dp(8));
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setHorizontallyScrolling(false);
        root.addView(editor, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button save = new Button(this);
        save.setText("Сохранить");
        save.setOnClickListener(v -> {
            Prefs.get(this).edit().putString(key, editor.getText().toString()).apply();
            Toast.makeText(this, "Сохранено. Применится при следующей загрузке страницы",
                    Toast.LENGTH_SHORT).show();
        });
        Button copy = new Button(this);
        copy.setText("Копировать");
        copy.setOnClickListener(v -> copyToClipboard(editor.getText().toString()));
        Button reset = new Button(this);
        reset.setText("Сброс");
        reset.setOnClickListener(v -> editor.setText(template));
        buttons.addView(save, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        buttons.addView(copy, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        buttons.addView(reset, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(buttons);
    }

    private void copyToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Maxclean", text));
        Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show();
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (IOException e) {
            return "Не удалось открыть файл: " + e.getMessage();
        }
    }

    @Override
    public void finish() {
        super.finish();
        if (Palette.animations(this)) overridePendingTransition(R.anim.mc_return, R.anim.mc_leave);
    }
}
