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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

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

    public static final class OutlineItem {
        @NonNull public final String title;
        public final int pageIndex;
        public final int level;
        @NonNull public final List<OutlineItem> children;

        public OutlineItem(@NonNull String title,
                           int pageIndex,
                           int level,
                           @NonNull List<OutlineItem> children) {
            this.title = title;
            this.pageIndex = Math.max(0, pageIndex);
            this.level = Math.max(0, level);
            this.children = Collections.unmodifiableList(new ArrayList<>(children));
        }
    }

    public interface OutlineCallback {
        void onOutlineLoaded(@NonNull List<OutlineItem> items);
        void onOutlineError(@NonNull Throwable error);
    }

    private static final class PdfTextRun {
        @NonNull final String text;
        @NonNull final RectF boundsPt;

        PdfTextRun(@NonNull String text, @NonNull RectF boundsPt) {
            this.text = text;
            this.boundsPt = boundsPt;
        }
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
    private final Map<Integer, List<PdfTextRun>> pageTextRunsCache =
            Collections.synchronizedMap(new HashMap<>());
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private static final class PrioritizedFutureTask<T> extends FutureTask<T>
            implements Comparable<PrioritizedFutureTask<?>> {
        private final int priorityScore; // 0 = currently visible in viewport, 1 = adjacent, 2 = background
        private final long sequence;

        PrioritizedFutureTask(@NonNull Runnable runnable, T result, int priorityScore, long sequence) {
            super(runnable, result);
            this.priorityScore = priorityScore;
            this.sequence = sequence;
        }

        @Override
        public int compareTo(@NonNull PrioritizedFutureTask<?> other) {
            int cmp = Integer.compare(this.priorityScore, other.priorityScore);
            if (cmp != 0) return cmp;
            // LIFO within the same tier so the pages currently in front of the user render first
            return Long.compare(other.sequence, this.sequence);
        }
    }

    private static final RenderCallback NO_OP_RENDER_CALLBACK = new RenderCallback() {
        @Override
        public void onRenderSuccess(@NonNull RenderRequest request, @NonNull ManagedBitmap bitmapLease) {
            bitmapLease.release();
        }

        @Override
        public void onRenderError(@NonNull RenderRequest request, @NonNull Throwable error) {
        }
    };

    private final ThreadPoolExecutor interactiveExecutor;
    private final ExecutorService backgroundExecutor;
    private final AtomicLong renderSequence = new AtomicLong(0L);
    private volatile int visibleStartPage = 0;
    private volatile int visibleEndPage = 1;
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

        this.interactiveExecutor = new ThreadPoolExecutor(
                2,
                2,
                0L,
                TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "MedvixPdf-Interactive-" + docKey);
                    t.setPriority(Thread.NORM_PRIORITY + 1);
                    return t;
                }
        );
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
        return !closed.get();
    }

    public boolean supportsNativeSelection() {
        return !closed.get();
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

        Runnable renderTask = () -> {
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
        };

        int priorityScore = computeRenderPriorityScore(request);
        if (request.highPriority || priorityScore == 0) {
            PrioritizedFutureTask<Void> prioritized = new PrioritizedFutureTask<>(
                    renderTask, null, priorityScore, renderSequence.incrementAndGet());
            interactiveExecutor.execute(prioritized);
            handle.attachFuture(prioritized);
        } else {
            Future<?> future = backgroundExecutor.submit(renderTask);
            handle.attachFuture(future);
        }
        return handle;
    }

    private int computeRenderPriorityScore(@NonNull RenderRequest request) {
        if (request.forPrint) return 0;
        int start = this.visibleStartPage;
        int end = this.visibleEndPage;
        if (request.pageIndex >= start && request.pageIndex <= end) {
            return 0;
        }
        if (Math.abs(request.pageIndex - start) <= 1 || Math.abs(request.pageIndex - end) <= 1) {
            return 1;
        }
        return 2;
    }

    /**
     * Updates the active [firstVisiblePage..lastVisiblePage] viewport window and
     * immediately pre-warms every visible page in BitmapMemoryPool at top priority.
     */
    public void setVisiblePageRange(int firstVisiblePage,
                                    int lastVisiblePage,
                                    float renderScale,
                                    int rotationDegrees) {
        if (closed.get() || totalPageCount <= 0) return;
        int safeStart = Math.max(0, Math.min(totalPageCount - 1, firstVisiblePage));
        int safeEnd = Math.max(safeStart, Math.min(totalPageCount - 1, lastVisiblePage));
        this.visibleStartPage = safeStart;
        this.visibleEndPage = safeEnd;

        if (renderScale > 0.05f) {
            int maxAccessibleVisible = Math.min(safeEnd, accessiblePageCount - 1);
            for (int p = safeStart; p <= maxAccessibleVisible; p++) {
                RenderRequest req = new RenderRequest.Builder(p)
                        .setScale(renderScale)
                        .setRotation(rotationDegrees)
                        .setKind(RenderKind.FULL_PAGE)
                        .setHighPriority(true)
                        .build();
                ManagedBitmap hit = acquireCachedBitmap(req);
                if (hit != null) {
                    hit.release();
                } else {
                    renderAsync(req, NO_OP_RENDER_CALLBACK);
                }
            }
        }
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
            List<SearchMatch> upgraded = searchSinglePageUpgraded(pageIndex, query, filterPattern);
            if (!upgraded.isEmpty()) {
                return upgraded;
            }
        }
        if (compatDelegate != null && resolvedDoc.hasBackingFile()) {
            File file = resolvedDoc.getBackingFile();
            if (file != null) {
                List<TextSpan> spans = compatDelegate.extractTextSpans(file, pageIndex);
                if (!spans.isEmpty()) {
                    return matchAgainstTextSpans(pageIndex, spans, filterPattern);
                }
            }
        }
        return searchSinglePageStreamFallback(pageIndex, filterPattern);
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

                // Join without extra separator so indices align 1:1 with PageMatchBounds.getTextStartIndex()
                String pageText = joinPageTextRaw(page.getTextContents());
                List<SearchMatch> filtered = new ArrayList<>(rawMatches.size());

                for (PageMatchBounds m : rawMatches) {
                    if (m == null || m.getBounds() == null || m.getBounds().isEmpty()) continue;
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
    private List<SearchMatch> searchSinglePageStreamFallback(int pageIndex,
                                                             @Nullable Pattern filterPattern) throws IOException {
        if (filterPattern == null) return Collections.emptyList();
        List<PdfTextRun> runs = getOrExtractPageTextRuns(pageIndex);
        if (runs.isEmpty()) return Collections.emptyList();

        List<SearchMatch> matches = new ArrayList<>();
        for (int i = 0; i < runs.size(); i++) {
            PdfTextRun run = runs.get(i);
            Matcher matcher = filterPattern.matcher(run.text);
            while (matcher.find()) {
                String snippet = buildRunContextSnippet(runs, i);
                matches.add(new SearchMatch(
                        pageIndex,
                        matches.size(),
                        matcher.start(),
                        snippet,
                        Collections.singletonList(new RectF(run.boundsPt))
                ));
            }
        }
        return matches;
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
- Native text selection between two PDF page point coordinates across all Android versions.
     */
    @Nullable
    public TextSelectionResult selectTextAtPoints(int pageIndex,
                                                    @NonNull Point startPt,
                                                    @NonNull Point stopPt) throws IOException {
        ensureOpen();
        validatePageIndex(pageIndex, true);
        if (isUpgradedPdfApiSupported()) {
            TextSelectionResult upgraded = selectTextUpgraded(pageIndex, startPt, stopPt);
            if (upgraded != null) {
                return upgraded;
            }
        }
        return selectTextStreamFallback(pageIndex, startPt, stopPt);
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
                if (allBounds.isEmpty()) return null;

                int startIdx = selection.getStart() != null ? selection.getStart().getIndex() : -1;
                int stopIdx = selection.getStop() != null ? selection.getStop().getIndex() : -1;
                return new TextSelectionResult(pageIndex, text, startIdx, stopIdx, allBounds);
            }
        } finally {
            renderLock.unlock();
        }
    }

    @Nullable
    private TextSelectionResult selectTextStreamFallback(int pageIndex,
                                                         @NonNull Point startPt,
                                                         @NonNull Point stopPt) throws IOException {
        List<PdfTextRun> runs = getOrExtractPageTextRuns(pageIndex);
        if (runs.isEmpty()) return null;

        float minX = Math.min(startPt.x, stopPt.x) - 6f;
        float maxX = Math.max(startPt.x, stopPt.x) + 6f;
        float minY = Math.min(startPt.y, stopPt.y) - 6f;
        float maxY = Math.max(startPt.y, stopPt.y) + 6f;
        RectF queryRect = new RectF(minX, minY, maxX, maxY);

        List<RectF> selectedBounds = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int firstIdx = -1;
        int lastIdx = -1;

        for (int i = 0; i < runs.size(); i++) {
            PdfTextRun run = runs.get(i);
            if (RectF.intersects(run.boundsPt, queryRect)) {
                if (firstIdx < 0) firstIdx = i;
                lastIdx = i;
                if (sb.length() > 0) sb.append(' ');
                sb.append(run.text);
                selectedBounds.add(new RectF(run.boundsPt));
            }
        }

        String text = sb.toString().trim();
        if (text.isEmpty() || selectedBounds.isEmpty()) return null;
        return new TextSelectionResult(pageIndex, text, firstIdx, lastIdx, selectedBounds);
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

    @RequiresApi(api = 35)
    @NonNull
    private static String joinPageTextRaw(@Nullable List<PdfPageTextContent> contents) {
        if (contents == null || contents.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (PdfPageTextContent c : contents) {
            if (c != null && !TextUtils.isEmpty(c.getText())) {
                sb.append(c.getText());
            }
        }
        return sb.toString();
    }

    // ── Book Outline (/Outlines) & Content-Stream Extraction ─────────────────

    @NonNull
    public TaskHandle extractOutlineAsync(@NonNull OutlineCallback callback) {
        TaskHandle handle = new TaskHandle();
        Future<?> future = backgroundExecutor.submit(() -> {
            if (handle.isCancelled() || closed.get()) return;
            try {
                List<OutlineItem> items = parsePdfOutlineTree();
                if (items.isEmpty()) {
                    items = extractFallbackTocFromFrontPages();
                }
                final List<OutlineItem> finalItems = items;
                if (!handle.isCancelled() && !closed.get()) {
                    mainHandler.post(() -> callback.onOutlineLoaded(finalItems));
                }
            } catch (Throwable t) {
                if (!handle.isCancelled() && !closed.get()) {
                    mainHandler.post(() -> callback.onOutlineError(t));
                }
            }
        });
        handle.attachFuture(future);
        return handle;
    }

    @NonNull
    private List<OutlineItem> parsePdfOutlineTree() {
        List<OutlineItem> roots = new ArrayList<>();
        if (closed.get()) return roots;

        try {
            byte[] pdfBytes = readAllBytesFromPfd();
            if (pdfBytes.length == 0) return roots;

            String raw = new String(pdfBytes, StandardCharsets.ISO_8859_1);
            Map<Integer, String> objects = indexIndirectObjects(raw);
            if (objects.isEmpty()) return roots;

            List<Integer> orderedPageObjNums = resolveOrderedPageObjectNumbers(objects);
            Map<Integer, Integer> objNumToPageIndex = new HashMap<>();
            for (int i = 0; i < orderedPageObjNums.size(); i++) {
                objNumToPageIndex.put(orderedPageObjNums.get(i), i);
            }

            int rootObj = findRootCatalogObjNum(raw, objects);
            String rootDict = objects.get(rootObj);
            if (rootDict == null) return roots;

            Integer outlinesObj = extractIndirectRef(rootDict, "/Outlines");
            if (outlinesObj == null) return roots;

            String outlinesDict = objects.get(outlinesObj);
            if (outlinesDict == null) return roots;

            Map<String, Integer> namedDests = buildNamedDestinationMap(rootDict, objects, objNumToPageIndex);

            Integer firstItemObj = extractIndirectRef(outlinesDict, "/First");
            if (firstItemObj != null) {
                walkOutlineSiblings(firstItemObj, 0, objects, objNumToPageIndex, namedDests, roots, 0);
            }
        } catch (Throwable ignored) {
        }
        return roots;
    }

    private void walkOutlineSiblings(int currentObjNum,
                                     int level,
                                     @NonNull Map<Integer, String> objects,
                                     @NonNull Map<Integer, Integer> objNumToPageIndex,
                                     @NonNull Map<String, Integer> namedDests,
                                     @NonNull List<OutlineItem> outList,
                                     int safetyDepth) {
        if (safetyDepth > 1200) return;
        Integer cursor = currentObjNum;
        int guard = 0;

        while (cursor != null && guard++ < 800) {
            String dict = objects.get(cursor);
            if (dict == null) break;

            String title = extractPdfStringValue(dict, "/Title");
            int targetPage = resolveOutlineDestinationPage(dict, objects, objNumToPageIndex, namedDests);

            List<OutlineItem> children = new ArrayList<>();
            Integer firstChild = extractIndirectRef(dict, "/First");
            if (firstChild != null && level < 5) {
                walkOutlineSiblings(firstChild, level + 1, objects, objNumToPageIndex,
                        namedDests, children, safetyDepth + 1);
            }

            if (!title.isEmpty()) {
                outList.add(new OutlineItem(title, targetPage, level, children));
            }

            cursor = extractIndirectRef(dict, "/Next");
        }
    }

    private int resolveOutlineDestinationPage(@NonNull String itemDict,
                                              @NonNull Map<Integer, String> objects,
                                              @NonNull Map<Integer, Integer> objNumToPageIndex,
                                              @NonNull Map<String, Integer> namedDests) {
        int page = resolveDestEntry(itemDict, objects, objNumToPageIndex, namedDests);
        if (page >= 0) return page;

        Integer actionRef = extractIndirectRef(itemDict, "/A");
        String actionDict = (actionRef != null) ? objects.get(actionRef) : itemDict;
        if (actionDict != null) {
            int actionPage = resolveDestEntry(actionDict, objects, objNumToPageIndex, namedDests);
            if (actionPage >= 0) return actionPage;
        }
        return 0;
    }

    private int resolveDestEntry(@NonNull String dict,
                                 @NonNull Map<Integer, String> objects,
                                 @NonNull Map<Integer, Integer> objNumToPageIndex,
                                 @NonNull Map<String, Integer> namedDests) {
        Matcher arrayRef = Pattern.compile("/(?:Dest|D)\\s*\\[\\s*(\\d+)\\s+\\d+\\s+R").matcher(dict);
        if (arrayRef.find()) {
            int pageObj = Integer.parseInt(arrayRef.group(1));
            Integer idx = objNumToPageIndex.get(pageObj);
            if (idx != null) return idx;
        }
        Matcher named = Pattern.compile("/(?:Dest|D)\\s*(?:\\(([^)]+)\\)|/([^\\s/\\[\\]<>()]+))").matcher(dict);
        if (named.find()) {
            String key = named.group(1) != null ? named.group(1) : named.group(2);
            Integer idx = namedDests.get(key);
            if (idx != null) return idx;
        }
        Integer indirectDest = extractIndirectRef(dict, "/Dest");
        if (indirectDest != null && objects.containsKey(indirectDest)) {
            String target = objects.get(indirectDest);
            Matcher m = Pattern.compile("\\[\\s*(\\d+)\\s+\\d+\\s+R").matcher(target);
            if (m.find()) {
                Integer idx = objNumToPageIndex.get(Integer.parseInt(m.group(1)));
                if (idx != null) return idx;
            }
        }
        return -1;
    }

    @NonNull
    private List<OutlineItem> extractFallbackTocFromFrontPages() {
        List<OutlineItem> fallback = new ArrayList<>();
        int scanLimit = Math.min(totalPageCount, 8);
        Pattern tocLinePattern = Pattern.compile("^(.{4,72}?)[.\\s·•_]{2,}(\\d{1,4})$");

        try {
            for (int p = 0; p < scanLimit; p++) {
                List<PdfTextRun> runs = getOrExtractPageTextRuns(p);
                for (PdfTextRun run : runs) {
                    Matcher m = tocLinePattern.matcher(run.text.trim());
                    if (m.matches()) {
                        String topic = m.group(1).trim();
                        int docPageNum = Integer.parseInt(m.group(2));
                        if (docPageNum >= 1 && docPageNum <= totalPageCount) {
                            fallback.add(new OutlineItem(topic, docPageNum - 1, 0, Collections.emptyList()));
                        }
                    }
                }
                if (fallback.size() >= 6) break;
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    @NonNull
    private List<PdfTextRun> getOrExtractPageTextRuns(int pageIndex) throws IOException {
        List<PdfTextRun> cached = pageTextRunsCache.get(pageIndex);
        if (cached != null) return cached;

        List<PdfTextRun> extracted = new ArrayList<>();
        PageMetrics metrics = getPageMetrics(pageIndex);
        float pageH = metrics.rawHeightPt;

        byte[] pdfBytes = readAllBytesFromPfd();
        if (pdfBytes.length > 0) {
            String raw = new String(pdfBytes, StandardCharsets.ISO_8859_1);
            Map<Integer, String> objects = indexIndirectObjects(raw);
            List<Integer> pageObjs = resolveOrderedPageObjectNumbers(objects);

            if (pageIndex >= 0 && pageIndex < pageObjs.size()) {
                String pageDict = objects.get(pageObjs.get(pageIndex));
                if (pageDict != null) {
                    String contentStream = extractDecodedPageContentStream(pageDict, objects);
                    parseContentStreamTextRuns(contentStream, pageH, extracted);
                }
            }
        }

        pageTextRunsCache.put(pageIndex, extracted);
        return extracted;
    }

    @NonNull
    private static String buildRunContextSnippet(@NonNull List<PdfTextRun> runs, int centerIdx) {
        int start = Math.max(0, centerIdx - 3);
        int end = Math.min(runs.size() - 1, centerIdx + 3);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i <= end; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(runs.get(i).text);
        }
        return sb.toString();
    }

    private static void parseContentStreamTextRuns(@NonNull String stream,
                                                   float pageHeightPt,
                                                   @NonNull List<PdfTextRun> outRuns) {
        if (stream.isEmpty()) return;
        float curX = 36f;
        float curY = pageHeightPt - 50f;
        float fontSize = 11f;

        Matcher tmMatcher = Pattern.compile(
                "([+-]?\\d*\\.?\\d+)\\s+([+-]?\\d*\\.?\\d+)\\s+([+-]?\\d*\\.?\\d+)\\s+([+-]?\\d*\\.?\\d+)\\s+([+-]?\\d*\\.?\\d+)\\s+([+-]?\\d*\\.?\\d+)\\s+Tm")
                .matcher(stream);
        Matcher tdMatcher = Pattern.compile("([+-]?\\d*\\.?\\d+)\\s+([+-]?\\d*\\.?\\d+)\\s+T[dD]").matcher(stream);
        Matcher tfMatcher = Pattern.compile("/\\S+\\s+(\\d*\\.?\\d+)\\s+Tf").matcher(stream);
        Matcher textOpMatcher = Pattern.compile("\\(([^()\\\\]*(?:\\\\.[^()\\\\]*)*)\\)\\s*Tj|\\[(.*?)\\]\\s*TJ", Pattern.DOTALL)
                .matcher(stream);

        int pos = 0;
        while (pos < stream.length()) {
            int nextText = textOpMatcher.find(pos) ? textOpMatcher.start() : Integer.MAX_VALUE;
            if (nextText == Integer.MAX_VALUE) break;

            if (tmMatcher.find(pos) && tmMatcher.start() < nextText) {
                fontSize = Math.max(6f, Math.abs(Float.parseFloat(tmMatcher.group(4))));
                curX = Float.parseFloat(tmMatcher.group(5));
                curY = Float.parseFloat(tmMatcher.group(6));
            }
            if (tdMatcher.find(pos) && tdMatcher.start() < nextText) {
                curX += Float.parseFloat(tdMatcher.group(1));
                curY += Float.parseFloat(tdMatcher.group(2));
            }
            if (tfMatcher.find(pos) && tfMatcher.start() < nextText) {
                fontSize = Math.max(6f, Float.parseFloat(tfMatcher.group(1)));
            }

            StringBuilder runText = new StringBuilder();
            if (textOpMatcher.group(1) != null) {
                runText.append(unescapePdfLiteral(textOpMatcher.group(1)));
            } else if (textOpMatcher.group(2) != null) {
                Matcher inner = Pattern.compile("\\(([^()\\\\]*(?:\\\\.[^()\\\\]*)*)\\)").matcher(textOpMatcher.group(2));
                while (inner.find()) {
                    runText.append(unescapePdfLiteral(inner.group(1)));
                }
            }

            String cleaned = runText.toString().trim();
            if (!cleaned.isEmpty()) {
                String[] words = cleaned.split("\\s+");
                float cursorX = curX;
                float topAndroidY = Math.max(0f, pageHeightPt - curY - fontSize * 0.85f);
                float bottomAndroidY = Math.min(pageHeightPt, topAndroidY + fontSize * 1.2f);
                float charWidth = fontSize * 0.52f;

                for (String w : words) {
                    if (w.isEmpty()) continue;
                    float wWidth = Math.max(8f, w.length() * charWidth);
                    outRuns.add(new PdfTextRun(
                            w,
                            new RectF(cursorX, topAndroidY, cursorX + wWidth, bottomAndroidY)
                    ));
                    cursorX += wWidth + charWidth;
                }
                curX = cursorX;
            }
            pos = textOpMatcher.end();
        }
    }

    @NonNull
    private byte[] readAllBytesFromPfd() throws IOException {
        renderLock.lock();
        try {
            if (closed.get()) return new byte[0];
            FileChannel channel = new FileInputStream(pfd.getFileDescriptor()).getChannel();
            long size = channel.size();
            if (size <= 0 || size > 64L * 1024L * 1024L) return new byte[0];
            ByteBuffer buffer = ByteBuffer.allocate((int) size);
            long pos = 0;
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer, pos);
                if (read <= 0) break;
                pos += read;
            }
            return buffer.array();
        } finally {
            renderLock.unlock();
        }
    }

    @NonNull
    private static Map<Integer, String> indexIndirectObjects(@NonNull String rawPdf) {
        Map<Integer, String> map = new HashMap<>();
        Matcher m = Pattern.compile("(\\d+)\\s+\\d+\\s+obj\\b(.*?)endobj", Pattern.DOTALL).matcher(rawPdf);
        while (m.find()) {
            map.put(Integer.parseInt(m.group(1)), m.group(2));
        }
        return map;
    }

    private static int findRootCatalogObjNum(@NonNull String rawPdf, @NonNull Map<Integer, String> objects) {
        Matcher trailer = Pattern.compile("/Root\\s+(\\d+)\\s+\\d+\\s+R").matcher(rawPdf);
        if (trailer.find()) {
            return Integer.parseInt(trailer.group(1));
        }
        for (Map.Entry<Integer, String> e : objects.entrySet()) {
            if (e.getValue().contains("/Type") && e.getValue().contains("/Catalog")) {
                return e.getKey();
            }
        }
        return -1;
    }

    @NonNull
    private static List<Integer> resolveOrderedPageObjectNumbers(@NonNull Map<Integer, String> objects) {
        List<Integer> pages = new ArrayList<>();
        int rootPagesObj = -1;
        for (Map.Entry<Integer, String> e : objects.entrySet()) {
            String v = e.getValue();
            if (v.contains("/Type") && v.contains("/Pages") && !v.contains("/Parent")) {
                rootPagesObj = e.getKey();
                break;
            }
        }
        if (rootPagesObj >= 0) {
            collectPagesRecursive(rootPagesObj, objects, pages, 0);
        }
        return pages;
    }

    private static void collectPagesRecursive(int nodeObj,
                                              @NonNull Map<Integer, String> objects,
                                              @NonNull List<Integer> outPages,
                                              int depth) {
        if (depth > 40) return;
        String dict = objects.get(nodeObj);
        if (dict == null) return;

        Matcher kids = Pattern.compile("/Kids\\s*\\[(.*?)\\]", Pattern.DOTALL).matcher(dict);
        if (kids.find()) {
            Matcher ref = Pattern.compile("(\\d+)\\s+\\d+\\s+R").matcher(kids.group(1));
            while (ref.find()) {
                int childObj = Integer.parseInt(ref.group(1));
                String childDict = objects.get(childObj);
                if (childDict != null && childDict.contains("/Kids")) {
                    collectPagesRecursive(childObj, objects, outPages, depth + 1);
                } else {
                    outPages.add(childObj);
                }
            }
        } else if (dict.contains("/Page")) {
            outPages.add(nodeObj);
        }
    }

    @NonNull
    private static Map<String, Integer> buildNamedDestinationMap(@NonNull String rootDict,
                                                                 @NonNull Map<Integer, String> objects,
                                                                 @NonNull Map<Integer, Integer> objNumToPageIndex) {
        Map<String, Integer> map = new HashMap<>();
        Integer namesObj = extractIndirectRef(rootDict, "/Names");
        if (namesObj != null && objects.containsKey(namesObj)) {
            String namesDict = objects.get(namesObj);
            Integer destsTreeObj = extractIndirectRef(namesDict, "/Dests");
            if (destsTreeObj != null) {
                parseNameTreeNode(destsTreeObj, objects, objNumToPageIndex, map, 0);
            }
        }
        return map;
    }

    private static void parseNameTreeNode(int nodeObj,
                                          @NonNull Map<Integer, String> objects,
                                          @NonNull Map<Integer, Integer> objNumToPageIndex,
                                          @NonNull Map<String, Integer> outMap,
                                          int depth) {
        if (depth > 25) return;
        String dict = objects.get(nodeObj);
        if (dict == null) return;

        Matcher kids = Pattern.compile("/Kids\\s*\\[(.*?)\\]", Pattern.DOTALL).matcher(dict);
        if (kids.find()) {
            Matcher ref = Pattern.compile("(\\d+)\\s+\\d+\\s+R").matcher(kids.group(1));
            while (ref.find()) {
                parseNameTreeNode(Integer.parseInt(ref.group(1)), objects, objNumToPageIndex, outMap, depth + 1);
            }
        }

        Matcher names = Pattern.compile("/Names\\s*\\[(.*?)\\]\\s*(?=/|>>)", Pattern.DOTALL).matcher(dict);
        if (names.find()) {
            Matcher pair = Pattern.compile("\\(([^)]+)\\)\\s*(?:\\[\\s*(\\d+)\\s+\\d+\\s+R|(\\d+)\\s+\\d+\\s+R)")
                    .matcher(names.group(1));
            while (pair.find()) {
                String name = pair.group(1);
                int targetObj = Integer.parseInt(pair.group(2) != null ? pair.group(2) : pair.group(3));
                Integer pageIdx = objNumToPageIndex.get(targetObj);
                if (pageIdx == null && objects.containsKey(targetObj)) {
                    Matcher m = Pattern.compile("\\[\\s*(\\d+)\\s+\\d+\\s+R").matcher(objects.get(targetObj));
                    if (m.find()) {
                        pageIdx = objNumToPageIndex.get(Integer.parseInt(m.group(1)));
                    }
                }
                if (pageIdx != null) {
                    outMap.put(name, pageIdx);
                }
            }
        }
    }

    @Nullable
    private static Integer extractIndirectRef(@NonNull String dict, @NonNull String key) {
        Matcher m = Pattern.compile(Pattern.quote(key) + "\\s+(\\d+)\\s+\\d+\\s+R").matcher(dict);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    @NonNull
    private static String extractPdfStringValue(@NonNull String dict, @NonNull String key) {
        Matcher lit = Pattern.compile(Pattern.quote(key) + "\\s*\\(([^()\\\\]*(?:\\\\.[^()\\\\]*)*)\\)").matcher(dict);
        if (lit.find()) {
            return unescapePdfLiteral(lit.group(1)).trim();
        }
        Matcher hex = Pattern.compile(Pattern.quote(key) + "\\s*<([0-9A-Fa-f\\s]+)>").matcher(dict);
        if (hex.find()) {
            return decodePdfHexString(hex.group(1)).trim();
        }
        return "";
    }

    @NonNull
    private static String unescapePdfLiteral(@NonNull String s) {
        return s.replace("\\(", "(")
                .replace("\\)", ")")
                .replace("\\\\", "\\")
                .replace("\\n", " ")
                .replace("\\r", " ")
                .replace("\\t", " ");
    }

    @NonNull
    private static String decodePdfHexString(@NonNull String hexRaw) {
        String clean = hexRaw.replaceAll("\\s+", "");
        if (clean.length() < 2) return "";
        byte[] bytes = new byte[clean.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    @NonNull
    private static String extractDecodedPageContentStream(@NonNull String pageDict,
                                                          @NonNull Map<Integer, String> objects) {
        List<Integer> contentObjs = new ArrayList<>();
        Matcher arr = Pattern.compile("/Contents\\s*\\[(.*?)\\]", Pattern.DOTALL).matcher(pageDict);
        if (arr.find()) {
            Matcher ref = Pattern.compile("(\\d+)\\s+\\d+\\s+R").matcher(arr.group(1));
            while (ref.find()) contentObjs.add(Integer.parseInt(ref.group(1)));
        } else {
            Integer single = extractIndirectRef(pageDict, "/Contents");
            if (single != null) contentObjs.add(single);
        }

        StringBuilder combined = new StringBuilder();
        for (int objNum : contentObjs) {
            String objBody = objects.get(objNum);
            if (objBody == null) continue;
            int streamStart = objBody.indexOf("stream");
            int streamEnd = objBody.lastIndexOf("endstream");
            if (streamStart < 0 || streamEnd <= streamStart) continue;

            int dataOffset = streamStart + 6;
            if (dataOffset < objBody.length() && objBody.charAt(dataOffset) == '\r') dataOffset++;
            if (dataOffset < objBody.length() && objBody.charAt(dataOffset) == '\n') dataOffset++;

            byte[] rawStream = objBody.substring(dataOffset, streamEnd).getBytes(StandardCharsets.ISO_8859_1);
            if (objBody.contains("/FlateDecode")) {
                byte[] inflated = inflateZlib(rawStream);
                combined.append(new String(inflated, StandardCharsets.ISO_8859_1)).append('\n');
            } else {
                combined.append(new String(rawStream, StandardCharsets.ISO_8859_1)).append('\n');
            }
        }
        return combined.toString();
    }

    @NonNull
    private static byte[] inflateZlib(@NonNull byte[] compressed) {
        Inflater inflater = new Inflater();
        inflater.setInput(compressed);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            while (!inflater.finished()) {
                int count = inflater.inflate(buf);
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break;
                } else {
                    out.write(buf, 0, count);
                }
            }
            return out.toByteArray();
        } catch (Throwable ignored) {
            return compressed;
        } finally {
            inflater.end();
        }
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