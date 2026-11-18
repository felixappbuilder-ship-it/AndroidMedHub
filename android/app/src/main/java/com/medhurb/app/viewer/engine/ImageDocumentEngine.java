package com.medhurb.app.viewer.engine;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.medhurb.app.viewer.render.BitmapMemoryPool;
import com.medhurb.app.viewer.render.BitmapMemoryPool.CacheKey;
import com.medhurb.app.viewer.render.BitmapMemoryPool.ManagedBitmap;
import com.medhurb.app.viewer.render.BitmapMemoryPool.RenderKind;
import com.medhurb.app.viewer.storage.MedvixStorageResolver;
import com.medhurb.app.viewer.storage.MedvixStorageResolver.DocumentKind;
import com.medhurb.app.viewer.storage.MedvixStorageResolver.ResolvedDocument;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ImageDocumentEngine
 * ============================================================================
 * Native Android high-resolution Raster, Animated (GIF/WebP), and Vector (SVG)
 * image rendering engine for MedVix.
 *
 * Architectural highlights:
 *  1. Reads zero-copy from MedvixStorageResolver (internal, external, sdcard,
 *     and external content:// URIs).
 *  2. Parses EXIF orientation (tag 0x0112) in pure Java without external
 *     dependencies and composes it with user-requested 0/90/180/270 rotations.
 *  3. Downsamples giant medical plates via power-of-two inSampleSize into pooled
 *     ARGB_8888 Bitmaps (via BitmapFactory inBitmap or API 28+ ImageDecoder
 *     software allocator) so full-screen views never exceed 3072 px.
 *  4. Decodes zoomed sub-viewport regions at 1:1 sensor resolution via
 *     BitmapRegionDecoder so students can inspect fine histological or
 *     anatomical labels without OutOfMemoryError.
 *  5. Supports resolution-independent SVG rasterization and API 28+ animated
 *     GIF/WebP drawables.
 */
public final class ImageDocumentEngine implements AutoCloseable {

    // ── Constants ────────────────────────────────────────────────────────────

    /** Safe upper bound for full-image bitmaps to stay below GL_MAX_TEXTURE_SIZE. */
    public static final int MAX_FULL_IMAGE_BITMAP_PX = 3072;

    /** Minimum dimension above which BitmapRegionDecoder tiling activates when zoomed. */
    public static final int REGION_DECODE_MIN_SOURCE_PX = 1600;

    private static final int EXIF_ORIENTATION_NORMAL          = 1;
    private static final int EXIF_ORIENTATION_FLIP_HORIZONTAL = 2;
    private static final int EXIF_ORIENTATION_ROTATE_180      = 3;
    private static final int EXIF_ORIENTATION_FLIP_VERTICAL   = 4;
    private static final int EXIF_ORIENTATION_TRANSPOSE       = 5;
    private static final int EXIF_ORIENTATION_ROTATE_90       = 6;
    private static final int EXIF_ORIENTATION_TRANSVERSE      = 7;
    private static final int EXIF_ORIENTATION_ROTATE_270      = 8;

    private static final int EXIF_SCAN_LIMIT_BYTES = 128 * 1024;

    // ── Domain Exceptions ────────────────────────────────────────────────────

    public static final class CorruptImageException extends IOException {
        public CorruptImageException(@NonNull String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }

    public static final class UnsupportedImageFormatException extends IOException {
        public UnsupportedImageFormatException(@NonNull String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }

    // ── Optional Vector SVG Render Delegate ──────────────────────────────────

    public interface SvgRenderDelegate {
        /** Returns intrinsic [width, height] in logical pixels (or <= 0 if fluid viewBox). */
        @NonNull
        float[] measureSvg(@NonNull InputStream svgStream) throws IOException;

        /** Renders the SVG scaled to fill [0..targetWidthPx, 0..targetHeightPx] onto canvas. */
        void renderSvgToCanvas(@NonNull InputStream svgStream,
                               @NonNull Canvas canvas,
                               int targetWidthPx,
                               int targetHeightPx) throws IOException;
    }

    // ── Immutable Image Metadata ─────────────────────────────────────────────

    public static final class ImageMetrics {
        public final int rawWidthPx;
        public final int rawHeightPx;
        public final int exifOrientation;
        public final boolean isVectorSvg;
        public final boolean hasAlpha;
        @NonNull public final String mimeType;

        public ImageMetrics(int rawWidthPx,
                            int rawHeightPx,
                            int exifOrientation,
                            boolean isVectorSvg,
                            boolean hasAlpha,
                            @NonNull String mimeType) {
            this.rawWidthPx = Math.max(1, rawWidthPx);
            this.rawHeightPx = Math.max(1, rawHeightPx);
            this.exifOrientation = exifOrientation;
            this.isVectorSvg = isVectorSvg;
            this.hasAlpha = hasAlpha;
            this.mimeType = mimeType;
        }

        public boolean isExifSwapped() {
            return exifOrientation == EXIF_ORIENTATION_ROTATE_90
                    || exifOrientation == EXIF_ORIENTATION_ROTATE_270
                    || exifOrientation == EXIF_ORIENTATION_TRANSPOSE
                    || exifOrientation == EXIF_ORIENTATION_TRANSVERSE;
        }

        /** Width after EXIF normalization (at 0° user rotation). */
        public int getNormalizedWidthPx() {
            return isExifSwapped() ? rawHeightPx : rawWidthPx;
        }

        /** Height after EXIF normalization (at 0° user rotation). */
        public int getNormalizedHeightPx() {
            return isExifSwapped() ? rawWidthPx : rawHeightPx;
        }

        /** Width after both EXIF normalization and user rotation (0, 90, 180, 270). */
        public int getOrientedWidthPx(int userRotationDegrees) {
            int rot = normalizeRotation(userRotationDegrees);
            int normW = getNormalizedWidthPx();
            int normH = getNormalizedHeightPx();
            return (rot == 90 || rot == 270) ? normH : normW;
        }

        /** Height after both EXIF normalization and user rotation (0, 90, 180, 270). */
        public int getOrientedHeightPx(int userRotationDegrees) {
            int rot = normalizeRotation(userRotationDegrees);
            int normW = getNormalizedWidthPx();
            int normH = getNormalizedHeightPx();
            return (rot == 90 || rot == 270) ? normW : normH;
        }
    }

    // ── Render Request & Async Handle ────────────────────────────────────────

    public static final class RenderRequest {
        public final float scale;
        public final int rotation;
        @NonNull public final RenderKind kind;
        @Nullable public final RectF orientedRegionPx;
        public final boolean bypassCache;
        public final boolean highPriority;
        public final int backgroundFillColor;

        private RenderRequest(Builder b) {
            this.scale = Math.max(0.02f, b.scale);
            this.rotation = normalizeRotation(b.rotation);
            this.kind = b.kind;
            this.orientedRegionPx = b.orientedRegionPx != null ? new RectF(b.orientedRegionPx) : null;
            this.bypassCache = b.bypassCache;
            this.highPriority = b.highPriority;
            this.backgroundFillColor = b.backgroundFillColor;
        }

        public static final class Builder {
            private float scale = 1.0f;
            private int rotation = 0;
            private RenderKind kind = RenderKind.RASTER_IMAGE;
            @Nullable private RectF orientedRegionPx = null;
            private boolean bypassCache = false;
            private boolean highPriority = true;
            private int backgroundFillColor = Color.TRANSPARENT;

            @NonNull
            public Builder setScale(float scale) {
                this.scale = scale;
                return this;
            }

            @NonNull
            public Builder setRotation(int rotation) {
                this.rotation = rotation;
                return this;
            }

            @NonNull
            public Builder setKind(@NonNull RenderKind kind) {
                this.kind = kind;
                return this;
            }

            /**
             * Optional sub-viewport region in EXIF+user-oriented image pixel coordinates
             * [0..orientedW, 0..orientedH]. Enables native BitmapRegionDecoder tiling.
             */
            @NonNull
            public Builder setOrientedRegionPx(@Nullable RectF regionPx) {
                this.orientedRegionPx = regionPx;
                if (regionPx != null) {
                    this.kind = RenderKind.VIEWPORT_TILE;
                }
                return this;
            }

            @NonNull
            public Builder setBypassCache(boolean bypassCache) {
                this.bypassCache = bypassCache;
                return this;
            }

            @NonNull
            public Builder setHighPriority(boolean highPriority) {
                this.highPriority = highPriority;
                return this;
            }

            @NonNull
            public Builder setBackgroundFillColor(int argbColor) {
                this.backgroundFillColor = argbColor;
                return this;
            }

            @NonNull
            public RenderRequest build() {
                return new RenderRequest(this);
            }
        }
    }

    public static final class TaskHandle {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        @Nullable private volatile Future<?> future;

        void attachFuture(@NonNull Future<?> future) {
            this.future = future;
            if (cancelled.get()) {
                future.cancel(true);
            }
        }

        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                Future<?> f = this.future;
                if (f != null) {
                    f.cancel(true);
                }
            }
        }

        public boolean isCancelled() {
            return cancelled.get();
        }
    }

    public interface RenderCallback {
        /** Called on main thread. Receiver owns the ManagedBitmap lease and MUST call release(). */
        void onRenderSuccess(@NonNull RenderRequest request, @NonNull ManagedBitmap bitmapLease);

        /** Called on main thread on failure (never called if cancelled). */
        void onRenderError(@NonNull RenderRequest request, @NonNull Throwable error);
    }

    public interface AnimatedDrawableCallback {
        void onDrawableReady(@NonNull Drawable drawable, boolean isAnimated);
        void onDrawableError(@NonNull Throwable error);
    }

    // ── Instance Fields ──────────────────────────────────────────────────────

    @NonNull private final Context appContext;
    @NonNull private final ResolvedDocument resolvedDoc;
    @NonNull private final BitmapMemoryPool memoryPool;
    @NonNull private final String docKey;
    @NonNull private final ImageMetrics metrics;
    @Nullable private final SvgRenderDelegate svgDelegate;

    private final ReentrantLock regionDecoderLock = new ReentrantLock(true);
    @Nullable private BitmapRegionDecoder regionDecoder;
    @Nullable private ParcelFileDescriptor regionPfd;
    private boolean regionDecoderAttempted = false;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ExecutorService interactiveExecutor;
    private final ExecutorService backgroundExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── Construction & Factory ───────────────────────────────────────────────

    private ImageDocumentEngine(@NonNull Context appContext,
                                @NonNull ResolvedDocument resolvedDoc,
                                @NonNull BitmapMemoryPool memoryPool,
                                @NonNull ImageMetrics metrics,
                                @Nullable SvgRenderDelegate svgDelegate) {
        this.appContext = appContext;
        this.resolvedDoc = resolvedDoc;
        this.memoryPool = memoryPool;
        this.docKey = resolvedDoc.slugId;
        this.metrics = metrics;
        this.svgDelegate = svgDelegate != null ? svgDelegate : ReflectiveAndroidSvgDelegate.tryCreate();

        this.interactiveExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "MedvixImg-Interactive-" + docKey);
            t.setPriority(Thread.NORM_PRIORITY + 1);
            return t;
        });
        this.backgroundExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "MedvixImg-Background-" + docKey);
            t.setPriority(Thread.MIN_PRIORITY + 1);
            return t;
        });
    }

    /**
     * Opens a ResolvedDocument (RASTER_IMAGE or SVG_IMAGE) and inspects its
     * intrinsic dimensions and EXIF orientation without allocating pixel buffers.
     */
    @NonNull
    public static ImageDocumentEngine open(@NonNull Context context,
                                           @NonNull ResolvedDocument resolvedDoc,
                                           @NonNull BitmapMemoryPool memoryPool,
                                           @Nullable SvgRenderDelegate svgDelegate) throws IOException {
        if (!resolvedDoc.isImage()) {
            throw new UnsupportedImageFormatException(
                    "ResolvedDocument is not an image: " + resolvedDoc.mimeType, null);
        }

        Context appCtx = context.getApplicationContext();
        SvgRenderDelegate effectiveSvg = svgDelegate != null
                ? svgDelegate
                : ReflectiveAndroidSvgDelegate.tryCreate();

        ImageMetrics metrics;
        if (resolvedDoc.kind == DocumentKind.SVG_IMAGE) {
            if (effectiveSvg == null) {
                throw new UnsupportedImageFormatException(
                        "SVG renderer is not configured for vector image: " + resolvedDoc.displayName, null);
            }
            try (InputStream is = resolvedDoc.openInputStream(appCtx)) {
                float[] dims = effectiveSvg.measureSvg(is);
                int w = dims.length >= 1 && dims[0] > 0f ? Math.round(dims[0]) : 1024;
                int h = dims.length >= 2 && dims[1] > 0f ? Math.round(dims[1]) : 1024;
                metrics = new ImageMetrics(w, h, EXIF_ORIENTATION_NORMAL, true, true, "image/svg+xml");
            }
        } else {
            metrics = probeRasterMetrics(appCtx, resolvedDoc);
        }

        return new ImageDocumentEngine(appCtx, resolvedDoc, memoryPool, metrics, effectiveSvg);
    }

    @NonNull
    private static ImageMetrics probeRasterMetrics(@NonNull Context appCtx,
                                                   @NonNull ResolvedDocument doc) throws IOException {
        int exifOrientation = EXIF_ORIENTATION_NORMAL;
        try (InputStream exifStream = doc.openInputStream(appCtx)) {
            exifOrientation = readExifOrientation(exifStream, doc.mimeType);
        } catch (IOException ignored) {
            // Non-EXIF format (PNG/BMP/GIF) or unreadable header
        }

        BitmapFactory.Options boundsOpts = new BitmapFactory.Options();
        boundsOpts.inJustDecodeBounds = true;

        try (InputStream boundsStream = doc.openInputStream(appCtx)) {
            BitmapFactory.decodeStream(boundsStream, null, boundsOpts);
        }

        int width = boundsOpts.outWidth;
        int height = boundsOpts.outHeight;
        String outMime = boundsOpts.outMimeType != null ? boundsOpts.outMimeType : doc.mimeType;

        // Fallback probe via API 28+ ImageDecoder for formats like HEIC/AVIF on certain ROMs
        if ((width <= 0 || height <= 0) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            int[] decodedDims = probeViaImageDecoderApi28(appCtx, doc);
            width = decodedDims[0];
            height = decodedDims[1];
        }

        if (width <= 0 || height <= 0) {
            throw new CorruptImageException("Unable to decode image dimensions for: " + doc.displayName, null);
        }

        boolean hasAlpha = !"image/jpeg".equalsIgnoreCase(outMime) && !"image/jpg".equalsIgnoreCase(outMime);
        return new ImageMetrics(width, height, exifOrientation, false, hasAlpha, outMime);
    }

    @RequiresApi(api = Build.VERSION_CODES.P)
    @NonNull
    private static int[] probeViaImageDecoderApi28(@NonNull Context appCtx,
                                                   @NonNull ResolvedDocument doc) throws IOException {
        final int[] dims = new int[]{ 0, 0 };
        ImageDecoder.Source source = createImageDecoderSource(appCtx, doc);
        ImageDecoder.decodeDrawable(source, (decoder, info, src) -> {
            dims[0] = info.getSize().getWidth();
            dims[1] = info.getSize().getHeight();
            // Abort actual pixel decode after reading header size
            decoder.setTargetSize(1, 1);
        });
        return dims;
    }

    // ── Public Metadata Accessors ────────────────────────────────────────────

    @NonNull
    public ResolvedDocument getResolvedDocument() {
        return resolvedDoc;
    }

    @NonNull
    public String getDocKey() {
        return docKey;
    }

    @NonNull
    public ImageMetrics getMetrics() {
        return metrics;
    }

    public boolean isVectorSvg() {
        return metrics.isVectorSvg;
    }

    public boolean supportsRegionTiling() {
        if (metrics.isVectorSvg) return false;
        int maxSide = Math.max(metrics.rawWidthPx, metrics.rawHeightPx);
        if (maxSide < REGION_DECODE_MIN_SOURCE_PX) return false;
        String m = metrics.mimeType.toLowerCase(Locale.ROOT);
        return m.contains("jpeg") || m.contains("jpg") || m.contains("png") || m.contains("webp");
    }

    public boolean isClosed() {
        return closed.get();
    }

    // ── Asynchronous & Synchronous Rendering ─────────────────────────────────

    /**
     * Synchronous cache lookup on the main thread for zero-flicker drawing.
     * Caller owns the returned ManagedBitmap lease and MUST call release().
     */
    @Nullable
    public ManagedBitmap acquireCachedBitmap(@NonNull RenderRequest request) {
        if (request.bypassCache || closed.get()) return null;
        CacheKey key = buildCacheKey(request);
        return memoryPool.get(key);
    }

    /**
     * Asynchronously renders the full image (at target scale) or a zoomed
     * sub-viewport tile into a pooled ARGB_8888 Bitmap.
     */
    @NonNull
    public TaskHandle renderAsync(@NonNull RenderRequest request,
                                  @NonNull RenderCallback callback) {
        TaskHandle handle = new TaskHandle();

        ManagedBitmap cached = acquireCachedBitmap(request);
        if (cached != null) {
            mainHandler.post(() -> {
                if (handle.isCancelled() || closed.get()) {
                    cached.release();
                    return;
                }
                callback.onRenderSuccess(request, cached);
            });
            return handle;
        }

        ExecutorService pool = request.highPriority ? interactiveExecutor : backgroundExecutor;
        Future<?> future = pool.submit(() -> {
            if (handle.isCancelled() || closed.get()) return;
            try {
                ManagedBitmap lease = renderInternal(request, handle);
                if (handle.isCancelled() || closed.get()) {
                    lease.release();
                    return;
                }
                mainHandler.post(() -> {
                    if (handle.isCancelled() || closed.get()) {
                        lease.release();
                        return;
                    }
                    callback.onRenderSuccess(request, lease);
                });
            } catch (CancellationException ignored) {
            } catch (Throwable t) {
                if (!handle.isCancelled() && !closed.get()) {
                    mainHandler.post(() -> callback.onRenderError(request, t));
                }
            }
        });

        handle.attachFuture(future);
        return handle;
    }

    @NonNull
    public ManagedBitmap renderSync(@NonNull RenderRequest request) throws IOException {
        return renderInternal(request, null);
    }

    /**
     * Loads an AnimatedImageDrawable (GIF / Animated WebP / Animated HEIF) on
     * API 28+ for smooth hardware-accelerated animation playback.
     */
    @NonNull
    public TaskHandle loadAnimatedDrawableAsync(@NonNull AnimatedDrawableCallback callback) {
        TaskHandle handle = new TaskHandle();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || metrics.isVectorSvg) {
            mainHandler.post(() -> callback.onDrawableError(
                    new UnsupportedImageFormatException("AnimatedImageDrawable requires API 28+ raster image", null)));
            return handle;
        }

        Future<?> future = interactiveExecutor.submit(() -> {
            if (handle.isCancelled() || closed.get()) return;
            try {
                Drawable drawable = decodeAnimatedDrawableApi28();
                boolean animated = drawable instanceof AnimatedImageDrawable;
                if (!handle.isCancelled() && !closed.get()) {
                    mainHandler.post(() -> callback.onDrawableReady(drawable, animated));
                }
            } catch (Throwable t) {
                if (!handle.isCancelled() && !closed.get()) {
                    mainHandler.post(() -> callback.onDrawableError(t));
                }
            }
        });
        handle.attachFuture(future);
        return handle;
    }

    @RequiresApi(api = Build.VERSION_CODES.P)
    @NonNull
    private Drawable decodeAnimatedDrawableApi28() throws IOException {
        ensureOpen();
        ImageDecoder.Source source = createImageDecoderSource(appContext, resolvedDoc);
        return ImageDecoder.decodeDrawable(source, (decoder, info, src) -> {
            int w = info.getSize().getWidth();
            int h = info.getSize().getHeight();
            int maxSide = Math.max(w, h);
            if (maxSide > MAX_FULL_IMAGE_BITMAP_PX) {
                float ratio = (float) MAX_FULL_IMAGE_BITMAP_PX / maxSide;
                decoder.setTargetSize(
                        Math.max(1, Math.round(w * ratio)),
                        Math.max(1, Math.round(h * ratio))
                );
            }
        });
    }

    // ── Core Rasterization & Region Decoding Pipeline ────────────────────────

    @NonNull
    private ManagedBitmap renderInternal(@NonNull RenderRequest request,
                                         @Nullable TaskHandle handle) throws IOException {
        ensureOpen();
        if (handle != null && handle.isCancelled()) {
            throw new CancellationException("Image render cancelled");
        }

        if (!request.bypassCache) {
            CacheKey key = buildCacheKey(request);
            ManagedBitmap hit = memoryPool.get(key);
            if (hit != null) {
                return hit;
            }
        }

        Bitmap rendered;
        if (metrics.isVectorSvg) {
            rendered = renderSvgBitmap(request, handle);
        } else if (request.orientedRegionPx != null && supportsRegionTiling()) {
            rendered = renderRasterRegionTile(request, handle);
        } else {
            rendered = renderFullRasterImage(request, handle);
        }

        if (handle != null && handle.isCancelled()) {
            memoryPool.recycleOrPool(rendered);
            throw new CancellationException("Image render cancelled after decode");
        }

        if (request.bypassCache) {
            return memoryPool.wrapStandalone(rendered, true);
        }
        CacheKey key = buildCacheKey(request);
        return memoryPool.putAndAcquire(key, rendered, true);
    }

    /**
     * Renders a vector SVG at the exact requested zoom scale (clamped to MAX_FULL_IMAGE_BITMAP_PX).
     */
    @NonNull
    private Bitmap renderSvgBitmap(@NonNull RenderRequest request,
                                   @Nullable TaskHandle handle) throws IOException {
        if (svgDelegate == null) {
            throw new UnsupportedImageFormatException("No SVG renderer available", null);
        }

        int orientedW = metrics.getOrientedWidthPx(request.rotation);
        int orientedH = metrics.getOrientedHeightPx(request.rotation);
        float effectiveScale = clampScaleToMaxDimension(request.scale, orientedW, orientedH);

        int outW = Math.max(1, Math.round(orientedW * effectiveScale));
        int outH = Math.max(1, Math.round(orientedH * effectiveScale));

        // Base unrotated SVG dimensions
        int unrotW = (request.rotation == 90 || request.rotation == 270) ? outH : outW;
        int unrotH = (request.rotation == 90 || request.rotation == 270) ? outW : outH;

        Bitmap target = memoryPool.obtainExact(outW, outH, Bitmap.Config.ARGB_8888, request.backgroundFillColor);
        try (InputStream is = resolvedDoc.openInputStream(appContext)) {
            if (handle != null && handle.isCancelled()) {
                throw new CancellationException("SVG render cancelled");
            }
            Canvas canvas = new Canvas(target);
            canvas.save();
            applyRotationOnlyTransform(canvas, request.rotation, outW, outH);
            svgDelegate.renderSvgToCanvas(is, canvas, unrotW, unrotH);
            canvas.restore();
            return target;
        } catch (Throwable t) {
            memoryPool.recycleOrPool(target);
            if (t instanceof IOException) throw (IOException) t;
            if (t instanceof CancellationException) throw (CancellationException) t;
            throw new CorruptImageException("Failed to rasterize SVG: " + resolvedDoc.displayName, t);
        }
    }

    /**
     * Decodes and orients the full raster image at optimal power-of-two sample size
     * using pooled ARGB_8888 Bitmaps.
     */
    @NonNull
    private Bitmap renderFullRasterImage(@NonNull RenderRequest request,
                                         @Nullable TaskHandle handle) throws IOException {
        int orientedW = metrics.getOrientedWidthPx(request.rotation);
        int orientedH = metrics.getOrientedHeightPx(request.rotation);
        float effectiveScale = clampScaleToMaxDimension(request.scale, orientedW, orientedH);

        int targetOrientedW = Math.max(1, Math.round(orientedW * effectiveScale));
        int targetOrientedH = Math.max(1, Math.round(orientedH * effectiveScale));

        // Target dimensions in raw sensor coordinates before EXIF/user orientation
        boolean totalSwapped = isTotalOrientationSwapped(metrics.exifOrientation, request.rotation);
        int targetRawW = totalSwapped ? targetOrientedH : targetOrientedW;
        int targetRawH = totalSwapped ? targetOrientedW : targetOrientedH;

        int sampleSize = calculatePowerOfTwoSampleSize(
                metrics.rawWidthPx, metrics.rawHeightPx, targetRawW, targetRawH);

        Bitmap rawDecoded = decodeRawSampledBitmap(sampleSize, handle);
        try {
            if (handle != null && handle.isCancelled()) {
                throw new CancellationException("Full raster render cancelled");
            }
            return transformAndFitBitmap(
                    rawDecoded,
                    metrics.exifOrientation,
                    request.rotation,
                    targetOrientedW,
                    targetOrientedH,
                    request.backgroundFillColor
            );
        } finally {
            memoryPool.recycleOrPool(rawDecoded);
        }
    }

    /**
     * Decodes only the requested sub-viewport region at high zoom using
     * BitmapRegionDecoder so giant medical plates remain crystal-clear and
     * memory-safe at 4x-8x magnification.
     */
    @NonNull
    private Bitmap renderRasterRegionTile(@NonNull RenderRequest request,
                                          @Nullable TaskHandle handle) throws IOException {
        RectF requestedOriented = request.orientedRegionPx;
        if (requestedOriented == null) {
            return renderFullRasterImage(request, handle);
        }

        int orientedW = metrics.getOrientedWidthPx(request.rotation);
        int orientedH = metrics.getOrientedHeightPx(request.rotation);
        RectF clampedOriented = clampRectToBounds(requestedOriented, orientedW, orientedH);

        float effectiveScale = clampScaleToMaxDimension(
                request.scale, clampedOriented.width(), clampedOriented.height());
        int outTileW = Math.max(1, Math.round(clampedOriented.width() * effectiveScale));
        int outTileH = Math.max(1, Math.round(clampedOriented.height() * effectiveScale));

        // Map the oriented viewport rectangle back to unrotated raw sensor coordinates
        Rect rawRegion = mapOrientedRectToRawSensorRect(
                clampedOriented,
                metrics.rawWidthPx,
                metrics.rawHeightPx,
                metrics.exifOrientation,
                request.rotation
        );

        boolean totalSwapped = isTotalOrientationSwapped(metrics.exifOrientation, request.rotation);
        int targetRawW = totalSwapped ? outTileH : outTileW;
        int targetRawH = totalSwapped ? outTileW : outTileH;
        int sampleSize = calculatePowerOfTwoSampleSize(
                rawRegion.width(), rawRegion.height(), targetRawW, targetRawH);

        BitmapRegionDecoder decoder = getOrCreateRegionDecoder();
        if (decoder == null) {
            // Fallback to full image decode if OEM decoder does not support region decoding
            return renderFullRasterImage(request, handle);
        }

        Bitmap rawTile = null;
        regionDecoderLock.lock();
        try {
            ensureOpen();
            if (handle != null && handle.isCancelled()) {
                throw new CancellationException("Region tile decode cancelled");
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            opts.inMutable = true;

            int expectedW = Math.max(1, rawRegion.width() / sampleSize);
            int expectedH = Math.max(1, rawRegion.height() / sampleSize);
            Bitmap reusable = memoryPool.obtainForInBitmap(expectedW, expectedH, Bitmap.Config.ARGB_8888);
            if (reusable != null) {
                opts.inBitmap = reusable;
            }

            try {
                rawTile = decoder.decodeRegion(rawRegion, opts);
            } catch (IllegalArgumentException inBitmapRejected) {
                if (opts.inBitmap != null) {
                    memoryPool.recycleOrPool(opts.inBitmap);
                    opts.inBitmap = null;
                }
                rawTile = decoder.decodeRegion(rawRegion, opts);
            }
        } finally {
            regionDecoderLock.unlock();
        }

        if (rawTile == null) {
            throw new CorruptImageException("BitmapRegionDecoder returned null for region: " + rawRegion, null);
        }

        try {
            return transformAndFitBitmap(
                    rawTile,
                    metrics.exifOrientation,
                    request.rotation,
                    outTileW,
                    outTileH,
                    request.backgroundFillColor
            );
        } finally {
            memoryPool.recycleOrPool(rawTile);
        }
    }

    @NonNull
    private Bitmap decodeRawSampledBitmap(int sampleSize,
                                          @Nullable TaskHandle handle) throws IOException {
        int expectedW = Math.max(1, metrics.rawWidthPx / sampleSize);
        int expectedH = Math.max(1, metrics.rawHeightPx / sampleSize);

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleSize;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        opts.inMutable = true;

        Bitmap reusable = memoryPool.obtainForInBitmap(expectedW, expectedH, Bitmap.Config.ARGB_8888);
        if (reusable != null) {
            opts.inBitmap = reusable;
        }

        Bitmap decoded = null;
        try (InputStream is = resolvedDoc.openInputStream(appContext)) {
            if (handle != null && handle.isCancelled()) {
                throw new CancellationException("Bitmap decode cancelled");
            }
            decoded = BitmapFactory.decodeStream(is, null, opts);
        } catch (IllegalArgumentException inBitmapRejected) {
            if (opts.inBitmap != null) {
                memoryPool.recycleOrPool(opts.inBitmap);
                opts.inBitmap = null;
            }
            try (InputStream retryStream = resolvedDoc.openInputStream(appContext)) {
                decoded = BitmapFactory.decodeStream(retryStream, null, opts);
            }
        } catch (Throwable t) {
            if (opts.inBitmap != null && decoded == null) {
                memoryPool.recycleOrPool(opts.inBitmap);
            }
            if (t instanceof IOException) throw (IOException) t;
            if (t instanceof CancellationException) throw (CancellationException) t;
            throw new CorruptImageException("BitmapFactory decode failed: " + resolvedDoc.displayName, t);
        }

        // Fallback to API 28+ ImageDecoder (software mutable allocator) if BitmapFactory returned null
        if (decoded == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (opts.inBitmap != null) {
                memoryPool.recycleOrPool(opts.inBitmap);
            }
            decoded = decodeViaImageDecoderApi28(expectedW, expectedH);
        }

        if (decoded == null) {
            throw new CorruptImageException("Failed to decode raster image: " + resolvedDoc.displayName, null);
        }
        return decoded;
    }

    @RequiresApi(api = Build.VERSION_CODES.P)
    @NonNull
    private Bitmap decodeViaImageDecoderApi28(int targetRawW, int targetRawH) throws IOException {
        ImageDecoder.Source source = createImageDecoderSource(appContext, resolvedDoc);
        return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
            // Software mutable bitmap ensures compatibility with BitmapMemoryPool,
            // ColorMatrixColorFilter (Dark/Sepia), and PrintDocumentAdapter.
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setMutableRequired(true);
            decoder.setTargetSize(Math.max(1, targetRawW), Math.max(1, targetRawH));
        });
    }

    @RequiresApi(api = Build.VERSION_CODES.P)
    @NonNull
    private static ImageDecoder.Source createImageDecoderSource(@NonNull Context appCtx,
                                                                @NonNull ResolvedDocument doc) throws IOException {
        File file = doc.getBackingFile();
        if (file != null && file.exists()) {
            return ImageDecoder.createSource(file);
        }
        if (doc.getSourceUri() != null) {
            return ImageDecoder.createSource(appCtx.getContentResolver(), doc.getSourceUri());
        }
        throw new IOException("No valid File or Uri for ImageDecoder");
    }

    // ── Lazy BitmapRegionDecoder Management ──────────────────────────────────

    @Nullable
    private BitmapRegionDecoder getOrCreateRegionDecoder() {
        regionDecoderLock.lock();
        try {
            if (closed.get()) return null;
            if (regionDecoder != null) return regionDecoder;
            if (regionDecoderAttempted) return null;
            regionDecoderAttempted = true;

            ParcelFileDescriptor pfd = resolvedDoc.openSeekableReadOnlyPfd(appContext);
            try {
                BitmapRegionDecoder dec;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    dec = BitmapRegionDecoder.newInstance(pfd);
                    // On API 31+, BitmapRegionDecoder dup's the FD internally; close our wrapper
                    MedvixStorageResolver.safeClose(pfd);
                } else {
                    try (InputStream is = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
                        dec = BitmapRegionDecoder.newInstance(is, false);
                    }
                }
                this.regionDecoder = dec;
                return dec;
            } catch (Throwable t) {
                MedvixStorageResolver.safeClose(pfd);
                return null;
            }
        } catch (IOException e) {
            return null;
        } finally {
            regionDecoderLock.unlock();
        }
    }

    // ── Cover Thumbnail Generation ───────────────────────────────────────────

    /**
     * Generates and atomically saves cache/thumbnails/{slug}.jpg if not yet cached.
     */
    @NonNull
    public TaskHandle ensureThumbnailCachedAsync(int maxDimensionPx,
                                                 @Nullable Runnable onSaved) {
        TaskHandle handle = new TaskHandle();
        Future<?> future = backgroundExecutor.submit(() -> {
            if (handle.isCancelled() || closed.get()) return;
            if (resolvedDoc.findExistingThumbnail(appContext) != null) return;

            try {
                int orientedW = metrics.getOrientedWidthPx(0);
                int orientedH = metrics.getOrientedHeightPx(0);
                int maxSide = Math.max(orientedW, orientedH);
                float thumbScale = Math.max(0.05f, Math.min(1.0f, (float) maxDimensionPx / maxSide));

                RenderRequest req = new RenderRequest.Builder()
                        .setScale(thumbScale)
                        .setRotation(0)
                        .setKind(RenderKind.THUMBNAIL)
                        .setBackgroundFillColor(Color.WHITE)
                        .setHighPriority(false)
                        .build();

                ManagedBitmap lease = renderSync(req);
                try {
                    if (handle.isCancelled() || closed.get()) return;
                    File target = resolvedDoc.getWritableThumbnailTarget(appContext, "jpg");
                    File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
                    try (FileOutputStream fos = new FileOutputStream(tmp)) {
                        lease.getBitmap().compress(Bitmap.CompressFormat.JPEG, 85, fos);
                        fos.getFD().sync();
                    }
                    if (!tmp.renameTo(target)) {
                        //noinspection ResultOfMethodCallIgnored
                        tmp.delete();
                    } else if (onSaved != null && !closed.get()) {
                        mainHandler.post(onSaved);
                    }
                } finally {
                    lease.release();
                }
            } catch (Throwable ignored) {
                // Best-effort offline thumbnail generation
            }
        });

        handle.attachFuture(future);
        return handle;
    }

    // ── EXIF Orientation & Geometry Transformations ──────────────────────────

    /**
     * Applies EXIF orientation + user rotation (0, 90, 180, 270) + scaling into
     * a pooled ARGB_8888 output Bitmap.
     */
    @NonNull
    private Bitmap transformAndFitBitmap(@NonNull Bitmap source,
                                         int exifOrientation,
                                         int userRotation,
                                         int targetOutW,
                                         int targetOutH,
                                         int fillColor) {
        int outW = Math.max(1, targetOutW);
        int outH = Math.max(1, targetOutH);

        // Fast path: identity orientation and exact dimensions
        if (exifOrientation == EXIF_ORIENTATION_NORMAL
                && userRotation == 0
                && source.getWidth() == outW
                && source.getHeight() == outH
                && source.isMutable()
                && source.getConfig() == Bitmap.Config.ARGB_8888
                && fillColor == Color.TRANSPARENT) {
            // Return a pooled copy so caller can safely recycle source in finally
            Bitmap copy = memoryPool.obtainExact(outW, outH, Bitmap.Config.ARGB_8888, Color.TRANSPARENT);
            Canvas c = new Canvas(copy);
            c.drawBitmap(source, 0f, 0f, null);
            return copy;
        }

        Bitmap out = memoryPool.obtainExact(outW, outH, Bitmap.Config.ARGB_8888, fillColor);
        Matrix matrix = buildForwardTransformMatrix(
                source.getWidth(),
                source.getHeight(),
                exifOrientation,
                userRotation,
                outW,
                outH
        );

        Canvas canvas = new Canvas(out);
        android.graphics.Paint paint = new android.graphics.Paint(
                android.graphics.Paint.FILTER_BITMAP_FLAG | android.graphics.Paint.DITHER_FLAG);
        canvas.drawBitmap(source, matrix, paint);
        return out;
    }

    /**
     * Builds the forward Matrix mapping raw sensor bitmap [0..rawW, 0..rawH]
     * through EXIF orientation, user rotation, and exact scaling to [0..outW, 0..outH].
     */
    @NonNull
    private static Matrix buildForwardTransformMatrix(int rawW,
                                                      int rawH,
                                                      int exifOrientation,
                                                      int userRotation,
                                                      int outW,
                                                      int outH) {
        Matrix m = new Matrix();
        float curW = rawW;
        float curH = rawH;

        // Step 1: Apply EXIF orientation to normalize into upright coordinates
        switch (exifOrientation) {
            case EXIF_ORIENTATION_FLIP_HORIZONTAL:
                m.postScale(-1f, 1f);
                m.postTranslate(curW, 0f);
                break;
            case EXIF_ORIENTATION_ROTATE_180:
                m.postRotate(180f);
                m.postTranslate(curW, curH);
                break;
            case EXIF_ORIENTATION_FLIP_VERTICAL:
                m.postScale(1f, -1f);
                m.postTranslate(0f, curH);
                break;
            case EXIF_ORIENTATION_TRANSPOSE:
                m.postRotate(90f);
                m.postTranslate(curH, 0f);
                m.postScale(-1f, 1f);
                m.postTranslate(curH, 0f);
                float tmp1 = curW;
                curW = curH;
                curH = tmp1;
                break;
            case EXIF_ORIENTATION_ROTATE_90:
                m.postRotate(90f);
                m.postTranslate(curH, 0f);
                float tmp2 = curW;
                curW = curH;
                curH = tmp2;
                break;
            case EXIF_ORIENTATION_TRANSVERSE:
                m.postRotate(270f);
                m.postTranslate(0f, curW);
                m.postScale(-1f, 1f);
                m.postTranslate(curH, 0f);
                float tmp3 = curW;
                curW = curH;
                curH = tmp3;
                break;
            case EXIF_ORIENTATION_ROTATE_270:
                m.postRotate(270f);
                m.postTranslate(0f, curW);
                float tmp4 = curW;
                curW = curH;
                curH = tmp4;
                break;
            case EXIF_ORIENTATION_NORMAL:
            default:
                break;
        }

        // Step 2: Apply user rotation (0, 90, 180, 270)
        int rot = normalizeRotation(userRotation);
        if (rot == 90) {
            m.postRotate(90f);
            m.postTranslate(curH, 0f);
            float t = curW;
            curW = curH;
            curH = t;
        } else if (rot == 180) {
            m.postRotate(180f);
            m.postTranslate(curW, curH);
        } else if (rot == 270) {
            m.postRotate(270f);
            m.postTranslate(0f, curW);
            float t = curW;
            curW = curH;
            curH = t;
        }

        // Step 3: Scale uniformly into target output dimensions
        float scaleX = outW / Math.max(1f, curW);
        float scaleY = outH / Math.max(1f, curH);
        m.postScale(scaleX, scaleY);
        return m;
    }

    /**
     * Inverts the orientation transform to map an oriented viewport rectangle
     * back into raw sensor coordinates for BitmapRegionDecoder.decodeRegion().
     */
    @NonNull
    private static Rect mapOrientedRectToRawSensorRect(@NonNull RectF orientedRect,
                                                       int fullRawW,
                                                       int fullRawH,
                                                       int exifOrientation,
                                                       int userRotation) {
        boolean swapped = isTotalOrientationSwapped(exifOrientation, userRotation);
        int fullOrientedW = swapped ? fullRawH : fullRawW;
        int fullOrientedH = swapped ? fullRawW : fullRawH;

        Matrix forward = buildForwardTransformMatrix(
                fullRawW, fullRawH, exifOrientation, userRotation, fullOrientedW, fullOrientedH);
        Matrix inverse = new Matrix();
        if (!forward.invert(inverse)) {
            return new Rect(0, 0, fullRawW, fullRawH);
        }

        RectF mapped = new RectF();
        inverse.mapRect(mapped, orientedRect);

        int l = Math.max(0, Math.min(fullRawW - 1, (int) Math.floor(mapped.left)));
        int t = Math.max(0, Math.min(fullRawH - 1, (int) Math.floor(mapped.top)));
        int r = Math.max(l + 1, Math.min(fullRawW, (int) Math.ceil(mapped.right)));
        int b = Math.max(t + 1, Math.min(fullRawH, (int) Math.ceil(mapped.bottom)));
        return new Rect(l, t, r, b);
    }

    private static void applyRotationOnlyTransform(@NonNull Canvas canvas,
                                                   int rotation,
                                                   int outW,
                                                   int outH) {
        int rot = normalizeRotation(rotation);
        if (rot == 90) {
            canvas.translate(outW, 0f);
            canvas.rotate(90f);
        } else if (rot == 180) {
            canvas.translate(outW, outH);
            canvas.rotate(180f);
        } else if (rot == 270) {
            canvas.translate(0f, outH);
            canvas.rotate(270f);
        }
    }

    private static boolean isTotalOrientationSwapped(int exifOrientation, int userRotation) {
        boolean exifSwap = (exifOrientation == EXIF_ORIENTATION_ROTATE_90
                || exifOrientation == EXIF_ORIENTATION_ROTATE_270
                || exifOrientation == EXIF_ORIENTATION_TRANSPOSE
                || exifOrientation == EXIF_ORIENTATION_TRANSVERSE);
        int rot = normalizeRotation(userRotation);
        boolean userSwap = (rot == 90 || rot == 270);
        return exifSwap ^ userSwap;
    }

    private static int calculatePowerOfTwoSampleSize(int rawW, int rawH, int reqW, int reqH) {
        int inSampleSize = 1;
        if (rawH > reqH || rawW > reqW) {
            int halfH = rawH / 2;
            int halfW = rawW / 2;
            while ((halfH / inSampleSize) >= reqH && (halfW / inSampleSize) >= reqW) {
                inSampleSize *= 2;
            }
        }
        return Math.max(1, inSampleSize);
    }

    private static float clampScaleToMaxDimension(float requestedScale, float widthPx, float heightPx) {
        float maxSide = Math.max(widthPx, heightPx);
        if (maxSide <= 0f) return requestedScale;
        float maxAllowed = (float) MAX_FULL_IMAGE_BITMAP_PX / maxSide;
        return Math.min(requestedScale, Math.max(0.02f, maxAllowed));
    }

    @NonNull
    private static RectF clampRectToBounds(@NonNull RectF rect, int maxW, int maxH) {
        float l = Math.max(0f, Math.min(maxW - 1f, rect.left));
        float t = Math.max(0f, Math.min(maxH - 1f, rect.top));
        float r = Math.max(l + 1f, Math.min((float) maxW, rect.right));
        float b = Math.max(t + 1f, Math.min((float) maxH, rect.bottom));
        return new RectF(l, t, r, b);
    }

    @NonNull
    private CacheKey buildCacheKey(@NonNull RenderRequest request) {
        if (request.orientedRegionPx != null) {
            Rect rounded = new Rect(
                    Math.round(request.orientedRegionPx.left),
                    Math.round(request.orientedRegionPx.top),
                    Math.round(request.orientedRegionPx.right),
                    Math.round(request.orientedRegionPx.bottom)
            );
            return CacheKey.forTile(docKey, 0, request.scale, request.rotation, rounded);
        }
        return CacheKey.forPage(docKey, 0, request.kind, request.scale, request.rotation);
    }

    public static int normalizeRotation(int deg) {
        int r = deg % 360;
        return r < 0 ? r + 360 : r;
    }

    // ── Zero-Dependency Binary EXIF Orientation Reader ───────────────────────

    /**
     * Reads EXIF Orientation tag (0x0112) from JPEG APP1 or WebP EXIF chunks
     * without external dependencies.
     */
    static int readExifOrientation(@NonNull InputStream rawStream, @Nullable String mimeType) {
        try {
            BufferedInputStream in = new BufferedInputStream(rawStream, 8192);
            in.mark(EXIF_SCAN_LIMIT_BYTES);
            byte[] buf = readUpTo(in, EXIF_SCAN_LIMIT_BYTES);
            if (buf.length < 12) return EXIF_ORIENTATION_NORMAL;

            // 1. JPEG: FF D8
            if ((buf[0] & 0xFF) == 0xFF && (buf[1] & 0xFF) == 0xD8) {
                int offset = 2;
                while (offset + 4 <= buf.length) {
                    if ((buf[offset] & 0xFF) != 0xFF) break;
                    int marker = buf[offset + 1] & 0xFF;
                    offset += 2;
                    if (marker == 0xD9 || marker == 0xDA) break; // EOI or SOS
                    if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) continue;

                    int segLen = ((buf[offset] & 0xFF) << 8) | (buf[offset + 1] & 0xFF);
                    if (segLen < 2 || offset + segLen > buf.length) break;

                    // APP1 (0xE1) with "Exif\0\0"
                    if (marker == 0xE1 && segLen >= 8
                            && buf[offset + 2] == 'E' && buf[offset + 3] == 'x'
                            && buf[offset + 4] == 'i' && buf[offset + 5] == 'f'
                            && buf[offset + 6] == 0x00 && buf[offset + 7] == 0x00) {
                        return parseTiffOrientation(buf, offset + 8, segLen - 8);
                    }
                    offset += segLen;
                }
            }

            // 2. WebP: "RIFF" .... "WEBP" containing "EXIF" chunk
            if (buf[0] == 'R' && buf[1] == 'I' && buf[2] == 'F' && buf[3] == 'F'
                    && buf[8] == 'W' && buf[9] == 'E' && buf[10] == 'B' && buf[11] == 'P') {
                int offset = 12;
                while (offset + 8 <= buf.length) {
                    int chunkSize = readInt32LE(buf, offset + 4);
                    if (chunkSize < 0 || offset + 8 + chunkSize > buf.length) break;
                    if (buf[offset] == 'E' && buf[offset + 1] == 'X'
                            && buf[offset + 2] == 'I' && buf[offset + 3] == 'F') {
                        int tiffStart = offset + 8;
                        int tiffLen = chunkSize;
                        if (tiffLen >= 6 && buf[tiffStart] == 'E' && buf[tiffStart + 1] == 'x'
                                && buf[tiffStart + 2] == 'i' && buf[tiffStart + 3] == 'f'
                                && buf[tiffStart + 4] == 0 && buf[tiffStart + 5] == 0) {
                            tiffStart += 6;
                            tiffLen -= 6;
                        }
                        return parseTiffOrientation(buf, tiffStart, tiffLen);
                    }
                    offset += 8 + chunkSize + (chunkSize & 1);
                }
            }
        } catch (Throwable ignored) {
        }
        return EXIF_ORIENTATION_NORMAL;
    }

    private static int parseTiffOrientation(@NonNull byte[] buf, int tiffOffset, int maxLen) {
        if (maxLen < 8 || tiffOffset + maxLen > buf.length) return EXIF_ORIENTATION_NORMAL;
        boolean littleEndian;
        if (buf[tiffOffset] == 'I' && buf[tiffOffset + 1] == 'I') {
            littleEndian = true;
        } else if (buf[tiffOffset] == 'M' && buf[tiffOffset + 1] == 'M') {
            littleEndian = false;
        } else {
            return EXIF_ORIENTATION_NORMAL;
        }

        int magic = readUInt16(buf, tiffOffset + 2, littleEndian);
        if (magic != 42) return EXIF_ORIENTATION_NORMAL;

        int ifd0Offset = readInt32(buf, tiffOffset + 4, littleEndian);
        if (ifd0Offset < 8 || ifd0Offset + 2 > maxLen) return EXIF_ORIENTATION_NORMAL;

        int entryCount = readUInt16(buf, tiffOffset + ifd0Offset, littleEndian);
        int pos = tiffOffset + ifd0Offset + 2;
        for (int i = 0; i < entryCount; i++) {
            if (pos + 12 > tiffOffset + maxLen) break;
            int tag = readUInt16(buf, pos, littleEndian);
            if (tag == 0x0112) { // Orientation tag
                int val = readUInt16(buf, pos + 8, littleEndian);
                if (val >= 1 && val <= 8) {
                    return val;
                }
                return EXIF_ORIENTATION_NORMAL;
            }
            pos += 12;
        }
        return EXIF_ORIENTATION_NORMAL;
    }

    private static int readUInt16(@NonNull byte[] b, int off, boolean le) {
        return le
                ? ((b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8))
                : (((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF));
    }

    private static int readInt32(@NonNull byte[] b, int off, boolean le) {
        return le
                ? readInt32LE(b, off)
                : (((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF));
    }

    private static int readInt32LE(@NonNull byte[] b, int off) {
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }

    @NonNull
    private static byte[] readUpTo(@NonNull InputStream in, int maxBytes) throws IOException {
        byte[] buf = new byte[maxBytes];
        int total = 0;
        while (total < maxBytes) {
            int r = in.read(buf, total, maxBytes - total);
            if (r <= 0) break;
            total += r;
        }
        if (total == maxBytes) return buf;
        byte[] trimmed = new byte[Math.max(0, total)];
        System.arraycopy(buf, 0, trimmed, 0, trimmed.length);
        return trimmed;
    }

    // ── Optional Reflective AndroidSVG Delegate (Zero Compile Dependency) ─────

    private static final class ReflectiveAndroidSvgDelegate implements SvgRenderDelegate {
        private final Method getFromInputStreamMethod;
        private final Method getDocumentWidthMethod;
        private final Method getDocumentHeightMethod;
        private final Method setDocumentWidthMethod;
        private final Method setDocumentHeightMethod;
        private final Method renderToCanvasMethod;

        private ReflectiveAndroidSvgDelegate(Method getFromInputStreamMethod,
                                             Method getDocumentWidthMethod,
                                             Method getDocumentHeightMethod,
                                             Method setDocumentWidthMethod,
                                             Method setDocumentHeightMethod,
                                             Method renderToCanvasMethod) {
            this.getFromInputStreamMethod = getFromInputStreamMethod;
            this.getDocumentWidthMethod = getDocumentWidthMethod;
            this.getDocumentHeightMethod = getDocumentHeightMethod;
            this.setDocumentWidthMethod = setDocumentWidthMethod;
            this.setDocumentHeightMethod = setDocumentHeightMethod;
            this.renderToCanvasMethod = renderToCanvasMethod;
        }

        @Nullable
        static ReflectiveAndroidSvgDelegate tryCreate() {
            try {
                Class<?> svgClass = Class.forName("com.caverock.androidsvg.SVG");
                return new ReflectiveAndroidSvgDelegate(
                        svgClass.getMethod("getFromInputStream", InputStream.class),
                        svgClass.getMethod("getDocumentWidth"),
                        svgClass.getMethod("getDocumentHeight"),
                        svgClass.getMethod("setDocumentWidth", float.class),
                        svgClass.getMethod("setDocumentHeight", float.class),
                        svgClass.getMethod("renderToCanvas", Canvas.class)
                );
            } catch (Throwable ignored) {
                return null;
            }
        }

        @NonNull
        @Override
        public float[] measureSvg(@NonNull InputStream svgStream) throws IOException {
            try {
                Object svg = getFromInputStreamMethod.invoke(null, svgStream);
                float w = (Float) getDocumentWidthMethod.invoke(svg);
                float h = (Float) getDocumentHeightMethod.invoke(svg);
                return new float[]{ w, h };
            } catch (Throwable t) {
                throw new IOException("Failed to measure SVG document", t);
            }
        }

        @Override
        public void renderSvgToCanvas(@NonNull InputStream svgStream,
                                      @NonNull Canvas canvas,
                                      int targetWidthPx,
                                      int targetHeightPx) throws IOException {
            try {
                Object svg = getFromInputStreamMethod.invoke(null, svgStream);
                setDocumentWidthMethod.invoke(svg, (float) targetWidthPx);
                setDocumentHeightMethod.invoke(svg, (float) targetHeightPx);
                renderToCanvasMethod.invoke(svg, canvas);
            } catch (Throwable t) {
                throw new IOException("Failed to render SVG to Canvas", t);
            }
        }
    }

    // ── Lifecycle & Cleanup ──────────────────────────────────────────────────

    private void ensureOpen() throws IOException {
        if (closed.get()) {
            throw new IOException("ImageDocumentEngine is already closed");
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        interactiveExecutor.shutdownNow();
        backgroundExecutor.shutdownNow();

        regionDecoderLock.lock();
        try {
            if (regionDecoder != null) {
                try {
                    regionDecoder.recycle();
                } catch (Throwable ignored) {
                }
                regionDecoder = null;
            }
            MedvixStorageResolver.safeClose(regionPfd);
            regionPfd = null;
        } finally {
            regionDecoderLock.unlock();
        }
    }
}