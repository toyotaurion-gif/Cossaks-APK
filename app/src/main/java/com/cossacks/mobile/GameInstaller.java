package com.cossacks.mobile;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Automatic first-launch installer: HTTPS download -> verified ZIP -> staging -> installed game. */
final class GameInstaller {
    interface Progress { void update(String description, int percent); }
    interface Cancellation { boolean cancelled(); }

    // Direct download from the port author's README; proprietary assets are not bundled here.
    static final String WEB_ARCHIVE =
            "https://ia800504.us.archive.org/25/items/cossacks-back-to-war-v-1.52/Cossacks152-Web-SinglePlayer.zip";
    private static final long MAX_DOWNLOAD = 4L * 1024 * 1024 * 1024;
    private static final long MAX_EXPANDED = 8L * 1024 * 1024 * 1024;
    private static final long REQUIRED_HEADROOM = 1800L * 1024 * 1024;
    private static final int MAX_FILES = 150000;
    private final Context app;
    private final Progress progress;
    private final Cancellation cancellation;

    GameInstaller(Context context, Progress progress, Cancellation cancellation) {
        this.app = context.getApplicationContext();
        this.progress = progress;
        this.cancellation = cancellation;
    }

    static File baseFolder(Context context) throws IOException {
        File external = context.getExternalFilesDir(null);
        File base = new File(external != null ? external : context.getFilesDir(), "cossacks152");
        if (!base.isDirectory() && !base.mkdirs()) throw new IOException("Нет доступа к памяти приложения");
        return base;
    }

    static File gameFolder(Context context) throws IOException {
        return new File(baseFolder(context), "game");
    }

    static boolean installed(Context context) {
        try {
            File game = gameFolder(context);
            return new File(game, ".installed-ok").isFile() &&
                    new File(game, "dmcr.js").isFile() && new File(game, "dmcr.wasm").isFile();
        } catch (IOException e) { return false; }
    }

    /** On APK updates, refresh the local controller without downloading game data again.
     *  WebView also serves the bundled controller directly; this synchronizes the on-disk copy.
     */
    static void syncController(Context context) throws IOException {
        if (!installed(context)) return;
        File game = gameFolder(context);
        File controller = new File(game, "dmcr.html");
        File temporary = new File(game, "dmcr.html.new");
        File previous = new File(game, "dmcr.html.previous");
        try (InputStream in = context.getAssets().open("dmcr.html")) {
            java.security.MessageDigest want = sha256();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) want.update(buf, 0, n);
            if (controller.isFile()) {
                java.security.MessageDigest have = sha256();
                try (InputStream onDisk = new FileInputStream(controller)) {
                    while ((n = onDisk.read(buf)) != -1) have.update(buf, 0, n);
                }
                if (java.util.Arrays.equals(have.digest(), want.digest())) return;
            }
        }
        try (InputStream in = context.getAssets().open("dmcr.html");
             OutputStream out = new BufferedOutputStream(new FileOutputStream(temporary))) {
            byte[] buf = new byte[64 * 1024]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        if (previous.exists() && !previous.delete()) {
            temporary.delete();
            throw new IOException("Не удалось очистить резервную копию управления");
        }
        boolean backedUp = controller.isFile() && controller.renameTo(previous);
        if (controller.exists() && !backedUp) {
            temporary.delete();
            throw new IOException("Не удалось сохранить предыдущую версию управления");
        }
        if (!temporary.renameTo(controller)) {
            if (backedUp) previous.renameTo(controller);
            temporary.delete();
            throw new IOException("Не удалось обновить управление");
        }
    }

    private static java.security.MessageDigest sha256() throws IOException {
        try { return java.security.MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IOException("SHA-256 unavailable", e); }
    }

    private void tell(String s, int p) { progress.update(s, Math.max(0, Math.min(100, p))); }
    private void check() throws IOException {
        if (cancellation.cancelled() || Thread.currentThread().isInterrupted())
            throw new IOException("Установка отменена");
    }
    private static String mb(long count) {
        return String.format(Locale.ROOT, "%.0f МБ", count / 1048576.0);
    }

