package com.medhurb.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.print.SilentPdfPrinter;
import android.provider.MediaStore;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.core.content.FileProvider;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@CapacitorPlugin(
    name = "MedVixPdfExport",
    permissions = {
        @Permission(
            strings = { Manifest.permission.POST_NOTIFICATIONS },
            alias = "notifications"
        )
    }
)
public class MedVixPdfExportPlugin extends Plugin {

    private static final String FOLDER_NAME = "MedVix";

    private static class DocumentMeta {
        final String categoryKey;
        final String displayTitle;
        final String notificationTitle;
        final String notificationBody;
        final String pdfFileName;
        final String route;

        DocumentMeta(
            String categoryKey,
            String displayTitle,
            String notificationTitle,
            String notificationBody,
            String pdfFileName,
            String route
        ) {
            this.categoryKey = categoryKey;
            this.displayTitle = displayTitle;
            this.notificationTitle = notificationTitle;
            this.notificationBody = notificationBody;
            this.pdfFileName = pdfFileName;
            this.route = route;
        }
    }

    @PluginMethod
    public void generateAndSavePdf(PluginCall call) {
        String htmlContent = call.getString("html");
        if (htmlContent == null || htmlContent.trim().isEmpty()) {
            call.reject("HTML content is required");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && getPermissionState("notifications") != PermissionState.GRANTED) {
            requestPermissionForAlias("notifications", call, "onNotificationPermissionResult");
        } else {
            renderAndSaveToMedVixFolder(call);
        }
    }

    @PermissionCallback
    private void onNotificationPermissionResult(PluginCall call) {
        renderAndSaveToMedVixFolder(call);
    }

    @PluginMethod
    public void listSavedPdfs(PluginCall call) {
        Executors.newSingleThreadExecutor().execute(() -> {
            Context context = getContext();
            JSArray pdfsArray = new JSArray();

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentResolver resolver = context.getContentResolver();
                    Uri collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);

                    String[] projection = new String[]{
                        MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.SIZE,
                        MediaStore.MediaColumns.DATE_MODIFIED
                    };

                    String relPath = Environment.DIRECTORY_DOCUMENTS + "/" + FOLDER_NAME;
                    String selection = MediaStore.MediaColumns.MIME_TYPE + " = ? AND ("
                        + MediaStore.MediaColumns.RELATIVE_PATH + " = ? OR "
                        + MediaStore.MediaColumns.RELATIVE_PATH + " = ?)";
                    String[] selectionArgs = new String[]{
                        "application/pdf",
                        relPath + "/",
                        relPath
                    };

                    String sortOrder = MediaStore.MediaColumns.DATE_MODIFIED + " DESC";

                    try (Cursor cursor = resolver.query(
                            collection, projection, selection, selectionArgs, sortOrder)) {
                        if (cursor != null) {
                            int idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
                            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
                            int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
                            int dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED);

                            while (cursor.moveToNext()) {
                                long id = cursor.getLong(idCol);
                                String name = cursor.getString(nameCol);
                                long size = cursor.getLong(sizeCol);
                                long modifiedMs = cursor.getLong(dateCol) * 1000L;
                                Uri contentUri = ContentUris.withAppendedId(collection, id);

                                JSObject item = new JSObject();
                                item.put("uri", contentUri.toString());
                                item.put("fileName", name);
                                item.put("category", inferCategoryFromFileName(name));
                                item.put("size", size);
                                item.put("lastModified", modifiedMs);
                                pdfsArray.put(item);
                            }
                        }
                    }
                } else {
                    File docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
                    File medVixDir = new File(docsDir, FOLDER_NAME);

                    if (medVixDir.exists() && medVixDir.isDirectory()) {
                        File[] files = medVixDir.listFiles(
                            (dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".pdf")
                        );

                        if (files != null) {
                            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                            String authority = context.getPackageName() + ".fileprovider";

                            for (File file : files) {
                                Uri fileUri = FileProvider.getUriForFile(context, authority, file);
                                JSObject item = new JSObject();
                                item.put("uri", fileUri.toString());
                                item.put("fileName", file.getName());
                                item.put("category", inferCategoryFromFileName(file.getName()));
                                item.put("size", file.length());
                                item.put("lastModified", file.lastModified());
                                pdfsArray.put(item);
                            }
                        }
                    }
                }

