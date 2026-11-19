package com.medhurb.app.viewer.print;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintJob;
import android.print.PrintManager;
import android.print.pdf.PrintedPdfDocument;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.medhurb.app.viewer.engine.ImageDocumentEngine;
import com.medhurb.app.viewer.engine.PdfDocumentEngine;
import com.medhurb.app.viewer.engine.PdfDocumentEngine.PageMetrics;
import com.medhurb.app.viewer.render.BitmapMemoryPool.ManagedBitmap;
import com.medhurb.app.viewer.storage.MedvixDocumentStore;
import com.medhurb.app.viewer.storage.MedvixDocumentStore.InkStrokeAnnotation;
import com.medhurb.app.viewer.storage.MedvixDocumentStore.InkToolType;
import com.medhurb.app.viewer.storage.MedvixDocumentStore.TextMarkupAnnotation;
import com.medhurb.app.viewer.storage.MedvixDocumentStore.TextMarkupStyle;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * MedvixDocumentPrintAdapter
 * ============================================================================
 * Native Android PrintDocumentAdapter for MedVix PDFs and high-resolution
 * Images (including SVG vector graphics).
 *
 * Key capabilities:
 *  - Renders PDFs with RENDER_MODE_FOR_PRINT at up to 300 DPI (clamped to
 *    3072 px per side so multi-page print spooling never triggers OOM).
 *  - Bypasses the interactive LruCache (bypassCache = true) and returns each
 *    page's Bitmap immediately to BitmapMemoryPool upon finishPage().
 *  - Honors PageMetrics.shouldScaleForPrinting, user rotation (0/90/180/270),
 *    and arbitrary discontinuous PageRange selections (e.g., "1-3, 8, 12-15").
 *  - Optionally burns saved highlights, underlines, strikeouts, and freehand
 *    ink annotations from MedvixDocumentStore onto the printed output.
 *  - Enforces 10% preview-mode page caps when printing unpurchased premium PDFs.
 */
public final class MedvixDocumentPrintAdapter extends PrintDocumentAdapter {

    // ── Constants ────────────────────────────────────────────────────────────

    private static final int MIN_PRINT_DPI          = 150;
    private static final int DEFAULT_PRINT_DPI      = 300;
    private static final int MAX_PRINT_DPI          = 300;
    private static final int MAX_PRINT_BITMAP_PX    = 3072;
    private static final float POINTS_PER_INCH      = 72.0f;

    // ── Static Convenience Launchers ─────────────────────────────────────────

    /**
     * Launches the Android system print dialog for an open PdfDocumentEngine.
     */
    @Nullable
    public static PrintJob printPdf(@NonNull Context context,
                                    @NonNull PdfDocumentEngine pdfEngine,
                                    @Nullable MedvixDocumentStore store,
                                    @Nullable String jobName,
                                    int rotationDegrees,
                                    boolean includeAnnotations,
                                    boolean isExternalDocument) {
        if (!isExternalDocument || !pdfEngine.getResolvedDocument().isExternal) {
            return null;
        }
        PrintManager pm = (PrintManager) context.getSystemService(Context.PRINT_SERVICE);
        if (pm == null) return null;

        String title = sanitizeJobName(jobName, pdfEngine.getResolvedDocument().displayName);
        int rot = PdfDocumentEngine.normalizeRotation(rotationDegrees);
        PageMetrics p0 = pdfEngine.getEstimatedPageMetrics(0);
        boolean landscape = p0.getRotatedWidthPt(rot) > p0.getRotatedHeightPt(rot);

        PrintAttributes attrs = new PrintAttributes.Builder()
                .setMediaSize(landscape
                        ? PrintAttributes.MediaSize.UNKNOWN_LANDSCAPE
                        : PrintAttributes.MediaSize.UNKNOWN_PORTRAIT)
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build();

        MedvixDocumentPrintAdapter adapter = new MedvixDocumentPrintAdapter(
                context.getApplicationContext(),
                pdfEngine,
                null,
                store,
                title,
                rot,
                includeAnnotations,
                true
        );
        return pm.print(title, adapter, attrs);
    }

