package com.medhurb.app.viewer.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.widget.OverScroller;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.medhurb.app.viewer.engine.ImageDocumentEngine;
import com.medhurb.app.viewer.engine.PdfDocumentEngine;
import com.medhurb.app.viewer.engine.PdfDocumentEngine.DocumentLink;
import com.medhurb.app.viewer.engine.PdfDocumentEngine.PageMetrics;
import com.medhurb.app.viewer.engine.PdfDocumentEngine.TextSelectionResult;
import com.medhurb.app.viewer.render.BitmapMemoryPool.ManagedBitmap;
import com.medhurb.app.viewer.render.BitmapMemoryPool.RenderKind;
import com.medhurb.app.viewer.storage.MedvixDocumentStore;
import com.medhurb.app.viewer.storage.MedvixDocumentStore.FitMode;
import com.medhurb.app.viewer.storage.MedvixDocumentStore.InkStrokeAnnotation;
import com.medhurb.app.viewer.storage.MedvixDocumentStore.InkToolType;
import com.medhurb.app.viewer.storage.MedvixDocumentStore.ReadingTheme;
import com.medhurb.app.viewer.storage.MedvixDocumentStore.TextMarkupAnnotation;
import com.medhurb.app.viewer.storage.MedvixDocumentStore.TextMarkupStyle;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DocumentCanvasView
 * ============================================================================
 * Unified hardware-accelerated Canvas view for rendering a single PDF page or
 * high-resolution/animated Image inside MedVix.
 *
 * Features:
 *  - Dual-layer progressive rendering (instant base bitmap + debounced high-DPI
 *    sub-viewport tiles via PdfRenderer destClip or BitmapRegionDecoder).
 *  - Smooth pinch-to-zoom (1.0x - 8.0x), double-tap focal zoom, and OverScroller
 *    inertial fling panning with automatic edge-handoff to parent RecyclerView.
 *  - Zero-re-render ReadingThemes (DAY, SEPIA, hue-preserving NIGHT inversion,
 *    HIGH_CONTRAST) via hardware ColorMatrixColorFilter.
 *  - Vector overlay rendering for saved highlights, underlines, strikeouts,
 *    search matches, internal/external links, and freehand ink strokes.
 *  - Interactive finger/stylus Pen, Highlighter, Eraser, and native PDF text
 *    selection with draggable start/end handles.
 */
public class DocumentCanvasView extends View {

    // ── Constants ────────────────────────────────────────────────────────────

    public static final float MIN_ZOOM_FACTOR           = 1.0f;
    public static final float MAX_ZOOM_FACTOR           = 8.0f;
    public static final float DOUBLE_TAP_ZOOM_FACTOR    = 2.4f;

    private static final float TILE_TRIGGER_ZOOM        = 1.35f;
    private static final float TILE_OVERSCAN_RATIO      = 0.22f;
    private static final long TILE_DEBOUNCE_MS          = 90L;
    private static final int MAX_BASE_BITMAP_PX         = 2048;
    private static final float HANDLE_HIT_RADIUS_DP     = 22f;
    private static final float ERASER_RADIUS_DP         = 16f;

    // ── Interaction Modes & Listener Contracts ───────────────────────────────

    public enum InteractionMode {
        READ,
        INK_PEN,
        INK_HIGHLIGHTER,
        INK_ERASER
    }

    public interface ViewerInteractionListener {
        /** Single tap on non-link area (typically toggles Activity toolbars). */
        void onSingleTap(@NonNull DocumentCanvasView view, int pageIndex);

        /** Tap on an internal PDF goto link or external URL. */
        void onLinkActivated(@NonNull DocumentCanvasView view, int pageIndex, @NonNull DocumentLink link);

        /** User selected text via long-press or handle drag. */
        void onTextSelected(@NonNull DocumentCanvasView view,
                            int pageIndex,
                            @NonNull TextSelectionResult selection,
                            @NonNull RectF anchorScreenRect);

        /** Active text selection was dismissed. */
        void onTextSelectionCleared(@NonNull DocumentCanvasView view, int pageIndex);

        /** User tapped an existing highlight/note annotation. */
        void onMarkupAnnotationTapped(@NonNull DocumentCanvasView view,
                                      int pageIndex,
                                      @NonNull TextMarkupAnnotation annotation,
                                      @NonNull RectF anchorScreenRect);

        /** Zoom or pan changed (used to sync multi-page zoom or save reader state). */
        void onViewportTransformChanged(@NonNull DocumentCanvasView view,
                                        int pageIndex,
                                        float zoomFactor,
                                        float normalizedScrollX,
                                        float normalizedScrollY);

        /** User tapped the "Unlock Full Document" CTA on a locked preview page. */
        void onUpgradePreviewRequested(int pageIndex);
    }

    // ── Document Binding State ───────────────────────────────────────────────

    @Nullable private PdfDocumentEngine pdfEngine;
    @Nullable private ImageDocumentEngine imageEngine;
    @Nullable private MedvixDocumentStore documentStore;
    @Nullable private ViewerInteractionListener interactionListener;

    private int pageIndex = 0;
    private int rotationDegrees = 0;
    private boolean previewLocked = false;
    private boolean standaloneFitPageMode = false;

    // Base unrotated logical dimensions (PDF points or EXIF-normalized image pixels)
    private float rawLogicalWidth = 612f;
    private float rawLogicalHeight = 792f;

    // ── Rendered Bitmap & Animated Drawable State ────────────────────────────

    @Nullable private ManagedBitmap baseBitmapLease;
    @Nullable private ManagedBitmap tileBitmapLease;
    @Nullable private RectF tileCoveredRotatedLogicalRect;
    private float tileRenderedScale = 0f;

    @Nullable private Drawable animatedDrawable;
    private boolean isAnimatedGifOrWebp = false;

    @Nullable private PdfDocumentEngine.TaskHandle activePdfBaseTask;
    @Nullable private PdfDocumentEngine.TaskHandle activePdfTileTask;
    @Nullable private PdfDocumentEngine.TaskHandle activePdfLinksTask;
    @Nullable private ImageDocumentEngine.TaskHandle activeImgBaseTask;
    @Nullable private ImageDocumentEngine.TaskHandle activeImgTileTask;
    @Nullable private ImageDocumentEngine.TaskHandle activeAnimTask;

    // ── Zoom, Pan & Fling Physics ────────────────────────────────────────────

    @NonNull private FitMode fitMode = FitMode.FIT_WIDTH;
    private float fitScale = 1.0f;       // Screen px per logical unit at zoomFactor = 1.0
    private float zoomFactor = 1.0f;     // User zoom multiplier [1.0 .. 8.0]
    private float panOffsetX = 0f;       // Screen px offset from left edge (<= 0 when zoomed)
    private float panOffsetY = 0f;       // Screen px offset from top edge (<= 0 when zoomed)

    private float pendingRestoreNormX = -1f;
    private float pendingRestoreNormY = -1f;

    @NonNull private final ScaleGestureDetector scaleDetector;
    @NonNull private final GestureDetector gestureDetector;
    @NonNull private final OverScroller scroller;
    @Nullable private ValueAnimator zoomAnimator;
    private boolean isScalingOrFlinging = false;

    // ── Theme, Paints & Overlays ─────────────────────────────────────────────

    @NonNull private ReadingTheme readingTheme = ReadingTheme.DAY;
    @NonNull private InteractionMode interactionMode = InteractionMode.READ;

    private int activeInkColor = Color.rgb(220, 38, 38);
    private float activeInkWidthPt = 2.4f;

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint pagePaperPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pageShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markupPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint searchMatchPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint searchActiveMatchPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectionFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectionHandlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linkBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint eraserRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lockOverlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lockTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private static final ColorMatrixColorFilter SEPIA_FILTER = createSepiaFilter();
    private static final ColorMatrixColorFilter NIGHT_INVERT_FILTER = createSmartNightFilter();
    private static final ColorMatrixColorFilter HIGH_CONTRAST_FILTER = createHighContrastFilter();

    // ── Search, Links, Selection & Active Ink Stroke State ───────────────────

    @NonNull private List<PdfDocumentEngine.SearchMatch> pageSearchMatches = Collections.emptyList();
    private int activeSearchMatchIndexOnPage = -1;
    @NonNull private List<DocumentLink> pageLinks = Collections.emptyList();

    @Nullable private TextSelectionResult activeSelection;
    @Nullable private PointF selectionAnchorPdfPt;
    @Nullable private PointF selectionFocusPdfPt;
    private int draggingHandleMode = HANDLE_NONE;
    private static final int HANDLE_NONE  = 0;
    private static final int HANDLE_START = 1;
    private static final int HANDLE_END   = 2;

