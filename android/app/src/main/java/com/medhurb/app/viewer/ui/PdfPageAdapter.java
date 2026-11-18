package com.medhurb.app.viewer.ui;

import android.content.Context;
import android.graphics.Color;
import android.util.SparseArray;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.medhurb.app.viewer.engine.PdfDocumentEngine;
import com.medhurb.app.viewer.engine.PdfDocumentEngine.PageMetrics;
import com.medhurb.app.viewer.engine.PdfDocumentEngine.SearchMatch;
import com.medhurb.app.viewer.engine.PdfDocumentEngine.TaskHandle;
import com.medhurb.app.viewer.render.BitmapMemoryPool.ManagedBitmap;
import com.medhurb.app.viewer.render.BitmapMemoryPool.RenderKind;
import com.medhurb.app.viewer.storage.MedvixDocumentStore;
import com.medhurb.app.viewer.storage.MedvixDocumentStore.FitMode;
import com.medhurb.app.viewer.storage.MedvixDocumentStore.ReadingTheme;
import com.medhurb.app.viewer.storage.MedvixDocumentStore.ScrollMode;
import com.medhurb.app.viewer.ui.DocumentCanvasView.InteractionMode;
import com.medhurb.app.viewer.ui.DocumentCanvasView.ViewerInteractionListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * PdfPageAdapter
 * ============================================================================
 * High-performance RecyclerView.Adapter binding DocumentCanvasView instances
 * to PdfDocumentEngine pages for MedVix.
 *
 * Key responsibilities:
 *  - Supports both vertical continuous scroll and horizontal paged modes.
 *  - Propagates theme, rotation, fit-mode, search matches, and ink/eraser
 *    tool changes via partial RecyclerView payloads (zero bitmap flicker).
 *  - Prefetches +/- 2 adjacent pages on background threads so rendered pages
 *    land in BitmapMemoryPool before scrolling brings them on-screen.
 *  - Releases ManagedBitmap leases deterministically in onViewRecycled().
 */
public final class PdfPageAdapter extends RecyclerView.Adapter<PdfPageAdapter.PageViewHolder> {

    // ── Partial Update Payloads ──────────────────────────────────────────────

    private static final String PAYLOAD_THEME           = "payload_theme";
    private static final String PAYLOAD_SEARCH          = "payload_search";
    private static final String PAYLOAD_INTERACTION     = "payload_interaction";
    private static final String PAYLOAD_ANNOTATIONS     = "payload_annotations";

    // ── Prefetch Tuning ──────────────────────────────────────────────────────

    private static final int PREFETCH_RADIUS_PAGES      = 2;
    private static final int MAX_PREFETCH_BITMAP_PX     = 1800;
    private static final float DEFAULT_PAGE_GAP_DP      = 8f;

    // ── Core Dependencies & Reader State ─────────────────────────────────────

    @NonNull private final PdfDocumentEngine pdfEngine;
    @Nullable private final MedvixDocumentStore documentStore;
    @Nullable private ViewerInteractionListener interactionListener;

    private int rotationDegrees;
    @NonNull private ReadingTheme readingTheme;
    @NonNull private ScrollMode scrollMode;
    @NonNull private FitMode fitMode;
    @NonNull private InteractionMode interactionMode = InteractionMode.READ;

    private int activeInkColor = Color.rgb(220, 38, 38);
    private float activeInkWidthPt = 2.4f;

    private final int pageGapPx;
    private int viewportWidthPx;

    // ── Search Highlight State ───────────────────────────────────────────────

    private final SparseArray<List<SearchMatch>> searchMatchesByPage = new SparseArray<>();
    private int activeSearchPageIndex = -1;
    private int activeSearchMatchIndexOnPage = -1;

    // ── One-Shot Initial Viewport Restoration ────────────────────────────────

    private int pendingRestorePageIndex = -1;
    private float pendingRestoreZoom = 1.0f;
    private float pendingRestoreNormX = 0f;
    private float pendingRestoreNormY = 0f;