    /** Restarts after killed app resume from the downloaded partial ZIP using HTTP Range. */
    File download() throws IOException {
        File destination = new File(baseFolder(app), "web-game.zip.part");
        long offset = destination.isFile() ? destination.length() : 0;
        if (offset > MAX_DOWNLOAD) { destination.delete(); offset = 0; }
        // A previous run may have downloaded the whole ZIP but been killed before extraction.
        if (offset > 1024 * 1024 && validArchive(destination)) {
            tell("Архив уже загружен, начинаем распаковку…", 55);
            return destination;
        }
        tell(offset > 0 ? "Продолжаем загрузку…" : "Подключение к серверу игры…", 0);
        HttpURLConnection conn = null;
        try {
            URL current = new URL(WEB_ARCHIVE);
            for (int redirects = 0; redirects < 6; redirects++) {
                check();
                if (!"https".equalsIgnoreCase(current.getProtocol()))
                    throw new IOException("Небезопасный адрес скачивания");
                conn = (HttpURLConnection) current.openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(90000);
                conn.setRequestProperty("User-Agent", "CossacksMobileAutoInstaller/1.0");
                conn.setRequestProperty("Accept-Encoding", "identity");
                if (offset > 0) conn.setRequestProperty("Range", "bytes=" + offset + "-");
                int status = conn.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String target = conn.getHeaderField("Location");
                    if (target == null) throw new IOException("Сервер вернул пустую переадресацию");
                    current = new URL(current, target);
                    conn.disconnect();conn = null;
                    continue;
                }
                if (status == 416 && offset > 0) {
                    // The remote end may report 'range not satisfiable' for a fully downloaded ZIP.
                    if (validArchive(destination)) { tell("Архив уже загружен", 55); return destination; }
                    destination.delete();
                    throw new IOException("Не удалось продолжить загрузку. Нажмите «Повторить».");
                }
                if (status != 200 && status != 206)
                    throw new IOException("Сервер игры недоступен (HTTP " + status + ")");
                // A server may ignore Range and send the whole file; never append in that case.
                if (status == 206) {
                    String range = conn.getHeaderField("Content-Range");
                    if (offset == 0 || range == null || !range.startsWith("bytes " + offset + "-"))
                        throw new IOException("Сервер вернул неверный диапазон загрузки");
                } else offset = 0;
                long remainder = conn.getContentLengthLong();
                long total = remainder >= 0 ? remainder + offset : -1;
                if (total > MAX_DOWNLOAD) throw new IOException("Архив слишком большой");
                long free = baseFolder(app).getUsableSpace();
                if (total > 0 && free < remainder + REQUIRED_HEADROOM)
                    throw new IOException("Недостаточно памяти: требуется примерно " +
                            mb(remainder + REQUIRED_HEADROOM) + " свободного места");

                long done = offset;
                long lastProgress = -1;
                try (InputStream in = new BufferedInputStream(conn.getInputStream(), 128 * 1024);
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(destination, offset > 0), 128 * 1024)) {
                    byte[] buffer = new byte[128 * 1024];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        check();
                        out.write(buffer, 0, n);
                        done += n;
                        if (done > MAX_DOWNLOAD) throw new IOException("Превышен допустимый размер архива");
                        if (done - lastProgress > 2 * 1048576L) {
                            int pct = total > 0 ? (int) Math.min(55, 55 * done / total) : 0;
                            tell("Загрузка игры: " + mb(done) +
                                    (total > 0 ? " из " + mb(total) : ""), pct);
                            lastProgress = done;
                        }
                    }
                }
                if (total > 0 && destination.length() != total)
                    throw new IOException("Загрузка прервалась; можно продолжить по кнопке «Повторить»");
                tell("Проверка загруженного архива…", 55);
                if (!validArchive(destination)) {
                    destination.delete();
                    throw new IOException("Скачанный файл не является полным архивом игры");
                }
                return destination;
            }
            throw new IOException("Слишком много перенаправлений скачивания");
        } finally { if (conn != null) conn.disconnect(); }
    }

    private static boolean validArchive(File archive) {
        if (!archive.isFile() || archive.length() < 1024 * 1024) return false;
        try (ZipFile zip = new ZipFile(archive)) {
            return layout(zip).totalFiles > 0;
        } catch (IOException ex) { return false; }
    }

    /** For optional user-supplied full web ZIP, without requesting filesystem permissions. */
    File importFrom(Uri source) throws IOException {
        File dst = new File(baseFolder(app), "web-game.zip.part");
        try (InputStream in = app.getContentResolver().openInputStream(source);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dst))) {
            if (in == null) throw new IOException("Не удалось открыть ZIP");
            byte[] b = new byte[128 * 1024]; int n; long count = 0;
            while ((n = in.read(b)) >= 0) {
                check(); out.write(b, 0, n); count += n;
                if (count > MAX_DOWNLOAD) throw new IOException("ZIP слишком большой");
                if (count % (8 * 1048576L) < b.length) tell("Копирование ZIP: " + mb(count), 0);
            }
        } catch (IOException e) { dst.delete(); throw e; }
        if (!validArchive(dst)) { dst.delete(); throw new IOException("В ZIP нет файлов игры 1.52"); }
        return dst;
    }

    private static class ArchiveLayout {
        final String prefix;
        final int totalFiles;
        final long totalBytes;
        ArchiveLayout(String prefix, int files, long bytes) {
            this.prefix = prefix; this.totalFiles = files; this.totalBytes = bytes;
        }
    }

    private static String normalize(String path) throws IOException {
        if (path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0 || path.startsWith("/"))
            throw new IOException("Некорректный путь внутри ZIP");
        String[] parts = path.split("/", -1);
        for (String p : parts)
            if (p.equals("..") || p.equals(".") || p.contains(":"))
                throw new IOException("Опасное имя файла в ZIP");
        return path;
    }

    private static ArchiveLayout layout(ZipFile zip) throws IOException {
        String common = null; boolean strip = true;
        long total = 0; int files = 0;
        Set<String> allFiles = new HashSet<>();
        Enumeration<? extends ZipEntry> it = zip.entries();
        while (it.hasMoreElements()) {
            ZipEntry e = it.nextElement();
            String name = normalize(e.getName());
            if (e.isDirectory() || name.startsWith("__MACOSX/")) continue;
            int slash = name.indexOf('/');
            if (slash < 0) strip = false;
            else if (common == null) common = name.substring(0, slash + 1);
            else if (!name.startsWith(common)) strip = false;
            files++;
            if (files > MAX_FILES) throw new IOException("Слишком много файлов в архиве");
            if (e.getSize() > 0) total += e.getSize();
            if (total > MAX_EXPANDED) throw new IOException("Архив слишком большой после распаковки");
            allFiles.add(name);
        }
        String prefix = strip && common != null ? common : "";
        if (!allFiles.contains(prefix + "dmcr.js") || !allFiles.contains(prefix + "dmcr.wasm"))
            throw new IOException("В архиве нет dmcr.js / dmcr.wasm");
        return new ArchiveLayout(prefix, files, total);
    }

    private static void deleteTree(File root) throws IOException {
        if (!root.exists()) return;
        File[] kids = root.listFiles();
        if (root.isDirectory() && kids != null) for (File f : kids) deleteTree(f);
        if (!root.delete()) throw new IOException("Не удалось очистить " + root.getName());
    }

    void install(File archive) throws IOException {
        File base = baseFolder(app);
        File stage = new File(base, "installing");
        File current = new File(base, "game");
        File backup = new File(base, "previous-game");
        deleteTree(stage);
        if (!stage.mkdirs()) throw new IOException("Не удалось создать папку установки");
        try (ZipFile zip = new ZipFile(archive)) {
            ArchiveLayout plan = layout(zip);
            if (base.getUsableSpace() < plan.totalBytes + 128L * 1024 * 1024)
                throw new IOException("Для распаковки нужно ещё " + mb(plan.totalBytes) + " свободного места");
            byte[] buf = new byte[128 * 1024];
            long copied = 0; int seen = 0;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                check();
                ZipEntry entry = entries.nextElement();
                String name = normalize(entry.getName());
                if (name.startsWith("__MACOSX/")) continue;
                if (!name.startsWith(plan.prefix)) continue;
                name = name.substring(plan.prefix.length());
                if (name.isEmpty()) continue;
                // The custom touch controller is provided by our APK; keep upstream engine/data.
                if (name.equals("index.html") || name.equals("dmcr.html")) continue;
                File target = new File(stage, name);
                if (!target.getCanonicalPath().startsWith(stage.getCanonicalPath() + File.separator))
                    throw new IOException("Недопустимый путь в архиве");
                if (entry.isDirectory()) {
                    if (!target.isDirectory() && !target.mkdirs())
                        throw new IOException("Не удалось создать папку " + name);
                    continue;
                }
                File parent = target.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs())
                    throw new IOException("Не удалось создать папку " + parent.getName());
                CRC32 crc = new CRC32();
                long fileBytes = 0;
                try (InputStream in = zip.getInputStream(entry);
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(target), 128 * 1024)) {
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        check();out.write(buf, 0, n);crc.update(buf, 0, n);
                        copied += n;fileBytes += n;
                        if (copied > MAX_EXPANDED) throw new IOException("Превышен допустимый размер распаковки");
                    }
                }
                if ((entry.getSize() >= 0 && fileBytes != entry.getSize()) ||
                        (entry.getCrc() >= 0 && crc.getValue() != entry.getCrc()))
                    throw new IOException("Повреждён файл " + name);
                seen++;
                if (seen % 25 == 0 || copied == plan.totalBytes) {
                    int percent = plan.totalBytes > 0 ? 55 + (int) (44 * copied / plan.totalBytes) : 60;
                    tell("Распаковка: " + seen + " / " + plan.totalFiles + " файлов", percent);
                }
            }
            if (!new File(stage, "dmcr.js").isFile() || !new File(stage, "dmcr.wasm").isFile())
                throw new IOException("Движок игры не установлен");
            try (InputStream in = app.getAssets().open("dmcr.html");
                 OutputStream out = new FileOutputStream(new File(stage, "dmcr.html"))) {
                byte[] b = new byte[64 * 1024]; int n;
                while ((n = in.read(b)) != -1) out.write(b, 0, n);
            }
            try (OutputStream out = new FileOutputStream(new File(stage, ".installed-ok"))) {
                out.write("web-1.52-auto-v1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            deleteTree(backup);
            if (current.exists() && !current.renameTo(backup))
                throw new IOException("Не удалось сохранить прежнюю версию игры");
            if (!stage.renameTo(current)) {
                if (backup.exists()) backup.renameTo(current);
                throw new IOException("Не удалось завершить установку");
            }
            tell("Готово! Открываем игру…", 100);
        } finally {
            // Don't discard the completed ZIP on a failed extraction: retry may reuse it.
            // A half-extracted tree is always discarded on the next attempt.
        }
        try { deleteTree(backup); } catch (IOException ignored) { /* optional cleanup */ }
        archive.delete();
    }
}
