package com.medhurb.app.viewer.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.medhurb.app.viewer.storage.MedvixStorageResolver.ResolvedDocument;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * MedvixDocumentStore
 * ============================================================================
 * Thread-safe, crash-atomic sidecar store for MedVix PDF and Image viewer state,
 * bookmarks, text highlights/notes, and freehand ink annotations.
 *
 * Storage layout:
 *  - Library items: content/files/blobs/{slug}/reader_state.json
 *                   content/files/blobs/{slug}/annotations.json
 *  - External URIs: cache/tmp/ext-sidecars/{slug}/reader_state.json
 *                   cache/tmp/ext-sidecars/{slug}/annotations.json
 *
 * Because library sidecars reside inside the item's blob directory, deleting a
 * download in resource-browser.js automatically purges all sidecar data.
 */
public final class MedvixDocumentStore implements AutoCloseable {

    // ── Constants ────────────────────────────────────────────────────────────

    public static final String FILE_READER_STATE    = "reader_state.json";
    public static final String FILE_ANNOTATIONS     = "annotations.json";

    private static final int SCHEMA_VERSION         = 1;
    private static final long DEBOUNCE_SAVE_MS      = 450L;
    private static final int MAX_UNDO_HISTORY       = 64;

    private static final String GLOBAL_PREFS_NAME   = "MedvixReaderGlobalPrefs";
    private static final String PREF_THEME          = "default_reading_theme";
    private static final String PREF_SCROLL_MODE    = "default_scroll_mode";
    private static final String PREF_FIT_MODE       = "default_fit_mode";
    private static final String PREF_KEEP_SCREEN_ON = "default_keep_screen_on";

    // ── Enums ────────────────────────────────────────────────────────────────

    public enum ReadingTheme {
        DAY("day"),
        SEPIA("sepia"),
        NIGHT("night"),
        HIGH_CONTRAST("high_contrast");

        public final String id;

        ReadingTheme(String id) {
            this.id = id;
        }

