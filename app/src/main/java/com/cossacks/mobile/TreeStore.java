package com.cossacks.mobile;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.webkit.MimeTypeMap;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Android Storage Access Framework. Persist a tree URI, not a raw /storage path. */
final class TreeStore {
    private final ContentResolver resolver;
    private final Uri tree;
    private final Uri root;
    private final ConcurrentHashMap<String, Uri> pathCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map<String, Uri>> dirCache = new ConcurrentHashMap<>();

    TreeStore(Context c, Uri uri) {
        resolver = c.getContentResolver();
        tree = uri;
        root = DocumentsContract.buildDocumentUriUsingTree(tree,
                    DocumentsContract.getTreeDocumentId(tree));
        pathCache.put("", root);
    }

    Uri directory(){ return tree; }

    private static String clean(String raw) throws IOException {
        if (raw == null) throw new IOException("Empty path");
        String p = raw.replace('\\', '/');
        if (p.startsWith("/") || p.indexOf('\0') >= 0 || p.contains(":"))
            throw new IOException("Unsafe path");
        String[] parts = p.split("/");
        for (String part : parts)
            if (part.equals("..") || part.equals(".")) throw new IOException("Unsafe path");
        while (p.endsWith("/")) p = p.substring(0,p.length()-1);
        return p;
    }

    private synchronized Map<String, Uri> children(Uri dir) throws IOException {
        String id = DocumentsContract.getDocumentId(dir);
        Map<String, Uri> cached = dirCache.get(id);
        if (cached != null) return cached;
        Map<String, Uri> result = new HashMap<>();
        Uri list = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
        String[] columns = { DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME };
        try (Cursor cursor = resolver.query(list, columns, null, null, null)) {
            if (cursor == null) throw new IOException("Cannot list selected folder");
            while (cursor.moveToNext()) {
                String itemId = cursor.getString(0), name = cursor.getString(1);
                result.put(name, DocumentsContract.buildDocumentUriUsingTree(tree, itemId));
            }
        } catch (SecurityException ex) { throw new IOException("Folder access was revoked", ex); }
        dirCache.put(id, result);
        return result;
    }

    private Uri find(String path, boolean create, boolean isFolder) throws IOException {
        String valid = clean(path);
        if(valid.isEmpty()) return root;
        Uri c = pathCache.get(valid);
        if(c!=null) return c;
        String[] parts = valid.split("/");
        Uri dir = root;
        String prefix = "";
        for (int i=0; i<parts.length; i++) {
            boolean last = i == parts.length-1;
            String segment=parts[i];
            prefix = prefix.isEmpty()?segment:prefix+"/"+segment;
            Uri known = pathCache.get(prefix);
            if(known!=null) {dir=known;continue;}
            Map<String, Uri> siblings = children(dir);
            Uri found = siblings.get(segment);
            if(found==null && create){
                String mime = (!last || isFolder)
                        ? DocumentsContract.Document.MIME_TYPE_DIR : mimeFor(segment);
                found = DocumentsContract.createDocument(resolver,dir,mime,segment);
                if(found == null) throw new IOException("Cannot create " + segment);
                siblings.put(segment,found);
            }
            if(found==null) return null;
            pathCache.put(prefix,found);
            dir=found;
        }
        return dir;
    }

    boolean exists(String path) throws IOException { return find(path,false,false) != null; }
    InputStream read(String path) throws IOException {
        Uri uri=find(path,false,false);
        if(uri==null) throw new FileNotFoundException(path);
        InputStream in = resolver.openInputStream(uri);
        if(in == null) throw new FileNotFoundException(path);
        return in;
    }
    OutputStream write(String path) throws IOException {
        Uri uri=find(path,true,false);
        if(uri==null) throw new IOException("Cannot open " + path);
        OutputStream out=resolver.openOutputStream(uri,"wt");
        if(out==null) throw new IOException("Cannot write " + path);
        return out;
    }
    void mkdir(String path) throws IOException { find(path,true,true); }
    void copyExisting(String src, String dest) throws IOException {
        try(InputStream in = read(src); OutputStream out=write(dest)){
            copy(in,out);
        }
    }
    static long copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64*1024]; long written=0;
        for(int n; (n=in.read(buffer))!=-1; ) { out.write(buffer,0,n); written+=n; }
        return written;
    }
    static String mimeFor(String name){
        String lower=name.toLowerCase(java.util.Locale.ROOT);
        if(lower.endsWith(".wasm")) return "application/wasm";
        if(lower.endsWith(".js") || lower.endsWith(".mjs")) return "text/javascript";
        if(lower.endsWith(".html")) return "text/html";
        if(lower.endsWith(".json")) return "application/json";
        if(lower.endsWith(".css")) return "text/css";
        if(lower.endsWith(".png")) return "image/png";
        if(lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if(lower.endsWith(".gif")) return "image/gif";
        if(lower.endsWith(".webp")) return "image/webp";
        if(lower.endsWith(".bmp")) return "image/bmp";
        if(lower.endsWith(".wav")) return "audio/wav";
        if(lower.endsWith(".txt") || lower.endsWith(".ini")) return "text/plain";
        if(lower.endsWith(".woff2")) return "font/woff2";
        if(lower.endsWith(".ttf")) return "font/ttf";
        if(lower.endsWith(".ogg")) return "audio/ogg";
        if(lower.endsWith(".mp3")) return "audio/mpeg";
        if(lower.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }
}