    // ── Adjacent-Page Prefetch Tracking ──────────────────────────────────────

    private int lastFocusedPageIndex = -1;
    private final SparseArray<TaskHandle> activePrefetchTasks = new SparseArray<>();

    // ── Constructor ──────────────────────────────────────────────────────────

    public PdfPageAdapter(@NonNull Context context,
                          @NonNull PdfDocumentEngine pdfEngine,
                          @Nullable MedvixDocumentStore documentStore,
                          int initialRotationDegrees,
                          @NonNull ReadingTheme initialTheme,
                          @NonNull ScrollMode initialScrollMode,
                          @NonNull FitMode initialFitMode) {
        this.pdfEngine = pdfEngine;
        this.documentStore = documentStore;
        this.rotationDegrees = PdfDocumentEngine.normalizeRotation(initialRotationDegrees);
        this.readingTheme = initialTheme;
        this.scrollMode = initialScrollMode;
        this.fitMode = initialFitMode;

        float density = context.getResources().getDisplayMetrics().density;
        this.pageGapPx = Math.max(0, Math.round(DEFAULT_PAGE_GAP_DP * density));
        this.viewportWidthPx = Math.max(1, context.getResources().getDisplayMetrics().widthPixels);

        setHasStableIds(true);
    }

    public void setViewerInteractionListener(@Nullable ViewerInteractionListener listener) {
        this.interactionListener = listener;
    }

    public void setViewportWidthPx(int widthPx) {
        if (widthPx > 0) {
            this.viewportWidthPx = widthPx;
        }
    }

    /**
     * Queues a one-time zoom & pan restoration that will be applied automatically
     * when targetPageIndex next binds.
     */
    public void scheduleInitialViewportRestore(int targetPageIndex,
                                               float zoomScale,
                                               float normalizedScrollX,
                                               float normalizedScrollY) {
        this.pendingRestorePageIndex = targetPageIndex;
        this.pendingRestoreZoom = zoomScale;
        this.pendingRestoreNormX = normalizedScrollX;
        this.pendingRestoreNormY = normalizedScrollY;
    }

    // ── RecyclerView.Adapter Overrides ───────────────────────────────────────

    @Override
    public int getItemCount() {
        return pdfEngine.isClosed() ? 0 : pdfEngine.getTotalPageCount();
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @NonNull
    @Override
    public PageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (parent.getWidth() > 0) {
            this.viewportWidthPx = parent.getWidth();
        }
        DocumentCanvasView canvasView = new DocumentCanvasView(parent.getContext());
        applyLayoutParamsForScrollMode(canvasView);
        return new PageViewHolder(canvasView);
    }

    @Override
    public void onBindViewHolder(@NonNull PageViewHolder holder, int position) {
        DocumentCanvasView view = holder.canvasView;
        applyLayoutParamsForScrollMode(view);

        boolean standaloneMode = (scrollMode != ScrollMode.VERTICAL_CONTINUOUS);
        FitMode effectiveFit = standaloneMode ? fitMode : FitMode.FIT_WIDTH;

        view.setViewerInteractionListener(interactionListener);
        view.bindPdfPage(
                pdfEngine,
                documentStore,
                position,
                rotationDegrees,
                readingTheme,
                effectiveFit,
                standaloneMode
        );

        view.setInteractionMode(interactionMode);
        view.setInkBrush(activeInkColor, activeInkWidthPt);
        applySearchStateToView(view, position);

        if (position == pendingRestorePageIndex) {
            float restoreZ = pendingRestoreZoom;
            float restoreX = pendingRestoreNormX;
            float restoreY = pendingRestoreNormY;
            pendingRestorePageIndex = -1;
            if (restoreZ > 1.01f || restoreX > 0.001f || restoreY > 0.001f) {
                view.restoreViewportNormalized(restoreZ, restoreX, restoreY);
            }
        }
    }

