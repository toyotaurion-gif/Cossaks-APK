package com.cossacks.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final int CHOOSE_TREE = 8101, CHOOSE_ZIP=8102;
    // This URL comes from the source developer's GitHub README (Web archive link).
    private static final String FULL_WEB_ZIP =
            "https://archive.org/download/cossacks-back-to-war-v-1.52/Cossacks152-Web-SinglePlayer.zip";
    private static final String REPOSITORY =
            "https://github.com/MisterCoderman/cossacks-1.52-platform";
    private static final int GOLD=Color.rgb(237,209,148), WHITE=0xffe5e2dc;
    private SharedPreferences prefs;
    private LinearLayout stack;
    private TextView status;
    private EditText urlInput;
    private WebView web;
    private TreeStore store;
    private int pendingAction=0; // 1 GitHub-linked archive; 2 device ZIP
    private String sourceUrl=FULL_WEB_ZIP;
    private final AtomicBoolean cancel = new AtomicBoolean(false);
    private boolean busy=false;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs=getSharedPreferences("cossacks_installer",MODE_PRIVATE);
        refreshFolder();
        showHome();
    }
    private void refreshFolder(){
        try{
            String saved=prefs.getString("tree_uri","");
            store=saved.isEmpty()?null:new TreeStore(this,Uri.parse(saved));
        }catch(Exception e){store=null;}
    }
    private int dp(int value){return (int)(getResources().getDisplayMetrics().density*value+.5f);}
    private TextView text(String t,int sp){
        TextView v=new TextView(this); v.setText(t);v.setTextColor(WHITE);
        v.setTextSize(sp);v.setPadding(dp(3),dp(8),dp(3),dp(8));return v;
    }
    private Button button(String t,Runnable r){
        Button b=new Button(this);b.setText(t);b.setAllCaps(false);
        b.setTextColor(Color.BLACK);b.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(GOLD));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.bottomMargin=dp(6);stack.addView(b,lp);
        b.setOnClickListener(v->{if(!busy)r.run();});return b;
    }
    private void showHome(){
        busy=false;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        if(web!=null){web.destroy();web=null;}
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xff151512);
        stack=new LinearLayout(this);stack.setOrientation(LinearLayout.VERTICAL);
        stack.setPadding(dp(18),dp(14),dp(18),dp(14));
        scroll.addView(stack);setContentView(scroll);

        TextView title=text("⚔ КАЗАКИ 1.52",26);title.setTextColor(GOLD);
        title.setGravity(Gravity.CENTER);stack.addView(title);
        stack.addView(text("Android • WebAssembly • локальная игра",14));
        boolean ready=store!=null && prefs.getBoolean("ready",false);
        if(ready){
            button("▶ ИГРАТЬ",this::launchGame);
            stack.addView(text("Игра установлена. Папка сохранена, повторная загрузка не требуется.",14));
            button("Обновить управление (без скачивания игры)",this::updateController);
        }else{
            stack.addView(text("Первый запуск: скачайте полный игровой пакет и выберите, где сохранить его на устройстве.",16));
        }
        button(ready?"Скачать игру заново / обновить файлы":"Скачать полный архив игры",this::confirmDownload);
        button("Установить из ZIP на устройстве",()->chooseFolder(2));
        button("Изменить папку игры",()->chooseFolder(0));
        stack.addView(text("Архив указан автором GitHub-проекта; его файлы размещены на Archive.org. Исходники GitHub отдельно не содержат полного набора игровых данных.",13));
        urlInput=new EditText(this);urlInput.setSingleLine(true);
        urlInput.setText(prefs.getString("last_source",FULL_WEB_ZIP));
        urlInput.setTextColor(WHITE);urlInput.setHintTextColor(0xffa2a09a);
        urlInput.setTextSize(12);urlInput.setSelectAllOnFocus(true);
        urlInput.setHint("Прямая HTTPS-ссылка на полный ZIP");
        stack.addView(urlInput,new LinearLayout.LayoutParams(-1,-2));
        button("Открыть GitHub проекта",()->startActivity(
                new Intent(Intent.ACTION_VIEW,Uri.parse(REPOSITORY))));
        String location=store==null?"Папка ещё не выбрана":
                "Выбранная папка: "+Uri.decode(store.directory().getLastPathSegment());
        stack.addView(text(location,13));
        status=text("DEL: двойной тап • Мышь: тачпад • ESC, CTRL и MRB — без изменений.",13);
        stack.addView(status);
    }
    private void confirmDownload(){
        String src=urlInput.getText().toString().trim();
        if(!src.startsWith("https://") || !src.toLowerCase(java.util.Locale.ROOT).contains(".zip")){
            message("Укажите прямую HTTPS-ссылку на полный ZIP-архив игры.");return;
        }
        sourceUrl=src;
        new AlertDialog.Builder(this)
          .setTitle("Скачать игру?")
          .setMessage("Полный архив может быть большим (ресурсы игры занимают примерно 1,5 ГБ после распаковки). Желателен Wi-Fi и достаточно свободной памяти. После загрузки будет установлен наш dmcr.html с защитой DEL (двойной тап). Продолжить?")
          .setNegativeButton("Отмена",null)
          .setPositiveButton("Скачать",(d,w)->chooseFolder(1))
          .show();
    }
    private void chooseFolder(int action){
        pendingAction=action;
        if(store!=null&&action!=0){ beginAction(); return; }
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent,CHOOSE_TREE);
    }
    private void beginAction(){
        if(pendingAction==1)startInstall(null);
        else if(pendingAction==2){
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.setType("application/zip");i.addCategory(Intent.CATEGORY_OPENABLE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i,CHOOSE_ZIP);
        }else showHome();
    }
    @Override protected void onActivityResult(int code,int result,Intent data){
        super.onActivityResult(code,result,data);
        if(result!=RESULT_OK || data==null || data.getData()==null)return;
        Uri uri=data.getData();
        if(code==CHOOSE_TREE){
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                prefs.edit().putString("tree_uri",uri.toString()).putBoolean("ready",false).apply();
                refreshFolder();beginAction();
            }catch(Exception e){message("Нет доступа к папке: "+e.getMessage());}
        } else if(code==CHOOSE_ZIP){startInstall(uri);}
    }
    private void startInstall(Uri imported){
        if(store==null){message("Сначала выберите папку");return;}
        cancel.set(false);busy=true;
        showProgressView();
        prefs.edit().putString("last_source",sourceUrl).apply();
        Thread t=new Thread(()->{
            File archive=null;
            GameInstaller installer=new GameInstaller(this,store,
                    s->runOnUiThread(()->{if(status!=null)status.setText(s);}),cancel::get);
            try{
                archive=imported==null?installer.download(sourceUrl):installer.importArchive(imported);
                installer.install(archive);
                prefs.edit().putBoolean("ready",true).apply();
                runOnUiThread(()->{busy=false;showHome();new AlertDialog.Builder(this)
                        .setTitle("Игра установлена").setMessage("Файлы сохранены. Можно играть офлайн.")
                        .setPositiveButton("Играть",(d,w)->launchGame())
                        .setNegativeButton("Позже",null).show();});
            }catch(Exception e){
                String error=e.getMessage()==null?e.toString():e.getMessage();
                runOnUiThread(()->{busy=false;showHome();message("Установка не завершена: "+error);});
            }finally{if(archive!=null) archive.delete();}
        },"CossacksInstaller");t.start();
    }
    private void showProgressView(){
        stack.removeAllViews();
        status=text("Подготовка…",18);stack.addView(status);
        stack.addView(text("Не закрывайте приложение до окончания распаковки.",14));
        Button cancelButton=new Button(this);cancelButton.setText("Отменить установку");
        stack.addView(cancelButton);
        cancelButton.setOnClickListener(v->{cancel.set(true);status.setText("Отмена…");});
    }
    private void updateController(){
        if(store==null){message("Папка игры не найдена");return;}
        try{
            new GameInstaller(this,store,s->{},()->false).updateController();
            message("dmcr.html обновлён. Резервная копия: dmcr.html.backup");
        }catch(Exception e){message("Ошибка: "+e.getMessage());}
    }
    private void launchGame(){
        if(store==null){message("Сначала выберите папку");return;}
        try{if(!store.exists("dmcr.js")||!store.exists("dmcr.wasm")){
            message("Файлы движка отсутствуют. Повторите установку.");return;
        }}catch(Exception e){message("Нет доступа к файлам: "+e.getMessage());return;}
        web=new WebView(this);web.setBackgroundColor(Color.BLACK);
        web.setWebViewClient(new GameWebViewClient(this,store));
        web.setWebChromeClient(new WebChromeClient());
        WebSettings s=web.getSettings();
        s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setBuiltInZoomControls(false);s.setDisplayZoomControls(false);
        s.setSupportZoom(false);s.setUseWideViewPort(true);
        web.setLayerType(View.LAYER_TYPE_HARDWARE,null);
        setContentView(web);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        web.loadUrl(GameWebViewClient.URL);
    }
    private void message(String m){new AlertDialog.Builder(this).setMessage(m).setPositiveButton("OK",null).show();}
    @Override public void onBackPressed(){
        if(web!=null){
            new AlertDialog.Builder(this).setTitle("Выйти в клиент?")
              .setPositiveButton("Да",(d,w)->showHome())
              .setNegativeButton("Продолжить игру",null).show();
        }else super.onBackPressed();
    }
    @Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);}
    @Override protected void onDestroy(){cancel.set(true);if(web!=null)web.destroy();super.onDestroy();}
}
