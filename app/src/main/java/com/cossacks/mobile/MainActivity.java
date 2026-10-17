package com.cossacks.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/** One-install Android launcher: automatic first-run download, extraction, and game start. */
public final class MainActivity extends Activity {
    private static final int PICK_ZIP = 8102;
    private static final int GOLD = Color.rgb(237, 209, 148);
    private static final int WHITE = 0xffe5e2dc;
    private final AtomicBoolean cancel = new AtomicBoolean(false);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private ProgressBar progress;
    private LinearLayout layout;
    private WebView web;
    private volatile boolean installing = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (GameInstaller.installed(this)) {
            // Synchronize the controller after an APK upgrade; preserve game resources and saves.
            try { GameInstaller.syncController(this); }
            catch (Exception ex) { android.util.Log.w("Cossacks152", "Controller sync deferred", ex); }
            launchGame();
        } else {
            showInstaller("Первый запуск: игра будет скачана и установлена автоматически.");
            ui.post(() -> startInstall(null));
        }
    }

    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + .5f); }
    private TextView text(String value, int size) {
        TextView t = new TextView(this);
        t.setText(value);t.setTextSize(size);t.setTextColor(WHITE);
        t.setPadding(dp(3), dp(9), dp(3), dp(9));
        return t;
    }
    private Button addButton(String caption, Runnable run) {
        Button b = new Button(this);
        b.setText(caption);b.setAllCaps(false);b.setTextColor(Color.BLACK);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(GOLD));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);layout.addView(b, lp);
        b.setOnClickListener(v -> run.run());
        return b;
    }

    private void showInstaller(String message) {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        if (web != null) { web.destroy(); web = null; }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);scroll.setBackgroundColor(0xff151512);
        layout = new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(28), dp(24), dp(18));
        scroll.addView(layout);setContentView(scroll);
        TextView heading = text("⚔ КАЗАКИ 1.52", 29);
        heading.setTextColor(GOLD);heading.setGravity(Gravity.CENTER);
        layout.addView(heading);
        layout.addView(text("Android • автоустановка • управление на русском", 15));
        status = text(message, 18); layout.addView(status);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);progress.setProgress(0);
        layout.addView(progress, new LinearLayout.LayoutParams(-1, dp(12)));
        layout.addView(text("Первый запуск: примерно 724 МБ для загрузки, затем распаковка. " +
                "Нужны интернет и примерно 3 ГБ свободной памяти. Лучше использовать Wi-Fi. " +
                "До завершения установки оставьте приложение открытым.", 14));
        addButton("Отменить", () -> {
            cancel.set(true);
            if (status != null) status.setText("Останавливаем установку…");
        });
    }

    private void showError(String error) {
        installing = false;
        showInstaller("Установка не завершена:\n" + error);
        addButton("↻ Повторить загрузку", () -> startInstall(null));
        addButton("Использовать готовый ZIP с телефона", this::chooseZip);
        layout.addView(text("Существующие частично скачанные данные сохраняются, " +
                "чтобы следующая попытка могла продолжить загрузку. " +
                "Папку для игры выбирать не требуется.", 13));
    }

    private void startInstall(Uri importedZip) {
        if (installing) return;
        installing = true;cancel.set(false);
        showInstaller(importedZip == null ? "Подключаемся к серверу игры…" : "Проверяем выбранный ZIP…");
        final TextView currentStatus = status;
        final ProgressBar currentBar = progress;
        Thread worker = new Thread(() -> {
            GameInstaller installer = new GameInstaller(this,
                    (s, percent) -> ui.post(() -> {
                        if (installing && status == currentStatus) {
                            currentStatus.setText(s);
                            currentBar.setProgress(percent);
                        }
                    }), cancel::get);
            try {
                File zip = importedZip == null ? installer.download() : installer.importFrom(importedZip);
                installer.install(zip);
                ui.post(() -> {
                    installing = false;
                    if (!isFinishing() && !isDestroyed()) launchGame();
                });
            } catch (Exception ex) {
                final String err = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                ui.post(() -> { if (!isFinishing() && !isDestroyed()) showError(err); });
            }
        }, "CossacksAutoInstaller");
        worker.start();
    }

    private void chooseZip() {
        if (installing) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("application/zip");intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, PICK_ZIP);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_ZIP && result == RESULT_OK && data != null && data.getData() != null)
            startInstall(data.getData());
    }

    private void launchGame() {
        if (!GameInstaller.installed(this)) {
            showError("Не найдены игровые файлы. Установите игру ещё раз.");
            return;
        }
        try {
            File game = GameInstaller.gameFolder(this);
            if (web != null) { web.destroy();web = null; }
            web = new WebView(this);web.setBackgroundColor(Color.BLACK);
            web.setWebViewClient(new GameWebViewClient(this, game));
            web.setWebChromeClient(new WebChromeClient());
            WebSettings settings = web.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            settings.setMediaPlaybackRequiresUserGesture(false);
            settings.setBuiltInZoomControls(false);
            settings.setDisplayZoomControls(false);
            settings.setSupportZoom(false);
            settings.setUseWideViewPort(true);
            web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            setContentView(web);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            web.loadUrl(GameWebViewClient.URL);
        } catch (Exception e) { showError("Не удалось открыть игру: " + e.getMessage()); }
    }

    @Override public void onBackPressed() {
        if (web != null) {
            new AlertDialog.Builder(this).setTitle("Закрыть игру?")
                    .setPositiveButton("Выйти", (dialog, which) -> finish())
                    .setNegativeButton("Продолжить", null).show();
        } else if (installing) {
            new AlertDialog.Builder(this).setMessage("Прервать установку? Загрузка сможет продолжиться позже.")
                    .setPositiveButton("Прервать", (dialog, which) -> { cancel.set(true);finish(); })
                    .setNegativeButton("Продолжить", null).show();
        } else { super.onBackPressed(); }
    }
    @Override public void onConfigurationChanged(Configuration c) { super.onConfigurationChanged(c); }
    @Override protected void onDestroy() {
        cancel.set(true);
        if (web != null) { web.destroy();web = null; }
        super.onDestroy();
    }
}
