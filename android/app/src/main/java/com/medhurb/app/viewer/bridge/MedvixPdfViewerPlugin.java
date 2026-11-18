package com.medhurb.app.viewer.bridge;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.medhurb.app.viewer.storage.MedvixStorageResolver;
import com.medhurb.app.viewer.storage.MedvixStorageResolver.ResolvedDocument;
import com.medhurb.app.viewer.ui.MedvixPdfActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Capacitor Bridge Plugin for the Native Android Document Viewer Engine.
 */
@CapacitorPlugin(name = "MedvixPdfViewer")
public class MedvixPdfViewerPlugin extends Plugin {

    private static final String TAG = "MedvixPdfViewerPlugin";

    public static final String EVENT_VIEWER_CLOSED   = "viewerClosed";
    public static final String EVENT_UNLOCK_REQUEST  = "unlockRequested";
    public static final String EVENT_EXTERNAL_OPENED = "externalDocumentOpened";

    private static final String SIDECAR_ROOT_DIR  = "viewer_sidecar";
    private static final String THUMB_CACHE_DIR   = "thumbnails";
    private static final String FILE_READER_STATE = "reader_state.json";
    private static final String FILE_ANNOTATIONS  = "annotations.json";

    /** Background pool for thumbnail generation & file inspection. */
    private static final ExecutorService sWorkerPool = Executors.newFixedThreadPool(2);

    /** Live plugin instance for emitting events from Activity lifecycle hooks. */
    private static volatile MedvixPdfViewerPlugin sInstance = null;

    @Override
    public void load() {
        sInstance = this;
    }

    @Override
    protected void handleOnDestroy() {
        if (sInstance == this) {
            sInstance = null;
        }
        super.handleOnDestroy();
    }

    // ========================================================================
    // 1. OPEN DOCUMENT IN NATIVE ACTIVITY
    // ========================================================================

