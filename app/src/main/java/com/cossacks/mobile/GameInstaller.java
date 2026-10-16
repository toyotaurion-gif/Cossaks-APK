package com.cossacks.mobile;

import android.content.Context;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class GameInstaller {
    interface Progress { void update(String text); }
    interface Cancellation { boolean cancelled(); }
    private static final long MAX_DOWNLOAD = 6L * 1024 * 1024 * 1024;
    private final Context app;
    private final TreeStore root;
    private final Progress progress;
    private final Cancellation cancel;

    GameInstaller(Context app, TreeStore root, Progress progress, Cancellation cancel){
        this.app=app.getApplicationContext(); this.root=root; this.progress=progress;this.cancel=cancel;
    }

    private void check() throws IOException {
        if(cancel.cancelled()) throw new IOException("Установка отменена");
    }
    private void report(String s){ progress.update(s); }
    private static String format(long bytes){ return (bytes / (1024*1024)) + " МБ"; }

    File download(String url) throws IOException {
        if(!url.startsWith("https://")) throw new IOException("Для загрузки требуется HTTPS");
        File dir=app.getExternalFilesDir(null);
        if(dir==null) dir=app.getCacheDir();
        File temp=new File(dir,"cossacks-download.tmp");
        // Persist only an extracted game, not a potentially incomplete archive.
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(30000); c.setReadTimeout(90000);
        c.setRequestProperty("User-Agent","CossacksMobile/0.9");
        try{
            int status=c.getResponseCode();
            if(status<200||status>=300)throw new IOException("HTTP " + status + ": архив недоступен");
            long length=c.getContentLengthLong();
            if(length>MAX_DOWNLOAD)throw new IOException("Архив слишком большой");
            byte[] block=new byte[128*1024]; long done=0; long lastReport=0;
            try(InputStream in=c.getInputStream(); OutputStream out=new FileOutputStream(temp)){
                for(int n; (n=in.read(block))!=-1;){
                    check();out.write(block,0,n);done+=n;
                    if(done>MAX_DOWNLOAD)throw new IOException("Слишком большой архив");
                    if(done-lastReport>2L*1024*1024){
                        report("Скачивание: " + format(done) +
                            (length>0 ? " / " + format(length):""));
                        lastReport=done;
                    }
                }
            }
            report("Архив загружен: " + format(done));
            return temp;
        } catch(IOException ex){ temp.delete();throw ex; }
        finally{ c.disconnect(); }
    }

    File importArchive(Uri u) throws IOException {
        File dir=app.getExternalFilesDir(null);
        if(dir==null) dir=app.getCacheDir();
        File temp=new File(dir,"cossacks-import.tmp");
        report("Копирование ZIP с устройства…");
        try(InputStream in=app.getContentResolver().openInputStream(u);
            OutputStream out=new FileOutputStream(temp)){
            if(in==null)throw new IOException("Нельзя открыть выбранный ZIP");
            byte[] buf=new byte[128*1024];long count=0;
            for(int n;(n=in.read(buf))!=-1;){check();out.write(buf,0,n);count+=n;
                if(count>MAX_DOWNLOAD)throw new IOException("Слишком большой ZIP"); }
        } catch(IOException e){temp.delete();throw e;}
        return temp;
    }

    void install(File archive) throws IOException {
        try(ZipFile zip=new ZipFile(archive)){
            // Support ZIPs rooted at either / or Cossacks152-Web-SinglePlayer/.
            String common=null;boolean canStrip=true;
            Set<String> names=new HashSet<>();
            Enumeration<? extends ZipEntry> entries=zip.entries();
            long totalUncompressed=0;
            int count=0;
            while(entries.hasMoreElements()) {
                ZipEntry e=entries.nextElement();
                if(e.isDirectory()||e.getName().startsWith("__MACOSX/"))continue;
                String name=e.getName().replace('\\','/');
                int slash=name.indexOf('/');
                if(slash<0)canStrip=false;
                else if(common==null)common=name.substring(0,slash+1);
                else if(!name.startsWith(common))canStrip=false;
                names.add(name);count++;
                if(e.getSize()>0)totalUncompressed+=e.getSize();
                if(totalUncompressed>8L*1024*1024*1024||count>150000)
                    throw new IOException("Unsafe ZIP size");
            }
            String prefix=canStrip&&common!=null?common:"";
            if(!names.contains(prefix+"dmcr.js")||!names.contains(prefix+"dmcr.wasm"))
                throw new IOException("Это не полный веб-архив: нет dmcr.js или dmcr.wasm");
            report("Распаковка файлов игры: 0 / " + count);
            Enumeration<? extends ZipEntry> all=zip.entries();
            int done=0;
            byte[] block=new byte[64*1024];
            while(all.hasMoreElements()){
                check(); ZipEntry e=all.nextElement();String original=e.getName();
                if(original.startsWith("__MACOSX/"))continue;
                String name=original.replace('\\','/');
                if(name.startsWith(prefix))name=name.substring(prefix.length());
                if(name.isEmpty())continue;
                if(e.isDirectory()){ root.mkdir(name); continue; }
                // Existing dmcr.html must be backed up and replaced by our stable controller,
                // and index.html is intercepted as our controller in the WebView.
                if(name.equals("dmcr.html")||name.equals("index.html")) continue;
                validateName(name);
                try(InputStream in=zip.getInputStream(e);OutputStream out=root.write(name)){
                    for(int n;(n=in.read(block))!=-1;){check();out.write(block,0,n);}
                }
                done++;
                if(done%50==0)report("Распаковка: " + done + " / " + count + " файлов");
            }
            if(!root.exists("dmcr.js") || !root.exists("dmcr.wasm"))
                throw new IOException("Проверка файлов не прошла");
            updateController();
            report("Готово. Игра установлена.");
        }
    }

    void updateController() throws IOException {
        // Retain the last usable controller before updating; the WebView always
        // serves the bundled good controller until the new write finishes.
        if(root.exists("dmcr.html"))root.copyExisting("dmcr.html","dmcr.html.backup");
        try(InputStream in=app.getAssets().open("dmcr.html");
            OutputStream out=root.write("dmcr.html")){
            TreeStore.copy(in,out);
        }
    }
    private static void validateName(String p) throws IOException {
        if(p.startsWith("/")||p.contains("..")||p.contains(":")||p.indexOf('\0')>=0)
            throw new IOException("Unsafe archive entry");
    }
}
