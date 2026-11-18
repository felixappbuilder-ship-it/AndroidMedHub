package com.medhurb.app.viewer.engine;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.pdf.LoadParams;
import android.graphics.pdf.PdfRenderer;
import android.graphics.pdf.content.PdfPageGotoLinkContent;
import android.graphics.pdf.content.PdfPageLinkContent;
import android.graphics.pdf.content.PdfPageTextContent;
import android.graphics.pdf.models.PageMatchBounds;
import android.graphics.pdf.models.selection.PageSelection;
import android.graphics.pdf.models.selection.SelectionBoundary;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.ext.SdkExtensions;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.medhurb.app.viewer.render.BitmapMemoryPool;
import com.medhurb.app.viewer.render.BitmapMemoryPool.CacheKey;
import com.medhurb.app.viewer.render.BitmapMemoryPool.ManagedBitmap;
import com.medhurb.app.viewer.render.BitmapMemoryPool.RenderKind;
import com.medhurb.app.viewer.storage.MedvixStorageResolver;
import com.medhurb.app.viewer.storage.MedvixStorageResolver.ResolvedDocument;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PdfDocumentEngine
 * ============================================================================
 * Production native PDF engine for MedVix.
 *
 * Key responsibilities:
 *  1. Opens seekable ParcelFileDescriptors directly from MedvixStorageResolver
 *     with optional password decryption (API 35+ LoadParams).
 *  2. Serializes all native PdfRenderer.Page lifecycles behind a fair ReentrantLock
 *     so concurrent viewport renders, prefetches, print jobs, and searches never
 *     collide on PdfRenderer's single-open-page invariant.
 *  3. Renders full pages and zoomed viewport sub-regions (destClip + Matrix)
 *     directly into pooled ARGB_8888 Bitmaps from BitmapMemoryPool.
 *  4. Exposes native text extraction, streaming full-text search, text selection,
 *     internal/external links, and PDF saving (PdfRenderer.write) on API 35+ /
 *     SDK Extension 13+, with a clean CompatTextDelegate bridge for API 21-34.
 */
public final class PdfDocumentEngine implements AutoCloseable {

    // ── Constants ────────────────────────────────────────────────────────────

    /** Safe upper bound for single full-page rasterization to prevent GL_MAX_TEXTURE_SIZE errors. */
    public static final int MAX_FULL_PAGE_BITMAP_PX = 3072;

    /** Preview fraction enforced when an unsubscribed user opens a premium PDF. */
    public static final double PREVIEW_PAGE_FRACTION = 0.10d;

    private static final int SNIPPET_CONTEXT_CHARS = 36;

    // ── Domain Exceptions ────────────────────────────────────────────────────

    public static final class PdfPasswordRequiredException extends IOException {
        public PdfPasswordRequiredException(@NonNull String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }

    public static final class PdfCorruptDocumentException extends IOException {
        public PdfCorruptDocumentException(@NonNull String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }

    public static final class PdfPreviewRestrictedException extends IOException {
        public final int requestedPageIndex;
        public final int accessiblePageCount;

        public PdfPreviewRestrictedException(int requestedPageIndex, int accessiblePageCount) {
            super("Page " + (requestedPageIndex + 1) + " is locked in preview mode ("
                    + accessiblePageCount + " preview pages available)");
            this.requestedPageIndex = requestedPageIndex;
            this.accessiblePageCount = accessiblePageCount;
        }
    }

    // ── Optional API 21–34 Compatibility Delegate (e.g. PdfiumAndroid) ────────

    public interface CompatTextDelegate {
        @NonNull
        List<TextSpan> extractTextSpans(@NonNull File backingFile, int pageIndex) throws IOException;

        @NonNull
        List<DocumentLink> extractLinks(@NonNull File backingFile, int pageIndex) throws IOException;

        void close();
    }

    // ── Immutable Data Models ────────────────────────────────────────────────

    public static final class PageMetrics {
        public final int pageIndex;
        public final float rawWidthPt;
        public final float rawHeightPt;
        public final boolean shouldScaleForPrinting;

        public PageMetrics(int pageIndex,
                           float rawWidthPt,
                           float rawHeightPt,
                           boolean shouldScaleForPrinting) {
            this.pageIndex = pageIndex;
            this.rawWidthPt = Math.max(1f, rawWidthPt);
            this.rawHeightPt = Math.max(1f, rawHeightPt);
            this.shouldScaleForPrinting = shouldScaleForPrinting;
        }

        public float getRotatedWidthPt(int rotationDegrees) {
            int rot = normalizeRotation(rotationDegrees);
            return (rot == 90 || rot == 270) ? rawHeightPt : rawWidthPt;
        }

        public float getRotatedHeightPt(int rotationDegrees) {
            int rot = normalizeRotation(rotationDegrees);
            return (rot == 90 || rot == 270) ? rawWidthPt : rawHeightPt;
        }
    }

    public static final class TextSpan {
        @NonNull public final String text;
        @NonNull public final List<RectF> boundsPt;

        public TextSpan(@NonNull String text, @NonNull List<RectF> boundsPt) {
            this.text = text;
            this.boundsPt = Collections.unmodifiableList(new ArrayList<>(boundsPt));
        }
    }

    public static final class SearchMatch {
        public final int pageIndex;
        public final int matchIndexOnPage;
        public final int textStartIndex;
        @NonNull public final String snippet;
        @NonNull public final List<RectF> boundsPt;

        public SearchMatch(int pageIndex,
                           int matchIndexOnPage,
                           int textStartIndex,
                           @NonNull String snippet,
                           @NonNull List<RectF> boundsPt) {
            this.pageIndex = pageIndex;
            this.matchIndexOnPage = matchIndexOnPage;
            this.textStartIndex = textStartIndex;
            this.snippet = snippet;
            this.boundsPt = Collections.unmodifiableList(new ArrayList<>(boundsPt));
        }
    }

