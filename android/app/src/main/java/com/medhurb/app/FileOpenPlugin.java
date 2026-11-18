package com.medhurb.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.medhurb.app.viewer.ui.MedvixPdfActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Capacitor plugin for receiving files the OS hands to the app via an
 * ACTION_VIEW intent (the "Open with" chooser).
 *
 * ============================================================================
 * HOW IT WORKS (NATIVE FAST-PATH + JS FALLBACK)
 * ============================================================================
 *
 *   1. The user taps a PDF (or image, or text file) in Files, Gmail, Chrome,
 *      WhatsApp, Drive, or any other app and picks MedVix from the chooser.
 *
 *   2. Android launches MainActivity with an ACTION_VIEW intent carrying a
 *      content:// URI. The manifest's <intent-filter> blocks (application/pdf,
 *      image/*, text/plain) match the file's MIME type.
 *
 *   3. MainActivity.onCreate (cold start) or onNewIntent (warm start) calls
 *      FileOpenPlugin.deliverIntent(this, intent).
 *
 *   4. PRIMARY PATH — Native Android Viewer (PDF & Images):
 *      • For PDFs and images, deliverIntent(context, intent) immediately
 *        starts MedvixPdfActivity directly in Java with
 *        Intent.FLAG_GRANT_READ_URI_PERMISSION, MedvixPdfActivity.EXTRA_TITLE,
 *        MedvixPdfActivity.EXTRA_MIME_TYPE, and
 *        MedvixPdfActivity.EXTRA_PREVIEW_MODE = false.
 *      • Zero WebView boot delay and zero disk copy: MedvixPdfActivity reads
 *        the content:// URI directly via ContentResolver.
 *      • External files are deliberately excluded from the catalogue preview
 *        paywall (previewMode = false).
 *
 *   5. FALLBACK PATH — WebView / JS Bridge (text/plain or legacy callers):
 *      • If the file is a plain-text document (not PDF/image), if Context is
 *        null, or if native Activity launch fails, deliverIntent falls back
 *        to the original JS delivery pipeline:
 *          – Warm start (sInstance != null): streams the URI to cacheDir in
 *            8 KB chunks and emits 'fileOpen' via notifyListeners.
 *          – Cold start (sInstance == null): stashes the URI in sPendingUris
 *            for JS to drain once via getPendingFile().
 *      • When the native fast-path already handled the file, sPendingUris
 *        stays empty and getPendingFile() resolves with no payload so JS does
 *        not open a duplicate viewer.
 *
 * ============================================================================
 * CACHE LIFECYCLE (FALLBACK PATH)
 * ============================================================================
 *
 *   Fallback files are written to getContext().getCacheDir() with names like
 *   "incoming-<timestamp>.<ext>". On plugin load(), pruneOldCacheFiles(24h)
 *   deletes any incoming-* file older than 24 hours without touching active
 *   session files.
 */
@CapacitorPlugin(name = "FileOpen")
public class FileOpenPlugin extends Plugin {

    private static final String TAG = "FileOpenPlugin";

    /**
     * Event name JS subscribes to via plugin.addListener('fileOpen', ...).
     * Kept as a public constant so both sides reference the same literal.
     */
    public static final String EVENT_OPEN = "fileOpen";

    /**
     * Static reference to the currently loaded plugin instance. Set in
     * load(), cleared in handleOnDestroy().
     */
    private static FileOpenPlugin sInstance = null;

    /**
     * URIs that arrived before the plugin was loaded on the JS fallback
     * path (cold start). Drained one-by-one by getPendingFile().
     */
    private static final List<Uri> sPendingUris = new ArrayList<>();

    @Override
    public void load() {
        sInstance = this;

        // Best-effort cleanup. Runs once per process start. Keeps the cache
        // bounded across long sessions without touching files that belong to
        // the current session.
        pruneOldCacheFiles(24L * 60 * 60 * 1000);
    }

    @Override
    protected void handleOnDestroy() {
        if (sInstance == this) sInstance = null;
        super.handleOnDestroy();
    }

    // ========================================================================
    // Called by MainActivity
    // ========================================================================

    /**
     * Preferred entry point used by MainActivity from both onCreate() (cold
     * start) and onNewIntent() (warm start).
     *
     * Launches native MedvixPdfActivity immediately in Java with zero-copy
     * URI permission forwarding for PDFs and images. Falls back automatically
     * to JS bridge delivery for non-native types (such as text/plain) or if
     * native Activity launch fails.
     *
     * Must be called on the main thread. Both lifecycle hooks satisfy this.
     *
     * @param context Activity or Application context from MainActivity
     * @param intent  the ACTION_VIEW intent that launched or re-launched the
     *                activity. May be null.
     */
    public static void deliverIntent(Context context, Intent intent) {
        List<Uri> uris = extractUris(intent);
        if (uris.isEmpty()) return;

        Uri primaryUri = uris.get(0);
        String intentMimeType = intent != null ? intent.getType() : null;

        if (context != null && launchNativeViewer(context, primaryUri, intentMimeType)) {
            return;
        }

        // Fallback to JS pipeline (e.g., text/plain or if native launch failed)
        deliverIntent(intent);
    }