    @PluginMethod
    public void openDocument(PluginCall call) {
        Context context = getContext();
        if (context == null) {
            call.reject("Android context unavailable.");
            return;
        }

        String resourceId   = optString(call, "resourceId");
        String relativePath = optString(call, "relativePath");
        String absolutePath = optString(call, "absolutePath");
        String uriString    = optString(call, "uri");
        String title        = optString(call, "title");
        String mimeType     = optString(call, "mimeType");
        String theme        = optString(call, "theme");
        boolean previewMode = Boolean.TRUE.equals(call.getBoolean("previewMode", false));
        int initialPage     = Math.max(0, call.getInt("initialPage", 0));

        ResolvedSource source = resolveSource(
                context, resourceId, relativePath, absolutePath, uriString, title, mimeType
        );

        if (!source.exists) {
            call.reject("Document not found on device storage.");
            return;
        }

        try {
            Intent intent = new Intent(context, MedvixPdfActivity.class);

            if (source.uri != null) {
                intent.setDataAndType(source.uri, source.mimeType);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else if (source.file != null) {
                Uri fileUri = Uri.fromFile(source.file);
                intent.setDataAndType(fileUri, source.mimeType);
                intent.putExtra(MedvixPdfActivity.EXTRA_ABSOLUTE_PATH, source.file.getAbsolutePath());
            }

            if (resourceId != null && !resourceId.isEmpty()) {
                intent.putExtra(MedvixPdfActivity.EXTRA_RESOURCE_ID, resourceId);
            }
            
            intent.putExtra(MedvixPdfActivity.EXTRA_TITLE, source.displayName);
            if (source.mimeType != null && !source.mimeType.isEmpty()) {
                intent.putExtra(MedvixPdfActivity.EXTRA_MIME_TYPE, source.mimeType);
            }
            intent.putExtra(MedvixPdfActivity.EXTRA_PREVIEW_MODE, previewMode);
            intent.putExtra(MedvixPdfActivity.EXTRA_INITIAL_PAGE, initialPage);
            if (theme != null && !theme.isEmpty()) {
                intent.putExtra(MedvixPdfActivity.EXTRA_THEME, theme);
            }

            startActivityForResult(call, intent, "handleViewerActivityResult");
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch MedvixPdfActivity", e);
            call.reject("Failed to open native document viewer: " + e.getMessage());
        }
    }

    @ActivityCallback
    private void handleViewerActivityResult(PluginCall call, ActivityResult result) {
        Intent data = result != null ? result.getData() : null;

        String action = "closed";
        String resourceId = call != null ? optString(call, "resourceId") : null;
        int lastPageIndex = 0;
        int totalPages = 0;
        boolean unlockRequested = false;

        if (data != null) {
            String extraAction = data.getStringExtra(MedvixPdfActivity.EXTRA_RESULT_ACTION);
            if (extraAction != null && !extraAction.isEmpty()) {
                action = extraAction;
            }
            
            String extraResId = data.getStringExtra(MedvixPdfActivity.EXTRA_RESULT_RESOURCE_ID);
            if (extraResId != null && !extraResId.isEmpty()) {
                resourceId = extraResId;
            }
            
            lastPageIndex = data.getIntExtra(MedvixPdfActivity.EXTRA_RESULT_LAST_PAGE, 0);
            totalPages = data.getIntExtra(MedvixPdfActivity.EXTRA_RESULT_TOTAL_PAGES, 0);
            
            unlockRequested = "unlock_subscription".equals(action);
        }

        JSObject payload = new JSObject();
        payload.put("action", action);
        if (resourceId != null && !resourceId.isEmpty()) {
            payload.put("resourceId", resourceId);
        }
        payload.put("lastPageIndex", lastPageIndex);
        payload.put("totalPages", totalPages);
        payload.put("unlockRequested", unlockRequested);

        notifyListeners(EVENT_VIEWER_CLOSED, payload);

        if (unlockRequested) {
            JSObject unlockPayload = new JSObject();
            if (resourceId != null && !resourceId.isEmpty()) {
                unlockPayload.put("resourceId", resourceId);
            }
            unlockPayload.put("lastPageIndex", lastPageIndex);
            unlockPayload.put("totalPages", totalPages);
            notifyListeners(EVENT_UNLOCK_REQUEST, unlockPayload);
        }

        if (call != null) {
            call.resolve(payload);
        }
    }

    // ========================================================================
    // 2. DOCUMENT INFO & SIDECAR METADATA INSPECTION
    // ========================================================================

    @PluginMethod
    public void getDocumentInfo(PluginCall call) {
        final Context context = getContext();
        if (context == null) {
            JSObject missing = new JSObject();
            missing.put("exists", false);
            call.resolve(missing);
            return;
        }

        final String resourceId   = optString(call, "resourceId");
        final String relativePath = optString(call, "relativePath");
        final String absolutePath = optString(call, "absolutePath");
        final String uriString    = optString(call, "uri");
        final String title        = optString(call, "title");
        final String mimeType     = optString(call, "mimeType");

        sWorkerPool.execute(() -> {
            try {
                ResolvedSource source = resolveSource(
                        context, resourceId, relativePath, absolutePath, uriString, title, mimeType
                );

                JSObject res = new JSObject();
                res.put("exists", source.exists);
                if (!source.exists) {
                    call.resolve(res);
                    return;
                }

                res.put("slugId", source.slugId);
                if (resourceId != null && !resourceId.isEmpty()) {
                    res.put("resourceId", resourceId);
                }
                res.put("displayName", source.displayName);
                res.put("mimeType", source.mimeType != null ? source.mimeType : "application/pdf");
                res.put("byteLength", source.byteLength);
                res.put("isPdf", source.isPdf);
                res.put("isImage", source.isImage);
                res.put("isRemovableStorage", source.isRemovableStorage);
                if (source.file != null) {
                    res.put("absolutePath", source.file.getAbsolutePath());
                }

                // Read sidecar state & annotations summary
                JSONObject readerState = readReaderState(context, source.slugId);
                if (readerState != null) {
                    res.put("readerState", JSObject.fromJSONObject(readerState));
                }

                JSONObject annotations = readAnnotations(context, source.slugId);
                int bookmarksCount = 0;
                int annotationsCount = 0;
                if (annotations != null) {
                    JSONArray bookmarks = annotations.optJSONArray("bookmarks");
                    JSONArray highlights = annotations.optJSONArray("highlights");
                    JSONArray notes = annotations.optJSONArray("notes");
                    JSONArray strokes = annotations.optJSONArray("strokes");

                    bookmarksCount = bookmarks != null ? bookmarks.length() : 0;
                    annotationsCount = (highlights != null ? highlights.length() : 0)
                            + (notes != null ? notes.length() : 0)
                            + (strokes != null ? strokes.length() : 0);
                }

                res.put("bookmarksCount", bookmarksCount);
                res.put("annotationsCount", annotationsCount);
                res.put("hasAnnotations", (bookmarksCount + annotationsCount) > 0);

                call.resolve(res);
            } catch (Exception e) {
                Log.w(TAG, "getDocumentInfo error", e);
                JSObject fallback = new JSObject();
                fallback.put("exists", false);
                call.resolve(fallback);
            }
        });
    }

    // ========================================================================
    // 3. WEBP COVER THUMBNAIL GENERATOR (CACHED)
    // ========================================================================

    @PluginMethod
    public void generateThumbnail(PluginCall call) {
        final Context context = getContext();
        if (context == null) {
            call.reject("Android context unavailable.");
            return;
        }

        final String resourceId   = optString(call, "resourceId");
        final String relativePath = optString(call, "relativePath");
        final String absolutePath = optString(call, "absolutePath");
        final String uriString    = optString(call, "uri");
        final String title        = optString(call, "title");
        final String mimeType     = optString(call, "mimeType");
        final int maxDim          = Math.max(96, Math.min(1024, call.getInt("maxDimension", 360)));

        sWorkerPool.execute(() -> {
            try {
                ResolvedSource source = resolveSource(
                        context, resourceId, relativePath, absolutePath, uriString, title, mimeType
                );

                if (!source.exists) {
                    call.reject("Source document does not exist.");
                    return;
                }

                File thumbDir = new File(context.getCacheDir(), THUMB_CACHE_DIR);
                if (!thumbDir.exists()) {
                    thumbDir.mkdirs();
                }

                File outFile = new File(thumbDir, source.slugId + "_" + maxDim + ".webp");
                if (outFile.exists() && outFile.length() > 0
                        && outFile.lastModified() >= source.lastModified) {
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(outFile.getAbsolutePath(), bounds);

                    JSObject cachedRes = new JSObject();
                    cachedRes.put("path", outFile.getAbsolutePath());
                    cachedRes.put("width", Math.max(0, bounds.outWidth));
                    cachedRes.put("height", Math.max(0, bounds.outHeight));
                    cachedRes.put("cached", true);
                    call.resolve(cachedRes);
                    return;
                }

                ThumbnailResult rendered = source.isImage
                        ? renderImageThumbnail(context, source, maxDim)
                        : renderPdfThumbnail(context, source, maxDim);

                if (rendered == null || rendered.bitmap == null) {
                    call.reject("Unable to render document thumbnail.");
                    return;
                }

                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    @SuppressWarnings("deprecation")
                    boolean ok = rendered.bitmap.compress(Bitmap.CompressFormat.WEBP, 82, fos);
                    if (!ok) {
                        call.reject("Failed to compress WebP thumbnail.");
                        return;
                    }
                } finally {
                    rendered.bitmap.recycle();
                }

                JSObject res = new JSObject();
                res.put("path", outFile.getAbsolutePath());
                res.put("width", rendered.width);
                res.put("height", rendered.height);
                if (rendered.pageCount > 0) {
                    res.put("pageCount", rendered.pageCount);
                }
                res.put("cached", false);
                call.resolve(res);
            } catch (Exception e) {
                Log.w(TAG, "generateThumbnail failed", e);
                call.reject("Thumbnail generation failed: " + e.getMessage());
            }
        });
    }