    public static final class DocumentLink {
        public final boolean isInternalGoto;
        public final int targetPageIndex;
        public final float targetXPt;
        public final float targetYPt;
        public final float targetZoom;
        @Nullable public final Uri externalUri;
        @NonNull public final List<RectF> boundsPt;

        private DocumentLink(boolean isInternalGoto,
                             int targetPageIndex,
                             float targetXPt,
                             float targetYPt,
                             float targetZoom,
                             @Nullable Uri externalUri,
                             @NonNull List<RectF> boundsPt) {
            this.isInternalGoto = isInternalGoto;
            this.targetPageIndex = targetPageIndex;
            this.targetXPt = targetXPt;
            this.targetYPt = targetYPt;
            this.targetZoom = targetZoom;
            this.externalUri = externalUri;
            this.boundsPt = Collections.unmodifiableList(new ArrayList<>(boundsPt));
        }

        @NonNull
        public static DocumentLink internalGoto(int targetPageIndex,
                                                float targetXPt,
                                                float targetYPt,
                                                float targetZoom,
                                                @NonNull List<RectF> boundsPt) {
            return new DocumentLink(true, targetPageIndex, targetXPt, targetYPt, targetZoom, null, boundsPt);
        }

        @NonNull
        public static DocumentLink externalUri(@NonNull Uri uri, @NonNull List<RectF> boundsPt) {
            return new DocumentLink(false, -1, 0f, 0f, 0f, uri, boundsPt);
        }
    }

    public static final class TextSelectionResult {
        public final int pageIndex;
        @NonNull public final String selectedText;
        public final int startIndex;
        public final int stopIndex;
        @NonNull public final List<RectF> boundsPt;

        public TextSelectionResult(int pageIndex,
                                   @NonNull String selectedText,
                                   int startIndex,
                                   int stopIndex,
                                   @NonNull List<RectF> boundsPt) {
            this.pageIndex = pageIndex;
            this.selectedText = selectedText;
            this.startIndex = startIndex;
            this.stopIndex = stopIndex;
            this.boundsPt = Collections.unmodifiableList(new ArrayList<>(boundsPt));
        }
    }

    // ── Render Request & Async Handles ───────────────────────────────────────

    public static final class RenderRequest {
        public final int pageIndex;
        public final float scale;
        public final int rotation;
        @NonNull public final RenderKind kind;
        @Nullable public final RectF rotatedPageRegionPt;
        public final boolean forPrint;
        public final boolean bypassCache;
        public final boolean highPriority;

        private RenderRequest(Builder b) {
            this.pageIndex = b.pageIndex;
            this.scale = Math.max(0.05f, b.scale);
            this.rotation = normalizeRotation(b.rotation);
            this.kind = b.kind;
            this.rotatedPageRegionPt = b.rotatedPageRegionPt != null ? new RectF(b.rotatedPageRegionPt) : null;
            this.forPrint = b.forPrint;
            this.bypassCache = b.bypassCache;
            this.highPriority = b.highPriority;
        }

        public static final class Builder {
            private final int pageIndex;
            private float scale = 1.0f;
            private int rotation = 0;
            private RenderKind kind = RenderKind.FULL_PAGE;
            @Nullable private RectF rotatedPageRegionPt = null;
            private boolean forPrint = false;
            private boolean bypassCache = false;
            private boolean highPriority = true;

            public Builder(int pageIndex) {
                this.pageIndex = pageIndex;
            }

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
             * Optional sub-region in rotated page point coordinates [0..rotW, 0..rotH].
             * When set, only this visible tile is rasterized via PdfRenderer destClip.
             */
            @NonNull
            public Builder setRotatedPageRegionPt(@Nullable RectF regionPt) {
                this.rotatedPageRegionPt = regionPt;
                if (regionPt != null) {
                    this.kind = RenderKind.VIEWPORT_TILE;
                }
                return this;
            }

            @NonNull
            public Builder setForPrint(boolean forPrint) {
                this.forPrint = forPrint;
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

        /** Called on main thread on error (not called if cancelled). */
        void onRenderError(@NonNull RenderRequest request, @NonNull Throwable error);
    }

    public interface SearchCallback {
        /** Streamed on main thread as each page with matches completes. */
        void onPageMatchesFound(int pageIndex, @NonNull List<SearchMatch> pageMatches, int runningTotal);

        /** Called on main thread when the entire accessible document scan finishes. */
        void onSearchCompleted(int totalMatches);

        /** Called on main thread if search fails unexpectedly. */
        void onSearchError(@NonNull Throwable error);
    }

    public interface TextCallback {
        void onTextExtracted(int pageIndex, @NonNull String fullText, @NonNull List<TextSpan> spans);
        void onTextError(int pageIndex, @NonNull Throwable error);
    }

    public interface LinksCallback {
        void onLinksExtracted(int pageIndex, @NonNull List<DocumentLink> links);
        void onLinksError(int pageIndex, @NonNull Throwable error);
    }

    // ── Instance State ───────────────────────────────────────────────────────

    @NonNull private final ResolvedDocument resolvedDoc;
    @NonNull private final BitmapMemoryPool memoryPool;
    @NonNull private final String docKey;
    @NonNull private final ParcelFileDescriptor pfd;
    @NonNull private final PdfRenderer renderer;
    @Nullable private final CompatTextDelegate compatDelegate;

    private final int totalPageCount;
    private final boolean previewMode;
    private final int accessiblePageCount;
    private final boolean passwordProtected;

    // Fair lock guarantees interactive viewport renders aren't starved by background search
    private final ReentrantLock renderLock = new ReentrantLock(true);
    private final PageMetrics[] metricsCache;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final ExecutorService interactiveExecutor;
    private final ExecutorService backgroundExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── Construction & Factory ───────────────────────────────────────────────