    /**
     * Legacy / fallback entry point without Context.
     *
     * Dispatches immediately to JS via notifyListeners('fileOpen') if the
     * plugin is loaded (warm start), or stashes in sPendingUris for
     * getPendingFile() to drain once WebView boots (cold start).
     *
     * @param intent the ACTION_VIEW intent that launched or re-launched the
     *               activity. May be null.
     */
    public static void deliverIntent(Intent intent) {
        List<Uri> uris = extractUris(intent);
        if (uris.isEmpty()) return;

        if (sInstance != null) {
            // Warm path — plugin is loaded, JS listener is attached.
            for (Uri uri : uris) sInstance.dispatch(uri);
        } else {
            // Cold path — plugin not yet loaded. Stash for getPendingFile.
            sPendingUris.addAll(uris);
        }
    }

    // ========================================================================
    // Native Viewer Direct Launch (Zero-Copy Fast-Path)
    // ========================================================================

    /**
     * Launch MedvixPdfActivity directly over the incoming content:// or
     * file:// URI, forwarding the caller's temporary read permission grant.
     *
     * Returns true when MedvixPdfActivity was started; returns false when the
     * file is a plain-text format handled by JS or if Activity launch fails.
     */
    private static boolean launchNativeViewer(Context context, Uri uri, String fallbackMimeType) {
        try {
            String displayName = queryDisplayNameStatic(context, uri);
            String mimeType = resolveMimeType(context, uri, fallbackMimeType);

            if (!isNativeViewerSupported(mimeType, displayName)) {
                return false;
            }

            Intent viewerIntent = new Intent(context, MedvixPdfActivity.class);
            viewerIntent.setDataAndType(uri, mimeType);
            viewerIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (!(context instanceof Activity)) {
                viewerIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }

            viewerIntent.putExtra(MedvixPdfActivity.EXTRA_TITLE, displayName);
            if (mimeType != null && !mimeType.isEmpty()) {
                viewerIntent.putExtra(MedvixPdfActivity.EXTRA_MIME_TYPE, mimeType);
            }
            viewerIntent.putExtra(MedvixPdfActivity.EXTRA_PREVIEW_MODE, false);

            context.startActivity(viewerIntent);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Direct native viewer launch failed, falling back to JS path", e);
            return false;
        }
    }

    /**
     * Resolve the best available MIME type from ContentResolver first, then
     * from the incoming Intent's explicit type.
     */
    private static String resolveMimeType(Context context, Uri uri, String fallbackMimeType) {
        try {
            String resolved = context.getContentResolver().getType(uri);
            if (resolved != null && !resolved.isEmpty()) {
                return resolved;
            }
        } catch (Exception ignored) {
            // fall through to intent type
        }
        return (fallbackMimeType != null && !fallbackMimeType.isEmpty()) ? fallbackMimeType : null;
    }

    /**
     * MedvixPdfActivity renders PDFs and raster images natively. Plain-text
     * files (text/plain) return false here so they route cleanly to the
     * WebView JS reader.
     */
    private static boolean isNativeViewerSupported(String mimeType, String displayName) {
        if (mimeType != null) {
            String lowerMime = mimeType.toLowerCase(Locale.US);
            if (lowerMime.startsWith("text/")) {
                return false;
            }
            if ("application/pdf".equals(lowerMime) || lowerMime.startsWith("image/")) {
                return true;
            }
        }

        if (displayName != null) {
            String lowerName = displayName.toLowerCase(Locale.US);
            if (lowerName.endsWith(".txt") || lowerName.endsWith(".md") || lowerName.endsWith(".csv")) {
                return false;
            }
            if (lowerName.endsWith(".pdf")
                    || lowerName.endsWith(".png")
                    || lowerName.endsWith(".jpg")
                    || lowerName.endsWith(".jpeg")
                    || lowerName.endsWith(".webp")
                    || lowerName.endsWith(".gif")
                    || lowerName.endsWith(".bmp")
                    || lowerName.endsWith(".heic")
                    || lowerName.endsWith(".heif")) {
                return true;
            }
        }

        // Unknown binary / octet-stream: let MedvixPdfActivity inspect magic bytes
        return true;
    }

    // ========================================================================
    // JS-callable methods (Preserved Fallback & Cold-Start Handshake)
    // ========================================================================