                JSObject ret = new JSObject();
                ret.put("documents", pdfsArray);
                call.resolve(ret);

            } catch (Exception e) {
                call.reject("Failed to list saved PDFs: " + e.getMessage());
            }
        });
    }

    /**
     * Hands the saved PDF URI directly to FileOpenPlugin on the main thread so
     * viewer.js opens it inside MedVix's built-in reader.
     */
    @PluginMethod
    public void openPdf(PluginCall call) {
        String uriString = call.getString("uri");
        if (uriString == null || uriString.trim().isEmpty()) {
            call.reject("PDF URI is required");
            return;
        }

        Activity activity = getActivity();
        if (activity == null) {
            call.reject("Activity unavailable");
            return;
        }

        activity.runOnUiThread(() -> {
            try {
                Context context = getContext();
                Uri targetUri = Uri.parse(uriString);

                if ("file".equalsIgnoreCase(targetUri.getScheme()) && targetUri.getPath() != null) {
                    targetUri = FileProvider.getUriForFile(
                        context,
                        context.getPackageName() + ".fileprovider",
                        new File(targetUri.getPath())
                    );
                }

                Intent viewIntent = new Intent(Intent.ACTION_VIEW);
                viewIntent.setDataAndType(targetUri, "application/pdf");
                viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                FileOpenPlugin.deliverIntent(viewIntent);
                call.resolve();
            } catch (Exception e) {
                call.reject("Failed to open PDF in MedVix: " + e.getMessage());
            }
        });
    }

    private void renderAndSaveToMedVixFolder(PluginCall call) {
        Activity activity = getActivity();
        if (activity == null) {
            call.reject("Activity unavailable");
            return;
        }

        String htmlContent = call.getString("html");
        String documentName = call.getString("documentName", "MedVix Document");
        String category = call.getString("category", "document");
        String explicitFileName = call.getString("fileName");
        String route = call.getString("route");

        final DocumentMeta meta = buildDocumentMeta(documentName, category, explicitFileName, route);
        File tempFile = new File(
            getContext().getCacheDir(),
            "export_" + System.currentTimeMillis() + "_" + meta.pdfFileName
        );

        activity.runOnUiThread(() -> {
            WebView offscreenWebView = new WebView(activity);
            WebSettings settings = offscreenWebView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setAllowFileAccess(true);

            final AtomicBoolean isPrinted = new AtomicBoolean(false);

            offscreenWebView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    if ("about:blank".equals(url)) return;
                    if (!isPrinted.compareAndSet(false, true)) return;

                    view.postDelayed(() -> {
                        SilentPdfPrinter.printToCache(view, tempFile, new SilentPdfPrinter.Callback() {
                            @Override
                            public void onSuccess(File pdfFile) {
                                destroyOffscreenWebView(view);
                                moveTempPdfToDocumentsMedVix(call, pdfFile, meta);
                            }

                            @Override
                            public void onError(String message) {
                                destroyOffscreenWebView(view);
                                if (tempFile.exists()) tempFile.delete();
                                call.reject(message);
                            }
                        });
                    }, 150);
                }
            });

            offscreenWebView.loadDataWithBaseURL(
                "file:///android_asset/public/",
                htmlContent,
                "text/html",
                "UTF-8",
                null
            );
        });
    }

    private DocumentMeta buildDocumentMeta(String rawTitle, String rawCategory,
                                           String explicitFileName, String route) {
        String cleanTitle = (rawTitle != null && !rawTitle.trim().isEmpty())
            ? rawTitle.trim()
            : "MedVix Document";

        String normalizedCat = (rawCategory != null && !rawCategory.trim().isEmpty())
            ? rawCategory.trim().toLowerCase(Locale.ROOT)
            : "document";

        String fileSuffix;
        String notifTitle;
        String notifPrefix;

        switch (normalizedCat) {
            case "exam":
            case "exams":
                fileSuffix = "Exam";
                notifTitle = "Exam Saved";
                notifPrefix = "Exam saved to Documents/MedVix";
                break;

            case "note":
            case "notes":
            case "study-notes":
                fileSuffix = "Notes";
                notifTitle = "Study Notes Saved";
                notifPrefix = "Study notes saved to Documents/MedVix";
                break;

            case "analytics":
            case "performance":
            case "performance-analysis":
            case "report":
                fileSuffix = "Performance_Report";
                notifTitle = "Performance Report Saved";
                notifPrefix = "Performance analysis saved to Documents/MedVix";
                break;

            case "mcq":
            case "mcq-sheet":
                fileSuffix = "MCQ_Sheet";
                notifTitle = "MCQ Sheet Saved";
                notifPrefix = "MCQ sheet saved to Documents/MedVix";
                break;

            case "answer-key":
            case "answers":
                fileSuffix = "Answer_Key";
                notifTitle = "Answer Key Saved";
                notifPrefix = "Answer key saved to Documents/MedVix";
                break;

            case "document":
                fileSuffix = "";
                notifTitle = "Document Saved";
                notifPrefix = "Document saved to Documents/MedVix";
                break;

            default:
                String label = capitalizeWords(normalizedCat.replace('-', ' ').replace('_', ' '));
                fileSuffix = label.replaceAll("[^a-zA-Z0-9]+", "_");
                notifTitle = label + " Saved";
                notifPrefix = label + " saved to Documents/MedVix";
                break;
        }

        String finalPdfName;
        if (explicitFileName != null && !explicitFileName.trim().isEmpty()) {
            String sanitizedExplicit = sanitizeFilePart(explicitFileName);
            finalPdfName = sanitizedExplicit.toLowerCase(Locale.ROOT).endsWith(".pdf")
                ? sanitizedExplicit
                : sanitizedExplicit + ".pdf";
        } else {
            String baseName = sanitizeFilePart(cleanTitle);
            if (!fileSuffix.isEmpty()
                    && !baseName.toLowerCase(Locale.ROOT).endsWith("_" + fileSuffix.toLowerCase(Locale.ROOT))) {
                baseName = baseName + "_" + fileSuffix;
            }
            finalPdfName = baseName + ".pdf";
        }

        String notifBody = notifPrefix + ": " + cleanTitle + " (Tap to open in MedVix)";
        return new DocumentMeta(normalizedCat, cleanTitle, notifTitle, notifBody, finalPdfName, route);
    }

    private String inferCategoryFromFileName(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith("_exam.pdf")) return "exam";
        if (lower.endsWith("_notes.pdf")) return "notes";
        if (lower.endsWith("_performance_report.pdf")) return "analytics";
        if (lower.endsWith("_mcq_sheet.pdf")) return "mcq-sheet";
        if (lower.endsWith("_answer_key.pdf")) return "answer-key";
        return "document";
    }

    private String sanitizeFilePart(String input) {
        String cleaned = input
            .replaceAll("(?i)\\.pdf$", "")
            .replaceAll("[^a-zA-Z0-9._-]+", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_|_$", "");
        return cleaned.isEmpty() ? "MedVix_Document" : cleaned;
    }

    private String capitalizeWords(String input) {
        String[] parts = input.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                sb.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return sb.length() > 0 ? sb.toString() : "Document";
    }

    private void moveTempPdfToDocumentsMedVix(PluginCall call, File tempFile, DocumentMeta meta) {
        Executors.newSingleThreadExecutor().execute(() -> {
            Context context = getContext();
            try {
                Uri savedUri;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentResolver resolver = context.getContentResolver();
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, meta.pdfFileName);
                    values.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
                    values.put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOCUMENTS + "/" + FOLDER_NAME
                    );
                    values.put(MediaStore.MediaColumns.IS_PENDING, 1);

                    Uri collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                    savedUri = resolver.insert(collection, values);

                    if (savedUri == null) {
                        throw new Exception("Failed to create file in Documents/" + FOLDER_NAME);
                    }

                    try (InputStream in = new FileInputStream(tempFile);
                         OutputStream out = resolver.openOutputStream(savedUri)) {
                        if (out == null) {
                            throw new Exception("Unable to open output stream");
                        }
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = in.read(buffer)) != -1) {
                            out.write(buffer, 0, len);
                        }
                        out.flush();
                    } catch (Exception writeErr) {
                        resolver.delete(savedUri, null, null);
                        throw writeErr;
                    }

                    values.clear();
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    resolver.update(savedUri, values, null, null);

                } else {
                    File docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
                    File medVixDir = new File(docsDir, FOLDER_NAME);
                    if (!medVixDir.exists() && !medVixDir.mkdirs()) {
                        throw new Exception("Could not create Documents/" + FOLDER_NAME);
                    }

                    File destFile = getUniqueFile(medVixDir, meta.pdfFileName);
                    try (InputStream in = new FileInputStream(tempFile);
                         OutputStream out = new FileOutputStream(destFile)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = in.read(buffer)) != -1) {
                            out.write(buffer, 0, len);
                        }
                        out.flush();
                    }

                    savedUri = FileProvider.getUriForFile(
                        context,
                        context.getPackageName() + ".fileprovider",
                        destFile
                    );
                }

                // Dispatch through MedVix's existing notification system
                int notificationId = (int) (System.currentTimeMillis() & 0x7FFFFFFF);
                MedVixNotifications.displayFileNotification(
                    context,
                    notificationId,
                    meta.notificationTitle,
                    meta.notificationBody,
                    savedUri,
                    "application/pdf",
                    meta.route
                );

                JSObject ret = new JSObject();
                ret.put("uri", savedUri.toString());
                ret.put("fileName", meta.pdfFileName);
                ret.put("documentName", meta.displayTitle);
                ret.put("category", meta.categoryKey);
                ret.put("folder", "Documents/" + FOLDER_NAME);
                call.resolve(ret);

            } catch (Exception e) {
                call.reject("Failed to save PDF: " + e.getMessage());
            } finally {
                if (tempFile.exists()) {
                    tempFile.delete();
                }
            }
        });
    }

    private File getUniqueFile(File dir, String fileName) {
        File file = new File(dir, fileName);
        if (!file.exists()) return file;

        String base = fileName.substring(0, fileName.lastIndexOf('.'));
        String ext = fileName.substring(fileName.lastIndexOf('.'));
        int count = 1;
        while (file.exists()) {
            file = new File(dir, base + "_(" + count + ")" + ext);
            count++;
        }
        return file;
    }

    private void destroyOffscreenWebView(WebView view) {
        if (view != null) {
            view.setWebViewClient(null);
            view.stopLoading();
            view.loadUrl("about:blank");
            view.destroy();
        }
    }
}