    private PdfDocumentEngine(@NonNull ResolvedDocument resolvedDoc,
                              @NonNull BitmapMemoryPool memoryPool,
                              @NonNull ParcelFileDescriptor pfd,
                              @NonNull PdfRenderer renderer,
                              boolean previewMode,
                              boolean passwordProtected,
                              @Nullable CompatTextDelegate compatDelegate) {
        this.resolvedDoc = resolvedDoc;
        this.memoryPool = memoryPool;
        this.docKey = resolvedDoc.slugId;
        this.pfd = pfd;
        this.renderer = renderer;
        this.previewMode = previewMode;
        this.passwordProtected = passwordProtected;
        this.compatDelegate = compatDelegate;

        this.totalPageCount = Math.max(0, renderer.getPageCount());
        this.accessiblePageCount = previewMode
                ? Math.min(totalPageCount, Math.max(1, (int) Math.ceil(totalPageCount * PREVIEW_PAGE_FRACTION)))
                : totalPageCount;
        this.metricsCache = new PageMetrics[this.totalPageCount];

        this.interactiveExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "MedvixPdf-Interactive-" + docKey);
            t.setPriority(Thread.NORM_PRIORITY + 1);
            return t;
        });
        this.backgroundExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "MedvixPdf-Background-" + docKey);
            t.setPriority(Thread.MIN_PRIORITY + 1);
            return t;
        });
    }

    /**
     * Opens a ResolvedDocument with native PdfRenderer.
     *
     * @throws PdfPasswordRequiredException if the PDF is encrypted and password is null/wrong
     * @throws PdfCorruptDocumentException  if the file is malformed or unreadable
     */
    @NonNull
    public static PdfDocumentEngine open(@NonNull Context context,
                                         @NonNull ResolvedDocument resolvedDoc,
                                         @NonNull BitmapMemoryPool memoryPool,
                                         @Nullable String password,
                                         boolean previewMode,
                                         @Nullable CompatTextDelegate compatDelegate) throws IOException {
        if (!resolvedDoc.isPdf()) {
            throw new PdfCorruptDocumentException("ResolvedDocument is not a PDF: " + resolvedDoc.mimeType, null);
        }

        ParcelFileDescriptor pfd = resolvedDoc.openSeekableReadOnlyPfd(context);
        try {
            PdfRenderer renderer = createPlatformRenderer(pfd, password);
            if (renderer.getPageCount() <= 0) {
                renderer.close();
                throw new PdfCorruptDocumentException("PDF document contains 0 pages", null);
            }
            return new PdfDocumentEngine(
                    resolvedDoc,
                    memoryPool,
                    pfd,
                    renderer,
                    previewMode,
                    !TextUtils.isEmpty(password),
                    compatDelegate
            );
        } catch (PdfPasswordRequiredException pwdEx) {
            MedvixStorageResolver.safeClose(pfd);
            throw pwdEx;
        } catch (IOException ioEx) {
            MedvixStorageResolver.safeClose(pfd);
            throw new PdfCorruptDocumentException("Unable to parse PDF document: " + ioEx.getMessage(), ioEx);
        } catch (Throwable t) {
            MedvixStorageResolver.safeClose(pfd);
            throw new PdfCorruptDocumentException("Unexpected native PDF open failure", t);
        }
    }

    @NonNull
    private static PdfRenderer createPlatformRenderer(@NonNull ParcelFileDescriptor pfd,
                                                      @Nullable String password) throws IOException {
        if (isUpgradedPdfApiSupported()) {
            return createUpgradedRenderer(pfd, password);
        }
        try {
            return new PdfRenderer(pfd);
        } catch (SecurityException secEx) {
            throw new PdfPasswordRequiredException(
                    "Password-protected PDF requires Android 15+ or upgraded PDF module", secEx);
        }
    }

    @RequiresApi(api = 35)
    @NonNull
    private static PdfRenderer createUpgradedRenderer(@NonNull ParcelFileDescriptor pfd,
                                                      @Nullable String password) throws IOException {
        try {
            if (!TextUtils.isEmpty(password)) {
                LoadParams params = new LoadParams.Builder()
                        .setPassword(password)
                        .build();
                return new PdfRenderer(pfd, params);
            }
            return new PdfRenderer(pfd);
        } catch (SecurityException secEx) {
            throw new PdfPasswordRequiredException("Document requires a valid password", secEx);
        }
    }

    // ── Capability & Document Inspection ─────────────────────────────────────

    public static boolean isUpgradedPdfApiSupported() {
        if (Build.VERSION.SDK_INT >= 35) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                return SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13;
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    public boolean supportsTextAndSearch() {
        return isUpgradedPdfApiSupported() || (compatDelegate != null && resolvedDoc.hasBackingFile());
    }

    public boolean supportsNativeSelection() {
        return isUpgradedPdfApiSupported();
    }

    public boolean supportsDocumentWrite() {
        return isUpgradedPdfApiSupported();
    }

    @NonNull
    public ResolvedDocument getResolvedDocument() {
        return resolvedDoc;
    }

    @NonNull
    public String getDocKey() {
        return docKey;
    }

    public int getTotalPageCount() {
        return totalPageCount;
    }

    public int getAccessiblePageCount() {
        return accessiblePageCount;
    }

    public boolean isPreviewMode() {
        return previewMode;
    }

    public boolean isPageLockedByPreview(int pageIndex) {
        return previewMode && pageIndex >= accessiblePageCount;
    }

    public boolean isPasswordProtected() {
        return passwordProtected;
    }

    public boolean isClosed() {
        return closed.get();
    }

    // ── Page Geometry & Metrics ──────────────────────────────────────────────

    /**
     * Returns exact PageMetrics (width, height in PDF points) for pageIndex.
     * Cached in memory after first inspection.
     */
    @NonNull
    public PageMetrics getPageMetrics(int pageIndex) throws IOException {
        validatePageIndex(pageIndex, false);
        PageMetrics cached = metricsCache[pageIndex];
        if (cached != null) return cached;

        ensureOpen();
        renderLock.lock();
        try {
            ensureOpen();
            if (metricsCache[pageIndex] != null) {
                return metricsCache[pageIndex];
            }
            try (PdfRenderer.Page page = renderer.openPage(pageIndex)) {
                PageMetrics m = new PageMetrics(
                        pageIndex,
                        page.getWidth(),
                        page.getHeight(),
                        true // Safe fallback for older compileSdk
                );
                metricsCache[pageIndex] = m;
                return m;
            }
        } finally {
            renderLock.unlock();
        }
    }

    /**
     * Returns cached PageMetrics if already measured, or falls back to page 0's
     * dimensions so RecyclerView layout passes never stall on unvisited pages.
     */
    @NonNull
    public PageMetrics getEstimatedPageMetrics(int pageIndex) {
        if (pageIndex >= 0 && pageIndex < totalPageCount && metricsCache[pageIndex] != null) {
            return metricsCache[pageIndex];
        }
        if (totalPageCount > 0 && metricsCache[0] != null) {
            PageMetrics p0 = metricsCache[0];
            return new PageMetrics(pageIndex, p0.rawWidthPt, p0.rawHeightPt, p0.shouldScaleForPrinting);
        }
        // Standard US Letter fallback (612 x 792 pt) prior to page 0 measurement
        return new PageMetrics(pageIndex, 612f, 792f, true);
    }

    /**
     * Background task that populates PageMetrics across all accessible pages
     * so heterogeneous page sizes (landscape diagrams mixed with portrait pages)
     * snap cleanly during fast scrubbing.
     */
    @NonNull
    public TaskHandle premeasureAllPagesAsync(@Nullable Runnable onComplete) {
        TaskHandle handle = new TaskHandle();
        Future<?> future = backgroundExecutor.submit(() -> {
            for (int i = 0; i < accessiblePageCount; i++) {
                if (handle.isCancelled() || closed.get()) return;
                if (metricsCache[i] != null) continue;
                try {
                    getPageMetrics(i);
                } catch (IOException ignored) {
                }
            }
            if (!handle.isCancelled() && !closed.get() && onComplete != null) {
                mainHandler.post(onComplete);
            }
        });
        handle.attachFuture(future);
        return handle;
    }

    // ── Rendering Pipeline (Full Page + Zoomed Viewport Tile) ────────────────

    /**
     * Checks BitmapMemoryPool synchronously on the UI thread for an immediate
     * zero-flicker cache hit before scheduling an asynchronous render.
     */
    @Nullable
    public ManagedBitmap acquireCachedBitmap(@NonNull RenderRequest request) {
        if (request.bypassCache || closed.get()) return null;
        CacheKey key = buildCacheKey(request);
        return key != null ? memoryPool.get(key) : null;
    }

    /**
     * Asynchronously renders a full page or zoomed viewport sub-region.
     * Delivers a retained ManagedBitmap lease on the main thread.
     */
    @NonNull
    public TaskHandle renderAsync(@NonNull RenderRequest request,
                                  @NonNull RenderCallback callback) {
        TaskHandle handle = new TaskHandle();

        // Fast-path synchronous cache check
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
                // Deliberately silent on cancellation
            } catch (Throwable error) {
                if (!handle.isCancelled() && !closed.get()) {
                    mainHandler.post(() -> callback.onRenderError(request, error));
                }
            }
        });

        handle.attachFuture(future);
        return handle;
    }

    /**
     * Synchronous render entry point for PrintDocumentAdapter or dedicated worker threads.
     * Caller owns the returned ManagedBitmap lease and MUST call release().
     */
    @NonNull
    public ManagedBitmap renderSync(@NonNull RenderRequest request) throws IOException {
        return renderInternal(request, null);
    }

    @NonNull
    private ManagedBitmap renderInternal(@NonNull RenderRequest request,
                                         @Nullable TaskHandle handle) throws IOException {
        ensureOpen();
        validatePageIndex(request.pageIndex, true);

        if (handle != null && handle.isCancelled()) {
            throw new CancellationException("Render cancelled prior to lock");
        }

        // Re-check cache after queue wait in case an identical request just finished
        if (!request.bypassCache) {
            CacheKey key = buildCacheKey(request);
            if (key != null) {
                ManagedBitmap hit = memoryPool.get(key);
                if (hit != null) {
                    return hit;
                }
            }
        }

        renderLock.lock();
        Bitmap targetBitmap = null;
        try {
            ensureOpen();
            if (handle != null && handle.isCancelled()) {
                throw new CancellationException("Render cancelled inside lock");
            }

            try (PdfRenderer.Page page = renderer.openPage(request.pageIndex)) {
                float rawW = Math.max(1f, page.getWidth());
                float rawH = Math.max(1f, page.getHeight());

                if (metricsCache[request.pageIndex] == null) {
                    metricsCache[request.pageIndex] = new PageMetrics(
                            request.pageIndex, rawW, rawH, true // Safe fallback
                    );
                }

                int rotation = request.rotation;
                boolean swapped = (rotation == 90 || rotation == 270);
                float rotPageW = swapped ? rawH : rawW;
                float rotPageH = swapped ? rawW : rawH;

                int renderMode = request.forPrint
                        ? PdfRenderer.Page.RENDER_MODE_FOR_PRINT
                        : PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY;

                Matrix matrix = new Matrix();
                Rect destClip = null;
                int outW;
                int outH;

                if (request.rotatedPageRegionPt != null) {
                    // ── Sub-Viewport Tile Render (Zoomed Crisp Mode) ─────────
                    RectF clampedRegion = clampRegionToPage(request.rotatedPageRegionPt, rotPageW, rotPageH);
                    float effectiveScale = clampScaleToMaxDimension(
                            request.scale, clampedRegion.width(), clampedRegion.height());

                    outW = Math.max(1, Math.round(clampedRegion.width() * effectiveScale));
                    outH = Math.max(1, Math.round(clampedRegion.height() * effectiveScale));
                    destClip = new Rect(0, 0, outW, outH);

                    applyBasePageTransform(matrix, effectiveScale, rotation, rotPageW, rotPageH);
                    matrix.postTranslate(-clampedRegion.left * effectiveScale, -clampedRegion.top * effectiveScale);
                } else {
                    // ── Full-Page Render ─────────────────────────────────────
                    float effectiveScale = clampScaleToMaxDimension(request.scale, rotPageW, rotPageH);
                    outW = Math.max(1, Math.round(rotPageW * effectiveScale));
                    outH = Math.max(1, Math.round(rotPageH * effectiveScale));

                    applyBasePageTransform(matrix, effectiveScale, rotation, rotPageW, rotPageH);
                }

                if (handle != null && handle.isCancelled()) {
                    throw new CancellationException("Render cancelled prior to bitmap checkout");
                }

                // White background required because RENDER_MODE_FOR_DISPLAY alpha-blends onto target
                targetBitmap = memoryPool.obtainExact(outW, outH, Bitmap.Config.ARGB_8888, Color.WHITE);
                page.render(targetBitmap, destClip, matrix, renderMode);

                Bitmap completed = targetBitmap;
                targetBitmap = null; // Ownership transferred to ManagedBitmap

                if (request.bypassCache || request.forPrint) {
                    return memoryPool.wrapStandalone(completed, true);
                }

                CacheKey key = buildCacheKey(request);
                if (key != null) {
                    return memoryPool.putAndAcquire(key, completed, true);
                }
                return memoryPool.wrapStandalone(completed, true);
            }
        } catch (Throwable t) {
            if (targetBitmap != null) {
                memoryPool.recycleOrPool(targetBitmap);
            }
            if (t instanceof IOException) throw (IOException) t;
            if (t instanceof CancellationException) throw (CancellationException) t;
            throw new IOException("Native PDF rasterization failed on page " + request.pageIndex, t);
        } finally {
            renderLock.unlock();
        }
    }

    private static void applyBasePageTransform(@NonNull Matrix matrix,
                                               float scale,
                                               int rotation,
                                               float rotPageW,
                                               float rotPageH) {
        matrix.postScale(scale, scale);
        if (rotation == 90) {
            matrix.postRotate(90f);
            matrix.postTranslate(rotPageW * scale, 0f);
        } else if (rotation == 180) {
            matrix.postRotate(180f);
            matrix.postTranslate(rotPageW * scale, rotPageH * scale);
        } else if (rotation == 270) {
            matrix.postRotate(270f);
            matrix.postTranslate(0f, rotPageH * scale);
        }
    }

    private static float clampScaleToMaxDimension(float requestedScale, float widthPt, float heightPt) {
        float maxSide = Math.max(widthPt, heightPt);
        if (maxSide <= 0f) return requestedScale;
        float maxAllowedScale = (float) MAX_FULL_PAGE_BITMAP_PX / maxSide;
        return Math.min(requestedScale, Math.max(0.05f, maxAllowedScale));
    }

    @NonNull
    private static RectF clampRegionToPage(@NonNull RectF region, float rotPageW, float rotPageH) {
        float l = Math.max(0f, Math.min(rotPageW - 1f, region.left));
        float t = Math.max(0f, Math.min(rotPageH - 1f, region.top));
        float r = Math.max(l + 1f, Math.min(rotPageW, region.right));
        float b = Math.max(t + 1f, Math.min(rotPageH, region.bottom));
        return new RectF(l, t, r, b);
    }

    @Nullable
    private CacheKey buildCacheKey(@NonNull RenderRequest request) {
        if (request.rotatedPageRegionPt != null) {
            Rect rounded = new Rect(
                    Math.round(request.rotatedPageRegionPt.left),
                    Math.round(request.rotatedPageRegionPt.top),
                    Math.round(request.rotatedPageRegionPt.right),
                    Math.round(request.rotatedPageRegionPt.bottom)
            );
            return CacheKey.forTile(docKey, request.pageIndex, request.scale, request.rotation, rounded);
        }
        return CacheKey.forPage(docKey, request.pageIndex, request.kind, request.scale, request.rotation);
    }

    // ── Text Extraction, Streaming Search, Selection & Links ─────────────────

    /**
     * Extracts structured text spans and bounding rectangles for a single page.
     */
    @NonNull
    public TaskHandle extractPageTextAsync(int pageIndex, @NonNull TextCallback callback) {
        TaskHandle handle = new TaskHandle();
        Future<?> future = backgroundExecutor.submit(() -> {
            if (handle.isCancelled() || closed.get()) return;
            try {
                List<TextSpan> spans = extractPageTextSync(pageIndex);
                StringBuilder full = new StringBuilder();
                for (int i = 0; i < spans.size(); i++) {
                    if (i > 0) full.append('\n');
                    full.append(spans.get(i).text);
                }
                String fullText = full.toString();
                if (!handle.isCancelled() && !closed.get()) {
                    mainHandler.post(() -> callback.onTextExtracted(pageIndex, fullText, spans));
                }
            } catch (Throwable t) {
                if (!handle.isCancelled() && !closed.get()) {
                    mainHandler.post(() -> callback.onTextError(pageIndex, t));
                }
            }
        });
        handle.attachFuture(future);
        return handle;
    }

    @NonNull
    public List<TextSpan> extractPageTextSync(int pageIndex) throws IOException {
        ensureOpen();
        validatePageIndex(pageIndex, true);

        if (isUpgradedPdfApiSupported()) {
            return extractPageTextUpgraded(pageIndex);
        }
        if (compatDelegate != null && resolvedDoc.hasBackingFile()) {
            File file = resolvedDoc.getBackingFile();
            if (file != null) {
                return compatDelegate.extractTextSpans(file, pageIndex);
            }
        }
        return Collections.emptyList();
    }

    @RequiresApi(api = 35)
    @NonNull
    private List<TextSpan> extractPageTextUpgraded(int pageIndex) throws IOException {
        renderLock.lock();
        try {
            ensureOpen();
            try (PdfRenderer.Page page = renderer.openPage(pageIndex)) {
                List<PdfPageTextContent> rawContents = page.getTextContents();
                if (rawContents == null || rawContents.isEmpty()) {
                    return Collections.emptyList();
                }
                List<TextSpan> result = new ArrayList<>(rawContents.size());
                for (PdfPageTextContent item : rawContents) {
                    if (item == null || TextUtils.isEmpty(item.getText())) continue;
                    result.add(new TextSpan(item.getText(), item.getBounds()));
                }
                return result;
            }
        } finally {
            renderLock.unlock();
        }
    }

    /**
     * Streams full-text search results across all accessible pages on a background
     * thread, delivering matches per page immediately as they are discovered.
     */
    @NonNull
    public TaskHandle searchDocumentAsync(@NonNull String rawQuery,
                                          boolean caseSensitive,
                                          boolean wholeWord,
                                          @NonNull SearchCallback callback) {
        TaskHandle handle = new TaskHandle();
        final String query = rawQuery.trim();
        if (query.isEmpty()) {
            mainHandler.post(() -> callback.onSearchCompleted(0));
            return handle;
        }

        Future<?> future = backgroundExecutor.submit(() -> {
            int runningTotal = 0;
            try {
                Pattern filterPattern = buildSearchFilterPattern(query, caseSensitive, wholeWord);

                for (int pageIdx = 0; pageIdx < accessiblePageCount; pageIdx++) {
                    if (handle.isCancelled() || closed.get()) return;

                    List<SearchMatch> pageMatches = searchSinglePageSync(pageIdx, query, filterPattern);
                    if (!pageMatches.isEmpty()) {
                        runningTotal += pageMatches.size();
                        final int pageNum = pageIdx;
                        final int currentTotal = runningTotal;
                        if (!handle.isCancelled() && !closed.get()) {
                            mainHandler.post(() ->
                                    callback.onPageMatchesFound(pageNum, pageMatches, currentTotal));
                        }
                    }
                }

                final int finalTotal = runningTotal;
                if (!handle.isCancelled() && !closed.get()) {
                    mainHandler.post(() -> callback.onSearchCompleted(finalTotal));
                }
            } catch (Throwable t) {
                if (!handle.isCancelled() && !closed.get()) {
                    mainHandler.post(() -> callback.onSearchError(t));
                }
            }
        });

        handle.attachFuture(future);
        return handle;
    }

    @NonNull
    private List<SearchMatch> searchSinglePageSync(int pageIndex,
                                                   @NonNull String query,
                                                   @Nullable Pattern filterPattern) throws IOException {
        ensureOpen();
        if (isUpgradedPdfApiSupported()) {
            return searchSinglePageUpgraded(pageIndex, query, filterPattern);
        }
        if (compatDelegate != null && resolvedDoc.hasBackingFile()) {
            File file = resolvedDoc.getBackingFile();
            if (file != null) {
                List<TextSpan> spans = compatDelegate.extractTextSpans(file, pageIndex);
                return matchAgainstTextSpans(pageIndex, spans, filterPattern);
            }
        }
        return Collections.emptyList();
    }

    @RequiresApi(api = 35)
    @NonNull
    private List<SearchMatch> searchSinglePageUpgraded(int pageIndex,
                                                       @NonNull String query,
                                                       @Nullable Pattern filterPattern) throws IOException {
        renderLock.lock();
        try {
            ensureOpen();
            try (PdfRenderer.Page page = renderer.openPage(pageIndex)) {
                List<PageMatchBounds> rawMatches = page.searchText(query);
                if (rawMatches == null || rawMatches.isEmpty()) {
                    return Collections.emptyList();
                }

                String pageText = joinPageText(page.getTextContents());
                List<SearchMatch> filtered = new ArrayList<>(rawMatches.size());

                for (PageMatchBounds m : rawMatches) {
                    if (m == null) continue;
                    int startIdx = m.getTextStartIndex();
                    String snippet = extractSnippet(pageText, startIdx, query.length());

                    if (filterPattern != null && !pageText.isEmpty() && startIdx >= 0 && startIdx < pageText.length()) {
                        int endIdx = Math.min(pageText.length(), startIdx + query.length());
                        String matchedSlice = pageText.substring(startIdx, endIdx);
                        if (!filterPattern.matcher(matchedSlice).matches()) {
                            continue;
                        }
                        if (isWholeWordRequired(filterPattern)) {
                            boolean leftBoundary = (startIdx == 0)
                                    || !Character.isLetterOrDigit(pageText.charAt(startIdx - 1));
                            boolean rightBoundary = (endIdx >= pageText.length())
                                    || !Character.isLetterOrDigit(pageText.charAt(endIdx));
                            if (!leftBoundary || !rightBoundary) {
                                continue;
                            }
                        }
                    }

                    filtered.add(new SearchMatch(
                            pageIndex,
                            filtered.size(),
                            startIdx,
                            snippet,
                            m.getBounds()
                    ));
                }
                return filtered;
            }
        } finally {
            renderLock.unlock();
        }
    }

    @NonNull
    private static List<SearchMatch> matchAgainstTextSpans(int pageIndex,
                                                           @NonNull List<TextSpan> spans,
                                                           @Nullable Pattern pattern) {
        if (spans.isEmpty() || pattern == null) return Collections.emptyList();
        List<SearchMatch> results = new ArrayList<>();
        for (TextSpan span : spans) {
            Matcher matcher = pattern.matcher(span.text);
            while (matcher.find()) {
                String snippet = extractSnippet(span.text, matcher.start(), matcher.end() - matcher.start());
                results.add(new SearchMatch(
                        pageIndex,
                        results.size(),
                        matcher.start(),
                        snippet,
                        span.boundsPt
                ));
            }
        }
        return results;
    }

    /**
     * Native text selection between two PDF page point coordinates (API 35+).
     */
    @Nullable
    public TextSelectionResult selectTextAtPoints(int pageIndex,
                                                    @NonNull Point startPt,
                                                    @NonNull Point stopPt) throws IOException {
        if (!isUpgradedPdfApiSupported()) return null;
        ensureOpen();
        validatePageIndex(pageIndex, true);
        return selectTextUpgraded(pageIndex, startPt, stopPt);
    }

    @RequiresApi(api = 35)
    @Nullable
    private TextSelectionResult selectTextUpgraded(int pageIndex,
                                                   @NonNull Point startPt,
                                                   @NonNull Point stopPt) throws IOException {
        renderLock.lock();
        try {
            ensureOpen();
            try (PdfRenderer.Page page = renderer.openPage(pageIndex)) {
                SelectionBoundary start = new SelectionBoundary(startPt);
                SelectionBoundary stop = new SelectionBoundary(stopPt);
                PageSelection selection = page.selectContent(start, stop);
                if (selection == null) return null;

                List<PdfPageTextContent> contents = selection.getSelectedTextContents();
                String text = joinPageText(contents);
                if (TextUtils.isEmpty(text)) return null;

                List<RectF> allBounds = new ArrayList<>();
                if (contents != null) {
                    for (PdfPageTextContent c : contents) {
                        if (c != null && c.getBounds() != null) {
                            allBounds.addAll(c.getBounds());
                        }
                    }
                }

                int startIdx = selection.getStart() != null ? selection.getStart().getIndex() : -1;
                int stopIdx = selection.getStop() != null ? selection.getStop().getIndex() : -1;
                return new TextSelectionResult(pageIndex, text, startIdx, stopIdx, allBounds);
            }
        } finally {
            renderLock.unlock();
        }
    }

    /**
     * Extracts internal cross-reference goto links and external web links on a page.
     */
    @NonNull
    public TaskHandle extractPageLinksAsync(int pageIndex, @NonNull LinksCallback callback) {
        TaskHandle handle = new TaskHandle();
        Future<?> future = backgroundExecutor.submit(() -> {
            if (handle.isCancelled() || closed.get()) return;
            try {
                List<DocumentLink> links = extractPageLinksSync(pageIndex);
                if (!handle.isCancelled() && !closed.get()) {
                    mainHandler.post(() -> callback.onLinksExtracted(pageIndex, links));
                }
            } catch (Throwable t) {
                if (!handle.isCancelled() && !closed.get()) {
                    mainHandler.post(() -> callback.onLinksError(pageIndex, t));
                }
            }
        });
        handle.attachFuture(future);
        return handle;
    }

    @NonNull
    public List<DocumentLink> extractPageLinksSync(int pageIndex) throws IOException {
        ensureOpen();
        validatePageIndex(pageIndex, true);

        if (isUpgradedPdfApiSupported()) {
            return extractPageLinksUpgraded(pageIndex);
        }
        if (compatDelegate != null && resolvedDoc.hasBackingFile()) {
            File file = resolvedDoc.getBackingFile();
            if (file != null) {
                return compatDelegate.extractLinks(file, pageIndex);
            }
        }
        return Collections.emptyList();
    }

    @RequiresApi(api = 35)
    @NonNull
    private List<DocumentLink> extractPageLinksUpgraded(int pageIndex) throws IOException {
        renderLock.lock();
        try {
            ensureOpen();
            try (PdfRenderer.Page page = renderer.openPage(pageIndex)) {
                List<DocumentLink> out = new ArrayList<>();

                List<PdfPageGotoLinkContent> gotoLinks = page.getGotoLinks();
                if (gotoLinks != null) {
                    for (PdfPageGotoLinkContent g : gotoLinks) {
                        if (g == null || g.getDestination() == null) continue;
                        PdfPageGotoLinkContent.Destination dest = g.getDestination();
                        out.add(DocumentLink.internalGoto(
                                dest.getPageNumber(),
                                dest.getXCoordinate(),
                                dest.getYCoordinate(),
                                dest.getZoom(),
                                g.getBounds()
                        ));
                    }
                }

                List<PdfPageLinkContent> webLinks = page.getLinkContents();
                if (webLinks != null) {
                    for (PdfPageLinkContent w : webLinks) {
                        if (w == null || w.getUri() == null) continue;
                        out.add(DocumentLink.externalUri(w.getUri(), w.getBounds()));
                    }
                }

                return out;
            }
        } finally {
            renderLock.unlock();
        }
    }

    // ── Document Saving & Cover Thumbnail Generation ─────────────────────────

    /**
     * Writes the open PDF (including any native form/ink edits) to destinationPfd.
     * Requires API 35+ / SDK Extension 13.
     */
    public void saveDocumentTo(@NonNull ParcelFileDescriptor destinationPfd,
                               boolean removePassword) throws IOException {
        if (!isUpgradedPdfApiSupported()) {
            throw new UnsupportedOperationException("Native PDF write requires Android 15+ (API 35)");
        }
        ensureOpen();
        writeUpgraded(destinationPfd, removePassword);
    }

    @RequiresApi(api = 35)
    private void writeUpgraded(@NonNull ParcelFileDescriptor destinationPfd,
                               boolean removePassword) throws IOException {
        renderLock.lock();
        try {
            ensureOpen();
            renderer.write(destinationPfd, removePassword);
        } finally {
            renderLock.unlock();
        }
    }

    /**
     * Ensures an offline cover thumbnail exists in cache/thumbnails/{slug}.jpg.
     * If missing, renders page 0 at thumbnail scale and writes it atomically.
     */
    @NonNull
    public TaskHandle ensureThumbnailCachedAsync(@NonNull Context context,
                                                 int maxDimensionPx,
                                                 @Nullable Runnable onSaved) {
        TaskHandle handle = new TaskHandle();
        final Context appCtx = context.getApplicationContext();

        Future<?> future = backgroundExecutor.submit(() -> {
            if (handle.isCancelled() || closed.get()) return;
            if (resolvedDoc.findExistingThumbnail(appCtx) != null) return;

            try {
                PageMetrics p0 = getPageMetrics(0);
                float maxSide = Math.max(p0.rawWidthPt, p0.rawHeightPt);
                float thumbScale = Math.max(0.1f, Math.min(1.5f, (float) maxDimensionPx / maxSide));

                RenderRequest req = new RenderRequest.Builder(0)
                        .setScale(thumbScale)
                        .setKind(RenderKind.THUMBNAIL)
                        .setHighPriority(false)
                        .build();

                ManagedBitmap lease = renderSync(req);
                try {
                    if (handle.isCancelled() || closed.get()) return;
                    File target = resolvedDoc.getWritableThumbnailTarget(appCtx, "jpg");
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
                // Thumbnail generation is best-effort
            }
        });

        handle.attachFuture(future);
        return handle;
    }

    // ── Coordinate Space Conversion (PDF Points <-> Rotated View Pixels) ─────

    /**
     * Converts a PDF-point rectangle (unrotated PDF space) into scaled, rotated
     * page pixel coordinates for drawing selection/search/link overlays.
     */
    @NonNull
    public static RectF pdfPointsToRotatedViewRect(@NonNull RectF pdfPtRect,
                                                   float rawPageWidthPt,
                                                   float rawPageHeightPt,
                                                   int rotationDegrees,
                                                   float scale) {
        int rot = normalizeRotation(rotationDegrees);
        float l = pdfPtRect.left * scale;
        float t = pdfPtRect.top * scale;
        float r = pdfPtRect.right * scale;
        float b = pdfPtRect.bottom * scale;
        float w = rawPageWidthPt * scale;
        float h = rawPageHeightPt * scale;

        switch (rot) {
            case 90:
                return new RectF(h - b, l, h - t, r);
            case 180:
                return new RectF(w - r, h - b, w - l, h - t);
            case 270:
                return new RectF(t, w - r, b, w - l);
            case 0:
            default:
                return new RectF(l, t, r, b);
        }
    }

    /**
     * Converts a touch coordinate in rotated page pixel space back into unrotated
     * PDF point space (for PdfRenderer.Page.selectContent and link hit-testing).
     */
    @NonNull
    public static PointF rotatedViewPointToPdfPoint(float viewX,
                                                      float viewY,
                                                      float rawPageWidthPt,
                                                      float rawPageHeightPt,
                                                      int rotationDegrees,
                                                      float scale) {
        float safeScale = Math.max(0.0001f, scale);
        int rot = normalizeRotation(rotationDegrees);
        float sx = viewX / safeScale;
        float sy = viewY / safeScale;

        switch (rot) {
            case 90:
                return new PointF(sy, rawPageHeightPt - sx);
            case 180:
                return new PointF(rawPageWidthPt - sx, rawPageHeightPt - sy);
            case 270:
                return new PointF(rawPageWidthPt - sy, sx);
            case 0:
            default:
                return new PointF(sx, sy);
        }
    }

    // ── Lifecycle & Cleanup ──────────────────────────────────────────────────

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        interactiveExecutor.shutdownNow();
        backgroundExecutor.shutdownNow();

        renderLock.lock();
        try {
            if (compatDelegate != null) {
                try {
                    compatDelegate.close();
                } catch (Throwable ignored) {
                }
            }
            try {
                renderer.close();
            } catch (Throwable ignored) {
            }
            MedvixStorageResolver.safeClose(pfd);
        } finally {
            renderLock.unlock();
        }
    }

    // ── Internal Validation & Text Helpers ───────────────────────────────────

    private void ensureOpen() throws IOException {
        if (closed.get()) {
            throw new IOException("PdfDocumentEngine is already closed");
        }
    }

    private void validatePageIndex(int pageIndex, boolean enforcePreviewCap) throws IOException {
        if (pageIndex < 0 || pageIndex >= totalPageCount) {
            throw new IndexOutOfBoundsException(
                    "pageIndex " + pageIndex + " out of bounds [0.." + (totalPageCount - 1) + "]");
        }
        if (enforcePreviewCap && isPageLockedByPreview(pageIndex)) {
            throw new PdfPreviewRestrictedException(pageIndex, accessiblePageCount);
        }
    }

    public static int normalizeRotation(int deg) {
        int r = deg % 360;
        return r < 0 ? r + 360 : r;
    }

    @RequiresApi(api = 35)
    @NonNull
    private static String joinPageText(@Nullable List<PdfPageTextContent> contents) {
        if (contents == null || contents.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < contents.size(); i++) {
            PdfPageTextContent c = contents.get(i);
            if (c == null || TextUtils.isEmpty(c.getText())) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(c.getText());
        }
        return sb.toString();
    }

    @NonNull
    private static Pattern buildSearchFilterPattern(@NonNull String query,
                                                    boolean caseSensitive,
                                                    boolean wholeWord) {
        String quoted = Pattern.quote(query);
        String expr = wholeWord ? "\\b" + quoted + "\\b" : quoted;
        int flags = caseSensitive ? 0 : (Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        return Pattern.compile(expr, flags);
    }

    private static boolean isWholeWordRequired(@NonNull Pattern pattern) {
        return pattern.pattern().startsWith("\\b");
    }

    @NonNull
    private static String extractSnippet(@NonNull String text, int startIndex, int matchLen) {
        if (text.isEmpty() || startIndex < 0 || startIndex >= text.length()) {
            return "";
        }
        int safeLen = Math.max(1, matchLen);
        int from = Math.max(0, startIndex - SNIPPET_CONTEXT_CHARS);
        int to = Math.min(text.length(), startIndex + safeLen + SNIPPET_CONTEXT_CHARS);
        String raw = text.substring(from, to).replace('\n', ' ').replace('\r', ' ').trim();
        String prefix = from > 0 ? "…" : "";
        String suffix = to < text.length() ? "…" : "";
        return String.format(Locale.ROOT, "%s%s%s", prefix, raw, suffix);
    }
}