    /**
     * Launches the Android system print dialog for an open ImageDocumentEngine.
     */
    @Nullable
    public static PrintJob printImage(@NonNull Context context,
                                      @NonNull ImageDocumentEngine imageEngine,
                                      @Nullable MedvixDocumentStore store,
                                      @Nullable String jobName,
                                      int rotationDegrees,
                                      boolean includeAnnotations,
                                      boolean isExternalDocument) {
        if (!isExternalDocument || !imageEngine.getResolvedDocument().isExternal) {
            return null;
        }
        PrintManager pm = (PrintManager) context.getSystemService(Context.PRINT_SERVICE);
        if (pm == null) return null;

        String title = sanitizeJobName(jobName, imageEngine.getResolvedDocument().displayName);
        int rot = ImageDocumentEngine.normalizeRotation(rotationDegrees);
        ImageDocumentEngine.ImageMetrics m = imageEngine.getMetrics();
        boolean landscape = m.getOrientedWidthPx(rot) > m.getOrientedHeightPx(rot);

        PrintAttributes attrs = new PrintAttributes.Builder()
                .setMediaSize(landscape
                        ? PrintAttributes.MediaSize.UNKNOWN_LANDSCAPE
                        : PrintAttributes.MediaSize.UNKNOWN_PORTRAIT)
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build();

        MedvixDocumentPrintAdapter adapter = new MedvixDocumentPrintAdapter(
                context.getApplicationContext(),
                null,
                imageEngine,
                store,
                title,
                rot,
                includeAnnotations,
                true
        );
        return pm.print(title, adapter, attrs);
    }

    // ── Instance State ───────────────────────────────────────────────────────

    @NonNull private final Context appContext;
    @Nullable private final PdfDocumentEngine pdfEngine;
    @Nullable private final ImageDocumentEngine imageEngine;
    @Nullable private final MedvixDocumentStore documentStore;
    @NonNull private final String documentTitle;
    private final int rotationDegrees;
    private final boolean includeAnnotations;
    private final boolean isExternalDocument;

    private final ExecutorService printExecutor;
    @Nullable private volatile Future<?> activeWriteFuture;
    @Nullable private PrintAttributes activePrintAttributes;

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint markupPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ── Constructor ──────────────────────────────────────────────────────────

