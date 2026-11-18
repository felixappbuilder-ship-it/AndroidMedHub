package com.medhurb.app.viewer.render;

import android.app.ActivityManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * BitmapMemoryPool
 * ============================================================================
 * Thread-safe, reference-counted LruCache + reusable native BitmapPool for
 * MedVix PDF pages, zoomed viewport tiles, thumbnails, and raster images.
 *
 * Lifecycle & Concurrency Contract:
 *  1. Callers obtain cached renders via get(CacheKey), which atomically retains
 *     and returns a ManagedBitmap lease (refCount >= 2: 1 for cache + 1 for caller).
 *  2. Callers MUST call managedBitmap.release() once finished drawing/blitting.
 *  3. When LruCache evicts an entry, it releases the cache's reference. Only when
 *     refCount reaches 0 is the underlying Bitmap returned to the reusable pool
 *     (or recycled if the pool is full or the bitmap is immutable).
 *  4. Register this instance with Application/Activity.registerComponentCallbacks()
 *     so Android OS TRIM_MEMORY_* signals trim buffers immediately.
 */
public final class BitmapMemoryPool implements ComponentCallbacks2 {

    // ── Render Artifact Classification ───────────────────────────────────────

    public enum RenderKind {
        THUMBNAIL(1),
        LOW_RES_PREVIEW(2),
        FULL_PAGE(3),
        VIEWPORT_TILE(4),
        RASTER_IMAGE(5);

        public final int priority;

        RenderKind(int priority) {
            this.priority = priority;
        }
    }

    // ── Zero-Allocation Cache Key ────────────────────────────────────────────

    public static final class CacheKey {
        @NonNull public final String docKey;
        public final int pageIndex;
        @NonNull public final RenderKind kind;
        public final int scalePercent;
        public final int rotation;
        public final int tileLeft;
        public final int tileTop;
        public final int tileRight;
        public final int tileBottom;
        private final int cachedHash;

        private CacheKey(@NonNull String docKey,
                         int pageIndex,
                         @NonNull RenderKind kind,
                         int scalePercent,
                         int rotation,
                         int tileLeft,
                         int tileTop,
                         int tileRight,
                         int tileBottom) {
            this.docKey = docKey;
            this.pageIndex = pageIndex;
            this.kind = kind;
            this.scalePercent = scalePercent;
            this.rotation = normalizeRotation(rotation);
            this.tileLeft = tileLeft;
            this.tileTop = tileTop;
            this.tileRight = tileRight;
            this.tileBottom = tileBottom;
            this.cachedHash = computeHash();
        }

        @NonNull
        public static CacheKey forPage(@NonNull String docKey,
                                       int pageIndex,
                                       @NonNull RenderKind kind,
                                       float scale,
                                       int rotation) {
            int pct = Math.max(1, Math.round(scale * 100f));
            return new CacheKey(docKey, pageIndex, kind, pct, rotation, 0, 0, 0, 0);
        }

        @NonNull
        public static CacheKey forTile(@NonNull String docKey,
                                       int pageIndex,
                                       float scale,
                                       int rotation,
                                       @NonNull Rect pageClipRect) {
            int pct = Math.max(1, Math.round(scale * 100f));
            return new CacheKey(
                    docKey,
                    pageIndex,
                    RenderKind.VIEWPORT_TILE,
                    pct,
                    rotation,
                    pageClipRect.left,
                    pageClipRect.top,
                    pageClipRect.right,
                    pageClipRect.bottom
            );
        }

        private static int normalizeRotation(int deg) {
            int r = deg % 360;
            return r < 0 ? r + 360 : r;
        }