    private static class ThumbnailResult {
        final Bitmap bitmap;
        final int width;
        final int height;
        final int pageCount;

        ThumbnailResult(Bitmap bitmap, int width, int height, int pageCount) {
            this.bitmap = bitmap;
            this.width = width;
            this.height = height;
            this.pageCount = pageCount;
        }
    }

    private ThumbnailResult renderPdfThumbnail(Context context, ResolvedSource source, int maxDim) {
        ParcelFileDescriptor pfd = null;
        PdfRenderer renderer = null;
        PdfRenderer.Page page = null;
        try {
            pfd = openParcelFileDescriptor(context, source);
            if (pfd == null) return null;

            renderer = new PdfRenderer(pfd);
            int pageCount = renderer.getPageCount();
            if (pageCount <= 0) return null;

            page = renderer.openPage(0);
            int srcW = Math.max(1, page.getWidth());
            int srcH = Math.max(1, page.getHeight());

            float scale = Math.min((float) maxDim / srcW, (float) maxDim / srcH);
            int targetW = Math.max(1, Math.round(srcW * scale));
            int targetH = Math.max(1, Math.round(srcH * scale));

            Bitmap bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            canvas.drawColor(Color.WHITE);
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

            return new ThumbnailResult(bmp, targetW, targetH, pageCount);
        } catch (Exception e) {
            Log.w(TAG, "renderPdfThumbnail error", e);
            return null;
        } finally {
            try { if (page != null) page.close(); } catch (Exception ignored) {}
            try { if (renderer != null) renderer.close(); } catch (Exception ignored) {}
            try { if (pfd != null) pfd.close(); } catch (Exception ignored) {}
        }
    }

