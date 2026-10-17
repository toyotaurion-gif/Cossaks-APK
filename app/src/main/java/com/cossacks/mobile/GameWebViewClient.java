package com.cossacks.mobile;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/** Local offline game files are mapped to one HTTPS origin for WebAssembly / fetch(). */
final class GameWebViewClient extends WebViewClient {
    static final String HOST = "appassets.androidplatform.net";
    static final String URL = "https://" + HOST + "/dmcr.html";
    private final Context app;
    private final File game;

    GameWebViewClient(Context context, File folder) {
        app = context.getApplicationContext();
        game = folder;
    }

    @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        Uri u = request.getUrl();
        if (!"https".equalsIgnoreCase(u.getScheme()) || !HOST.equalsIgnoreCase(u.getHost()))
            return null;
        String path = u.getPath() == null ? "" : u.getPath();
        if (path.startsWith("/")) path = path.substring(1);
        if (path.isEmpty()) path = "dmcr.html";
        try {
            File f = new File(game, path);
            if (!f.getCanonicalPath().startsWith(game.getCanonicalPath() + File.separator) ||
                    (!(path.equals("dmcr.html") || path.equals("index.html")) && !f.isFile()))
                throw new java.io.IOException("Invalid or missing file");
            InputStream data = (path.equals("dmcr.html") || path.equals("index.html"))
                    ? app.getAssets().open("dmcr.html") : new FileInputStream(f);
            String mime = TreeStore.mimeFor(path);
            WebResourceResponse response = new WebResourceResponse(mime,
                    mime.startsWith("text/") || mime.equals("application/json") ? "UTF-8" : null, data);
            response.setResponseHeaders(Collections.singletonMap("Cache-Control", "no-store"));
            return response;
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                    Collections.emptyMap(),
                    new ByteArrayInputStream(("Missing game file: " + path).getBytes(StandardCharsets.UTF_8)));
        }
    }
}