        private int computeHash() {
            int h = docKey.hashCode();
            h = 31 * h + pageIndex;
            h = 31 * h + kind.ordinal();
            h = 31 * h + scalePercent;
            h = 31 * h + rotation;
            h = 31 * h + tileLeft;
            h = 31 * h + tileTop;
            h = 31 * h + tileRight;
            h = 31 * h + tileBottom;
            return h;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CacheKey)) return false;
            CacheKey that = (CacheKey) o;
            return pageIndex == that.pageIndex
                    && scalePercent == that.scalePercent
                    && rotation == that.rotation
                    && tileLeft == that.tileLeft
                    && tileTop == that.tileTop
                    && tileRight == that.tileRight
                    && tileBottom == that.tileBottom
                    && kind == that.kind
                    && docKey.equals(that.docKey);
        }

        @Override
        public int hashCode() {
            return cachedHash;
        }
    }

    // ── Reference-Counted Bitmap Lease ───────────────────────────────────────

    public static final class ManagedBitmap {
        @NonNull private final Bitmap bitmap;
        @NonNull private final BitmapMemoryPool ownerPool;
        private final boolean poolable;
        private final int byteSize;
        private final AtomicInteger refCount = new AtomicInteger(1);
        private final AtomicBoolean returned = new AtomicBoolean(false);

        ManagedBitmap(@NonNull Bitmap bitmap,
                      @NonNull BitmapMemoryPool ownerPool,
                      boolean poolable) {
            this.bitmap = bitmap;
            this.ownerPool = ownerPool;
            this.poolable = poolable && bitmap.isMutable();
            this.byteSize = Math.max(1, bitmap.getAllocationByteCount());
        }

        @NonNull
        public Bitmap getBitmap() {
            if (bitmap.isRecycled()) {
                throw new IllegalStateException("Attempted to access a recycled ManagedBitmap");
            }
            return bitmap;
        }

        public int getWidth() {
            return bitmap.getWidth();
        }

        public int getHeight() {
            return bitmap.getHeight();
        }

        public int getByteSize() {
            return byteSize;
        }

        public boolean isValid() {
            return !bitmap.isRecycled() && refCount.get() > 0;
        }

        /**
         * Increments the reference count. Returns false if the lease has already
         * expired and been returned to the pool.
         */
        public boolean retain() {
            while (true) {
                int current = refCount.get();
                if (current <= 0 || bitmap.isRecycled()) {
                    return false;
                }
                if (refCount.compareAndSet(current, current + 1)) {
                    return true;
                }
            }
        }

        /**
         * Decrements the reference count. When it reaches 0, returns the Bitmap
         * to the owner pool (or recycles it if non-poolable).
         */
        public void release() {
            while (true) {
                int current = refCount.get();
                if (current <= 0) {
                    return;
                }
                int next = current - 1;
                if (refCount.compareAndSet(current, next)) {
                    if (next == 0 && returned.compareAndSet(false, true)) {
                        ownerPool.onManagedBitmapZeroRefs(bitmap, poolable);
                    }
                    return;
                }
            }
        }
    }

    // ── Diagnostics Snapshot ─────────────────────────────────────────────────

    public static final class PoolStats {
        public final int cacheSizeBytes;
        public final int cacheMaxBytes;
        public final int poolSizeBytes;
        public final int poolMaxBytes;
        public final int pooledBitmapCount;
        public final int activeLeasedCount;
        public final long cacheHits;
        public final long cacheMisses;
        public final long poolReuses;
        public final long freshAllocations;

        PoolStats(int cacheSizeBytes,
                  int cacheMaxBytes,
                  int poolSizeBytes,
                  int poolMaxBytes,
                  int pooledBitmapCount,
                  int activeLeasedCount,
                  long cacheHits,
                  long cacheMisses,
                  long poolReuses,
                  long freshAllocations) {
            this.cacheSizeBytes = cacheSizeBytes;
            this.cacheMaxBytes = cacheMaxBytes;
            this.poolSizeBytes = poolSizeBytes;
            this.poolMaxBytes = poolMaxBytes;
            this.pooledBitmapCount = pooledBitmapCount;
            this.activeLeasedCount = activeLeasedCount;
            this.cacheHits = cacheHits;
            this.cacheMisses = cacheMisses;
            this.poolReuses = poolReuses;
            this.freshAllocations = freshAllocations;
        }
    }

    // ── Fields & Constants ───────────────────────────────────────────────────

    private static final int MB = 1024 * 1024;
    private static final int MAX_RECONFIGURE_OVERSIZE_RATIO = 2; // Avoid wasting >2x RAM on a tiny tile
    private static final int MAX_BITMAPS_PER_BUCKET = 6;

    private final int maxCacheBytes;
    private final int maxPoolBytes;
    private final LruCache<CacheKey, ManagedBitmap> lruCache;

    // Reusable pool indexed by allocationByteCount -> FIFO queue of mutable Bitmaps
    private final Object poolLock = new Object();
    private final NavigableMap<Integer, Deque<Bitmap>> sizeBuckets = new TreeMap<>();
    private int currentPoolBytes = 0;
    private int pooledCount = 0;

    // Telemetry counters
    private final AtomicInteger activeLeasedBitmaps = new AtomicInteger(0);
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final AtomicLong poolReuses = new AtomicLong(0);
    private final AtomicLong freshAllocations = new AtomicLong(0);

    // ── Construction & Memory Budgeting ──────────────────────────────────────

    public BitmapMemoryPool(@NonNull Context context) {
        this(computeBudgets(context.getApplicationContext()));
    }

    private BitmapMemoryPool(@NonNull int[] budgets) {
        this.maxCacheBytes = budgets[0];
        this.maxPoolBytes = budgets[1];

        this.lruCache = new LruCache<CacheKey, ManagedBitmap>(this.maxCacheBytes) {
            @Override
            protected int sizeOf(@NonNull CacheKey key, @NonNull ManagedBitmap value) {
                return value.getByteSize();
            }

            @Override
            protected void entryRemoved(boolean evicted,
                                        @NonNull CacheKey key,
                                        @NonNull ManagedBitmap oldValue,
                                        @Nullable ManagedBitmap newValue) {
                // Release the cache's ownership reference. If a View is currently
                // drawing oldValue, it stays alive until the View calls release().
                oldValue.release();
            }
        };
    }

    /**
     * Computes [maxCacheBytes, maxPoolBytes] dynamically from the device's
     * ActivityManager memory class and low-RAM classification.
     */
    @NonNull
    private static int[] computeBudgets(@NonNull Context appContext) {
        ActivityManager am = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
        int memClassMb = 128;
        boolean isLowRam = false;

        if (am != null) {
            memClassMb = Math.max(32, am.getMemoryClass());
            isLowRam = am.isLowRamDevice();
        }

        long totalHeapBytes = (long) memClassMb * MB;
        int cacheBytes;
        int poolBytes;

        if (isLowRam || memClassMb <= 128) {
            // Conservative budget for 2-3 GB RAM devices (prevents LMK kills)
            cacheBytes = (int) Math.min(28L * MB, Math.max(12L * MB, totalHeapBytes / 8));
            poolBytes  = (int) Math.min(10L * MB, Math.max(4L * MB, totalHeapBytes / 20));
        } else {
            // Standard & high-RAM devices
            cacheBytes = (int) Math.min(96L * MB, Math.max(32L * MB, (totalHeapBytes * 18) / 100));
            poolBytes  = (int) Math.min(32L * MB, Math.max(12L * MB, (totalHeapBytes * 7) / 100));
        }

        return new int[]{ cacheBytes, poolBytes };
    }

    // ── Cache Operations (Lease-Based) ───────────────────────────────────────

    /**
     * Looks up a cached render and atomically retains a lease for the caller.
     * Caller MUST call release() on the returned ManagedBitmap when done.
     */
    @Nullable
    public ManagedBitmap get(@NonNull CacheKey key) {
        ManagedBitmap entry = lruCache.get(key);
        if (entry != null) {
            if (entry.retain()) {
                cacheHits.incrementAndGet();
                return entry;
            }
            // Entry was concurrently invalidated/recycled; remove stale mapping
            lruCache.remove(key);
        }
        cacheMisses.incrementAndGet();
        return null;
    }

    /**
     * Wraps a freshly rendered Bitmap in a ManagedBitmap, inserts it into the
     * LruCache, and returns a retained lease to the caller.
     *
     * Caller owns the returned lease and MUST call release() when done.
     */
    @NonNull
    public ManagedBitmap putAndAcquire(@NonNull CacheKey key,
                                       @NonNull Bitmap bitmap,
                                       boolean poolable) {
        ManagedBitmap managed = new ManagedBitmap(bitmap, this, poolable);
        activeLeasedBitmaps.incrementAndGet();

        // Retain a second reference for the caller BEFORE inserting into lruCache
        // so immediate eviction under tight memory cannot invalidate the caller's lease.
        managed.retain();
        lruCache.put(key, managed);
        return managed;
    }

    /**
     * Wraps a standalone Bitmap (e.g., an ephemeral full-screen image not stored
     * in LruCache) so it still participates in the reusable BitmapPool on release().
     */
    @NonNull
    public ManagedBitmap wrapStandalone(@NonNull Bitmap bitmap, boolean poolable) {
        ManagedBitmap managed = new ManagedBitmap(bitmap, this, poolable);
        activeLeasedBitmaps.incrementAndGet();
        return managed;
    }

    // ── Reusable Native Bitmap Allocation / Checkout ─────────────────────────

    /**
     * Obtains a mutable Bitmap with exact (width, height, config) dimensions,
     * reusing and reconfiguring a pooled Bitmap whenever possible.
     *
     * Ideal for PdfRenderer.Page.render() and offscreen Canvas targets.
     */
    @NonNull
    public Bitmap obtainExact(int width,
                              int height,
                              @NonNull Bitmap.Config config,
                              int fillColor) {
        int safeW = Math.max(1, width);
        int safeH = Math.max(1, height);
        int requiredBytes = computeByteCount(safeW, safeH, config);

        Bitmap candidate = checkoutFromPool(requiredBytes, requiredBytes * MAX_RECONFIGURE_OVERSIZE_RATIO);
        if (candidate != null) {
            try {
                if (candidate.getWidth() != safeW
                        || candidate.getHeight() != safeH
                        || candidate.getConfig() != config) {
                    candidate.reconfigure(safeW, safeH, config);
                }
                candidate.setHasAlpha(config == Bitmap.Config.ARGB_8888);
                candidate.eraseColor(fillColor);
                poolReuses.incrementAndGet();
                return candidate;
            } catch (Exception ignored) {
                // Some OEM graphics drivers reject reconfigure() on certain strides;
                // recycle safely and fall back to a fresh allocation.
                if (!candidate.isRecycled()) {
                    candidate.recycle();
                }
            }
        }

        freshAllocations.incrementAndGet();
        Bitmap fresh = Bitmap.createBitmap(safeW, safeH, config);
        if (fillColor != Color.TRANSPARENT) {
            fresh.eraseColor(fillColor);
        }
        return fresh;
    }

    /**
     * Obtains a mutable Bitmap suitable for BitmapFactory.Options.inBitmap
     * (API 19+: allocationByteCount >= decoded width * height * bytesPerPixel).
     * Returns null if no suitable pooled bitmap exists so BitmapFactory allocates fresh.
     */
    @Nullable
    public Bitmap obtainForInBitmap(int targetWidth,
                                    int targetHeight,
                                    @NonNull Bitmap.Config config) {
        int safeW = Math.max(1, targetWidth);
        int safeH = Math.max(1, targetHeight);
        int requiredBytes = computeByteCount(safeW, safeH, config);

        Bitmap candidate = checkoutFromPool(requiredBytes, requiredBytes * MAX_RECONFIGURE_OVERSIZE_RATIO);
        if (candidate != null) {
            poolReuses.incrementAndGet();
        }
        return candidate;
    }

    @Nullable
    private Bitmap checkoutFromPool(int minBytes, int maxBytes) {
        synchronized (poolLock) {
            Map.Entry<Integer, Deque<Bitmap>> entry = sizeBuckets.ceilingEntry(minBytes);
            while (entry != null && entry.getKey() <= maxBytes) {
                Deque<Bitmap> deque = entry.getValue();
                while (!deque.isEmpty()) {
                    Bitmap bmp = deque.pollFirst();
                    if (bmp == null) continue;
                    int allocBytes = bmp.isRecycled() ? 0 : bmp.getAllocationByteCount();
                    currentPoolBytes = Math.max(0, currentPoolBytes - allocBytes);
                    pooledCount = Math.max(0, pooledCount - 1);

                    if (deque.isEmpty()) {
                        sizeBuckets.remove(entry.getKey());
                    }
                    if (!bmp.isRecycled() && bmp.isMutable()) {
                        return bmp;
                    }
                }
                entry = sizeBuckets.higherEntry(entry.getKey());
            }
        }
        return null;
    }

    /**
     * Returns a finished mutable Bitmap to the reusable pool, or recycles it if
     * the pool is at capacity or the bitmap cannot be reused.
     */
    public void recycleOrPool(@Nullable Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return;
        if (!bitmap.isMutable()) {
            bitmap.recycle();
            return;
        }

        int allocBytes = bitmap.getAllocationByteCount();
        // Do not pool giant single allocations (> 50% of total pool budget)
        if (allocBytes <= 0 || allocBytes > (maxPoolBytes / 2)) {
            bitmap.recycle();
            return;
        }

        synchronized (poolLock) {
            evictPoolToFitLocked(maxPoolBytes - allocBytes);
            if (currentPoolBytes + allocBytes > maxPoolBytes) {
                bitmap.recycle();
                return;
            }

            Deque<Bitmap> deque = sizeBuckets.get(allocBytes);
            if (deque == null) {
                deque = new ArrayDeque<>(MAX_BITMAPS_PER_BUCKET);
                sizeBuckets.put(allocBytes, deque);
            } else if (deque.size() >= MAX_BITMAPS_PER_BUCKET) {
                Bitmap oldest = deque.pollFirst();
                if (oldest != null && !oldest.isRecycled()) {
                    currentPoolBytes = Math.max(0, currentPoolBytes - oldest.getAllocationByteCount());
                    pooledCount = Math.max(0, pooledCount - 1);
                    oldest.recycle();
                }
            }

            deque.addLast(bitmap);
            currentPoolBytes += allocBytes;
            pooledCount++;
        }
    }

    private void onManagedBitmapZeroRefs(@NonNull Bitmap bitmap, boolean poolable) {
        activeLeasedBitmaps.decrementAndGet();
        if (poolable) {
            recycleOrPool(bitmap);
        } else if (!bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    // ── Targeted Invalidation & Eviction ─────────────────────────────────────

    /**
     * Evicts all cached pages and tiles belonging to a specific document
     * (e.g., when closing a document or rotating all pages).
     */
    public void evictDocument(@NonNull String docKey) {
        Map<CacheKey, ManagedBitmap> snapshot = lruCache.snapshot();
        for (CacheKey key : snapshot.keySet()) {
            if (key.docKey.equals(docKey)) {
                lruCache.remove(key);
            }
        }
    }

    /**
     * Evicts only zoomed viewport tiles for a specific page while keeping base
     * full-page renders and thumbnails warm.
     */
    public void evictTilesForPage(@NonNull String docKey, int pageIndex) {
        Map<CacheKey, ManagedBitmap> snapshot = lruCache.snapshot();
        for (CacheKey key : snapshot.keySet()) {
            if (key.docKey.equals(docKey)
                    && key.pageIndex == pageIndex
                    && key.kind == RenderKind.VIEWPORT_TILE) {
                lruCache.remove(key);
            }
        }
    }

    /**
     * Evicts all non-thumbnail renders across all documents while preserving
     * lightweight cover thumbnails.
     */
    public void evictAllExceptThumbnails() {
        Map<CacheKey, ManagedBitmap> snapshot = lruCache.snapshot();
        for (CacheKey key : snapshot.keySet()) {
            if (key.kind != RenderKind.THUMBNAIL) {
                lruCache.remove(key);
            }
        }
        flushPool();
    }

    /**
     * Flushes and recycles every idle Bitmap sitting in the reusable pool.
     */
    public void flushPool() {
        List<Bitmap> toRecycle = new ArrayList<>();
        synchronized (poolLock) {
            for (Deque<Bitmap> deque : sizeBuckets.values()) {
                while (!deque.isEmpty()) {
                    Bitmap b = deque.pollFirst();
                    if (b != null) toRecycle.add(b);
                }
            }
            sizeBuckets.clear();
            currentPoolBytes = 0;
            pooledCount = 0;
        }
        for (Bitmap b : toRecycle) {
            if (!b.isRecycled()) {
                b.recycle();
            }
        }
    }

    /**
     * Completely clears both the LruCache and the reusable BitmapPool.
     * Any Bitmap still actively leased by a View remains valid until its
     * consumer calls release().
     */
    public void clearAll() {
        lruCache.evictAll();
        flushPool();
    }

    private void evictPoolToFitLocked(int targetMaxBytes) {
        int safeTarget = Math.max(0, targetMaxBytes);
        Iterator<Map.Entry<Integer, Deque<Bitmap>>> it = sizeBuckets.entrySet().iterator();
        while (currentPoolBytes > safeTarget && it.hasNext()) {
            Map.Entry<Integer, Deque<Bitmap>> entry = it.next();
            Deque<Bitmap> deque = entry.getValue();
            while (currentPoolBytes > safeTarget && !deque.isEmpty()) {
                Bitmap victim = deque.pollFirst();
                if (victim != null && !victim.isRecycled()) {
                    currentPoolBytes = Math.max(0, currentPoolBytes - victim.getAllocationByteCount());
                    pooledCount = Math.max(0, pooledCount - 1);
                    victim.recycle();
                }
            }
            if (deque.isEmpty()) {
                it.remove();
            }
        }
    }

    // ── Android OS ComponentCallbacks2 Memory Pressure Hooks ─────────────────

    /**
     * Responds to Android's memory-pressure signals with a severity escalation
     * that matches Android's own ordering:
     *
     *   Foreground tiers (light → critical):
     *     TRIM_MEMORY_RUNNING_MODERATE (5)  → light cache trim
     *     TRIM_MEMORY_RUNNING_LOW      (10) → moderate cache trim + pool flush
     *     TRIM_MEMORY_RUNNING_CRITICAL (15) → aggressive trim + pool flush
     *
     *   UI_HIDDEN (20) — app just went to background; keep the pool warm so
     *   return-to-foreground stays fast. Light trim only.
     *
     *   Background tiers (moderate → complete):
     *     TRIM_MEMORY_BACKGROUND (40) → moderate cache trim + pool flush
     *     TRIM_MEMORY_MODERATE   (60) → aggressive trim + pool flush
     *     TRIM_MEMORY_COMPLETE   (80) → clear everything
     *
     * Previous implementation used a single `level >= RUNNING_CRITICAL` guard,
     * which incorrectly routed every signal from 15 upward — including the
     * light UI_HIDDEN transition and moderate BACKGROUND — into clearAll(),
     * causing unnecessary re-renders whenever the app was backgrounded.
     */
    @Override
    public void onTrimMemory(int level) {
        // UI_HIDDEN is a distinct transition (numeric value 20 sits between
        // RUNNING_CRITICAL=15 and BACKGROUND=40) — handle it first.
        if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            // Going to background: light cache trim, keep pool warm for fast return.
            lruCache.trimToSize(maxCacheBytes / 2);
            return;
        }

        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            // Background & system will kill soon — clear everything.
            clearAll();
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            // Background & low memory — aggressive trim.
            lruCache.trimToSize(maxCacheBytes / 4);
            flushPool();
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            // Background & moderate pressure — trim half.
            lruCache.trimToSize(maxCacheBytes / 2);
            flushPool();
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            // Foreground & critical — aggressive trim, accept re-render of non-visible pages.
            lruCache.trimToSize(maxCacheBytes / 4);
            flushPool();
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            // Foreground & low — trim half + drain pool.
            lruCache.trimToSize(maxCacheBytes / 2);
            flushPool();
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) {
            // Foreground & moderate — light trim only.
            lruCache.trimToSize((maxCacheBytes * 3L) / 4);
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        // No-op; orientation changes are handled by viewport layout without discarding pages
    }

    @Override
    public void onLowMemory() {
        clearAll();
    }

    // ── Telemetry & Byte Helpers ─────────────────────────────────────────────

    @NonNull
    public PoolStats getStats() {
        int poolBytes;
        int poolItems;
        synchronized (poolLock) {
            poolBytes = currentPoolBytes;
            poolItems = pooledCount;
        }
        return new PoolStats(
                lruCache.size(),
                maxCacheBytes,
                poolBytes,
                maxPoolBytes,
                poolItems,
                activeLeasedBitmaps.get(),
                cacheHits.get(),
                cacheMisses.get(),
                poolReuses.get(),
                freshAllocations.get()
        );
    }

    private static int computeByteCount(int width, int height, @NonNull Bitmap.Config config) {
        int bytesPerPixel;
        switch (config) {
            case ALPHA_8:
                bytesPerPixel = 1;
                break;
            case RGB_565:
            case ARGB_4444:
                bytesPerPixel = 2;
                break;
            case RGBA_F16:
                bytesPerPixel = 8;
                break;
            case ARGB_8888:
            default:
                bytesPerPixel = 4;
                break;
        }
        long total = (long) width * (long) height * bytesPerPixel;
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }
}