    private ThumbnailResult renderImageThumbnail(Context context, ResolvedSource source, int maxDim) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;

            try (InputStream in = openInputStream(context, source)) {
                if (in == null) return null;
                BitmapFactory.decodeStream(in, null, bounds);
            }

            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int inSample = 1;
            int halfW = bounds.outWidth / 2;
            int halfH = bounds.outHeight / 2;
            while ((halfW / inSample) >= maxDim && (halfH / inSample) >= maxDim) {
                inSample *= 2;
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = inSample;
            Bitmap decoded;
            try (InputStream in = openInputStream(context, source)) {
                if (in == null) return null;
                decoded = BitmapFactory.decodeStream(in, null, opts);
            }
            if (decoded == null) return null;

            float scale = Math.min(
                    1.0f,
                    Math.min((float) maxDim / decoded.getWidth(), (float) maxDim / decoded.getHeight())
            );
            int targetW = Math.max(1, Math.round(decoded.getWidth() * scale));
            int targetH = Math.max(1, Math.round(decoded.getHeight() * scale));

            Bitmap scaled = (targetW == decoded.getWidth() && targetH == decoded.getHeight())
                    ? decoded
                    : Bitmap.createScaledBitmap(decoded, targetW, targetH, true);

            if (scaled != decoded) {
                decoded.recycle();
            }

            return new ThumbnailResult(scaled, targetW, targetH, 1);
        } catch (Exception e) {
            Log.w(TAG, "renderImageThumbnail error", e);
            return null;
        }
    }

    // ========================================================================
    // 4. ANNOTATIONS EXPORT & SIDECAR CLEANUP
    // ========================================================================

    @PluginMethod
    public void exportAnnotations(PluginCall call) {
        Context context = getContext();
        if (context == null) {
            call.reject("Android context unavailable.");
            return;
        }

        ResolvedSource source = resolveSource(
                context,
                optString(call, "resourceId"),
                optString(call, "relativePath"),
                optString(call, "absolutePath"),
                optString(call, "uri"),
                null,
                null
        );

        JSONObject annotations = readAnnotations(context, source.slugId);
        if (annotations == null) {
            annotations = createEmptyAnnotationsJson();
        }

        JSObject res = new JSObject();
        res.put("slugId", source.slugId);
        res.put("annotationsJson", annotations.toString());
        call.resolve(res);
    }

    @PluginMethod
    public void clearSidecarData(PluginCall call) {
        Context context = getContext();
        if (context == null) {
            call.resolve(new JSObject().put("cleared", false));
            return;
        }

        ResolvedSource source = resolveSource(
                context,
                optString(call, "resourceId"),
                optString(call, "relativePath"),
                optString(call, "absolutePath"),
                optString(call, "uri"),
                null,
                null
        );

        boolean cleared = deleteSidecarDir(context, source.slugId);
        JSObject res = new JSObject();
        res.put("cleared", cleared);
        call.resolve(res);
    }

    // ========================================================================
    // 5. SHARED STATIC SIDECAR & EVENT HELPERS (Used by MedvixPdfActivity)
    // ========================================================================

    public static void notifyExternalViewerClosed(
            String action,
            String resourceId,
            int lastPageIndex,
            int totalPages,
            boolean unlockRequested
    ) {
        MedvixPdfViewerPlugin inst = sInstance;
        if (inst == null) return;

        JSObject payload = new JSObject();
        payload.put("action", action != null ? action : "closed");
        if (resourceId != null && !resourceId.isEmpty()) {
            payload.put("resourceId", resourceId);
        }
        payload.put("lastPageIndex", lastPageIndex);
        payload.put("totalPages", totalPages);
        payload.put("unlockRequested", unlockRequested);

        inst.notifyListeners(EVENT_VIEWER_CLOSED, payload);
    }

    public static File getSidecarDir(Context context, String slugId) {
        String safeSlug = (slugId == null || slugId.isEmpty()) ? "default_doc" : slugId;
        File dir = new File(new File(context.getFilesDir(), SIDECAR_ROOT_DIR), safeSlug);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static JSONObject readReaderState(Context context, String slugId) {
        File file = new File(getSidecarDir(context, slugId), FILE_READER_STATE);
        return readJsonFile(file);
    }

    public static void writeReaderState(Context context, String slugId, JSONObject state) {
        if (context == null || state == null) return;
        File file = new File(getSidecarDir(context, slugId), FILE_READER_STATE);
        writeJsonFile(file, state);
    }

    public static JSONObject readAnnotations(Context context, String slugId) {
        File file = new File(getSidecarDir(context, slugId), FILE_ANNOTATIONS);
        return readJsonFile(file);
    }

    public static void writeAnnotations(Context context, String slugId, JSONObject annotations) {
        if (context == null || annotations == null) return;
        File file = new File(getSidecarDir(context, slugId), FILE_ANNOTATIONS);
        writeJsonFile(file, annotations);
    }

    public static JSONObject createEmptyAnnotationsJson() {
        JSONObject root = new JSONObject();
        try {
            root.put("version", 1);
            root.put("bookmarks", new JSONArray());
            root.put("highlights", new JSONArray());
            root.put("notes", new JSONArray());
            root.put("strokes", new JSONArray());
        } catch (Exception ignored) {}
        return root;
    }

    private static boolean deleteSidecarDir(Context context, String slugId) {
        try {
            File dir = new File(new File(context.getFilesDir(), SIDECAR_ROOT_DIR), slugId);
            if (!dir.exists()) return true;
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    child.delete();
                }
            }
            return dir.delete();
        } catch (Exception e) {
            return false;
        }
    }

    private static JSONObject readJsonFile(File file) {
        if (file == null || !file.exists() || !file.isFile()) return null;
        try (FileInputStream fis = new FileInputStream(file);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeJsonFile(File file, JSONObject json) {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
            fos.flush();
        } catch (Exception e) {
            Log.w(TAG, "Failed writing sidecar JSON: " + file.getName(), e);
        }
    }

    // ========================================================================
    // 6. SOURCE RESOLUTION & PATH SECURITY
    // ========================================================================

    private static class ResolvedSource {
        boolean exists = false;
        File file = null;
        Uri uri = null;
        String slugId = "doc_unknown";
        String displayName = "Document";
        String mimeType = "application/pdf";
        long byteLength = 0L;
        long lastModified = 0L;
        boolean isPdf = true;
        boolean isImage = false;
        boolean isRemovableStorage = false;
    }

    private ResolvedSource resolveSource(
            Context context,
            String resourceId,
            String relativePath,
            String absolutePath,
            String uriString,
            String explicitTitle,
            String explicitMimeType
    ) {
        ResolvedSource out = new ResolvedSource();

        // 1. Explicit content:// or file:// URI
        if (uriString != null && !uriString.isEmpty()) {
            Uri parsed = Uri.parse(uriString);
            if ("file".equalsIgnoreCase(parsed.getScheme()) && parsed.getPath() != null) {
                File f = new File(parsed.getPath());
                if (f.exists() && f.isFile()) {
                    return populateFromFile(out, f, resourceId, explicitTitle, explicitMimeType);
                }
            } else {
                out.uri = parsed;
                out.displayName = (explicitTitle != null && !explicitTitle.isEmpty())
                        ? explicitTitle
                        : queryUriDisplayName(context, parsed);
                out.mimeType = resolveUriMimeType(context, parsed, out.displayName, explicitMimeType);
                out.byteLength = queryUriSize(context, parsed);
                out.exists = checkUriReadable(context, parsed);
                out.isImage = isImageType(out.mimeType, out.displayName);
                out.isPdf = !out.isImage;
                out.slugId = computeSlug(resourceId, uriString, out.displayName, out.byteLength);
                return out;
            }
        }

        // 2. Explicit absolute path
        if (absolutePath != null && !absolutePath.isEmpty()) {
            File f = new File(absolutePath);
            if (f.exists() && f.isFile()) {
                return populateFromFile(out, f, resourceId, explicitTitle, explicitMimeType);
            }
        }

        // 3. Relative path inside app storage roots (with traversal guard)
        if (relativePath != null && !relativePath.isEmpty()) {
            File f = resolveSafeRelativeFile(context, relativePath);
            if (f != null && f.exists() && f.isFile()) {
                return populateFromFile(out, f, resourceId, explicitTitle, explicitMimeType);
            }
        }

        // 4. Lookup by resourceId in standard MedVix content folders
        if (resourceId != null && !resourceId.isEmpty()) {
            File f = findFileByResourceId(context, resourceId);
            if (f != null && f.exists() && f.isFile()) {
                return populateFromFile(out, f, resourceId, explicitTitle, explicitMimeType);
            }
        }

        out.slugId = computeSlug(resourceId, relativePath, explicitTitle, 0L);
        return out;
    }

    private ResolvedSource populateFromFile(
            ResolvedSource out,
            File file,
            String resourceId,
            String explicitTitle,
            String explicitMimeType
    ) {
        out.exists = true;
        out.file = file;
        out.byteLength = file.length();
        out.lastModified = file.lastModified();
        out.displayName = (explicitTitle != null && !explicitTitle.isEmpty())
                ? explicitTitle
                : file.getName();
        out.mimeType = inferMimeType(out.displayName, explicitMimeType);
        out.isImage = isImageType(out.mimeType, out.displayName);
        out.isPdf = !out.isImage;
        try {
            out.isRemovableStorage = Environment.isExternalStorageRemovable(file);
        } catch (Exception ignored) {
            out.isRemovableStorage = false;
        }
        out.slugId = computeSlug(resourceId, file.getAbsolutePath(), out.displayName, out.byteLength);
        return out;
    }

    private File resolveSafeRelativeFile(Context context, String relativePath) {
        String cleanRel = relativePath.startsWith("/") ? relativePath.substring(1) : relativePath;

        File[] roots = new File[]{
                context.getFilesDir(),
                new File(context.getFilesDir(), "content"),
                context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
                new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "MedVix"),
                context.getCacheDir()
        };

        for (File root : roots) {
            if (root == null) continue;
            try {
                String canonicalRoot = root.getCanonicalPath();
                File candidate = new File(root, cleanRel);
                String canonicalTarget = candidate.getCanonicalPath();
                if (canonicalTarget.startsWith(canonicalRoot) && candidate.exists() && candidate.isFile()) {
                    return candidate;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * FIXED: Now uses the robust MedvixStorageResolver which perfectly mirrors 
     * the layout.js logic used by the JavaScript downloader.
     */
    private File findFileByResourceId(Context context, String resourceId) {
        // 1. Use the robust MedvixStorageResolver
        try {
            ResolvedDocument doc = MedvixStorageResolver.resolveByResourceId(context, resourceId, null, null);
            if (doc != null && doc.hasBackingFile()) {
                return doc.getBackingFile();
            }
        } catch (Exception e) {
            Log.w(TAG, "MedvixStorageResolver failed, falling back", e);
        }

        // 2. Legacy fallback (naive search) - just in case
        String safeId = resourceId.replaceAll("[^a-zA-Z0-9._-]", "_");
        File[] dirs = new File[]{
                new File(context.getFilesDir(), "content"),
                new File(context.getFilesDir(), "downloads"),
                new File(context.getFilesDir(), "resources"),
                context.getFilesDir(),
                context.getCacheDir()
        };

        for (File dir : dirs) {
            if (dir == null || !dir.exists() || !dir.isDirectory()) continue;
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (f.isFile() && f.getName().startsWith(safeId)) {
                    return f;
                }
            }
        }
        return null;
    }

    private ParcelFileDescriptor openParcelFileDescriptor(Context context, ResolvedSource source) {
        try {
            if (source.file != null) {
                return ParcelFileDescriptor.open(source.file, ParcelFileDescriptor.MODE_READ_ONLY);
            }
            if (source.uri != null) {
                return context.getContentResolver().openFileDescriptor(source.uri, "r");
            }
        } catch (Exception e) {
            Log.w(TAG, "openParcelFileDescriptor failed", e);
        }
        return null;
    }

    private InputStream openInputStream(Context context, ResolvedSource source) {
        try {
            if (source.file != null) {
                return new FileInputStream(source.file);
            }
            if (source.uri != null) {
                return context.getContentResolver().openInputStream(source.uri);
            }
        } catch (Exception e) {
            Log.w(TAG, "openInputStream failed", e);
        }
        return null;
    }

    private boolean checkUriReadable(Context context, Uri uri) {
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r")) {
            return pfd != null;
        } catch (Exception e) {
            return false;
        }
    }

    private String queryUriDisplayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = cursor.getString(idx);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        } catch (Exception ignored) {}
        String last = uri.getLastPathSegment();
        return (last != null && !last.isEmpty()) ? last : "Document";
    }

    private long queryUriSize(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !cursor.isNull(idx)) {
                    return cursor.getLong(idx);
                }
            }
        } catch (Exception ignored) {}
        return 0L;
    }

    private String resolveUriMimeType(Context context, Uri uri, String name, String explicitMime) {
        try {
            ContentResolver cr = context.getContentResolver();
            String detected = cr.getType(uri);
            if (detected != null && !detected.isEmpty()) return detected;
        } catch (Exception ignored) {}
        return inferMimeType(name, explicitMime);
    }

    private static String inferMimeType(String filename, String fallback) {
        if (fallback != null && !fallback.isEmpty()) return fallback;
        if (filename != null) {
            String lower = filename.toLowerCase(Locale.US);
            if (lower.endsWith(".png")) return "image/png";
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
            if (lower.endsWith(".webp")) return "image/webp";
            if (lower.endsWith(".gif")) return "image/gif";
            if (lower.endsWith(".bmp")) return "image/bmp";
        }
        return "application/pdf";
    }

    private static boolean isImageType(String mimeType, String filename) {
        if (mimeType != null && mimeType.toLowerCase(Locale.US).startsWith("image/")) {
            return true;
        }
        if (filename != null) {
            String lower = filename.toLowerCase(Locale.US);
            return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                    || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp")
                    || lower.endsWith(".heic") || lower.endsWith(".heif");
        }
        return false;
    }

    public static String computeSlug(String resourceId, String pathOrUri, String name, long size) {
        if (resourceId != null && !resourceId.isEmpty()) {
            return "res_" + resourceId.replaceAll("[^a-zA-Z0-9_-]", "_");
        }
        String seed = (pathOrUri != null ? pathOrUri : "") + "|" + (name != null ? name : "") + "|" + size;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("doc_");
            for (int i = 0; i < 12; i++) {
                hex.append(String.format(Locale.US, "%02x", hash[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return "doc_" + Math.abs(seed.hashCode());
        }
    }

    private static String optString(PluginCall call, String key) {
        if (call == null) return null;
        String v = call.getString(key);
        return (v != null && !v.trim().isEmpty()) ? v.trim() : null;
    }
}