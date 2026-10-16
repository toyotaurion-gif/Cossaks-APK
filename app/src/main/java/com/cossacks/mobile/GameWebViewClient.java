package com.cossacks.mobile;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;

/** Serves game files under a proper HTTPS origin without any external server. */
final class GameWebViewClient extends WebViewClient {
    static final String HOST = "appassets.androidplatform.net";
    static final String URL = "https://" + HOST + "/dmcr.html";
    private final Context app;
    private final TreeStore files;

    GameWebViewClient(Context c, TreeStore store) { app = c.getApplicationContext(); files = store; }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView web, WebResourceRequest req){
        Uri u=req.getUrl();
        if (!"https".equalsIgnoreCase(u.getScheme()) || !HOST.equalsIgnoreCase(u.getHost())) return null;
        String path = u.getPath()==null? "":u.getPath();
        if(path.startsWith("/")) path=path.substring(1);
        if(path.isEmpty()) path="dmcr.html";
        String mime=TreeStore.mimeFor(path);
        try {
            InputStream in = (path.equals("dmcr.html") || path.equals("index.html"))
                    ? app.getAssets().open("dmcr.html") : files.read(path);
            WebResourceResponse response=new WebResourceResponse(mime,
                    mime.startsWith("text/")||mime.equals("application/json")?"UTF-8":null,in);
            response.setResponseHeaders(Collections.singletonMap("Cache-Control", "no-cache"));
            return response;
        } catch (Exception ex) {
            WebResourceResponse missing=new WebResourceResponse("text/plain","UTF-8",404,
                    "Not Found", Collections.emptyMap(),
                    new ByteArrayInputStream(("Missing game file: "+path).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return missing;
        }
    }
}