    public MedvixDocumentPrintAdapter(@NonNull Context context,
                                      @Nullable PdfDocumentEngine pdfEngine,
                                      @Nullable ImageDocumentEngine imageEngine,
                                      @Nullable MedvixDocumentStore documentStore,
                                      @NonNull String documentTitle,
                                      int rotationDegrees,
                                      boolean includeAnnotations,
                                      boolean isExternalDocument) {
        if (pdfEngine == null && imageEngine == null) {
            throw new IllegalArgumentException("Either pdfEngine or imageEngine must be non-null");
        }
        this.appContext = context.getApplicationContext();
        this.pdfEngine = pdfEngine;
        this.imageEngine = imageEngine;
        this.documentStore = documentStore;
        this.documentTitle = sanitizeJobName(documentTitle, "MedVix Document");
        this.rotationDegrees = PdfDocumentEngine.normalizeRotation(rotationDegrees);
        this.includeAnnotations = includeAnnotations;
        boolean docExternal = (pdfEngine != null && pdfEngine.getResolvedDocument().isExternal)
                || (imageEngine != null && imageEngine.getResolvedDocument().isExternal);
        this.isExternalDocument = isExternalDocument && docExternal;

        this.printExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "MedvixPrint-Worker");
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        });

        this.markupPaint.setStyle(Paint.Style.FILL);
        this.inkPaint.setStyle(Paint.Style.STROKE);
        this.inkPaint.setStrokeCap(Paint.Cap.ROUND);
        this.inkPaint.setStrokeJoin(Paint.Join.ROUND);
    }

    // ── PrintDocumentAdapter Lifecycle ───────────────────────────────────────

    @Override
    public void onLayout(@Nullable PrintAttributes oldAttributes,
                         @NonNull PrintAttributes newAttributes,
                         @NonNull CancellationSignal cancellationSignal,
                         @NonNull LayoutResultCallback callback,
                         @Nullable Bundle extras) {
        if (cancellationSignal.isCanceled() || !isExternalDocument) {
            callback.onLayoutCancelled();
            return;
        }

        if (isEngineClosed()) {
            callback.onLayoutFailed("Document was closed before printing could begin");
            return;
        }

        boolean attributesChanged = !newAttributes.equals(this.activePrintAttributes);
        this.activePrintAttributes = newAttributes;

        int printablePageCount = getPrintablePageCount();
        if (printablePageCount <= 0) {
            callback.onLayoutFailed("No printable pages available in document");
            return;
        }

        PrintDocumentInfo info = new PrintDocumentInfo.Builder(documentTitle)
                .setContentType(pdfEngine != null
                        ? PrintDocumentInfo.CONTENT_TYPE_DOCUMENT
                        : PrintDocumentInfo.CONTENT_TYPE_PHOTO)
                .setPageCount(printablePageCount)
                .build();

        callback.onLayoutFinished(info, attributesChanged);
    }

    @Override
    public void onWrite(@NonNull PageRange[] pages,
                        @NonNull ParcelFileDescriptor destination,
                        @NonNull CancellationSignal cancellationSignal,
                        @NonNull WriteResultCallback callback) {
        if (cancellationSignal.isCanceled() || !isExternalDocument) {
            safeClosePfd(destination);
            callback.onWriteCancelled();
            return;
        }

        final PrintAttributes attrs = this.activePrintAttributes;
        if (attrs == null || isEngineClosed()) {
            safeClosePfd(destination);
            callback.onWriteFailed("Print session is not initialized or document is closed");
            return;
        }

        final int printablePageCount = getPrintablePageCount();
        final List<Integer> targetPageIndices = expandPageRanges(pages, printablePageCount);
        if (targetPageIndices.isEmpty()) {
            safeClosePfd(destination);
            callback.onWriteFailed("Selected page range contains no valid pages");
            return;
        }

        Future<?> previous = this.activeWriteFuture;
        if (previous != null && !previous.isDone()) {
            previous.cancel(true);
        }

        this.activeWriteFuture = printExecutor.submit(() -> {
            PrintedPdfDocument printedDoc = null;
            try {
                printedDoc = new PrintedPdfDocument(appContext, attrs);
                int targetDpi = resolveTargetPrintDpi(attrs);

                for (int outIndex = 0; outIndex < targetPageIndices.size(); outIndex++) {
                    if (cancellationSignal.isCanceled() || Thread.currentThread().isInterrupted()) {
                        callback.onWriteCancelled();
                        return;
                    }

                    int docPageIndex = targetPageIndices.get(outIndex);
                    PdfDocument.Page outPage = printedDoc.startPage(outIndex);
                    try {
                        if (pdfEngine != null) {
                            drawPdfPageToPrintCanvas(printedDoc, outPage, docPageIndex, targetDpi, cancellationSignal);
                        } else if (imageEngine != null) {
                            drawImageToPrintCanvas(printedDoc, outPage, targetDpi, cancellationSignal);
                        }
                    } finally {
                        printedDoc.finishPage(outPage);
                    }
                }

                if (cancellationSignal.isCanceled()) {
                    callback.onWriteCancelled();
                    return;
                }

                try (FileOutputStream outStream = new FileOutputStream(destination.getFileDescriptor())) {
                    printedDoc.writeTo(outStream);
                    outStream.flush();
                }

                PageRange[] writtenRanges = compactPageIndicesToRanges(targetPageIndices);
                callback.onWriteFinished(writtenRanges);
            } catch (PrintJobCancelledException cancelled) {
                callback.onWriteCancelled();
            } catch (Throwable t) {
                if (cancellationSignal.isCanceled()) {
                    callback.onWriteCancelled();
                } else {
                    String msg = !TextUtils.isEmpty(t.getMessage()) ? t.getMessage() : "Print rendering error";
                    callback.onWriteFailed(msg);
                }
            } finally {
                if (printedDoc != null) {
                    try {
                        printedDoc.close();
                    } catch (Throwable ignored) {
                    }
                }
                safeClosePfd(destination);
            }
        });
    }

    @Override
    public void onFinish() {
        Future<?> active = this.activeWriteFuture;
        if (active != null && !active.isDone()) {
            active.cancel(true);
        }
        printExecutor.shutdownNow();
        super.onFinish();
    }

    // ── PDF & Image Print Page Rasterization ─────────────────────────────────

    private void drawPdfPageToPrintCanvas(@NonNull PrintedPdfDocument printedDoc,
                                          @NonNull PdfDocument.Page outPage,
                                          int pageIndex,
                                          int targetDpi,
                                          @NonNull CancellationSignal cancellationSignal) throws IOException {
        if (pdfEngine == null) return;
        checkCancelled(cancellationSignal);

        PageMetrics metrics = pdfEngine.getPageMetrics(pageIndex);
        float rotPageW = metrics.getRotatedWidthPt(rotationDegrees);
        float rotPageH = metrics.getRotatedHeightPt(rotationDegrees);

        Rect contentRect = printedDoc.getPageContentRect();
        RectF destRect = computeDestinationRectOnPaper(
                rotPageW, rotPageH, contentRect, metrics.shouldScaleForPrinting);

        // Compute raster scale from target DPI (points -> pixels) clamped to safe GPU/RAM ceiling
        float dpiScale = (float) targetDpi / POINTS_PER_INCH;
        float maxSidePt = Math.max(rotPageW, rotPageH);
        if (maxSidePt * dpiScale > MAX_PRINT_BITMAP_PX) {
            dpiScale = (float) MAX_PRINT_BITMAP_PX / maxSidePt;
        }
        dpiScale = Math.max(1.0f, dpiScale);

        PdfDocumentEngine.RenderRequest req = new PdfDocumentEngine.RenderRequest.Builder(pageIndex)
                .setScale(dpiScale)
                .setRotation(rotationDegrees)
                .setForPrint(true)
                .setBypassCache(true)
                .build();

        checkCancelled(cancellationSignal);
        ManagedBitmap lease = pdfEngine.renderSync(req);
        try {
            checkCancelled(cancellationSignal);
            Canvas canvas = outPage.getCanvas();
            Bitmap bmp = lease.getBitmap();
            Rect srcRect = new Rect(0, 0, bmp.getWidth(), bmp.getHeight());
            canvas.drawBitmap(bmp, srcRect, destRect, bitmapPaint);

            if (includeAnnotations && documentStore != null) {
                drawAnnotationsOntoPrintCanvas(
                        canvas,
                        pageIndex,
                        metrics.rawWidthPt,
                        metrics.rawHeightPt,
                        rotPageW,
                        rotPageH,
                        destRect
                );
            }
        } finally {
            lease.release();
        }
    }

    private void drawImageToPrintCanvas(@NonNull PrintedPdfDocument printedDoc,
                                        @NonNull PdfDocument.Page outPage,
                                        int targetDpi,
                                        @NonNull CancellationSignal cancellationSignal) throws IOException {
        if (imageEngine == null) return;
        checkCancelled(cancellationSignal);

        ImageDocumentEngine.ImageMetrics metrics = imageEngine.getMetrics();
        float orientedW = metrics.getOrientedWidthPx(rotationDegrees);
        float orientedH = metrics.getOrientedHeightPx(rotationDegrees);

        Rect contentRect = printedDoc.getPageContentRect();
        RectF destRect = computeDestinationRectOnPaper(orientedW, orientedH, contentRect, true);

        // Compute desired pixel dimensions on paper at target DPI
        float desiredPrintPxW = (destRect.width() / POINTS_PER_INCH) * targetDpi;
        float desiredPrintPxH = (destRect.height() / POINTS_PER_INCH) * targetDpi;
        float desiredScale = Math.min(1.0f, Math.max(desiredPrintPxW / orientedW, desiredPrintPxH / orientedH));

        float maxSide = Math.max(orientedW, orientedH);
        if (maxSide * desiredScale > MAX_PRINT_BITMAP_PX) {
            desiredScale = (float) MAX_PRINT_BITMAP_PX / maxSide;
        }
        desiredScale = Math.max(0.05f, desiredScale);

        ImageDocumentEngine.RenderRequest req = new ImageDocumentEngine.RenderRequest.Builder()
                .setScale(desiredScale)
                .setRotation(rotationDegrees)
                .setBypassCache(true)
                .setBackgroundFillColor(Color.WHITE)
                .build();

        checkCancelled(cancellationSignal);
        ManagedBitmap lease = imageEngine.renderSync(req);
        try {
            checkCancelled(cancellationSignal);
            Canvas canvas = outPage.getCanvas();
            Bitmap bmp = lease.getBitmap();
            Rect srcRect = new Rect(0, 0, bmp.getWidth(), bmp.getHeight());
            canvas.drawBitmap(bmp, srcRect, destRect, bitmapPaint);

            if (includeAnnotations && documentStore != null) {
                drawAnnotationsOntoPrintCanvas(
                        canvas,
                        0,
                        metrics.getNormalizedWidthPx(),
                        metrics.getNormalizedHeightPx(),
                        orientedW,
                        orientedH,
                        destRect
                );
            }
        } finally {
            lease.release();
        }
    }

    // ── Vector Annotation Burn-In for Printed Pages ──────────────────────────

    private void drawAnnotationsOntoPrintCanvas(@NonNull Canvas canvas,
                                                int pageIndex,
                                                float rawLogicalW,
                                                float rawLogicalH,
                                                float rotatedLogicalW,
                                                float rotatedLogicalH,
                                                @NonNull RectF destPaperRect) {
        MedvixDocumentStore store = this.documentStore;
        if (store == null) return;

        float scaleX = destPaperRect.width() / Math.max(1f, rotatedLogicalW);
        float scaleY = destPaperRect.height() / Math.max(1f, rotatedLogicalH);
        float uniformScale = Math.min(scaleX, scaleY);

        // 1. Text Markups (Highlights, Underlines, Strikeouts)
        List<TextMarkupAnnotation> markups = store.getTextMarkupsForPage(pageIndex);
        for (TextMarkupAnnotation m : markups) {
            markupPaint.setColor(m.argbColor);
            for (RectF ptRect : m.rectsPt) {
                RectF r = PdfDocumentEngine.pdfPointsToRotatedViewRect(
                        ptRect, rawLogicalW, rawLogicalH, rotationDegrees, uniformScale);
                r.offset(destPaperRect.left, destPaperRect.top);

                if (m.style == TextMarkupStyle.UNDERLINE) {
                    float h = Math.max(0.75f, r.height() * 0.12f);
                    canvas.drawRect(r.left, r.bottom - h, r.right, r.bottom, markupPaint);
                } else if (m.style == TextMarkupStyle.STRIKEOUT) {
                    float h = Math.max(0.75f, r.height() * 0.10f);
                    float mid = r.centerY();
                    canvas.drawRect(r.left, mid - h * 0.5f, r.right, mid + h * 0.5f, markupPaint);
                } else {
                    canvas.drawRect(r, markupPaint);
                }
            }
        }

        // 2. Freehand Ink Strokes (Pen & Highlighter)
        List<InkStrokeAnnotation> strokes = store.getInkStrokesForPage(pageIndex);
        Path strokePath = new Path();
        for (InkStrokeAnnotation stroke : strokes) {
            int count = stroke.getPointCount();
            if (count == 0) continue;

            inkPaint.setColor(stroke.argbColor);
            inkPaint.setStrokeWidth(Math.max(0.5f, stroke.strokeWidthPt * uniformScale));
            if (stroke.tool == InkToolType.HIGHLIGHTER_INK) {
                inkPaint.setAlpha(95);
                inkPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.MULTIPLY));
            } else {
                inkPaint.setAlpha(255);
                inkPaint.setXfermode(null);
            }

            if (count == 1) {
                PointF p = mapUnrotatedPointToPrintPaper(
                        stroke.getX(0), stroke.getY(0), rawLogicalW, rawLogicalH, uniformScale, destPaperRect);
                canvas.drawPoint(p.x, p.y, inkPaint);
                continue;
            }

            strokePath.reset();
            PointF p0 = mapUnrotatedPointToPrintPaper(
                    stroke.getX(0), stroke.getY(0), rawLogicalW, rawLogicalH, uniformScale, destPaperRect);
            strokePath.moveTo(p0.x, p0.y);
            float prevX = p0.x;
            float prevY = p0.y;
            for (int i = 1; i < count; i++) {
                PointF pi = mapUnrotatedPointToPrintPaper(
                        stroke.getX(i), stroke.getY(i), rawLogicalW, rawLogicalH, uniformScale, destPaperRect);
                strokePath.quadTo(prevX, prevY, (prevX + pi.x) * 0.5f, (prevY + pi.y) * 0.5f);
                prevX = pi.x;
                prevY = pi.y;
            }
            strokePath.lineTo(prevX, prevY);
            canvas.drawPath(strokePath, inkPaint);
        }
        inkPaint.setXfermode(null);
        inkPaint.setAlpha(255);
    }

    @NonNull
    private PointF mapUnrotatedPointToPrintPaper(float rawX,
                                                 float rawY,
                                                 float rawLogicalW,
                                                 float rawLogicalH,
                                                 float scale,
                                                 @NonNull RectF destPaperRect) {
        RectF mapped = PdfDocumentEngine.pdfPointsToRotatedViewRect(
                new RectF(rawX, rawY, rawX, rawY),
                rawLogicalW,
                rawLogicalH,
                rotationDegrees,
                scale
        );
        return new PointF(destPaperRect.left + mapped.left, destPaperRect.top + mapped.top);
    }

    // ── Geometry, PageRange & Utility Helpers ────────────────────────────────

    @NonNull
    private static RectF computeDestinationRectOnPaper(float contentW,
                                                       float contentH,
                                                       @NonNull Rect printableRect,
                                                       boolean shouldScaleForPrinting) {
        float paperW = Math.max(1f, printableRect.width());
        float paperH = Math.max(1f, printableRect.height());
        float srcW = Math.max(1f, contentW);
        float srcH = Math.max(1f, contentH);

        float scale;
        if (!shouldScaleForPrinting && srcW <= paperW && srcH <= paperH) {
            scale = 1.0f;
        } else {
            scale = Math.min(paperW / srcW, paperH / srcH);
        }

        float drawnW = srcW * scale;
        float drawnH = srcH * scale;
        float left = printableRect.left + (paperW - drawnW) * 0.5f;
        float top = printableRect.top + (paperH - drawnH) * 0.5f;
        return new RectF(left, top, left + drawnW, top + drawnH);
    }

    private int getPrintablePageCount() {
        if (pdfEngine != null) {
            return pdfEngine.isPreviewMode()
                    ? pdfEngine.getAccessiblePageCount()
                    : pdfEngine.getTotalPageCount();
        }
        return imageEngine != null ? 1 : 0;
    }

    private boolean isEngineClosed() {
        if (pdfEngine != null) return pdfEngine.isClosed();
        if (imageEngine != null) return imageEngine.isClosed();
        return true;
    }

    private static int resolveTargetPrintDpi(@NonNull PrintAttributes attrs) {
        PrintAttributes.Resolution res = attrs.getResolution();
        if (res == null) return DEFAULT_PRINT_DPI;
        int rawDpi = Math.max(res.getHorizontalDpi(), res.getVerticalDpi());
        if (rawDpi <= 0) return DEFAULT_PRINT_DPI;
        return Math.max(MIN_PRINT_DPI, Math.min(MAX_PRINT_DPI, rawDpi));
    }

    @NonNull
    private static List<Integer> expandPageRanges(@NonNull PageRange[] ranges, int totalPages) {
        TreeSet<Integer> uniquePages = new TreeSet<>();
        for (PageRange range : ranges) {
            if (range == null) continue;
            if (PageRange.ALL_PAGES.equals(range)) {
                for (int i = 0; i < totalPages; i++) {
                    uniquePages.add(i);
                }
                break;
            }
            int start = Math.max(0, range.getStart());
            int end = Math.min(totalPages - 1, range.getEnd());
            for (int i = start; i <= end; i++) {
                uniquePages.add(i);
            }
        }
        return new ArrayList<>(uniquePages);
    }

    @NonNull
    private static PageRange[] compactPageIndicesToRanges(@NonNull List<Integer> sortedIndices) {
        if (sortedIndices.isEmpty()) return new PageRange[0];
        List<PageRange> ranges = new ArrayList<>();
        int rangeStart = sortedIndices.get(0);
        int prev = rangeStart;

        for (int i = 1; i < sortedIndices.size(); i++) {
            int cur = sortedIndices.get(i);
            if (cur == prev + 1) {
                prev = cur;
            } else {
                ranges.add(new PageRange(rangeStart, prev));
                rangeStart = cur;
                prev = cur;
            }
        }
        ranges.add(new PageRange(rangeStart, prev));
        return ranges.toArray(new PageRange[0]);
    }

    @NonNull
    private static String sanitizeJobName(@Nullable String primary, @Nullable String fallback) {
        String raw = !TextUtils.isEmpty(primary) ? primary : fallback;
        if (TextUtils.isEmpty(raw)) return "MedVix Document";
        String cleaned = raw.trim().replaceAll("[\\r\\n\\t]+", " ");
        return cleaned.isEmpty() ? "MedVix Document" : cleaned;
    }

    private static void checkCancelled(@NonNull CancellationSignal signal) {
        if (signal.isCanceled() || Thread.currentThread().isInterrupted()) {
            throw new PrintJobCancelledException();
        }
    }

    private static void safeClosePfd(@Nullable ParcelFileDescriptor pfd) {
        if (pfd != null) {
            try {
                pfd.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final class PrintJobCancelledException extends RuntimeException {
        PrintJobCancelledException() {
            super("Print job cancelled");
        }
    }
}