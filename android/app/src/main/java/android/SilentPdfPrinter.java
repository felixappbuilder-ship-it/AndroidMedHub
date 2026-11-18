package android.print;

import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.webkit.WebView;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Headless HTML-to-PDF renderer that drives Android's PrintDocumentAdapter
 * directly into a local cache file without launching PrintManager or any
 * system print preview UI.
 *
 * Must remain in package {@code android.print} so it can subclass
 * {@link PrintDocumentAdapter.LayoutResultCallback} and
 * {@link PrintDocumentAdapter.WriteResultCallback}, whose constructors are
 * package-private in the Android SDK.
 */
public final class SilentPdfPrinter {

    public interface Callback {
        void onSuccess(File pdfFile);
        void onError(String message);
    }

    private SilentPdfPrinter() {
        // Utility class
    }

    /**
     * Renders the given WebView into an ISO A4, 300 DPI color PDF at {@code outFile}.
     * Must be called on the Android main (UI) thread.
     */
    public static void printToCache(WebView webView, File outFile, Callback callback) {
        PrintAttributes defaultAttributes = new PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setResolution(new PrintAttributes.Resolution("medvix_pdf", "PDF", 300, 300))
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build();

        printToCache(webView, outFile, defaultAttributes, callback);
    }

    /**
     * Renders the given WebView into {@code outFile} using custom {@link PrintAttributes}.
     * Must be called on the Android main (UI) thread.
     */
    public static void printToCache(
            WebView webView,
            File outFile,
            PrintAttributes attributes,
            Callback callback
    ) {
        if (webView == null) {
            if (callback != null) callback.onError("WebView instance is null");
            return;
        }
        if (outFile == null) {
            if (callback != null) callback.onError("Output file is null");
            return;
        }

        final AtomicBoolean finished = new AtomicBoolean(false);
        final PrintDocumentAdapter adapter = webView.createPrintDocumentAdapter(outFile.getName());
        final CancellationSignal layoutCancelSignal = new CancellationSignal();

        try {
            adapter.onStart();

            adapter.onLayout(
                    null,
                    attributes,
                    layoutCancelSignal,
                    new PrintDocumentAdapter.LayoutResultCallback() {
                        @Override
                        public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
                            writePdfPages(adapter, outFile, finished, callback);
                        }

                        @Override
                        public void onLayoutFailed(CharSequence error) {
                            safeFinishAdapter(adapter);
                            notifyErrorOnce(
                                    finished,
                                    callback,
                                    error != null ? error.toString() : "PDF layout failed"
                            );
                        }

                        @Override
                        public void onLayoutCancelled() {
                            safeFinishAdapter(adapter);
                            notifyErrorOnce(finished, callback, "PDF layout cancelled");
                        }
                    },
                    new Bundle()
            );
        } catch (Exception e) {
            safeFinishAdapter(adapter);
            notifyErrorOnce(finished, callback, "Failed to start PDF layout: " + e.getMessage());
        }
    }

    private static void writePdfPages(
            PrintDocumentAdapter adapter,
            File outFile,
            AtomicBoolean finished,
            Callback callback
    ) {
        ParcelFileDescriptor pfd = null;
        try {
            File parent = outFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                safeFinishAdapter(adapter);
                notifyErrorOnce(finished, callback, "Failed to create cache directory for PDF");
                return;
            }

            if (outFile.exists() && !outFile.delete()) {
                safeFinishAdapter(adapter);
                notifyErrorOnce(finished, callback, "Failed to overwrite existing temporary PDF");
                return;
            }

            if (!outFile.createNewFile()) {
                safeFinishAdapter(adapter);
                notifyErrorOnce(finished, callback, "Could not create temporary PDF file");
                return;
            }

            pfd = ParcelFileDescriptor.open(
                    outFile,
                    ParcelFileDescriptor.MODE_READ_WRITE
                            | ParcelFileDescriptor.MODE_CREATE
                            | ParcelFileDescriptor.MODE_TRUNCATE
            );

            final ParcelFileDescriptor activePfd = pfd;
            final CancellationSignal writeCancelSignal = new CancellationSignal();

            adapter.onWrite(
                    new PageRange[]{PageRange.ALL_PAGES},
                    activePfd,
                    writeCancelSignal,
                    new PrintDocumentAdapter.WriteResultCallback() {
                        @Override
                        public void onWriteFinished(PageRange[] pages) {
                            closeQuietly(activePfd);
                            safeFinishAdapter(adapter);

                            if (!outFile.exists() || outFile.length() == 0L) {
                                notifyErrorOnce(finished, callback, "Generated PDF file is empty");
                                return;
                            }

                            if (finished.compareAndSet(false, true) && callback != null) {
                                callback.onSuccess(outFile);
                            }
                        }

                        @Override
                        public void onWriteFailed(CharSequence error) {
                            closeQuietly(activePfd);
                            safeFinishAdapter(adapter);
                            deleteQuietly(outFile);
                            notifyErrorOnce(
                                    finished,
                                    callback,
                                    error != null ? error.toString() : "PDF write failed"
                            );
                        }

                        @Override
                        public void onWriteCancelled() {
                            closeQuietly(activePfd);
                            safeFinishAdapter(adapter);
                            deleteQuietly(outFile);
                            notifyErrorOnce(finished, callback, "PDF write cancelled");
                        }
                    }
            );
        } catch (Exception e) {
            closeQuietly(pfd);
            safeFinishAdapter(adapter);
            deleteQuietly(outFile);
            notifyErrorOnce(finished, callback, "PDF I/O error: " + e.getMessage());
        }
    }

    private static void notifyErrorOnce(AtomicBoolean finished, Callback callback, String message) {
        if (finished.compareAndSet(false, true) && callback != null) {
            callback.onError(message);
        }
    }

    private static void safeFinishAdapter(PrintDocumentAdapter adapter) {
        if (adapter != null) {
            try {
                adapter.onFinish();
            } catch (Exception ignored) {
            }
        }
    }

    private static void closeQuietly(ParcelFileDescriptor pfd) {
        if (pfd != null) {
            try {
                pfd.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}