    /**
     * JS-initiated: pop one cold-start URI from the fallback queue and return
     * it as a file payload.
     *
     * Called once by JS during initial plugin handshake. If nothing is
     * pending (normal launcher tap, or already opened via native fast-path),
     * resolves with no payload.
     *
     * Resolves with:
     *   { path: string, name: string, mimeType: string|null, size: number }
     *
     * Rejects with:
     *   "Failed to read the launched file." — when a queued URI could not be
     *   opened or copied.
     */
    @PluginMethod
    public void getPendingFile(PluginCall call) {
        if (sPendingUris.isEmpty()) {
            call.resolve();
            return;
        }

        Uri uri = sPendingUris.remove(0);
        JSObject payload = materialise(uri);
        if (payload == null) {
            call.reject("Failed to read the launched file.");
            return;
        }
        call.resolve(payload);
    }

    // ========================================================================
    // Internals
    // ========================================================================

    /**
     * Pull every content:// or file:// URI out of an ACTION_VIEW intent.
     *
     * Supports both single-URI intents (intent.getData()) and batched multi-
     * select intents (intent.getClipData()).
     *
     * The ACTION_VIEW check makes deliverIntent() safe to call unconditionally
     * in MainActivity.onCreate: normal ACTION_MAIN launcher taps return an
     * empty list.
     */
    private static List<Uri> extractUris(Intent intent) {
        List<Uri> out = new ArrayList<>();
        if (intent == null) return out;
        if (!Intent.ACTION_VIEW.equals(intent.getAction())) return out;

        Uri single = intent.getData();
        if (single != null) out.add(single);

        if (intent.getClipData() != null) {
            int count = intent.getClipData().getItemCount();
            for (int i = 0; i < count; i++) {
                Uri u = intent.getClipData().getItemAt(i).getUri();
                if (u != null && !out.contains(u)) out.add(u);
            }
        }
        return out;
    }

    /**
     * Warm-start JS fallback path: materialise the URI into cacheDir and
     * emit it to JS immediately via notifyListeners('fileOpen', payload).
     */
    private void dispatch(Uri uri) {
        JSObject payload = materialise(uri);
        if (payload != null) notifyListeners(EVENT_OPEN, payload);
    }

    /**
     * Copy the content:// stream into the app cache directory in 8 KB chunks
     * and return a payload with an absolute local path for WebView consumption.
     *
     * Returns null on any failure.
     */
    private JSObject materialise(Uri uri) {
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = getContext().getContentResolver().openInputStream(uri);
            if (in == null) return null;

            // Preserve the original extension so the viewer's MIME detection
            // (which falls back to filename extension when the Content-Type
            // header is absent) still works after the copy.
            String displayName = queryDisplayName(uri);
            String ext = "";
            int dot = displayName.lastIndexOf('.');
            if (dot >= 0) ext = displayName.substring(dot);

            File cacheDir = getContext().getCacheDir();
            File target = new File(cacheDir, "incoming-" + System.currentTimeMillis() + ext);
            out = new FileOutputStream(target);

            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);

            JSObject payload = new JSObject();
            payload.put("path", target.getAbsolutePath());
            payload.put("name", displayName);
            payload.put("mimeType", getContext().getContentResolver().getType(uri));
            payload.put("size", target.length());
            return payload;
        } catch (Exception e) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (out != null) out.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Instance helper delegating to queryDisplayNameStatic().
     */
    private String queryDisplayName(Uri uri) {
        return queryDisplayNameStatic(getContext(), uri);
    }

    /**
     * Ask the ContentResolver for the original file's display name via
     * OpenableColumns.DISPLAY_NAME, falling back to the URI's last path
     * segment or "document".
     */
    private static String queryDisplayNameStatic(Context context, Uri uri) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver()
                    .query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = cursor.getString(idx);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        } catch (Exception ignored) {
            // fall through to URI segment / default
        } finally {
            try { if (cursor != null) cursor.close(); } catch (Exception ignored) {}
        }

        String lastSeg = uri.getLastPathSegment();
        if (lastSeg != null && !lastSeg.isEmpty()) {
            int slash = lastSeg.lastIndexOf('/');
            String cleaned = slash >= 0 ? lastSeg.substring(slash + 1) : lastSeg;
            if (!cleaned.isEmpty()) return cleaned;
        }
        return "document";
    }

    /**
     * Delete any incoming-* files older than the given age from the cache
     * directory. Called on plugin load() so it runs once per process start.
     */
    private void pruneOldCacheFiles(long maxAgeMs) {
        try {
            File cacheDir = getContext().getCacheDir();
            File[] files = cacheDir.listFiles();
            if (files == null) return;

            long cutoff = System.currentTimeMillis() - maxAgeMs;
            for (File f : files) {
                if (f.getName().startsWith("incoming-") && f.lastModified() < cutoff) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        } catch (Exception ignored) {
            // Best-effort. A failure to prune must not affect the launch.
        }
    }
}