    @Override
    public void onBindViewHolder(@NonNull PageViewHolder holder,
                                 int position,
                                 @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position);
            return;
        }

        DocumentCanvasView view = holder.canvasView;
        for (Object payload : payloads) {
            if (PAYLOAD_THEME.equals(payload)) {
                view.setReadingTheme(readingTheme);
            } else if (PAYLOAD_SEARCH.equals(payload)) {
                applySearchStateToView(view, position);
            } else if (PAYLOAD_INTERACTION.equals(payload)) {
                view.setInteractionMode(interactionMode);
                view.setInkBrush(activeInkColor, activeInkWidthPt);
            } else if (PAYLOAD_ANNOTATIONS.equals(payload)) {
                view.invalidate();
            }
        }
    }

    @Override
    public void onViewRecycled(@NonNull PageViewHolder holder) {
        holder.canvasView.setViewerInteractionListener(null);
        holder.canvasView.unbind();
        super.onViewRecycled(holder);
    }

    @Override
    public boolean onFailedToRecycleView(@NonNull PageViewHolder holder) {
        holder.canvasView.setViewerInteractionListener(null);
        holder.canvasView.unbind();
        return true;
    }

    // ── Dynamic State Updates (Called by Activity Toolbar / Controls) ────────

    public void setReadingTheme(@NonNull ReadingTheme theme) {
        if (this.readingTheme == theme) return;
        this.readingTheme = theme;
        int count = getItemCount();
        if (count > 0) {
            notifyItemRangeChanged(0, count, PAYLOAD_THEME);
        }
    }

    public void setRotationDegrees(int degrees) {
        int norm = PdfDocumentEngine.normalizeRotation(degrees);
        if (this.rotationDegrees == norm) return;
        this.rotationDegrees = norm;
        cancelAllPrefetches();
        int count = getItemCount();
        if (count > 0) {
            notifyItemRangeChanged(0, count);
        }
        if (lastFocusedPageIndex >= 0) {
            scheduleAdjacentPrefetch(lastFocusedPageIndex);
        }
    }

    public void setScrollMode(@NonNull ScrollMode mode) {
        if (this.scrollMode == mode) return;
        this.scrollMode = mode;
        int count = getItemCount();
        if (count > 0) {
            notifyItemRangeChanged(0, count);
        }
    }

    public void setFitMode(@NonNull FitMode mode) {
        if (this.fitMode == mode) return;
        this.fitMode = mode;
        int count = getItemCount();
        if (count > 0) {
            notifyItemRangeChanged(0, count);
        }
    }

    public void setInteractionMode(@NonNull InteractionMode mode) {
        if (this.interactionMode == mode) return;
        this.interactionMode = mode;
        int count = getItemCount();
        if (count > 0) {
            notifyItemRangeChanged(0, count, PAYLOAD_INTERACTION);
        }
    }

    public void setInkBrush(int argbColor, float widthPt) {
        this.activeInkColor = argbColor;
        this.activeInkWidthPt = Math.max(0.5f, widthPt);
        int count = getItemCount();
        if (count > 0) {
            notifyItemRangeChanged(0, count, PAYLOAD_INTERACTION);
        }
    }

    /**
     * Notifies a specific page (or all pages if pageIndex < 0) to redraw vector
     * highlights/ink strokes after an Undo, Redo, or note edit in MedvixDocumentStore.
     */
    public void notifyAnnotationsUpdated(int affectedPageIndex) {
        int count = getItemCount();
        if (count <= 0) return;
        if (affectedPageIndex >= 0 && affectedPageIndex < count) {
            notifyItemChanged(affectedPageIndex, PAYLOAD_ANNOTATIONS);
        } else {
            notifyItemRangeChanged(0, count, PAYLOAD_ANNOTATIONS);
        }
    }

    // ── Search Highlight Propagation ─────────────────────────────────────────

    /**
     * Replaces all search match highlights across the document and updates
     * affected pages in-place without re-rendering page bitmaps.
     */
    public void setSearchMatches(@NonNull List<SearchMatch> allMatches,
                                 int activePageIndex,
                                 int activeMatchIndexOnPage) {
        List<Integer> previouslyHighlightedPages = new ArrayList<>(searchMatchesByPage.size());
        for (int i = 0; i < searchMatchesByPage.size(); i++) {
            previouslyHighlightedPages.add(searchMatchesByPage.keyAt(i));
        }

        searchMatchesByPage.clear();
        for (SearchMatch match : allMatches) {
            List<SearchMatch> list = searchMatchesByPage.get(match.pageIndex);
            if (list == null) {
                list = new ArrayList<>();
                searchMatchesByPage.put(match.pageIndex, list);
            }
            list.add(match);
        }

        this.activeSearchPageIndex = activePageIndex;
        this.activeSearchMatchIndexOnPage = activeMatchIndexOnPage;

        int count = getItemCount();
        for (int oldPage : previouslyHighlightedPages) {
            if (oldPage >= 0 && oldPage < count && searchMatchesByPage.get(oldPage) == null) {
                notifyItemChanged(oldPage, PAYLOAD_SEARCH);
            }
        }
        for (int i = 0; i < searchMatchesByPage.size(); i++) {
            int p = searchMatchesByPage.keyAt(i);
            if (p >= 0 && p < count) {
                notifyItemChanged(p, PAYLOAD_SEARCH);
            }
        }
    }

    /**
     * Moves the active orange search focus indicator to (pageIndex, matchIndexOnPage).
     */
    public void setActiveSearchMatch(int pageIndex, int matchIndexOnPage) {
        int prevPage = this.activeSearchPageIndex;
        this.activeSearchPageIndex = pageIndex;
        this.activeSearchMatchIndexOnPage = matchIndexOnPage;

        int count = getItemCount();
        if (prevPage >= 0 && prevPage < count && prevPage != pageIndex) {
            notifyItemChanged(prevPage, PAYLOAD_SEARCH);
        }
        if (pageIndex >= 0 && pageIndex < count) {
            notifyItemChanged(pageIndex, PAYLOAD_SEARCH);
        }
    }

    public void clearSearchMatches() {
        setSearchMatches(Collections.emptyList(), -1, -1);
    }

    private void applySearchStateToView(@NonNull DocumentCanvasView view, int pageIndex) {
        List<SearchMatch> matches = searchMatchesByPage.get(pageIndex);
        if (matches == null || matches.isEmpty()) {
            view.setSearchMatches(Collections.emptyList(), -1);
        } else {
            int activeIdx = (pageIndex == activeSearchPageIndex) ? activeSearchMatchIndexOnPage : -1;
            view.setSearchMatches(matches, activeIdx);
        }
    }

    // ── Adjacent-Page Prefetch Pipeline (+/- 2 Pages) ────────────────────────

    /**
     * Called by the host RecyclerView scroll listener whenever the primary visible
     * page changes. Cancels out-of-window prefetches and pre-warms +/- 2 pages
     * into BitmapMemoryPool.
     */
    public void onFocusedPageChanged(int focusedPageIndex) {
        if (focusedPageIndex < 0 || focusedPageIndex >= getItemCount()) return;
        if (this.lastFocusedPageIndex == focusedPageIndex) return;
        this.lastFocusedPageIndex = focusedPageIndex;
        scheduleAdjacentPrefetch(focusedPageIndex);
    }

    private void scheduleAdjacentPrefetch(int centerPage) {
        if (pdfEngine.isClosed()) return;

        int total = getItemCount();
        int minPage = Math.max(0, centerPage - PREFETCH_RADIUS_PAGES);
        int maxPage = Math.min(total - 1, centerPage + PREFETCH_RADIUS_PAGES);

        // 1. Cancel any active prefetch outside the [center - 2 .. center + 2] window
        for (int i = activePrefetchTasks.size() - 1; i >= 0; i--) {
            int p = activePrefetchTasks.keyAt(i);
            if (p < minPage || p > maxPage || p == centerPage) {
                TaskHandle handle = activePrefetchTasks.valueAt(i);
                if (handle != null) handle.cancel();
                activePrefetchTasks.removeAt(i);
            }
        }

        // 2. Prioritize forward reading direction first (+1, -1, +2, -2)
        int[] offsets = { 1, -1, 2, -2 };
        for (int offset : offsets) {
            int targetPage = centerPage + offset;
            if (targetPage < minPage || targetPage > maxPage) continue;
            if (pdfEngine.isPageLockedByPreview(targetPage)) continue;
            if (activePrefetchTasks.get(targetPage) != null) continue;

            prefetchSinglePage(targetPage);
        }
    }

    private void prefetchSinglePage(int pageIndex) {
        PageMetrics est = pdfEngine.getEstimatedPageMetrics(pageIndex);
        float rotW = Math.max(1f, est.getRotatedWidthPt(rotationDegrees));
        float rotH = Math.max(1f, est.getRotatedHeightPt(rotationDegrees));

        float fitScale = (float) viewportWidthPx / rotW;
        float targetScale = Math.max(fitScale, 1.25f);
        float maxSide = Math.max(rotW, rotH);
        if (maxSide * targetScale > MAX_PREFETCH_BITMAP_PX) {
            targetScale = MAX_PREFETCH_BITMAP_PX / maxSide;
        }

        PdfDocumentEngine.RenderRequest req = new PdfDocumentEngine.RenderRequest.Builder(pageIndex)
                .setScale(targetScale)
                .setRotation(rotationDegrees)
                .setKind(RenderKind.FULL_PAGE)
                .setHighPriority(false)
                .build();

        // If already cached in BitmapMemoryPool, release our probe lease immediately
        ManagedBitmap alreadyCached = pdfEngine.acquireCachedBitmap(req);
        if (alreadyCached != null) {
            alreadyCached.release();
            return;
        }

        TaskHandle handle = pdfEngine.renderAsync(req, new PdfDocumentEngine.RenderCallback() {
            @Override
            public void onRenderSuccess(@NonNull PdfDocumentEngine.RenderRequest request,
                                        @NonNull ManagedBitmap bitmapLease) {
                activePrefetchTasks.remove(request.pageIndex);
                // Release our prefetch lease immediately: the bitmap stays warm inside
                // BitmapMemoryPool's LruCache (refCount == 1) for instant page binding.
                bitmapLease.release();
            }

            @Override
            public void onRenderError(@NonNull PdfDocumentEngine.RenderRequest request,
                                      @NonNull Throwable error) {
                activePrefetchTasks.remove(request.pageIndex);
            }
        });

        activePrefetchTasks.put(pageIndex, handle);
    }

    public void cancelAllPrefetches() {
        for (int i = 0; i < activePrefetchTasks.size(); i++) {
            TaskHandle handle = activePrefetchTasks.valueAt(i);
            if (handle != null) {
                handle.cancel();
            }
        }
        activePrefetchTasks.clear();
    }

    // ── LayoutParams & Cleanup ───────────────────────────────────────────────

    private void applyLayoutParamsForScrollMode(@NonNull DocumentCanvasView view) {
        ViewGroup.LayoutParams existing = view.getLayoutParams();
        RecyclerView.LayoutParams lp;
        if (existing instanceof RecyclerView.LayoutParams) {
            lp = (RecyclerView.LayoutParams) existing;
        } else {
            lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
        }

        if (scrollMode == ScrollMode.VERTICAL_CONTINUOUS) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.setMargins(0, 0, 0, pageGapPx);
        } else {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.setMargins(0, 0, 0, 0);
        }
        view.setLayoutParams(lp);
    }

    /**
     * Cancels all pending prefetch work and clears transient adapter state.
     */
    public void shutdown() {
        cancelAllPrefetches();
        searchMatchesByPage.clear();
        interactionListener = null;
    }

    // ── ViewHolder ───────────────────────────────────────────────────────────

    public static final class PageViewHolder extends RecyclerView.ViewHolder {
        @NonNull public final DocumentCanvasView canvasView;

        public PageViewHolder(@NonNull DocumentCanvasView canvasView) {
            super(canvasView);
            this.canvasView = canvasView;
        }
    }
}