        @NonNull
        public static ReadingTheme fromId(@Nullable String raw) {
            if (raw == null) return DAY;
            switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "sepia":
                    return SEPIA;
                case "night":
                case "dark":
                    return NIGHT;
                case "high_contrast":
                    return HIGH_CONTRAST;
                case "day":
                default:
                    return DAY;
            }
        }
    }

    public enum ScrollMode {
        VERTICAL_CONTINUOUS("vertical_continuous"),
        HORIZONTAL_PAGED("horizontal_paged"),
        SINGLE_PAGE("single_page");

        public final String id;

        ScrollMode(String id) {
            this.id = id;
        }

        @NonNull
        public static ScrollMode fromId(@Nullable String raw) {
            if (raw == null) return VERTICAL_CONTINUOUS;
            switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "horizontal_paged":
                case "horizontal":
                    return HORIZONTAL_PAGED;
                case "single_page":
                case "single":
                    return SINGLE_PAGE;
                case "vertical_continuous":
                default:
                    return VERTICAL_CONTINUOUS;
            }
        }
    }

    public enum FitMode {
        FIT_WIDTH("fit_width"),
        FIT_PAGE("fit_page"),
        CUSTOM_ZOOM("custom_zoom");

        public final String id;

        FitMode(String id) {
            this.id = id;
        }

        @NonNull
        public static FitMode fromId(@Nullable String raw) {
            if (raw == null) return FIT_WIDTH;
            switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "fit_page":
                    return FIT_PAGE;
                case "custom_zoom":
                    return CUSTOM_ZOOM;
                case "fit_width":
                default:
                    return FIT_WIDTH;
            }
        }
    }

    public enum TextMarkupStyle {
        HIGHLIGHT("highlight"),
        UNDERLINE("underline"),
        STRIKEOUT("strikeout");

        public final String id;

        TextMarkupStyle(String id) {
            this.id = id;
        }

        @NonNull
        public static TextMarkupStyle fromId(@Nullable String raw) {
            if (raw == null) return HIGHLIGHT;
            switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "underline":
                    return UNDERLINE;
                case "strikeout":
                case "strikethrough":
                    return STRIKEOUT;
                case "highlight":
                default:
                    return HIGHLIGHT;
            }
        }
    }

    public enum InkToolType {
        PEN("pen"),
        HIGHLIGHTER_INK("highlighter_ink");

        public final String id;

        InkToolType(String id) {
            this.id = id;
        }

        @NonNull
        public static InkToolType fromId(@Nullable String raw) {
            if ("highlighter_ink".equalsIgnoreCase(raw)) {
                return HIGHLIGHTER_INK;
            }
            return PEN;
        }
    }

    // ── Immutable Domain Models ──────────────────────────────────────────────

    public static final class ReaderState {
        public final int lastPageIndex;
        public final float zoomScale;
        public final float normalizedScrollX;
        public final float normalizedScrollY;
        public final int rotationDegrees;
        @NonNull public final ReadingTheme readingTheme;
        @NonNull public final ScrollMode scrollMode;
        @NonNull public final FitMode fitMode;
        public final boolean keepScreenOn;
        public final long updatedAtMs;

        public ReaderState(int lastPageIndex,
                           float zoomScale,
                           float normalizedScrollX,
                           float normalizedScrollY,
                           int rotationDegrees,
                           @NonNull ReadingTheme readingTheme,
                           @NonNull ScrollMode scrollMode,
                           @NonNull FitMode fitMode,
                           boolean keepScreenOn,
                           long updatedAtMs) {
            this.lastPageIndex = Math.max(0, lastPageIndex);
            this.zoomScale = Math.max(0.1f, Math.min(12.0f, zoomScale));
            this.normalizedScrollX = clamp01(normalizedScrollX);
            this.normalizedScrollY = clamp01(normalizedScrollY);
            this.rotationDegrees = normalizeRotation(rotationDegrees);
            this.readingTheme = readingTheme;
            this.scrollMode = scrollMode;
            this.fitMode = fitMode;
            this.keepScreenOn = keepScreenOn;
            this.updatedAtMs = updatedAtMs;
        }
    }

    public static final class BookmarkItem {
        public final int pageIndex;
        @NonNull public final String label;
        public final long createdAtMs;

        public BookmarkItem(int pageIndex, @NonNull String label, long createdAtMs) {
            this.pageIndex = Math.max(0, pageIndex);
            this.label = label;
            this.createdAtMs = createdAtMs;
        }
    }

    /**
     * Text highlight, underline, or strikeout stored in unrotated PDF point space.
     */
    public static final class TextMarkupAnnotation {
        @NonNull public final String id;
        public final int pageIndex;
        @NonNull public final TextMarkupStyle style;
        public final int argbColor;
        @NonNull public final String quotedText;
        @NonNull public final String note;
        @NonNull public final List<RectF> rectsPt;
        @NonNull public final RectF unionBoundsPt;
        public final long createdAtMs;
        public final long updatedAtMs;

        public TextMarkupAnnotation(@NonNull String id,
                                    int pageIndex,
                                    @NonNull TextMarkupStyle style,
                                    int argbColor,
                                    @NonNull String quotedText,
                                    @NonNull String note,
                                    @NonNull List<RectF> rectsPt,
                                    long createdAtMs,
                                    long updatedAtMs) {
            this.id = id;
            this.pageIndex = Math.max(0, pageIndex);
            this.style = style;
            this.argbColor = argbColor;
            this.quotedText = quotedText;
            this.note = note;

            List<RectF> copy = new ArrayList<>(rectsPt.size());
            RectF union = new RectF();
            boolean first = true;
            for (RectF r : rectsPt) {
                if (r == null) continue;
                RectF cloned = new RectF(r);
                copy.add(cloned);
                if (first) {
                    union.set(cloned);
                    first = false;
                } else {
                    union.union(cloned);
                }
            }
            this.rectsPt = Collections.unmodifiableList(copy);
            this.unionBoundsPt = union;
            this.createdAtMs = createdAtMs;
            this.updatedAtMs = updatedAtMs;
        }
    }

    /**
     * Freehand pen or translucent highlighter stroke stored in unrotated
     * PDF point (or base image pixel) space as a packed [x0, y0, x1, y1, ...] array.
     */
    public static final class InkStrokeAnnotation {
        @NonNull public final String id;
        public final int pageIndex;
        @NonNull public final InkToolType tool;
        public final int argbColor;
        public final float strokeWidthPt;
        @NonNull private final float[] packedPointsPt;
        @NonNull public final RectF boundsPt;
        public final long createdAtMs;

        public InkStrokeAnnotation(@NonNull String id,
                                   int pageIndex,
                                   @NonNull InkToolType tool,
                                   int argbColor,
                                   float strokeWidthPt,
                                   @NonNull float[] packedPointsPt,
                                   long createdAtMs) {
            this.id = id;
            this.pageIndex = Math.max(0, pageIndex);
            this.tool = tool;
            this.argbColor = argbColor;
            this.strokeWidthPt = Math.max(0.25f, strokeWidthPt);

            // Ensure even length (x, y pairs)
            int evenLen = packedPointsPt.length & ~1;
            this.packedPointsPt = new float[evenLen];
            System.arraycopy(packedPointsPt, 0, this.packedPointsPt, 0, evenLen);

            RectF bounds = new RectF();
            if (evenLen >= 2) {
                float minX = this.packedPointsPt[0];
                float maxX = minX;
                float minY = this.packedPointsPt[1];
                float maxY = minY;
                for (int i = 2; i < evenLen; i += 2) {
                    float x = this.packedPointsPt[i];
                    float y = this.packedPointsPt[i + 1];
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
                float pad = this.strokeWidthPt * 0.6f;
                bounds.set(minX - pad, minY - pad, maxX + pad, maxY + pad);
            }
            this.boundsPt = bounds;
            this.createdAtMs = createdAtMs;
        }

        public int getPointCount() {
            return packedPointsPt.length / 2;
        }

        public float getX(int pointIndex) {
            return packedPointsPt[pointIndex * 2];
        }

        public float getY(int pointIndex) {
            return packedPointsPt[pointIndex * 2 + 1];
        }

        @NonNull
        public float[] copyPackedPoints() {
            float[] copy = new float[packedPointsPt.length];
            System.arraycopy(packedPointsPt, 0, copy, 0, packedPointsPt.length);
            return copy;
        }
    }

    // ── Listener & Undo Command Interfaces ───────────────────────────────────

    public interface StoreChangeListener {
        void onAnnotationsChanged(int affectedPageIndex);
        void onBookmarksChanged();
    }

    private interface UndoableCommand {
        int getPageIndex();
        void executeLocked();
        void undoLocked();
    }

    // ── Instance State ───────────────────────────────────────────────────────

    @NonNull private final Context appContext;
    @NonNull private final ResolvedDocument resolvedDoc;
    @NonNull private final File stateFile;
    @NonNull private final File annotationsFile;

    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final ScheduledExecutorService ioExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean closed = new AtomicBoolean(false);

    @NonNull private ReaderState readerState;
    private final Map<Integer, BookmarkItem> bookmarksByPage = new LinkedHashMap<>();
    private final Map<Integer, List<TextMarkupAnnotation>> markupsByPage = new LinkedHashMap<>();
    private final Map<Integer, List<InkStrokeAnnotation>> strokesByPage = new LinkedHashMap<>();

    private final Deque<UndoableCommand> undoStack = new ArrayDeque<>(MAX_UNDO_HISTORY);
    private final Deque<UndoableCommand> redoStack = new ArrayDeque<>(MAX_UNDO_HISTORY);

    private volatile boolean stateDirty = false;
    private volatile boolean annotationsDirty = false;
    @Nullable private ScheduledFuture<?> pendingSaveFuture;
    @Nullable private volatile StoreChangeListener changeListener;

    // ── Construction ─────────────────────────────────────────────────────────

    public MedvixDocumentStore(@NonNull Context context, @NonNull ResolvedDocument resolvedDoc) {
        this.appContext = context.getApplicationContext();
        this.resolvedDoc = resolvedDoc;
        this.stateFile = resolvedDoc.getSidecarFile(this.appContext, FILE_READER_STATE);
        this.annotationsFile = resolvedDoc.getSidecarFile(this.appContext, FILE_ANNOTATIONS);

        this.ioExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MedvixDocStore-" + resolvedDoc.slugId);
            t.setPriority(Thread.MIN_PRIORITY + 2);
            return t;
        });

        this.readerState = loadReaderStateFromDisk();
        loadAnnotationsFromDisk();
    }

    public void setStoreChangeListener(@Nullable StoreChangeListener listener) {
        this.changeListener = listener;
    }

    // ── Reader State Operations ──────────────────────────────────────────────

    @NonNull
    public ReaderState getReaderState() {
        rwLock.readLock().lock();
        try {
            return readerState;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public void updateViewportPosition(int pageIndex,
                                       float zoomScale,
                                       float normalizedScrollX,
                                       float normalizedScrollY,
                                       @NonNull FitMode fitMode) {
        rwLock.writeLock().lock();
        try {
            ReaderState cur = this.readerState;
            this.readerState = new ReaderState(
                    pageIndex,
                    zoomScale,
                    normalizedScrollX,
                    normalizedScrollY,
                    cur.rotationDegrees,
                    cur.readingTheme,
                    cur.scrollMode,
                    fitMode,
                    cur.keepScreenOn,
                    System.currentTimeMillis()
            );
            stateDirty = true;
            scheduleDebouncedSaveLocked();
        } finally {
            rwLock.writeLock().unlock();
        }
        saveGlobalFitMode(fitMode);
    }

    public void updateRotation(int rotationDegrees) {
        rwLock.writeLock().lock();
        try {
            ReaderState cur = this.readerState;
            this.readerState = new ReaderState(
                    cur.lastPageIndex,
                    cur.zoomScale,
                    cur.normalizedScrollX,
                    cur.normalizedScrollY,
                    rotationDegrees,
                    cur.readingTheme,
                    cur.scrollMode,
                    cur.fitMode,
                    cur.keepScreenOn,
                    System.currentTimeMillis()
            );
            stateDirty = true;
            scheduleDebouncedSaveLocked();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public void updateReadingPreferences(@NonNull ReadingTheme theme,
                                         @NonNull ScrollMode scrollMode,
                                         boolean keepScreenOn) {
        rwLock.writeLock().lock();
        try {
            ReaderState cur = this.readerState;
            this.readerState = new ReaderState(
                    cur.lastPageIndex,
                    cur.zoomScale,
                    cur.normalizedScrollX,
                    cur.normalizedScrollY,
                    cur.rotationDegrees,
                    theme,
                    scrollMode,
                    cur.fitMode,
                    keepScreenOn,
                    System.currentTimeMillis()
            );
            stateDirty = true;
            scheduleDebouncedSaveLocked();
        } finally {
            rwLock.writeLock().unlock();
        }

        // Persist global defaults so subsequent documents inherit user preferences
        appContext.getSharedPreferences(GLOBAL_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_THEME, theme.id)
                .putString(PREF_SCROLL_MODE, scrollMode.id)
                .putBoolean(PREF_KEEP_SCREEN_ON, keepScreenOn)
                .apply();
    }

    private void saveGlobalFitMode(@NonNull FitMode fitMode) {
        if (fitMode == FitMode.CUSTOM_ZOOM) return;
        appContext.getSharedPreferences(GLOBAL_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_FIT_MODE, fitMode.id)
                .apply();
    }

    // ── Bookmarks Operations ─────────────────────────────────────────────────

    public boolean isPageBookmarked(int pageIndex) {
        rwLock.readLock().lock();
        try {
            return bookmarksByPage.containsKey(pageIndex);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Toggles a bookmark on pageIndex. Returns true if bookmarked after toggle, false if removed.
     */
    public boolean toggleBookmark(int pageIndex, @Nullable String customLabel) {
        int safePage = Math.max(0, pageIndex);
        boolean added;
        rwLock.writeLock().lock();
        try {
            if (bookmarksByPage.containsKey(safePage)) {
                bookmarksByPage.remove(safePage);
                added = false;
            } else {
                String label = !TextUtils.isEmpty(customLabel)
                        ? customLabel.trim()
                        : ("Page " + (safePage + 1));
                bookmarksByPage.put(safePage, new BookmarkItem(safePage, label, System.currentTimeMillis()));
                added = true;
            }
            annotationsDirty = true;
            scheduleDebouncedSaveLocked();
        } finally {
            rwLock.writeLock().unlock();
        }
        notifyBookmarksChanged();
        return added;
    }

    @NonNull
    public List<BookmarkItem> getAllBookmarksSorted() {
        rwLock.readLock().lock();
        try {
            List<BookmarkItem> list = new ArrayList<>(bookmarksByPage.values());
            Collections.sort(list, Comparator.comparingInt(b -> b.pageIndex));
            return Collections.unmodifiableList(list);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    // ── Text Markup (Highlight / Underline / Strikeout) Operations ───────────

    @NonNull
    public TextMarkupAnnotation addTextMarkup(int pageIndex,
                                              @NonNull TextMarkupStyle style,
                                              int argbColor,
                                              @NonNull String quotedText,
                                              @Nullable String note,
                                              @NonNull List<RectF> rectsPt) {
        long now = System.currentTimeMillis();
        final TextMarkupAnnotation item = new TextMarkupAnnotation(
                UUID.randomUUID().toString(),
                pageIndex,
                style,
                argbColor,
                quotedText,
                note != null ? note.trim() : "",
                rectsPt,
                now,
                now
        );

        executeCommand(new UndoableCommand() {
            @Override
            public int getPageIndex() {
                return item.pageIndex;
            }

            @Override
            public void executeLocked() {
                getOrCreateMarkupListLocked(item.pageIndex).add(item);
            }

            @Override
            public void undoLocked() {
                removeMarkupByIdLocked(item.pageIndex, item.id);
            }
        });

        return item;
    }

    public boolean updateTextMarkupNoteAndColor(int pageIndex,
                                                @NonNull String annotationId,
                                                @Nullable String newNote,
                                                int newArgbColor) {
        rwLock.writeLock().lock();
        try {
            List<TextMarkupAnnotation> list = markupsByPage.get(pageIndex);
            if (list == null) return false;
            for (int i = 0; i < list.size(); i++) {
                TextMarkupAnnotation old = list.get(i);
                if (old.id.equals(annotationId)) {
                    final TextMarkupAnnotation updated = new TextMarkupAnnotation(
                            old.id,
                            old.pageIndex,
                            old.style,
                            newArgbColor,
                            old.quotedText,
                            newNote != null ? newNote.trim() : "",
                            old.rectsPt,
                            old.createdAtMs,
                            System.currentTimeMillis()
                    );
                    final TextMarkupAnnotation previous = old;
                    recordAndRunCommandLocked(new UndoableCommand() {
                        @Override
                        public int getPageIndex() {
                            return updated.pageIndex;
                        }

                        @Override
                        public void executeLocked() {
                            replaceMarkupLocked(updated);
                        }

                        @Override
                        public void undoLocked() {
                            replaceMarkupLocked(previous);
                        }
                    });
                    return true;
                }
            }
            return false;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public boolean deleteTextMarkup(int pageIndex, @NonNull String annotationId) {
        rwLock.writeLock().lock();
        try {
            TextMarkupAnnotation existing = findMarkupLocked(pageIndex, annotationId);
            if (existing == null) return false;
            final TextMarkupAnnotation target = existing;
            recordAndRunCommandLocked(new UndoableCommand() {
                @Override
                public int getPageIndex() {
                    return target.pageIndex;
                }

                @Override
                public void executeLocked() {
                    removeMarkupByIdLocked(target.pageIndex, target.id);
                }

                @Override
                public void undoLocked() {
                    getOrCreateMarkupListLocked(target.pageIndex).add(target);
                }
            });
            return true;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @NonNull
    public List<TextMarkupAnnotation> getTextMarkupsForPage(int pageIndex) {
        rwLock.readLock().lock();
        try {
            List<TextMarkupAnnotation> list = markupsByPage.get(pageIndex);
            if (list == null || list.isEmpty()) {
                return Collections.emptyList();
            }
            return Collections.unmodifiableList(new ArrayList<>(list));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @NonNull
    public List<TextMarkupAnnotation> getAllTextMarkupsSorted() {
        rwLock.readLock().lock();
        try {
            List<TextMarkupAnnotation> all = new ArrayList<>();
            List<Integer> pages = new ArrayList<>(markupsByPage.keySet());
            Collections.sort(pages);
            for (int p : pages) {
                List<TextMarkupAnnotation> sub = markupsByPage.get(p);
                if (sub != null) all.addAll(sub);
            }
            return Collections.unmodifiableList(all);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    // ── Freehand Ink Stroke Operations ───────────────────────────────────────

    @NonNull
    public InkStrokeAnnotation addInkStroke(int pageIndex,
                                            @NonNull InkToolType tool,
                                            int argbColor,
                                            float strokeWidthPt,
                                            @NonNull float[] packedPointsPt) {
        final InkStrokeAnnotation stroke = new InkStrokeAnnotation(
                UUID.randomUUID().toString(),
                pageIndex,
                tool,
                argbColor,
                strokeWidthPt,
                packedPointsPt,
                System.currentTimeMillis()
        );

        executeCommand(new UndoableCommand() {
            @Override
            public int getPageIndex() {
                return stroke.pageIndex;
            }

            @Override
            public void executeLocked() {
                getOrCreateStrokeListLocked(stroke.pageIndex).add(stroke);
            }

            @Override
            public void undoLocked() {
                removeStrokeByIdLocked(stroke.pageIndex, stroke.id);
            }
        });

        return stroke;
    }

    /**
     * Erases any ink strokes on pageIndex whose stroke segments intersect the
     * eraser circle (centerPt, radiusPt). Returns number of erased strokes.
     */
    public int eraseInkStrokesNearPoint(int pageIndex,
                                        float ptX,
                                        float ptY,
                                        float eraserRadiusPt) {
        rwLock.writeLock().lock();
        try {
            List<InkStrokeAnnotation> list = strokesByPage.get(pageIndex);
            if (list == null || list.isEmpty()) return 0;

            final List<InkStrokeAnnotation> removed = new ArrayList<>();
            for (InkStrokeAnnotation stroke : list) {
                if (!stroke.boundsPt.intersects(
                        ptX - eraserRadiusPt, ptY - eraserRadiusPt,
                        ptX + eraserRadiusPt, ptY + eraserRadiusPt)) {
                    continue;
                }
                if (doesStrokeIntersectCircle(stroke, ptX, ptY, eraserRadiusPt + stroke.strokeWidthPt * 0.5f)) {
                    removed.add(stroke);
                }
            }

            if (removed.isEmpty()) return 0;

            final int targetPage = pageIndex;
            recordAndRunCommandLocked(new UndoableCommand() {
                @Override
                public int getPageIndex() {
                    return targetPage;
                }

                @Override
                public void executeLocked() {
                    for (InkStrokeAnnotation s : removed) {
                        removeStrokeByIdLocked(targetPage, s.id);
                    }
                }

                @Override
                public void undoLocked() {
                    List<InkStrokeAnnotation> pageList = getOrCreateStrokeListLocked(targetPage);
                    pageList.addAll(removed);
                }
            });

            return removed.size();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @NonNull
    public List<InkStrokeAnnotation> getInkStrokesForPage(int pageIndex) {
        rwLock.readLock().lock();
        try {
            List<InkStrokeAnnotation> list = strokesByPage.get(pageIndex);
            if (list == null || list.isEmpty()) {
                return Collections.emptyList();
            }
            return Collections.unmodifiableList(new ArrayList<>(list));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public boolean hasAnyAnnotations() {
        rwLock.readLock().lock();
        try {
            for (List<TextMarkupAnnotation> l : markupsByPage.values()) {
                if (!l.isEmpty()) return true;
            }
            for (List<InkStrokeAnnotation> l : strokesByPage.values()) {
                if (!l.isEmpty()) return true;
            }
            return false;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    // ── Undo / Redo Engine ───────────────────────────────────────────────────

    public boolean canUndo() {
        rwLock.readLock().lock();
        try {
            return !undoStack.isEmpty();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public boolean canRedo() {
        rwLock.readLock().lock();
        try {
            return !redoStack.isEmpty();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /** Undoes the last annotation mutation. Returns affected pageIndex, or -1 if stack empty. */
    public int undo() {
        int affectedPage = -1;
        rwLock.writeLock().lock();
        try {
            UndoableCommand cmd = undoStack.pollLast();
            if (cmd == null) return -1;
            cmd.undoLocked();
            redoStack.addLast(cmd);
            affectedPage = cmd.getPageIndex();
            annotationsDirty = true;
            scheduleDebouncedSaveLocked();
        } finally {
            rwLock.writeLock().unlock();
        }
        notifyAnnotationsChanged(affectedPage);
        return affectedPage;
    }

    /** Redoes the last undone annotation mutation. Returns affected pageIndex, or -1 if stack empty. */
    public int redo() {
        int affectedPage = -1;
        rwLock.writeLock().lock();
        try {
            UndoableCommand cmd = redoStack.pollLast();
            if (cmd == null) return -1;
            cmd.executeLocked();
            undoStack.addLast(cmd);
            affectedPage = cmd.getPageIndex();
            annotationsDirty = true;
            scheduleDebouncedSaveLocked();
        } finally {
            rwLock.writeLock().unlock();
        }
        notifyAnnotationsChanged(affectedPage);
        return affectedPage;
    }

    private void executeCommand(@NonNull UndoableCommand cmd) {
        rwLock.writeLock().lock();
        try {
            recordAndRunCommandLocked(cmd);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private void recordAndRunCommandLocked(@NonNull UndoableCommand cmd) {
        cmd.executeLocked();
        if (undoStack.size() >= MAX_UNDO_HISTORY) {
            undoStack.pollFirst();
        }
        undoStack.addLast(cmd);
        redoStack.clear();
        annotationsDirty = true;
        scheduleDebouncedSaveLocked();
        notifyAnnotationsChanged(cmd.getPageIndex());
    }

    // ── Export & Disk Serialization (Crash-Safe Atomic Writes) ───────────────

    /**
     * Serializes all bookmarks, highlights, notes, and ink strokes into a
     * self-contained JSON string for Capacitor / cloud backup integration.
     */
    @NonNull
    public String exportAnnotationsJsonString() throws JSONException {
        rwLock.readLock().lock();
        try {
            return serializeAnnotationsLocked().toString(2);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Immediately flushes any pending reader state and annotation changes to disk
     * synchronously (safe to call from Activity.onPause() / onStop()).
     */
    public void flushNowSync() {
        String statePayload = null;
        String annotationsPayload = null;

        rwLock.writeLock().lock();
        try {
            if (pendingSaveFuture != null) {
                pendingSaveFuture.cancel(false);
                pendingSaveFuture = null;
            }
            if (stateDirty) {
                try {
                    statePayload = serializeReaderStateLocked().toString();
                    stateDirty = false;
                } catch (JSONException ignored) {
                }
            }
            if (annotationsDirty) {
                try {
                    annotationsPayload = serializeAnnotationsLocked().toString();
                    annotationsDirty = false;
                } catch (JSONException ignored) {
                }
            }
        } finally {
            rwLock.writeLock().unlock();
        }

        if (statePayload != null) {
            writeAtomicUtf8(stateFile, statePayload);
        }
        if (annotationsPayload != null) {
            writeAtomicUtf8(annotationsFile, annotationsPayload);
        }
    }

    private void scheduleDebouncedSaveLocked() {
        if (closed.get() || ioExecutor.isShutdown()) return;
        if (pendingSaveFuture != null && !pendingSaveFuture.isDone()) {
            pendingSaveFuture.cancel(false);
        }
        pendingSaveFuture = ioExecutor.schedule(this::flushNowSync, DEBOUNCE_SAVE_MS, TimeUnit.MILLISECONDS);
    }

    @NonNull
    private JSONObject serializeReaderStateLocked() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("slugId", resolvedDoc.slugId);
        if (!TextUtils.isEmpty(resolvedDoc.resourceId)) {
            root.put("resourceId", resolvedDoc.resourceId);
        }
        root.put("lastPageIndex", readerState.lastPageIndex);
        root.put("zoomScale", readerState.zoomScale);
        root.put("normalizedScrollX", readerState.normalizedScrollX);
        root.put("normalizedScrollY", readerState.normalizedScrollY);
        root.put("rotationDegrees", readerState.rotationDegrees);
        root.put("readingTheme", readerState.readingTheme.id);
        root.put("scrollMode", readerState.scrollMode.id);
        root.put("fitMode", readerState.fitMode.id);
        root.put("keepScreenOn", readerState.keepScreenOn);
        root.put("updatedAtMs", readerState.updatedAtMs);
        return root;
    }

    @NonNull
    private JSONObject serializeAnnotationsLocked() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("slugId", resolvedDoc.slugId);
        root.put("updatedAtMs", System.currentTimeMillis());

        // 1. Bookmarks
        JSONArray bookmarksArr = new JSONArray();
        List<BookmarkItem> sortedBookmarks = new ArrayList<>(bookmarksByPage.values());
        Collections.sort(sortedBookmarks, Comparator.comparingInt(b -> b.pageIndex));
        for (BookmarkItem b : sortedBookmarks) {
            JSONObject obj = new JSONObject();
            obj.put("pageIndex", b.pageIndex);
            obj.put("label", b.label);
            obj.put("createdAtMs", b.createdAtMs);
            bookmarksArr.put(obj);
        }
        root.put("bookmarks", bookmarksArr);

        // 2. Text Markups
        JSONArray markupsArr = new JSONArray();
        for (List<TextMarkupAnnotation> pageList : markupsByPage.values()) {
            for (TextMarkupAnnotation m : pageList) {
                JSONObject obj = new JSONObject();
                obj.put("id", m.id);
                obj.put("pageIndex", m.pageIndex);
                obj.put("style", m.style.id);
                obj.put("argbColor", m.argbColor);
                obj.put("quotedText", m.quotedText);
                obj.put("note", m.note);
                obj.put("createdAtMs", m.createdAtMs);
                obj.put("updatedAtMs", m.updatedAtMs);

                JSONArray rectsArr = new JSONArray();
                for (RectF r : m.rectsPt) {
                    JSONArray box = new JSONArray();
                    box.put(round3(r.left));
                    box.put(round3(r.top));
                    box.put(round3(r.right));
                    box.put(round3(r.bottom));
                    rectsArr.put(box);
                }
                obj.put("rectsPt", rectsArr);
                markupsArr.put(obj);
            }
        }
        root.put("textMarkups", markupsArr);

        // 3. Ink Strokes
        JSONArray strokesArr = new JSONArray();
        for (List<InkStrokeAnnotation> pageList : strokesByPage.values()) {
            for (InkStrokeAnnotation s : pageList) {
                JSONObject obj = new JSONObject();
                obj.put("id", s.id);
                obj.put("pageIndex", s.pageIndex);
                obj.put("tool", s.tool.id);
                obj.put("argbColor", s.argbColor);
                obj.put("strokeWidthPt", round3(s.strokeWidthPt));
                obj.put("createdAtMs", s.createdAtMs);

                JSONArray ptsArr = new JSONArray();
                float[] pts = s.copyPackedPoints();
                for (float v : pts) {
                    ptsArr.put(round3(v));
                }
                obj.put("pointsPt", ptsArr);
                strokesArr.put(obj);
            }
        }
        root.put("inkStrokes", strokesArr);

        return root;
    }

    @NonNull
    private ReaderState loadReaderStateFromDisk() {
        SharedPreferences globalPrefs = appContext.getSharedPreferences(GLOBAL_PREFS_NAME, Context.MODE_PRIVATE);
        ReadingTheme defaultTheme = ReadingTheme.fromId(globalPrefs.getString(PREF_THEME, ReadingTheme.DAY.id));
        ScrollMode defaultScroll = ScrollMode.fromId(globalPrefs.getString(PREF_SCROLL_MODE, ScrollMode.VERTICAL_CONTINUOUS.id));
        FitMode defaultFit = FitMode.fromId(globalPrefs.getString(PREF_FIT_MODE, FitMode.FIT_WIDTH.id));
        boolean defaultKeepAwake = globalPrefs.getBoolean(PREF_KEEP_SCREEN_ON, true);

        String raw = readUtf8File(stateFile);
        if (TextUtils.isEmpty(raw)) {
            return new ReaderState(
                    0, 1.0f, 0f, 0f, 0,
                    defaultTheme, defaultScroll, defaultFit, defaultKeepAwake, 0L
            );
        }

        try {
            JSONObject obj = new JSONObject(raw);
            return new ReaderState(
                    obj.optInt("lastPageIndex", 0),
                    (float) obj.optDouble("zoomScale", 1.0d),
                    (float) obj.optDouble("normalizedScrollX", 0.0d),
                    (float) obj.optDouble("normalizedScrollY", 0.0d),
                    obj.optInt("rotationDegrees", 0),
                    ReadingTheme.fromId(obj.optString("readingTheme", defaultTheme.id)),
                    ScrollMode.fromId(obj.optString("scrollMode", defaultScroll.id)),
                    FitMode.fromId(obj.optString("fitMode", defaultFit.id)),
                    obj.optBoolean("keepScreenOn", defaultKeepAwake),
                    obj.optLong("updatedAtMs", 0L)
            );
        } catch (JSONException e) {
            return new ReaderState(
                    0, 1.0f, 0f, 0f, 0,
                    defaultTheme, defaultScroll, defaultFit, defaultKeepAwake, 0L
            );
        }
    }

    private void loadAnnotationsFromDisk() {
        String raw = readUtf8File(annotationsFile);
        if (TextUtils.isEmpty(raw)) return;

        try {
            JSONObject root = new JSONObject(raw);

            // 1. Bookmarks
            JSONArray bookmarksArr = root.optJSONArray("bookmarks");
            if (bookmarksArr != null) {
                for (int i = 0; i < bookmarksArr.length(); i++) {
                    JSONObject obj = bookmarksArr.optJSONObject(i);
                    if (obj == null) continue;
                    int page = Math.max(0, obj.optInt("pageIndex", 0));
                    String label = obj.optString("label", "Page " + (page + 1));
                    long created = obj.optLong("createdAtMs", 0L);
                    bookmarksByPage.put(page, new BookmarkItem(page, label, created));
                }
            }

            // 2. Text Markups
            JSONArray markupsArr = root.optJSONArray("textMarkups");
            if (markupsArr != null) {
                for (int i = 0; i < markupsArr.length(); i++) {
                    JSONObject obj = markupsArr.optJSONObject(i);
                    if (obj == null) continue;
                    String id = obj.optString("id", UUID.randomUUID().toString());
                    int page = Math.max(0, obj.optInt("pageIndex", 0));
                    TextMarkupStyle style = TextMarkupStyle.fromId(obj.optString("style", "highlight"));
                    int color = obj.optInt("argbColor", Color.argb(110, 255, 235, 59));
                    String quoted = obj.optString("quotedText", "");
                    String note = obj.optString("note", "");
                    long created = obj.optLong("createdAtMs", 0L);
                    long updated = obj.optLong("updatedAtMs", created);

                    JSONArray rectsArr = obj.optJSONArray("rectsPt");
                    List<RectF> rects = new ArrayList<>();
                    if (rectsArr != null) {
                        for (int rIdx = 0; rIdx < rectsArr.length(); rIdx++) {
                            JSONArray box = rectsArr.optJSONArray(rIdx);
                            if (box != null && box.length() >= 4) {
                                rects.add(new RectF(
                                        (float) box.optDouble(0, 0.0),
                                        (float) box.optDouble(1, 0.0),
                                        (float) box.optDouble(2, 0.0),
                                        (float) box.optDouble(3, 0.0)
                                ));
                            }
                        }
                    }

                    if (!rects.isEmpty()) {
                        getOrCreateMarkupListLocked(page).add(new TextMarkupAnnotation(
                                id, page, style, color, quoted, note, rects, created, updated
                        ));
                    }
                }
            }

            // 3. Ink Strokes
            JSONArray strokesArr = root.optJSONArray("inkStrokes");
            if (strokesArr != null) {
                for (int i = 0; i < strokesArr.length(); i++) {
                    JSONObject obj = strokesArr.optJSONObject(i);
                    if (obj == null) continue;
                    String id = obj.optString("id", UUID.randomUUID().toString());
                    int page = Math.max(0, obj.optInt("pageIndex", 0));
                    InkToolType tool = InkToolType.fromId(obj.optString("tool", "pen"));
                    int color = obj.optInt("argbColor", Color.rgb(220, 38, 38));
                    float width = (float) obj.optDouble("strokeWidthPt", 2.5d);
                    long created = obj.optLong("createdAtMs", 0L);

                    JSONArray ptsArr = obj.optJSONArray("pointsPt");
                    if (ptsArr != null && ptsArr.length() >= 2) {
                        int evenLen = ptsArr.length() & ~1;
                        float[] pts = new float[evenLen];
                        for (int pIdx = 0; pIdx < evenLen; pIdx++) {
                            pts[pIdx] = (float) ptsArr.optDouble(pIdx, 0.0);
                        }
                        getOrCreateStrokeListLocked(page).add(new InkStrokeAnnotation(
                                id, page, tool, color, width, pts, created
                        ));
                    }
                }
            }
        } catch (JSONException ignored) {
            // Corrupt sidecar is gracefully ignored rather than blocking document open
        }
    }

    // ── Internal Collection & Geometry Helpers ───────────────────────────────

    @NonNull
    private List<TextMarkupAnnotation> getOrCreateMarkupListLocked(int pageIndex) {
        List<TextMarkupAnnotation> list = markupsByPage.get(pageIndex);
        if (list == null) {
            list = new ArrayList<>();
            markupsByPage.put(pageIndex, list);
        }
        return list;
    }

    @Nullable
    private TextMarkupAnnotation findMarkupLocked(int pageIndex, @NonNull String id) {
        List<TextMarkupAnnotation> list = markupsByPage.get(pageIndex);
        if (list == null) return null;
        for (TextMarkupAnnotation item : list) {
            if (item.id.equals(id)) return item;
        }
        return null;
    }

    private void replaceMarkupLocked(@NonNull TextMarkupAnnotation replacement) {
        List<TextMarkupAnnotation> list = getOrCreateMarkupListLocked(replacement.pageIndex);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(replacement.id)) {
                list.set(i, replacement);
                return;
            }
        }
        list.add(replacement);
    }

    private void removeMarkupByIdLocked(int pageIndex, @NonNull String id) {
        List<TextMarkupAnnotation> list = markupsByPage.get(pageIndex);
        if (list == null) return;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id.equals(id)) {
                list.remove(i);
            }
        }
    }

    @NonNull
    private List<InkStrokeAnnotation> getOrCreateStrokeListLocked(int pageIndex) {
        List<InkStrokeAnnotation> list = strokesByPage.get(pageIndex);
        if (list == null) {
            list = new ArrayList<>();
            strokesByPage.put(pageIndex, list);
        }
        return list;
    }

    private void removeStrokeByIdLocked(int pageIndex, @NonNull String id) {
        List<InkStrokeAnnotation> list = strokesByPage.get(pageIndex);
        if (list == null) return;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id.equals(id)) {
                list.remove(i);
            }
        }
    }

    private static boolean doesStrokeIntersectCircle(@NonNull InkStrokeAnnotation stroke,
                                                     float cx,
                                                     float cy,
                                                     float radius) {
        int count = stroke.getPointCount();
        if (count == 0) return false;
        float rSq = radius * radius;
        if (count == 1) {
            float dx = stroke.getX(0) - cx;
            float dy = stroke.getY(0) - cy;
            return (dx * dx + dy * dy) <= rSq;
        }
        for (int i = 0; i < count - 1; i++) {
            float ax = stroke.getX(i);
            float ay = stroke.getY(i);
            float bx = stroke.getX(i + 1);
            float by = stroke.getY(i + 1);
            if (distanceSqPointToSegment(cx, cy, ax, ay, bx, by) <= rSq) {
                return true;
            }
        }
        return false;
    }

    private static float distanceSqPointToSegment(float px, float py,
                                                  float ax, float ay,
                                                  float bx, float by) {
        float abx = bx - ax;
        float aby = by - ay;
        float lenSq = abx * abx + aby * aby;
        if (lenSq <= 1e-6f) {
            float dx = px - ax;
            float dy = py - ay;
            return dx * dx + dy * dy;
        }
        float t = ((px - ax) * abx + (py - ay) * aby) / lenSq;
        t = Math.max(0f, Math.min(1f, t));
        float projX = ax + t * abx;
        float projY = ay + t * aby;
        float dx = px - projX;
        float dy = py - projY;
        return dx * dx + dy * dy;
    }

    private void notifyAnnotationsChanged(int pageIndex) {
        StoreChangeListener listener = this.changeListener;
        if (listener != null) {
            mainHandler.post(() -> listener.onAnnotationsChanged(pageIndex));
        }
    }

    private void notifyBookmarksChanged() {
        StoreChangeListener listener = this.changeListener;
        if (listener != null) {
            mainHandler.post(listener::onBookmarksChanged);
        }
    }

    private static void writeAtomicUtf8(@NonNull File targetFile, @NonNull String content) {
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        File tmpFile = new File(parent, targetFile.getName() + ".tmp");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
            fos.write(bytes);
            fos.flush();
            fos.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmpFile.delete();
            return;
        }

        if (!tmpFile.renameTo(targetFile)) {
            // Fallback for filesystems where renameTo fails over an existing target
            try (FileOutputStream direct = new FileOutputStream(targetFile)) {
                direct.write(bytes);
                direct.flush();
                direct.getFD().sync();
            } catch (IOException ignored) {
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmpFile.delete();
            }
        }
    }

    @Nullable
    private static String readUtf8File(@NonNull File file) {
        if (!file.isFile() || file.length() <= 0 || file.length() > 8L * 1024L * 1024L) {
            return null;
        }
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream baos = new ByteArrayOutputStream((int) file.length())) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = fis.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            return null;
        }
    }

    private static float clamp01(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return 0f;
        return Math.max(0f, Math.min(1f, v));
    }

    private static int normalizeRotation(int deg) {
        int r = deg % 360;
        return r < 0 ? r + 360 : r;
    }

    private static double round3(float value) {
        return Math.round(value * 1000.0d) / 1000.0d;
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        flushNowSync();
        ioExecutor.shutdownNow();
    }
}