    // Live in-progress freehand stroke (stored in unrotated PDF/image coordinates)
    private final List<Float> currentStrokePointsPt = new ArrayList<>(128);
    private final Path reusableInkPath = new Path();
    private boolean isDrawingStroke = false;
    private float lastEraserScreenX = -1f;
    private float lastEraserScreenY = -1f;

    private final ExecutorService selectionExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MedvixCanvas-Select");
        t.setPriority(Thread.NORM_PRIORITY);
        return t;
    });

    private final Runnable debouncedTileRunnable = this::requestCrispViewportTile;

    // Reusable scratch rects to minimize GC during 60/120fps onDraw()
    private final Rect srcRectScratch = new Rect();
    private final RectF dstRectScratch = new RectF();
    private final RectF pageBoundsScratch = new RectF();

    // ── Constructors ─────────────────────────────────────────────────────────

    public DocumentCanvasView(@NonNull Context context) {
        this(context, null);
    }

    public DocumentCanvasView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DocumentCanvasView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setWillNotDraw(false);
        setFocusable(true);
        setFocusableInTouchMode(true);

        this.scroller = new OverScroller(context, new DecelerateInterpolator());
        this.scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            this.scaleDetector.setQuickScaleEnabled(false);
        }
        this.gestureDetector = new GestureDetector(context, new GestureListener());
        this.gestureDetector.setIsLongpressEnabled(true);

        initPaints();
    }

    private void initPaints() {
        pagePaperPaint.setColor(Color.WHITE);
        pagePaperPaint.setStyle(Paint.Style.FILL);

        pageShadowPaint.setColor(Color.argb(28, 0, 0, 0));
        pageShadowPaint.setStyle(Paint.Style.STROKE);
        pageShadowPaint.setStrokeWidth(dpToPx(1f));

        markupPaint.setStyle(Paint.Style.FILL);

        searchMatchPaint.setColor(Color.argb(95, 255, 213, 79));
        searchMatchPaint.setStyle(Paint.Style.FILL);

        searchActiveMatchPaint.setColor(Color.argb(150, 255, 111, 0));
        searchActiveMatchPaint.setStyle(Paint.Style.FILL);

        selectionFillPaint.setColor(Color.argb(85, 33, 150, 243));
        selectionFillPaint.setStyle(Paint.Style.FILL);

        selectionHandlePaint.setColor(Color.rgb(25, 118, 210));
        selectionHandlePaint.setStyle(Paint.Style.FILL);

        linkBorderPaint.setColor(Color.argb(70, 25, 118, 210));
        linkBorderPaint.setStyle(Paint.Style.STROKE);
        linkBorderPaint.setStrokeWidth(dpToPx(1f));
        linkBorderPaint.setPathEffect(new DashPathEffect(new float[]{ dpToPx(3f), dpToPx(2f) }, 0f));

        inkPaint.setStyle(Paint.Style.STROKE);
        inkPaint.setStrokeCap(Paint.Cap.ROUND);
        inkPaint.setStrokeJoin(Paint.Join.ROUND);

        eraserRingPaint.setColor(Color.argb(180, 220, 38, 38));
        eraserRingPaint.setStyle(Paint.Style.STROKE);
        eraserRingPaint.setStrokeWidth(dpToPx(1.5f));

        lockOverlayPaint.setColor(Color.argb(232, 18, 22, 31));
        lockOverlayPaint.setStyle(Paint.Style.FILL);

        lockTextPaint.setColor(Color.WHITE);
        lockTextPaint.setTextAlign(Paint.Align.CENTER);
        lockTextPaint.setTextSize(dpToPx(15f));
        lockTextPaint.setFakeBoldText(true);
    }

    // ── Public Binding API (PDF Page & Image) ────────────────────────────────

    public void setViewerInteractionListener(@Nullable ViewerInteractionListener listener) {
        this.interactionListener = listener;
    }

    /**
     * Binds a single PDF page from PdfDocumentEngine to this canvas view.
     */
    public void bindPdfPage(@NonNull PdfDocumentEngine engine,
                            @Nullable MedvixDocumentStore store,
                            int pageIndex,
                            int rotationDegrees,
                            @NonNull ReadingTheme theme,
                            @NonNull FitMode fitMode,
                            boolean standaloneSinglePage) {
        unbind();

        this.pdfEngine = engine;
        this.documentStore = store;
        this.pageIndex = pageIndex;
        this.rotationDegrees = PdfDocumentEngine.normalizeRotation(rotationDegrees);
        this.standaloneFitPageMode = standaloneSinglePage;
        this.previewLocked = engine.isPageLockedByPreview(pageIndex);

        PageMetrics est = engine.getEstimatedPageMetrics(pageIndex);
        this.rawLogicalWidth = est.rawWidthPt;
        this.rawLogicalHeight = est.rawHeightPt;

        applyReadingThemeInternal(theme);
        this.fitMode = fitMode;
        this.zoomFactor = 1.0f;
        this.panOffsetX = 0f;
        this.panOffsetY = 0f;

        recomputeFitScale();
        requestLayout();

        if (!previewLocked) {
            requestPdfBaseRender();
            requestPdfLinks();
        }
        invalidate();
    }

    /**
     * Binds a raster, animated (GIF/WebP), or vector (SVG) image from ImageDocumentEngine.
     */
    public void bindImageDocument(@NonNull ImageDocumentEngine engine,
                                  @Nullable MedvixDocumentStore store,
                                  int rotationDegrees,
                                  @NonNull ReadingTheme theme,
                                  @NonNull FitMode fitMode) {
        unbind();

        this.imageEngine = engine;
        this.documentStore = store;
        this.pageIndex = 0;
        this.rotationDegrees = ImageDocumentEngine.normalizeRotation(rotationDegrees);
        this.standaloneFitPageMode = true;
        this.previewLocked = false;

        ImageDocumentEngine.ImageMetrics m = engine.getMetrics();
        this.rawLogicalWidth = m.getNormalizedWidthPx();
        this.rawLogicalHeight = m.getNormalizedHeightPx();

        applyReadingThemeInternal(theme);
        this.fitMode = fitMode;
        this.zoomFactor = 1.0f;
        this.panOffsetX = 0f;
        this.panOffsetY = 0f;

        recomputeFitScale();
        requestLayout();

        String mime = m.mimeType.toLowerCase();
        boolean potentiallyAnimated = !m.isVectorSvg
                && (mime.contains("gif") || mime.contains("webp") || mime.contains("heif"))
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P;

        if (potentiallyAnimated) {
            activeAnimTask = engine.loadAnimatedDrawableAsync(new ImageDocumentEngine.AnimatedDrawableCallback() {
                @Override
                public void onDrawableReady(@NonNull Drawable drawable, boolean isAnimated) {
                    if (isAnimated && drawable instanceof AnimatedImageDrawable) {
                        animatedDrawable = drawable;
                        isAnimatedGifOrWebp = true;
                        drawable.setCallback(DocumentCanvasView.this);
                        ((AnimatedImageDrawable) drawable).start();
                        invalidate();
                    } else {
                        requestImageBaseRender();
                    }
                }

                @Override
                public void onDrawableError(@NonNull Throwable error) {
                    requestImageBaseRender();
                }
            });
        } else {
            requestImageBaseRender();
        }
        invalidate();
    }

    /**
     * Releases all bitmap leases, animated drawables, and in-flight background
     * tasks so this View can be safely recycled or destroyed with zero leaks.
     */
    public void unbind() {
        removeCallbacks(debouncedTileRunnable);
        if (zoomAnimator != null) {
            zoomAnimator.cancel();
            zoomAnimator = null;
        }
        scroller.abortAnimation();

        cancelTask(activePdfBaseTask);
        activePdfBaseTask = null;
        cancelTask(activePdfTileTask);
        activePdfTileTask = null;
        cancelTask(activePdfLinksTask);
        activePdfLinksTask = null;

        cancelImgTask(activeImgBaseTask);
        activeImgBaseTask = null;
        cancelImgTask(activeImgTileTask);
        activeImgTileTask = null;
        cancelImgTask(activeAnimTask);
        activeAnimTask = null;

        if (animatedDrawable != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    && animatedDrawable instanceof AnimatedImageDrawable) {
                ((AnimatedImageDrawable) animatedDrawable).stop();
            }
            animatedDrawable.setCallback(null);
            animatedDrawable = null;
            isAnimatedGifOrWebp = false;
        }

        releaseBaseBitmap();
        releaseTileBitmap();

        pdfEngine = null;
        imageEngine = null;
        documentStore = null;
        pageLinks = Collections.emptyList();
        pageSearchMatches = Collections.emptyList();
        activeSearchMatchIndexOnPage = -1;
        clearTextSelection(false);
        currentStrokePointsPt.clear();
        isDrawingStroke = false;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        unbind();
    }

    // ── State Setters (Theme, Rotation, Zoom, Mode, Search) ──────────────────

    public int getPageIndex() {
        return pageIndex;
    }

    public float getZoomFactor() {
        return zoomFactor;
    }

    public void setReadingTheme(@NonNull ReadingTheme theme) {
        if (this.readingTheme == theme) return;
        applyReadingThemeInternal(theme);
        invalidate();
    }

    private void applyReadingThemeInternal(@NonNull ReadingTheme theme) {
        this.readingTheme = theme;
        switch (theme) {
            case SEPIA:
                bitmapPaint.setColorFilter(SEPIA_FILTER);
                pagePaperPaint.setColor(Color.rgb(244, 236, 216));
                break;
            case NIGHT:
                bitmapPaint.setColorFilter(NIGHT_INVERT_FILTER);
                pagePaperPaint.setColor(Color.rgb(22, 24, 29));
                break;
            case HIGH_CONTRAST:
                bitmapPaint.setColorFilter(HIGH_CONTRAST_FILTER);
                pagePaperPaint.setColor(Color.WHITE);
                break;
            case DAY:
            default:
                bitmapPaint.setColorFilter(null);
                pagePaperPaint.setColor(Color.WHITE);
                break;
        }
    }

    public void setRotationDegrees(int rotationDegrees) {
        int norm = PdfDocumentEngine.normalizeRotation(rotationDegrees);
        if (this.rotationDegrees == norm) return;
        this.rotationDegrees = norm;
        releaseBaseBitmap();
        releaseTileBitmap();
        this.zoomFactor = 1.0f;
        this.panOffsetX = 0f;
        this.panOffsetY = 0f;
        recomputeFitScale();
        requestLayout();
        if (!previewLocked) {
            if (pdfEngine != null) requestPdfBaseRender();
            if (imageEngine != null) requestImageBaseRender();
        }
        invalidate();
    }

    public void setFitMode(@NonNull FitMode mode) {
        if (this.fitMode == mode) return;
        this.fitMode = mode;
        this.zoomFactor = 1.0f;
        this.panOffsetX = 0f;
        this.panOffsetY = 0f;
        releaseTileBitmap();
        recomputeFitScale();
        requestLayout();
        if (!previewLocked) {
            if (pdfEngine != null) requestPdfBaseRender();
            if (imageEngine != null) requestImageBaseRender();
        }
        invalidate();
    }

    public void setInteractionMode(@NonNull InteractionMode mode) {
        if (this.interactionMode == mode) return;
        this.interactionMode = mode;
        if (mode != InteractionMode.READ) {
            clearTextSelection(true);
        }
        invalidate();
    }

    public void setInkBrush(int argbColor, float widthPt) {
        this.activeInkColor = argbColor;
        this.activeInkWidthPt = Math.max(0.5f, widthPt);
    }

    public void setSearchMatches(@NonNull List<PdfDocumentEngine.SearchMatch> matches,
                                 int activeMatchIndexOnPage) {
        this.pageSearchMatches = matches;
        this.activeSearchMatchIndexOnPage = activeMatchIndexOnPage;
        invalidate();
    }

    public void restoreViewportNormalized(float zoomFactor, float normScrollX, float normScrollY) {
        float clampedZoom = clamp(zoomFactor, MIN_ZOOM_FACTOR, MAX_ZOOM_FACTOR);
        boolean zoomChanged = Math.abs(this.zoomFactor - clampedZoom) > 0.0005f;
        this.zoomFactor = clampedZoom;
        this.pendingRestoreNormX = normScrollX;
        this.pendingRestoreNormY = normScrollY;
        if (zoomChanged && !standaloneFitPageMode) {
            requestLayout();
        }
        applyPendingNormalizedScrollIfReady();
        scheduleCrispTileUpdate();
        invalidate();
    }

    public void clearTextSelection(boolean notifyListener) {
        boolean hadSelection = activeSelection != null;
        activeSelection = null;
        selectionAnchorPdfPt = null;
        selectionFocusPdfPt = null;
        draggingHandleMode = HANDLE_NONE;
        if (hadSelection) {
            invalidate();
            if (notifyListener && interactionListener != null) {
                interactionListener.onTextSelectionCleared(this, pageIndex);
            }
        }
    }

    /**
     * Smoothly animates zoom to targetZoom centered on screen point (focusX, focusY).
     */
    public void animateZoomTo(float targetZoom, float focusX, float focusY) {
        float clampedTarget = clamp(targetZoom, MIN_ZOOM_FACTOR, MAX_ZOOM_FACTOR);
        if (zoomAnimator != null) {
            zoomAnimator.cancel();
        }
        final float startZoom = this.zoomFactor;
        zoomAnimator = ValueAnimator.ofFloat(startZoom, clampedTarget);
        zoomAnimator.setDuration(220L);
        zoomAnimator.setInterpolator(new DecelerateInterpolator());
        zoomAnimator.addUpdateListener(anim -> {
            float z = (Float) anim.getAnimatedValue();
            applyZoomAroundFocalPoint(z, focusX, focusY);
            if (!standaloneFitPageMode) {
                requestLayout();
            }
            notifyViewportChanged();
            invalidate();
        });
        zoomAnimator.start();
    }

    // ── Measurement & Coordinate Geometry ────────────────────────────────────

    private float getRotatedLogicalWidth() {
        boolean swapped = (rotationDegrees == 90 || rotationDegrees == 270);
        return swapped ? rawLogicalHeight : rawLogicalWidth;
    }

    private float getRotatedLogicalHeight() {
        boolean swapped = (rotationDegrees == 90 || rotationDegrees == 270);
        return swapped ? rawLogicalWidth : rawLogicalHeight;
    }

    private float getEffectiveScale() {
        return Math.max(0.01f, fitScale * zoomFactor);
    }

    private void recomputeFitScale() {
        int viewW = getMeasuredWidth() > 0 ? getMeasuredWidth() : getResources().getDisplayMetrics().widthPixels;
        int viewH = getMeasuredHeight() > 0 ? getMeasuredHeight() : getResources().getDisplayMetrics().heightPixels;
        float rotW = Math.max(1f, getRotatedLogicalWidth());
        float rotH = Math.max(1f, getRotatedLogicalHeight());

        if (standaloneFitPageMode && fitMode == FitMode.FIT_PAGE && viewH > 0) {
            fitScale = Math.min((float) viewW / rotW, (float) viewH / rotH);
        } else {
            fitScale = (float) viewW / rotW;
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        float rotW = Math.max(1f, getRotatedLogicalWidth());
        float rotH = Math.max(1f, getRotatedLogicalHeight());

        int measuredW = Math.max(1, widthSize);
        int measuredH;

        if (heightMode == MeasureSpec.EXACTLY && heightSize > 0) {
            // Standalone full-screen single-page or image viewer
            measuredH = heightSize;
            if (fitMode == FitMode.FIT_PAGE) {
                fitScale = Math.min((float) measuredW / rotW, (float) measuredH / rotH);
            } else {
                fitScale = (float) measuredW / rotW;
            }
        } else {
            // Vertical RecyclerView continuous page item: scale height uniformly with zoomFactor
            // so pages never overlap or leave gaps and vertical scrolling stays in the RecyclerView.
            fitScale = (float) measuredW / rotW;
            measuredH = Math.max(1, Math.round(rotH * fitScale * zoomFactor));
            if (heightMode == MeasureSpec.AT_MOST && heightSize > 0) {
                measuredH = Math.min(measuredH, heightSize);
            }
        }

        setMeasuredDimension(measuredW, measuredH);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        recomputeFitScale();
        clampPanOffsets();
        applyPendingNormalizedScrollIfReady();
        if (!previewLocked && (w != oldw || h != oldh)) {
            if (pdfEngine != null && baseBitmapLease == null) requestPdfBaseRender();
            if (imageEngine != null && baseBitmapLease == null) requestImageBaseRender();
            scheduleCrispTileUpdate();
        }
    }

    private void computeContentScreenBounds(@NonNull RectF outBounds) {
        float scale = getEffectiveScale();
        float contentW = getRotatedLogicalWidth() * scale;
        float contentH = getRotatedLogicalHeight() * scale;
        int viewW = getWidth();
        int viewH = getHeight();

        float left = (contentW <= viewW) ? (viewW - contentW) * 0.5f : panOffsetX;
        float top = (contentH <= viewH) ? (viewH - contentH) * 0.5f : panOffsetY;
        outBounds.set(left, top, left + contentW, top + contentH);
    }

    private void clampPanOffsets() {
        float scale = getEffectiveScale();
        float contentW = getRotatedLogicalWidth() * scale;
        float contentH = getRotatedLogicalHeight() * scale;
        int viewW = getWidth();
        int viewH = getHeight();

        if (contentW <= viewW) {
            panOffsetX = 0f;
        } else {
            panOffsetX = clamp(panOffsetX, viewW - contentW, 0f);
        }

        if (contentH <= viewH) {
            panOffsetY = 0f;
        } else {
            panOffsetY = clamp(panOffsetY, viewH - contentH, 0f);
        }
    }

    private void applyPendingNormalizedScrollIfReady() {
        if (pendingRestoreNormX < 0f && pendingRestoreNormY < 0f) return;
        if (getWidth() <= 0 || getHeight() <= 0) return;

        float scale = getEffectiveScale();
        float contentW = getRotatedLogicalWidth() * scale;
        float contentH = getRotatedLogicalHeight() * scale;
        float maxScrollX = Math.max(0f, contentW - getWidth());
        float maxScrollY = Math.max(0f, contentH - getHeight());

        if (pendingRestoreNormX >= 0f) {
            panOffsetX = -clamp(pendingRestoreNormX, 0f, 1f) * maxScrollX;
            pendingRestoreNormX = -1f;
        }
        if (pendingRestoreNormY >= 0f) {
            panOffsetY = -clamp(pendingRestoreNormY, 0f, 1f) * maxScrollY;
            pendingRestoreNormY = -1f;
        }
        clampPanOffsets();
    }

    private void notifyViewportChanged() {
        if (interactionListener == null) return;
        float scale = getEffectiveScale();
        float maxScrollX = Math.max(1f, getRotatedLogicalWidth() * scale - getWidth());
        float maxScrollY = Math.max(1f, getRotatedLogicalHeight() * scale - getHeight());
        float normX = clamp(-panOffsetX / maxScrollX, 0f, 1f);
        float normY = clamp(-panOffsetY / maxScrollY, 0f, 1f);
        interactionListener.onViewportTransformChanged(this, pageIndex, zoomFactor, normX, normY);
    }

    @NonNull
    private PointF screenToUnrotatedPdfPoint(float screenX, float screenY) {
        computeContentScreenBounds(pageBoundsScratch);
        float relViewX = screenX - pageBoundsScratch.left;
        float relViewY = screenY - pageBoundsScratch.top;
        return PdfDocumentEngine.rotatedViewPointToPdfPoint(
                relViewX,
                relViewY,
                rawLogicalWidth,
                rawLogicalHeight,
                rotationDegrees,
                getEffectiveScale()
        );
    }

    @NonNull
    private RectF unrotatedPdfRectToScreenRect(@NonNull RectF pdfRect) {
        computeContentScreenBounds(pageBoundsScratch);
        RectF rotViewRect = PdfDocumentEngine.pdfPointsToRotatedViewRect(
                pdfRect,
                rawLogicalWidth,
                rawLogicalHeight,
                rotationDegrees,
                getEffectiveScale()
        );
        rotViewRect.offset(pageBoundsScratch.left, pageBoundsScratch.top);
        return rotViewRect;
    }

    // ── Asynchronous Base & Crisp Tile Rendering ─────────────────────────────

    private void requestPdfBaseRender() {
        PdfDocumentEngine engine = this.pdfEngine;
        if (engine == null || previewLocked) return;

        cancelTask(activePdfBaseTask);

        float rotW = Math.max(1f, getRotatedLogicalWidth());
        float rotH = Math.max(1f, getRotatedLogicalHeight());
        float targetBaseScale = Math.max(fitScale, 1.25f);
        float maxSide = Math.max(rotW, rotH);
        if (maxSide * targetBaseScale > MAX_BASE_BITMAP_PX) {
            targetBaseScale = MAX_BASE_BITMAP_PX / maxSide;
        }

        PdfDocumentEngine.RenderRequest req = new PdfDocumentEngine.RenderRequest.Builder(pageIndex)
                .setScale(targetBaseScale)
                .setRotation(rotationDegrees)
                .setKind(RenderKind.FULL_PAGE)
                .setHighPriority(true)
                .build();

        ManagedBitmap immediate = engine.acquireCachedBitmap(req);
        if (immediate != null) {
            replaceBaseBitmap(immediate);
            invalidate();
            return;
        }

        activePdfBaseTask = engine.renderAsync(req, new PdfDocumentEngine.RenderCallback() {
            @Override
            public void onRenderSuccess(@NonNull PdfDocumentEngine.RenderRequest request,
                                        @NonNull ManagedBitmap bitmapLease) {
                if (pdfEngine != engine || pageIndex != request.pageIndex) {
                    bitmapLease.release();
                    return;
                }
                // Update exact page dimensions if measured for the first time
                try {
                    PageMetrics exact = engine.getPageMetrics(pageIndex);
                    if (Math.abs(exact.rawWidthPt - rawLogicalWidth) > 0.5f
                            || Math.abs(exact.rawHeightPt - rawLogicalHeight) > 0.5f) {
                        rawLogicalWidth = exact.rawWidthPt;
                        rawLogicalHeight = exact.rawHeightPt;
                        recomputeFitScale();
                        requestLayout();
                    }
                } catch (IOException ignored) {
                }

                replaceBaseBitmap(bitmapLease);
                invalidate();
                if (zoomFactor >= TILE_TRIGGER_ZOOM) {
                    scheduleCrispTileUpdate();
                }
            }

            @Override
            public void onRenderError(@NonNull PdfDocumentEngine.RenderRequest request,
                                      @NonNull Throwable error) {
                // Base paper background remains visible
            }
        });
    }

    private void requestPdfLinks() {
        PdfDocumentEngine engine = this.pdfEngine;
        if (engine == null || previewLocked) return;

        cancelTask(activePdfLinksTask);
        activePdfLinksTask = engine.extractPageLinksAsync(pageIndex, new PdfDocumentEngine.LinksCallback() {
            @Override
            public void onLinksExtracted(int pIdx, @NonNull List<DocumentLink> links) {
                if (pdfEngine == engine && pageIndex == pIdx) {
                    pageLinks = links;
                    if (!links.isEmpty()) {
                        invalidate();
                    }
                }
            }

            @Override
            public void onLinksError(int pIdx, @NonNull Throwable error) {
            }
        });
    }

    private void requestImageBaseRender() {
        ImageDocumentEngine engine = this.imageEngine;
        if (engine == null) return;

        cancelImgTask(activeImgBaseTask);

        float rotW = Math.max(1f, getRotatedLogicalWidth());
        float rotH = Math.max(1f, getRotatedLogicalHeight());
        float targetScale = Math.min(1.0f, Math.max(fitScale, 1.0f));
        float maxSide = Math.max(rotW, rotH);
        if (maxSide * targetScale > MAX_BASE_BITMAP_PX) {
            targetScale = MAX_BASE_BITMAP_PX / maxSide;
        }

        ImageDocumentEngine.RenderRequest req = new ImageDocumentEngine.RenderRequest.Builder()
                .setScale(targetScale)
                .setRotation(rotationDegrees)
                .setKind(RenderKind.RASTER_IMAGE)
                .setHighPriority(true)
                .build();

        ManagedBitmap immediate = engine.acquireCachedBitmap(req);
        if (immediate != null) {
            replaceBaseBitmap(immediate);
            invalidate();
            return;
        }

        activeImgBaseTask = engine.renderAsync(req, new ImageDocumentEngine.RenderCallback() {
            @Override
            public void onRenderSuccess(@NonNull ImageDocumentEngine.RenderRequest request,
                                        @NonNull ManagedBitmap bitmapLease) {
                if (imageEngine != engine) {
                    bitmapLease.release();
                    return;
                }
                replaceBaseBitmap(bitmapLease);
                invalidate();
                if (zoomFactor >= TILE_TRIGGER_ZOOM) {
                    scheduleCrispTileUpdate();
                }
            }

            @Override
            public void onRenderError(@NonNull ImageDocumentEngine.RenderRequest request,
                                      @NonNull Throwable error) {
            }
        });
    }

    private void scheduleCrispTileUpdate() {
        removeCallbacks(debouncedTileRunnable);
        if (zoomFactor < TILE_TRIGGER_ZOOM) {
            releaseTileBitmap();
            return;
        }
        postDelayed(debouncedTileRunnable, TILE_DEBOUNCE_MS);
    }

    private void requestCrispViewportTile() {
        if (zoomFactor < TILE_TRIGGER_ZOOM || getWidth() <= 0 || getHeight() <= 0 || previewLocked) {
            return;
        }

        float scale = getEffectiveScale();
        computeContentScreenBounds(pageBoundsScratch);

        // Compute visible rectangle in rotated logical page coordinates
        float visLeft = Math.max(0f, -pageBoundsScratch.left / scale);
        float visTop = Math.max(0f, -pageBoundsScratch.top / scale);
        float visRight = Math.min(getRotatedLogicalWidth(), (getWidth() - pageBoundsScratch.left) / scale);
        float visBottom = Math.min(getRotatedLogicalHeight(), (getHeight() - pageBoundsScratch.top) / scale);

        if (visRight <= visLeft + 1f || visBottom <= visTop + 1f) return;

        // Add 22% overscan padding so micro-pans remain crisp without immediate re-render
        float padX = (visRight - visLeft) * TILE_OVERSCAN_RATIO;
        float padY = (visBottom - visTop) * TILE_OVERSCAN_RATIO;
        RectF overscanRect = new RectF(
                Math.max(0f, visLeft - padX),
                Math.max(0f, visTop - padY),
                Math.min(getRotatedLogicalWidth(), visRight + padX),
                Math.min(getRotatedLogicalHeight(), visBottom + padY)
        );

        // Skip re-rendering if existing tile already covers visible region at comparable scale
        if (tileBitmapLease != null
                && tileCoveredRotatedLogicalRect != null
                && tileCoveredRotatedLogicalRect.contains(visLeft, visTop, visRight, visBottom)
                && Math.abs(tileRenderedScale - scale) / scale < 0.15f) {
            return;
        }

        if (pdfEngine != null) {
            final PdfDocumentEngine engine = this.pdfEngine;
            cancelTask(activePdfTileTask);

            PdfDocumentEngine.RenderRequest req = new PdfDocumentEngine.RenderRequest.Builder(pageIndex)
                    .setScale(scale)
                    .setRotation(rotationDegrees)
                    .setRotatedPageRegionPt(overscanRect)
                    .setHighPriority(true)
                    .build();

            activePdfTileTask = engine.renderAsync(req, new PdfDocumentEngine.RenderCallback() {
                @Override
                public void onRenderSuccess(@NonNull PdfDocumentEngine.RenderRequest request,
                                            @NonNull ManagedBitmap bitmapLease) {
                    if (pdfEngine != engine || pageIndex != request.pageIndex || zoomFactor < TILE_TRIGGER_ZOOM) {
                        bitmapLease.release();
                        return;
                    }
                    replaceTileBitmap(bitmapLease, overscanRect, request.scale);
                    invalidate();
                }

                @Override
                public void onRenderError(@NonNull PdfDocumentEngine.RenderRequest request,
                                          @NonNull Throwable error) {
                }
            });
        } else if (imageEngine != null) {
            final ImageDocumentEngine engine = this.imageEngine;
            cancelImgTask(activeImgTileTask);

            ImageDocumentEngine.RenderRequest.Builder builder = new ImageDocumentEngine.RenderRequest.Builder()
                    .setScale(scale)
                    .setRotation(rotationDegrees)
                    .setHighPriority(true);

            // Vector SVGs re-render cleanly in full; large rasters use BitmapRegionDecoder
            if (!engine.isVectorSvg() && engine.supportsRegionTiling()) {
                builder.setOrientedRegionPx(overscanRect);
            } else {
                overscanRect.set(0f, 0f, getRotatedLogicalWidth(), getRotatedLogicalHeight());
            }

            ImageDocumentEngine.RenderRequest req = builder.build();
            activeImgTileTask = engine.renderAsync(req, new ImageDocumentEngine.RenderCallback() {
                @Override
                public void onRenderSuccess(@NonNull ImageDocumentEngine.RenderRequest request,
                                            @NonNull ManagedBitmap bitmapLease) {
                    if (imageEngine != engine || zoomFactor < TILE_TRIGGER_ZOOM) {
                        bitmapLease.release();
                        return;
                    }
                    replaceTileBitmap(bitmapLease, overscanRect, request.scale);
                    invalidate();
                }

                @Override
                public void onRenderError(@NonNull ImageDocumentEngine.RenderRequest request,
                                      @NonNull Throwable error) {
                }
            });
        }
    }

    private void replaceBaseBitmap(@NonNull ManagedBitmap next) {
        ManagedBitmap prev = this.baseBitmapLease;
        this.baseBitmapLease = next;
        if (prev != null && prev != next) {
            prev.release();
        }
    }

    private void releaseBaseBitmap() {
        ManagedBitmap prev = this.baseBitmapLease;
        this.baseBitmapLease = null;
        if (prev != null) {
            prev.release();
        }
    }

    private void replaceTileBitmap(@NonNull ManagedBitmap next,
                                   @NonNull RectF coveredRotatedRect,
                                   float renderedScale) {
        ManagedBitmap prev = this.tileBitmapLease;
        this.tileBitmapLease = next;
        this.tileCoveredRotatedLogicalRect = new RectF(coveredRotatedRect);
        this.tileRenderedScale = renderedScale;
        if (prev != null && prev != next) {
            prev.release();
        }
    }

    private void releaseTileBitmap() {
        ManagedBitmap prev = this.tileBitmapLease;
        this.tileBitmapLease = null;
        this.tileCoveredRotatedLogicalRect = null;
        this.tileRenderedScale = 0f;
        if (prev != null) {
            prev.release();
        }
    }

    // ── Canvas Drawing Pipeline (60 / 120 FPS) ───────────────────────────────

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        computeContentScreenBounds(pageBoundsScratch);

        // 1. Page background paper & subtle hairline border
        canvas.drawRect(pageBoundsScratch, pagePaperPaint);

        if (previewLocked) {
            drawPreviewLockOverlay(canvas, pageBoundsScratch);
            return;
        }

        // 2. Animated GIF/WebP Drawable OR Dual-Layer Bitmap (Base + High-DPI Tile)
        if (isAnimatedGifOrWebp && animatedDrawable != null) {
            animatedDrawable.setBounds(
                    Math.round(pageBoundsScratch.left),
                    Math.round(pageBoundsScratch.top),
                    Math.round(pageBoundsScratch.right),
                    Math.round(pageBoundsScratch.bottom)
            );
            animatedDrawable.setColorFilter(bitmapPaint.getColorFilter());
            animatedDrawable.draw(canvas);
        } else {
            ManagedBitmap base = this.baseBitmapLease;
            if (base != null && base.isValid()) {
                srcRectScratch.set(0, 0, base.getWidth(), base.getHeight());
                canvas.drawBitmap(base.getBitmap(), srcRectScratch, pageBoundsScratch, bitmapPaint);
            }

            ManagedBitmap tile = this.tileBitmapLease;
            RectF covered = this.tileCoveredRotatedLogicalRect;
            if (tile != null && tile.isValid() && covered != null) {
                float scale = getEffectiveScale();
                dstRectScratch.set(
                        pageBoundsScratch.left + covered.left * scale,
                        pageBoundsScratch.top + covered.top * scale,
                        pageBoundsScratch.left + covered.right * scale,
                        pageBoundsScratch.top + covered.bottom * scale
                );
                srcRectScratch.set(0, 0, tile.getWidth(), tile.getHeight());
                canvas.drawBitmap(tile.getBitmap(), srcRectScratch, dstRectScratch, bitmapPaint);
            }
        }

        // 3. Saved Text Markups (Highlights, Underlines, Strikeouts)
        drawSavedTextMarkups(canvas);

        // 4. Search Match Highlights
        drawSearchMatches(canvas);

        // 5. Subtle Link Indicators
        drawPageLinks(canvas);

        // 6. Saved + In-Progress Freehand Ink Strokes
        drawSavedInkStrokes(canvas);
        drawActiveInkStroke(canvas);

        // 7. Active Text Selection & Drag Handles
        drawTextSelectionOverlay(canvas);

        // 8. Eraser Cursor Ring
        if (interactionMode == InteractionMode.INK_ERASER && lastEraserScreenX >= 0f) {
            canvas.drawCircle(lastEraserScreenX, lastEraserScreenY, dpToPx(ERASER_RADIUS_DP), eraserRingPaint);
        }

        canvas.drawRect(pageBoundsScratch, pageShadowPaint);
    }

    private void drawSavedTextMarkups(@NonNull Canvas canvas) {
        MedvixDocumentStore store = this.documentStore;
        if (store == null) return;
        List<TextMarkupAnnotation> markups = store.getTextMarkupsForPage(pageIndex);
        if (markups.isEmpty()) return;

        for (TextMarkupAnnotation m : markups) {
            markupPaint.setColor(m.argbColor);
            for (RectF ptRect : m.rectsPt) {
                RectF sr = unrotatedPdfRectToScreenRect(ptRect);
                if (m.style == TextMarkupStyle.UNDERLINE) {
                    float lineH = Math.max(dpToPx(1.5f), sr.height() * 0.12f);
                    canvas.drawRect(sr.left, sr.bottom - lineH, sr.right, sr.bottom, markupPaint);
                } else if (m.style == TextMarkupStyle.STRIKEOUT) {
                    float lineH = Math.max(dpToPx(1.5f), sr.height() * 0.10f);
                    float midY = sr.centerY();
                    canvas.drawRect(sr.left, midY - lineH * 0.5f, sr.right, midY + lineH * 0.5f, markupPaint);
                } else {
                    canvas.drawRoundRect(sr, dpToPx(2f), dpToPx(2f), markupPaint);
                }
            }
        }
    }

    private void drawSearchMatches(@NonNull Canvas canvas) {
        if (pageSearchMatches.isEmpty()) return;
        for (int i = 0; i < pageSearchMatches.size(); i++) {
            PdfDocumentEngine.SearchMatch match = pageSearchMatches.get(i);
            Paint p = (i == activeSearchMatchIndexOnPage) ? searchActiveMatchPaint : searchMatchPaint;
            for (RectF ptRect : match.boundsPt) {
                RectF sr = unrotatedPdfRectToScreenRect(ptRect);
                canvas.drawRoundRect(sr, dpToPx(2f), dpToPx(2f), p);
            }
        }
    }

    private void drawPageLinks(@NonNull Canvas canvas) {
        if (pageLinks.isEmpty()) return;
        for (DocumentLink link : pageLinks) {
            for (RectF ptRect : link.boundsPt) {
                RectF sr = unrotatedPdfRectToScreenRect(ptRect);
                canvas.drawRect(sr, linkBorderPaint);
            }
        }
    }

    private void drawSavedInkStrokes(@NonNull Canvas canvas) {
        MedvixDocumentStore store = this.documentStore;
        if (store == null) return;
        List<InkStrokeAnnotation> strokes = store.getInkStrokesForPage(pageIndex);
        if (strokes.isEmpty()) return;

        float scale = getEffectiveScale();
        for (InkStrokeAnnotation stroke : strokes) {
            int count = stroke.getPointCount();
            if (count == 0) continue;

            inkPaint.setColor(stroke.argbColor);
            inkPaint.setStrokeWidth(Math.max(1.5f, stroke.strokeWidthPt * scale));
            if (stroke.tool == InkToolType.HIGHLIGHTER_INK) {
                inkPaint.setAlpha(95);
                inkPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.MULTIPLY));
            } else {
                inkPaint.setAlpha(255);
                inkPaint.setXfermode(null);
            }

            if (count == 1) {
                PointF sp = unrotatedPdfPointToScreenPoint(stroke.getX(0), stroke.getY(0));
                canvas.drawPoint(sp.x, sp.y, inkPaint);
                continue;
            }

            reusableInkPath.reset();
            PointF p0 = unrotatedPdfPointToScreenPoint(stroke.getX(0), stroke.getY(0));
            reusableInkPath.moveTo(p0.x, p0.y);
            float prevX = p0.x;
            float prevY = p0.y;
            for (int i = 1; i < count; i++) {
                PointF pi = unrotatedPdfPointToScreenPoint(stroke.getX(i), stroke.getY(i));
                float midX = (prevX + pi.x) * 0.5f;
                float midY = (prevY + pi.y) * 0.5f;
                reusableInkPath.quadTo(prevX, prevY, midX, midY);
                prevX = pi.x;
                prevY = pi.y;
            }
            reusableInkPath.lineTo(prevX, prevY);
            canvas.drawPath(reusableInkPath, inkPaint);
        }
        inkPaint.setXfermode(null);
        inkPaint.setAlpha(255);
    }

    private void drawActiveInkStroke(@NonNull Canvas canvas) {
        int ptsLen = currentStrokePointsPt.size();
        if (!isDrawingStroke || ptsLen < 2) return;

        float scale = getEffectiveScale();
        inkPaint.setColor(activeInkColor);
        inkPaint.setStrokeWidth(Math.max(1.5f, activeInkWidthPt * scale));
        inkPaint.setAlpha(interactionMode == InteractionMode.INK_HIGHLIGHTER ? 95 : 255);

        reusableInkPath.reset();
        PointF p0 = unrotatedPdfPointToScreenPoint(currentStrokePointsPt.get(0), currentStrokePointsPt.get(1));
        reusableInkPath.moveTo(p0.x, p0.y);
        float prevX = p0.x;
        float prevY = p0.y;

        for (int i = 2; i < ptsLen; i += 2) {
            PointF pi = unrotatedPdfPointToScreenPoint(currentStrokePointsPt.get(i), currentStrokePointsPt.get(i + 1));
            float midX = (prevX + pi.x) * 0.5f;
            float midY = (prevY + pi.y) * 0.5f;
            reusableInkPath.quadTo(prevX, prevY, midX, midY);
            prevX = pi.x;
            prevY = pi.y;
        }
        reusableInkPath.lineTo(prevX, prevY);
        canvas.drawPath(reusableInkPath, inkPaint);
        inkPaint.setAlpha(255);
    }

    private void drawTextSelectionOverlay(@NonNull Canvas canvas) {
        TextSelectionResult sel = this.activeSelection;
        if (sel == null || sel.boundsPt.isEmpty()) return;

        RectF firstScreenRect = null;
        RectF lastScreenRect = null;

        for (RectF ptRect : sel.boundsPt) {
            RectF sr = unrotatedPdfRectToScreenRect(ptRect);
            canvas.drawRect(sr, selectionFillPaint);
            if (firstScreenRect == null) firstScreenRect = new RectF(sr);
            lastScreenRect = new RectF(sr);
        }

        if (firstScreenRect != null && lastScreenRect != null) {
            float r = dpToPx(7f);
            // Start handle (top-left tear-drop)
            canvas.drawLine(firstScreenRect.left, firstScreenRect.top,
                    firstScreenRect.left, firstScreenRect.bottom, selectionHandlePaint);
            canvas.drawCircle(firstScreenRect.left, firstScreenRect.top - r * 0.6f, r, selectionHandlePaint);

            // End handle (bottom-right tear-drop)
            canvas.drawLine(lastScreenRect.right, lastScreenRect.top,
                    lastScreenRect.right, lastScreenRect.bottom, selectionHandlePaint);
            canvas.drawCircle(lastScreenRect.right, lastScreenRect.bottom + r * 0.6f, r, selectionHandlePaint);
        }
    }

    private void drawPreviewLockOverlay(@NonNull Canvas canvas, @NonNull RectF bounds) {
        canvas.drawRect(bounds, lockOverlayPaint);
        float cx = bounds.centerX();
        float cy = bounds.centerY();

        lockTextPaint.setTextSize(dpToPx(16f));
        lockTextPaint.setFakeBoldText(true);
        canvas.drawText("Premium Preview Page " + (pageIndex + 1), cx, cy - dpToPx(14f), lockTextPaint);

        lockTextPaint.setTextSize(dpToPx(13f));
        lockTextPaint.setFakeBoldText(false);
        canvas.drawText("Tap to unlock full textbook access", cx, cy + dpToPx(12f), lockTextPaint);
    }

    @NonNull
    private PointF unrotatedPdfPointToScreenPoint(float pdfX, float pdfY) {
        RectF r = unrotatedPdfRectToScreenRect(new RectF(pdfX, pdfY, pdfX, pdfY));
        return new PointF(r.left, r.top);
    }

    // ── Touch, Pinch-Zoom, Inertial Fling, Ink & Selection Handling ──────────

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        if (previewLocked) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP && interactionListener != null) {
                interactionListener.onUpgradePreviewRequested(pageIndex);
            }
            return true;
        }

        int action = event.getActionMasked();

        // 1. Handle active Ink / Highlighter / Eraser tools for single-pointer strokes
        if (interactionMode != InteractionMode.READ && event.getPointerCount() == 1 && !scaleDetector.isInProgress()) {
            if (handleAnnotationTouchEvent(event)) {
                return true;
            }
        }

        // 2. Handle dragging of native text selection handles
        if (activeSelection != null && event.getPointerCount() == 1 && !scaleDetector.isInProgress()) {
            if (handleSelectionHandleDrag(event)) {
                return true;
            }
        }

        if (draggingHandleMode != HANDLE_NONE && getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }

        // 3. Multi-touch pinch zoom + single-finger pan/fling/tap/long-press
        boolean scaleHandled = scaleDetector.onTouchEvent(event);
        boolean gestureHandled = gestureDetector.onTouchEvent(event);

        // Cooperate with parent RecyclerView:
        // - During multi-touch pinch, lock parent interception.
        // - In continuous vertical scroll mode, let RecyclerView intercept vertical drags
        //   while DocumentCanvasView handles horizontal panning across the zoomed page.
        ViewParent parent = getParent();
        if (parent != null) {
            if (scaleDetector.isInProgress()) {
                parent.requestDisallowInterceptTouchEvent(true);
            } else if (!standaloneFitPageMode) {
                if (action == MotionEvent.ACTION_DOWN) {
                    parent.requestDisallowInterceptTouchEvent(false);
                }
            } else if (zoomFactor > 1.02f) {
                parent.requestDisallowInterceptTouchEvent(true);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                parent.requestDisallowInterceptTouchEvent(false);
            }
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            isScalingOrFlinging = false;
            scheduleCrispTileUpdate();
            notifyViewportChanged();
        }

        return scaleHandled || gestureHandled || super.onTouchEvent(event);
    }

    private boolean handleAnnotationTouchEvent(@NonNull MotionEvent event) {
        ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(true);
        }

        int action = event.getActionMasked();
        float x = event.getX();
        float y = event.getY();

        if (interactionMode == InteractionMode.INK_ERASER) {
            lastEraserScreenX = x;
            lastEraserScreenY = y;
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                if (documentStore != null) {
                    PointF pt = screenToUnrotatedPdfPoint(x, y);
                    float radiusPt = dpToPx(ERASER_RADIUS_DP) / getEffectiveScale();
                    int erased = documentStore.eraseInkStrokesNearPoint(pageIndex, pt.x, pt.y, radiusPt);
                    if (erased > 0) {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                }
                invalidate();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                lastEraserScreenX = -1f;
                lastEraserScreenY = -1f;
                invalidate();
            }
            return true;
        }

        // PEN or HIGHLIGHTER_INK
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                currentStrokePointsPt.clear();
                isDrawingStroke = true;
                appendScreenPointToCurrentStroke(x, y);
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!isDrawingStroke) return false;
                int historySize = event.getHistorySize();
                for (int h = 0; h < historySize; h++) {
                    appendScreenPointToCurrentStroke(event.getHistoricalX(h), event.getHistoricalY(h));
                }
                appendScreenPointToCurrentStroke(x, y);
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!isDrawingStroke) return false;
                appendScreenPointToCurrentStroke(x, y);
                isDrawingStroke = false;
                commitCurrentInkStroke();
                invalidate();
                return true;

            default:
                return false;
        }
    }

    private void appendScreenPointToCurrentStroke(float screenX, float screenY) {
        PointF pt = screenToUnrotatedPdfPoint(screenX, screenY);
        float clampedX = clamp(pt.x, 0f, rawLogicalWidth);
        float clampedY = clamp(pt.y, 0f, rawLogicalHeight);
        currentStrokePointsPt.add(clampedX);
        currentStrokePointsPt.add(clampedY);
    }

    private void commitCurrentInkStroke() {
        MedvixDocumentStore store = this.documentStore;
        int count = currentStrokePointsPt.size();
        if (store != null && count >= 2) {
            float[] packed = new float[count];
            for (int i = 0; i < count; i++) {
                packed[i] = currentStrokePointsPt.get(i);
            }
            InkToolType tool = (interactionMode == InteractionMode.INK_HIGHLIGHTER)
                    ? InkToolType.HIGHLIGHTER_INK
                    : InkToolType.PEN;
            store.addInkStroke(pageIndex, tool, activeInkColor, activeInkWidthPt, packed);
        }
        currentStrokePointsPt.clear();
    }

    private boolean handleSelectionHandleDrag(@NonNull MotionEvent event) {
        TextSelectionResult sel = this.activeSelection;
        if (sel == null || sel.boundsPt.isEmpty()) return false;

        int action = event.getActionMasked();
        float x = event.getX();
        float y = event.getY();
        float hitRadius = dpToPx(HANDLE_HIT_RADIUS_DP);

        if (action == MotionEvent.ACTION_DOWN) {
            RectF first = unrotatedPdfRectToScreenRect(sel.boundsPt.get(0));
            RectF last = unrotatedPdfRectToScreenRect(sel.boundsPt.get(sel.boundsPt.size() - 1));

            if (Math.hypot(x - first.left, y - first.top) <= hitRadius) {
                draggingHandleMode = HANDLE_START;
                return true;
            }
            if (Math.hypot(x - last.right, y - last.bottom) <= hitRadius) {
                draggingHandleMode = HANDLE_END;
                return true;
            }
            return false;
        }

        if (draggingHandleMode == HANDLE_NONE) return false;

        ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(true);
        }

        if (action == MotionEvent.ACTION_MOVE) {
            PointF draggedPt = screenToUnrotatedPdfPoint(x, y);
            if (draggingHandleMode == HANDLE_START) {
                selectionAnchorPdfPt = draggedPt;
            } else {
                selectionFocusPdfPt = draggedPt;
            }
            if (selectionAnchorPdfPt != null && selectionFocusPdfPt != null) {
                runAsyncTextSelection(selectionAnchorPdfPt, selectionFocusPdfPt, false);
            }
            return true;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            draggingHandleMode = HANDLE_NONE;
            if (activeSelection != null && interactionListener != null) {
                interactionListener.onTextSelected(
                        this, pageIndex, activeSelection, computeSelectionUnionScreenRect(activeSelection));
            }
            return true;
        }

        return false;
    }

    private void runAsyncTextSelection(@NonNull PointF startPt,
                                       @NonNull PointF stopPt,
                                       boolean notifyImmediately) {
        PdfDocumentEngine engine = this.pdfEngine;
        if (engine == null || !engine.supportsNativeSelection()) return;

        final Point p1 = new Point(Math.round(startPt.x), Math.round(startPt.y));
        final Point p2 = new Point(Math.round(stopPt.x), Math.round(stopPt.y));

        selectionExecutor.submit(() -> {
            try {
                TextSelectionResult result = engine.selectTextAtPoints(pageIndex, p1, p2);
                if (result != null && !result.boundsPt.isEmpty()) {
                    post(() -> {
                        activeSelection = result;
                        invalidate();
                        if (notifyImmediately && interactionListener != null) {
                            interactionListener.onTextSelected(
                                    this, pageIndex, result, computeSelectionUnionScreenRect(result));
                        }
                    });
                }
            } catch (IOException ignored) {
            }
        });
    }

    @NonNull
    private RectF computeSelectionUnionScreenRect(@NonNull TextSelectionResult sel) {
        RectF union = new RectF();
        boolean first = true;
        for (RectF r : sel.boundsPt) {
            RectF sr = unrotatedPdfRectToScreenRect(r);
            if (first) {
                union.set(sr);
                first = false;
            } else {
                union.union(sr);
            }
        }
        return union;
    }

    private void applyZoomAroundFocalPoint(float newZoomFactor, float focalScreenX, float focalScreenY) {
        float clampedZoom = clamp(newZoomFactor, MIN_ZOOM_FACTOR, MAX_ZOOM_FACTOR);
        float oldScale = getEffectiveScale();
        computeContentScreenBounds(pageBoundsScratch);

        // Logical point under the user's fingers prior to zoom change
        float logicalFocalX = (focalScreenX - pageBoundsScratch.left) / oldScale;
        float logicalFocalY = (focalScreenY - pageBoundsScratch.top) / oldScale;

        this.zoomFactor = clampedZoom;
        float newScale = getEffectiveScale();

        this.panOffsetX = focalScreenX - (logicalFocalX * newScale);
        this.panOffsetY = focalScreenY - (logicalFocalY * newScale);
        clampPanOffsets();
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            panOffsetX = scroller.getCurrX();
            panOffsetY = scroller.getCurrY();
            clampPanOffsets();
            if (!standaloneFitPageMode) {
                notifyViewportChanged();
            }
            postInvalidateOnAnimation();
        } else if (isScalingOrFlinging) {
            isScalingOrFlinging = false;
            scheduleCrispTileUpdate();
            notifyViewportChanged();
        }
    }

    // ── Gesture Listeners ────────────────────────────────────────────────────

    private final class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
            scroller.abortAnimation();
            isScalingOrFlinging = true;
            removeCallbacks(debouncedTileRunnable);
            return true;
        }

        @Override
        public boolean onScale(@NonNull ScaleGestureDetector detector) {
            float nextZoom = zoomFactor * detector.getScaleFactor();
            applyZoomAroundFocalPoint(nextZoom, detector.getFocusX(), detector.getFocusY());
            if (!standaloneFitPageMode) {
                requestLayout();
            }
            notifyViewportChanged();
            invalidate();
            return true;
        }

        @Override
        public void onScaleEnd(@NonNull ScaleGestureDetector detector) {
            if (zoomFactor < 1.03f) {
                animateZoomTo(1.0f, detector.getFocusX(), detector.getFocusY());
            } else {
                scheduleCrispTileUpdate();
                notifyViewportChanged();
            }
        }
    }

    private final class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(@NonNull MotionEvent e) {
            if (!scroller.isFinished()) {
                scroller.abortAnimation();
            }
            return true;
        }

        @Override
        public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
            if (activeSelection != null) {
                clearTextSelection(true);
                return true;
            }

            PointF pdfPt = screenToUnrotatedPdfPoint(e.getX(), e.getY());

            // 1. Check if user tapped an existing highlight/note annotation
            if (documentStore != null) {
                List<TextMarkupAnnotation> markups = documentStore.getTextMarkupsForPage(pageIndex);
                for (int i = markups.size() - 1; i >= 0; i--) {
                    TextMarkupAnnotation m = markups.get(i);
                    for (RectF r : m.rectsPt) {
                        if (r.contains(pdfPt.x, pdfPt.y)) {
                            if (interactionListener != null) {
                                interactionListener.onMarkupAnnotationTapped(
                                        DocumentCanvasView.this, pageIndex, m, unrotatedPdfRectToScreenRect(m.unionBoundsPt));
                            }
                            return true;
                        }
                    }
                }
            }

            // 2. Check if user tapped an internal goto link or external URL
            for (DocumentLink link : pageLinks) {
                for (RectF r : link.boundsPt) {
                    if (r.contains(pdfPt.x, pdfPt.y)) {
                        if (interactionListener != null) {
                            interactionListener.onLinkActivated(DocumentCanvasView.this, pageIndex, link);
                        }
                        return true;
                    }
                }
            }

            // 3. Otherwise notify Activity to toggle reading chrome/toolbars
            if (interactionListener != null) {
                interactionListener.onSingleTap(DocumentCanvasView.this, pageIndex);
            }
            return true;
        }

        @Override
        public boolean onDoubleTap(@NonNull MotionEvent e) {
            if (interactionMode != InteractionMode.READ) return false;
            float target = (zoomFactor > 1.25f) ? MIN_ZOOM_FACTOR : DOUBLE_TAP_ZOOM_FACTOR;
            animateZoomTo(target, e.getX(), e.getY());
            return true;
        }

        @Override
        public void onLongPress(@NonNull MotionEvent e) {
            if (interactionMode != InteractionMode.READ || previewLocked) return;
            if (pdfEngine == null || !pdfEngine.supportsNativeSelection()) return;

            ViewParent parent = getParent();
            if (parent != null) {
                parent.requestDisallowInterceptTouchEvent(true);
            }

            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            PointF tapPt = screenToUnrotatedPdfPoint(e.getX(), e.getY());
            selectionAnchorPdfPt = new PointF(
                    Math.max(0f, tapPt.x - 18f),
                    Math.max(0f, tapPt.y - 6f));
            selectionFocusPdfPt = new PointF(
                    Math.min(rawLogicalWidth, tapPt.x + 18f),
                    Math.min(rawLogicalHeight, tapPt.y + 6f));
            runAsyncTextSelection(selectionAnchorPdfPt, selectionFocusPdfPt, true);
        }

        @Override
        public boolean onScroll(@Nullable MotionEvent e1,
                                @NonNull MotionEvent e2,
                                float distanceX,
                                float distanceY) {
            if (zoomFactor <= 1.01f) {
                // Unzoomed: allow parent RecyclerView to handle vertical/horizontal page scrolling
                ViewParent parent = getParent();
                if (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(false);
                }
                return false;
            }

            if (!standaloneFitPageMode) {
                // Continuous vertical scroll mode: vertical scrolling stays in RecyclerView,
                // while horizontal panning updates synchronously across all pages.
                boolean mostlyVertical = Math.abs(distanceY) > Math.abs(distanceX);
                ViewParent parent = getParent();
                if (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(!mostlyVertical);
                }
                if (mostlyVertical) {
                    return false;
                }
                panOffsetX -= distanceX;
                clampPanOffsets();
                notifyViewportChanged();
                invalidate();
                return true;
            }

            float prevX = panOffsetX;
            float prevY = panOffsetY;
            panOffsetX -= distanceX;
            panOffsetY -= distanceY;
            clampPanOffsets();

            // Hand off to parent RecyclerView if user hits the vertical/horizontal page boundary
            boolean hitBoundary = (Math.abs(panOffsetX - prevX) < 0.5f && Math.abs(distanceX) > Math.abs(distanceY))
                    || (Math.abs(panOffsetY - prevY) < 0.5f && Math.abs(distanceY) >= Math.abs(distanceX));
            ViewParent parent = getParent();
            if (parent != null) {
                parent.requestDisallowInterceptTouchEvent(!hitBoundary);
            }

            invalidate();
            return true;
        }

        @Override
        public boolean onFling(@Nullable MotionEvent e1,
                               @NonNull MotionEvent e2,
                               float velocityX,
                               float velocityY) {
            if (zoomFactor <= 1.01f) return false;

            float scale = getEffectiveScale();
            float contentW = getRotatedLogicalWidth() * scale;
            float contentH = getRotatedLogicalHeight() * scale;
            int minX = Math.min(0, Math.round(getWidth() - contentW));
            int minY = Math.min(0, Math.round(getHeight() - contentH));

            isScalingOrFlinging = true;
            scroller.fling(
                    Math.round(panOffsetX),
                    Math.round(panOffsetY),
                    Math.round(velocityX),
                    Math.round(velocityY),
                    minX, 0,
                    minY, 0
            );
            postInvalidateOnAnimation();
            return true;
        }
    }

    // ── Drawable Callback (Animated GIF / WebP Invalidation) ─────────────────

    @Override
    protected boolean verifyDrawable(@NonNull Drawable who) {
        return who == animatedDrawable || super.verifyDrawable(who);
    }

    // ── Hardware ColorMatrix Presets ─────────────────────────────────────────

    @NonNull
    private static ColorMatrixColorFilter createSepiaFilter() {
        ColorMatrix cm = new ColorMatrix(new float[]{
                0.96f, 0.03f, 0.01f, 0f, 0f,
                0.02f, 0.91f, 0.01f, 0f, 0f,
                0.01f, 0.03f, 0.78f, 0f, 0f,
                0f,    0f,    0f,    1f, 0f
        });
        return new ColorMatrixColorFilter(cm);
    }

    /**
     * Smart luminance-inverting Night filter:
     * Inverts brightness (white paper -> #16181D dark background, black text -> soft off-white)
     * while rotating hue 180 degrees so anatomical red/blue color coding remains preserved.
     */
    @NonNull
    private static ColorMatrixColorFilter createSmartNightFilter() {
        ColorMatrix cm = new ColorMatrix(new float[]{
                -0.25f, -0.65f, -0.05f, 0f, 232f,
                -0.25f, -0.65f, -0.05f, 0f, 234f,
                -0.25f, -0.65f, -0.05f, 0f, 238f,
                0f,     0f,     0f,     1f, 0f
        });
        return new ColorMatrixColorFilter(cm);
    }

    @NonNull
    private static ColorMatrixColorFilter createHighContrastFilter() {
        float contrast = 1.32f;
        float translate = (-0.5f * contrast + 0.5f) * 255f;
        ColorMatrix cm = new ColorMatrix(new float[]{
                contrast, 0f,       0f,       0f, translate,
                0f,       contrast, 0f,       0f, translate,
                0f,       0f,       contrast, 0f, translate,
                0f,       0f,       0f,       1f, 0f
        });
        return new ColorMatrixColorFilter(cm);
    }

    // ── Utility Helpers ──────────────────────────────────────────────────────

    private static void cancelTask(@Nullable PdfDocumentEngine.TaskHandle handle) {
        if (handle != null) handle.cancel();
    }

    private static void cancelImgTask(@Nullable ImageDocumentEngine.TaskHandle handle) {
        if (handle != null) handle.cancel();
    }

    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    private static float clamp(float val, float min, float max) {
        if (Float.isNaN(val) || Float.isInfinite(val)) return min;
        return Math.max(min, Math.min(max, val));
    }
}