package com.medhurb.app.viewer.storage;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * MedvixStorageResolver
 * ============================================================================
 * Zero-copy native storage, URI, magic-byte format, sidecar, and thumbnail
 * resolver for MedVix PDF and Image rendering.
 *
 * Architectural guarantees:
 *  1. Searches all three MedVix roots ('internal', 'external', 'sdcard') in
 *     exact parity with scripts/db/app-storage.js and content-store.js.
 *  2. Computes filesystem slugs with byte-for-byte parity to scripts/db/layout.js.
 *  3. Sniffs magic bytes via non-destructive pread() so extensionless blob.bin
 *     files and external content:// URIs are reliably classified as PDF,
 *     RASTER_IMAGE, or SVG_IMAGE before hitting native decoders.
 *  4. Guarantees seekable ParcelFileDescriptors for PdfRenderer (automatically
 *     spooling non-seekable pipe streams from third-party content providers).
 *  5. Flags removable SD-card documents so viewers can attach ACTION_MEDIA_EJECT
 *     SIGBUS protection.
 *  6. Distinguishes private MedVix library documents (isExternal = false) from
 *     external ACTION_VIEW URIs/files (isExternal = true).
 */
public final class MedvixStorageResolver {

    // ── Layout Constants (mirrors scripts/db/layout.js & db.js) ─────────────
    public static final String DIR_CONTENT          = "content";
    public static final String DIR_CACHE            = "cache";
    public static final String DIR_SYSTEM           = "system";
    public static final String DIR_THUMBNAILS       = "cache/thumbnails";
    public static final String DIR_MANIFESTS        = "cache/manifests";
    public static final String DIR_TMP              = "cache/tmp";
    public static final String DIR_EXT_SIDECARS     = "cache/tmp/ext-sidecars";

    public static final String NS_FILES             = "files";
    public static final String COL_BLOBS            = "blobs";
    public static final String NS_PUBLIC_ASSETS     = "public-assets";
    public static final String COL_ASSETS           = "assets";
    public static final String BLOB_FILENAME        = "blob.bin";

    private static final String[] THUMBNAIL_EXTS    = { "jpg", "png", "webp", "gif", "svg", "bin" };
    private static final String[] PREF_FILE_CANDIDATES = {
        "MedVixStoragePrefs",
        "CapacitorStorage"
    };
    private static final String[] PREF_KEY_CANDIDATES = {
        "preferred_storage_root",
        "medvix_preferred_root",
        "preferredRoot"
    };

    private static final int HEADER_SNIFF_BYTES     = 1024;
    private static final int SPOOL_BUFFER_BYTES     = 64 * 1024;

    private MedvixStorageResolver() {
        // Static utility class
    }

    // ── Enums ────────────────────────────────────────────────────────────────

    public enum RootType {
        INTERNAL("internal"),
        EXTERNAL("external"),
        SDCARD("sdcard"),
        URI("uri"),
        EXTERNAL_FILE("external_file");

        public final String id;

        RootType(String id) {
            this.id = id;
        }

        @NonNull
        public static RootType fromId(@Nullable String raw) {
            if (raw == null) return INTERNAL;
            switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "external":
                    return EXTERNAL;
                case "sdcard":
                    return SDCARD;
                case "internal":
                default:
                    return INTERNAL;
            }
        }
    }

    public enum DocumentKind {
        PDF,
        RASTER_IMAGE,
        SVG_IMAGE,
        UNSUPPORTED
    }

    // ── Value Objects ────────────────────────────────────────────────────────

    public static final class StorageRoot {
        @NonNull public final RootType type;
        @NonNull public final File directory;
        public final boolean removable;
        public final boolean available;

        public StorageRoot(@NonNull RootType type,
                           @NonNull File directory,
                           boolean removable,
                           boolean available) {
            this.type = type;
            this.directory = directory;
            this.removable = removable;
            this.available = available;
        }
    }

    /**
     * Immutable descriptor for a resolved PDF or Image ready for native rendering.
     */
    public static final class ResolvedDocument {
        @NonNull public final DocumentKind kind;
        @NonNull public final RootType rootType;
        @Nullable public final String resourceId;
        @NonNull public final String slugId;
        @NonNull public final String displayName;
        @NonNull public final String mimeType;
        public final long byteLength;
        public final boolean isRemovableStorage;
        public final boolean isExternal;

        @Nullable private final File backingFile;
        @Nullable private final Uri sourceUri;
        @Nullable private final File itemDirectory;

        private ResolvedDocument(@NonNull DocumentKind kind,
                                 @NonNull RootType rootType,
                                 @Nullable String resourceId,
                                 @NonNull String slugId,
                                 @NonNull String displayName,
                                 @NonNull String mimeType,
                                 long byteLength,
                                 boolean isRemovableStorage,
                                 boolean isExternal,
                                 @Nullable File backingFile,
                                 @Nullable Uri sourceUri,
                                 @Nullable File itemDirectory) {
            this.kind = kind;
            this.rootType = rootType;
            this.resourceId = resourceId;
            this.slugId = slugId;
            this.displayName = displayName;
            this.mimeType = mimeType;
            this.byteLength = byteLength;
            this.isRemovableStorage = isRemovableStorage;
            this.isExternal = isExternal;
            this.backingFile = backingFile;
            this.sourceUri = sourceUri;
            this.itemDirectory = itemDirectory;
        }

        public boolean isPdf() {
            return kind == DocumentKind.PDF;
        }

        public boolean isImage() {
            return kind == DocumentKind.RASTER_IMAGE || kind == DocumentKind.SVG_IMAGE;
        }

        public boolean isExternal() {
            return isExternal;
        }

        public boolean hasBackingFile() {
            return backingFile != null && backingFile.exists();
        }

        @Nullable
        public File getBackingFile() {
            return backingFile;
        }

        @Nullable
        public Uri getSourceUri() {
            return sourceUri;
        }

        /**
         * Opens a fresh, read-only, guaranteed-seekable ParcelFileDescriptor.
         * Caller (or PdfRenderer) owns the returned descriptor and must close it.
         */
        @NonNull
        public ParcelFileDescriptor openSeekableReadOnlyPfd(@NonNull Context context) throws IOException {
            if (backingFile != null) {
                if (!backingFile.exists() || !backingFile.canRead()) {
                    throw new IOException("Backing file not readable: " + backingFile.getAbsolutePath());
                }
                return ParcelFileDescriptor.open(backingFile, ParcelFileDescriptor.MODE_READ_ONLY);
            }

            if (sourceUri != null) {
                ContentResolver cr = context.getApplicationContext().getContentResolver();
                ParcelFileDescriptor pfd = cr.openFileDescriptor(sourceUri, "r");
                if (pfd == null) {
                    throw new IOException("ContentResolver returned null PFD for: " + sourceUri);
                }
                if (isSeekable(pfd)) {
                    return pfd;
                }
                // Provider returned a non-seekable pipe (e.g., cloud stream).
                // Spool once to cache/tmp so PdfRenderer can mmap/seek safely.
                try {
                    File spooled = spoolPfdToTempFile(context, pfd);
                    return ParcelFileDescriptor.open(spooled, ParcelFileDescriptor.MODE_READ_ONLY);
                } finally {
                    safeClose(pfd);
                }
            }

            throw new IOException("ResolvedDocument has neither backingFile nor sourceUri");
        }

        /**
         * Opens a fresh InputStream for streaming decoders (e.g., SVG parser or EXIF reader).
         */
        @NonNull
        public InputStream openInputStream(@NonNull Context context) throws IOException {
            if (backingFile != null) {
                return new FileInputStream(backingFile);
            }
            if (sourceUri != null) {
                InputStream is = context.getApplicationContext()
                        .getContentResolver()
                        .openInputStream(sourceUri);
                if (is == null) {
                    throw new IOException("Unable to open InputStream for: " + sourceUri);
                }
                return is;
            }
            throw new IOException("No stream source available");
        }

        /**
         * Returns the directory holding sidecar files (annotations, bookmarks, reading state).
         * For MedVix library items, this is content/files/blobs/{slugId}/ so deleting the
         * download in resource-browser.js automatically purges all sidecar data.
         */
        @NonNull
        public File getOrCreateSidecarDirectory(@NonNull Context context) {
            if (itemDirectory != null) {
                if (!itemDirectory.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    itemDirectory.mkdirs();
                }
                return itemDirectory;
            }
            File writeRoot = resolveActiveWriteRoot(context);
            File dir = new File(writeRoot, DIR_EXT_SIDECARS + "/" + slugId);
            if (!dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            return dir;
        }

        /**
         * Safely resolves a sanitized sidecar file inside this document's sidecar directory.
         */
        @NonNull
        public File getSidecarFile(@NonNull Context context, @NonNull String filename) {
            String cleanName = sanitizeFilename(filename);
            return new File(getOrCreateSidecarDirectory(context), cleanName);
        }

        /**
         * Searches all storage roots for an existing cached thumbnail for this resource.
         */
        @Nullable
        public File findExistingThumbnail(@NonNull Context context) {
            if (TextUtils.isEmpty(resourceId)) return null;
            List<StorageRoot> roots = getOrderedSearchRoots(context);
            for (StorageRoot root : roots) {
                if (!root.available) continue;
                for (String ext : THUMBNAIL_EXTS) {
                    File candidate = new File(root.directory, thumbnailRelativePath(resourceId, ext));
                    if (candidate.isFile() && candidate.length() > 0) {
                        return candidate;
                    }
                }
            }
            return null;
        }

        /**
         * Returns the writable thumbnail File in the active write root (creating parent dirs).
         */
        @NonNull
        public File getWritableThumbnailTarget(@NonNull Context context, @NonNull String ext) {
            String safeExt = TextUtils.isEmpty(ext) ? "jpg" : ext.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (safeExt.isEmpty()) safeExt = "jpg";
            String key = !TextUtils.isEmpty(resourceId) ? resourceId : slugId;
            File writeRoot = resolveActiveWriteRoot(context);
            File target = new File(writeRoot, thumbnailRelativePath(key, safeExt));
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            return target;
        }
    }

    // ── Slug & Relative Path Computation (mirrors scripts/db/layout.js) ──────

    /**
     * Exact Java equivalent of layout.slug(input) in scripts/db/layout.js:
     * lowerCase -> NFKD -> strip [\u0300-\u036f] -> [^a-z0-9]+ to '-' -> trim '-' -> max 64 chars.
     */
    @NonNull
    public static String slug(@Nullable String input) {
        String raw = (input == null || input.isEmpty()) ? "unknown" : input;
        String lower = raw.toLowerCase(Locale.ROOT);
        String normalized = Normalizer.normalize(lower, Normalizer.Form.NFKD);
        String noAccents = normalized.replaceAll("[\\u0300-\\u036f]", "");
        String dashed = noAccents.replaceAll("[^a-z0-9]+", "-");
        String trimmed = dashed.replaceAll("^-+|-+$", "");
        if (trimmed.length() > 64) {
            trimmed = trimmed.substring(0, 64);
        }
        return trimmed.isEmpty() ? "unknown" : trimmed;
    }

    @NonNull
    public static String itemRelativeDir(@NonNull String namespace,
                                         @NonNull String collection,
                                         @NonNull String itemId) {
        return DIR_CONTENT + "/" + slug(namespace) + "/" + slug(collection) + "/" + slug(itemId);
    }

    @NonNull
    public static String blobRelativePath(@NonNull String resourceId) {
        return itemRelativeDir(NS_FILES, COL_BLOBS, resourceId) + "/" + BLOB_FILENAME;
    }

    @NonNull
    public static String publicAssetRelativePath(@NonNull String assetKey) {
        return itemRelativeDir(NS_PUBLIC_ASSETS, COL_ASSETS, assetKey) + "/" + BLOB_FILENAME;
    }

    @NonNull
    public static String thumbnailRelativePath(@NonNull String key, @NonNull String ext) {
        return DIR_THUMBNAILS + "/" + slug(key) + "." + ext;
    }

    // ── Storage Root Discovery ───────────────────────────────────────────────

    /**
     * Discovers all available MedVix storage roots ('internal', 'external', 'sdcard').
     */
    @NonNull
    public static List<StorageRoot> getAvailableRoots(@NonNull Context context) {
        Context appCtx = context.getApplicationContext();
        Map<RootType, StorageRoot> found = new LinkedHashMap<>();

        // 1. Internal app-private storage (always available)
        File internalDir = resolveBridgeRootDir(appCtx.getFilesDir());
        found.put(RootType.INTERNAL, new StorageRoot(
                RootType.INTERNAL,
                internalDir,
                false,
                internalDir.exists() || internalDir.mkdirs()
        ));

        // 2. External & Removable SD Card directories
        File[] extDirs = appCtx.getExternalFilesDirs(null);
        if (extDirs != null) {
            for (File dir : extDirs) {
                if (dir == null) continue;
                String state = Environment.getExternalStorageState(dir);
                boolean mounted = Environment.MEDIA_MOUNTED.equals(state)
                        || Environment.MEDIA_MOUNTED_READ_ONLY.equals(state);
                if (!mounted) continue;

                boolean removable = false;
                try {
                    removable = Environment.isExternalStorageRemovable(dir);
                } catch (IllegalArgumentException ignored) {
                    // Some OEM ROMs throw if volume is transient
                }

                File resolved = resolveBridgeRootDir(dir);
                if (removable) {
                    if (!found.containsKey(RootType.SDCARD)) {
                        found.put(RootType.SDCARD, new StorageRoot(
                                RootType.SDCARD, resolved, true, true
                        ));
                    }
                } else {
                    if (!found.containsKey(RootType.EXTERNAL)) {
                        found.put(RootType.EXTERNAL, new StorageRoot(
                                RootType.EXTERNAL, resolved, false, true
                        ));
                    }
                }
            }
        }

        return new ArrayList<>(found.values());
    }

    /**
     * Returns roots ordered with the user's preferred root first, followed by all
     * remaining roots so cross-root reads always find files regardless of where they
     * were originally downloaded.
     */
    @NonNull
    public static List<StorageRoot> getOrderedSearchRoots(@NonNull Context context) {
        List<StorageRoot> all = getAvailableRoots(context);
        RootType preferred = getPreferredRootType(context);

        List<StorageRoot> ordered = new ArrayList<>(all.size());
        for (StorageRoot root : all) {
            if (root.type == preferred) {
                ordered.add(root);
            }
        }
        for (StorageRoot root : all) {
            if (root.type != preferred) {
                ordered.add(root);
            }
        }
        return ordered;
    }

    /**
     * Resolves the current active write root (falling back to INTERNAL if SDCARD is unmounted).
     */
    @NonNull
    public static File resolveActiveWriteRoot(@NonNull Context context) {
        RootType preferred = getPreferredRootType(context);
        List<StorageRoot> roots = getAvailableRoots(context);
        for (StorageRoot root : roots) {
            if (root.type == preferred && root.available && root.directory.canWrite()) {
                return root.directory;
            }
        }
        for (StorageRoot root : roots) {
            if (root.type == RootType.INTERNAL && root.available) {
                return root.directory;
            }
        }
        return context.getApplicationContext().getFilesDir();
    }

    @NonNull
    public static RootType getPreferredRootType(@NonNull Context context) {
        Context appCtx = context.getApplicationContext();
        for (String prefName : PREF_FILE_CANDIDATES) {
            SharedPreferences prefs = appCtx.getSharedPreferences(prefName, Context.MODE_PRIVATE);
            for (String key : PREF_KEY_CANDIDATES) {
                String val = prefs.getString(key, null);
                if (!TextUtils.isEmpty(val)) {
                    return RootType.fromId(val);
                }
            }
        }
        return RootType.INTERNAL;
    }

    /**
     * Handles both direct root layout ({baseDir}/content/...) and optional named
     * bridge subdir ({baseDir}/medvix/content/...) if present.
     */
    @NonNull
    private static File resolveBridgeRootDir(@NonNull File baseDir) {
        File directContent = new File(baseDir, DIR_CONTENT);
        if (directContent.exists()) {
            return baseDir;
        }
        File subDir = new File(baseDir, "medvix");
        if (new File(subDir, DIR_CONTENT).exists()) {
            return subDir;
        }
        return baseDir;
    }

    // ── Primary Resolution Entry Points ──────────────────────────────────────

    /**
     * Resolves a downloaded MedVix library resource by its Convex resourceId across
     * all three storage roots ('internal', 'external', 'sdcard').
     * Always marks the resulting document as internal (isExternal = false).
     */
    @Nullable
    public static ResolvedDocument resolveByResourceId(@NonNull Context context,
                                                       @NonNull String resourceId,
                                                       @Nullable String titleHint,
                                                       @Nullable String mimeHint) {
        if (TextUtils.isEmpty(resourceId)) return null;

        String slugId = slug(resourceId);
        String blobRel = blobRelativePath(resourceId);
        String assetRel = publicAssetRelativePath(resourceId);
        List<StorageRoot> roots = getOrderedSearchRoots(context);

        for (StorageRoot root : roots) {
            if (!root.available) continue;

            File candidate = new File(root.directory, blobRel);
            if (candidate.isFile() && candidate.length() > 0) {
                return buildFromLocalFile(
                        candidate,
                        root.type,
                        resourceId,
                        slugId,
                        titleHint,
                        mimeHint,
                        root.removable,
                        false,
                        candidate.getParentFile()
                );
            }

            File assetCandidate = new File(root.directory, assetRel);
            if (assetCandidate.isFile() && assetCandidate.length() > 0) {
                return buildFromLocalFile(
                        assetCandidate,
                        root.type,
                        resourceId,
                        slugId,
                        titleHint,
                        mimeHint,
                        root.removable,
                        false,
                        assetCandidate.getParentFile()
                );
            }
        }

        return null;
    }

    /**
     * Resolves a document stored at a MedVix-relative path (e.g., "content/files/blobs/.../blob.bin")
     * across all three storage roots.
     * Always marks the resulting document as internal (isExternal = false).
     */
    @Nullable
    public static ResolvedDocument resolveByRelativePath(@NonNull Context context,
                                                         @NonNull String relativePath,
                                                         @Nullable String titleHint,
                                                         @Nullable String mimeHint) {
        String cleanRel = sanitizeRelativePath(relativePath);
        if (cleanRel == null) return null;

        List<StorageRoot> roots = getOrderedSearchRoots(context);
        for (StorageRoot root : roots) {
            if (!root.available) continue;
            File candidate = new File(root.directory, cleanRel);
            if (candidate.isFile() && candidate.length() > 0) {
                String derivedSlug = slug(candidate.getParentFile() != null
                        ? candidate.getParentFile().getName()
                        : candidate.getName());
                return buildFromLocalFile(
                        candidate,
                        root.type,
                        null,
                        derivedSlug,
                        titleHint,
                        mimeHint,
                        root.removable,
                        false,
                        candidate.getParentFile()
                );
            }
        }
        return null;
    }

    /**
     * Resolves an Android Uri (content:// or file://) opened from an external intent,
     * file picker, or share sheet.
     * Marks isExternal = true unless the URI resolves inside MedVix's own private storage/authority.
     */
    @Nullable
    public static ResolvedDocument resolveByUri(@NonNull Context context,
                                              @NonNull Uri uri,
                                              @Nullable String titleHint,
                                              @Nullable String mimeHint) {
        String scheme = uri.getScheme();
        if ("file".equalsIgnoreCase(scheme)) {
            String path = uri.getPath();
            if (TextUtils.isEmpty(path)) return null;
            return resolveByAbsolutePath(context, path, titleHint, mimeHint);
        }

        if (!"content".equalsIgnoreCase(scheme)) {
            return null;
        }

        Context appCtx = context.getApplicationContext();
        ContentResolver cr = appCtx.getContentResolver();

        String queriedName = null;
        long queriedSize = -1L;

        try (Cursor cursor = cr.query(uri,
                new String[]{ OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE },
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                    queriedName = cursor.getString(nameIdx);
                }
                int sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                    queriedSize = cursor.getLong(sizeIdx);
                }
            }
        } catch (Exception ignored) {
            // Some custom providers do not support projection queries
        }

        String resolvedMime = !TextUtils.isEmpty(mimeHint) ? mimeHint : cr.getType(uri);
        String displayName = !TextUtils.isEmpty(titleHint)
                ? titleHint
                : (!TextUtils.isEmpty(queriedName) ? queriedName : "Document");

        try (ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "r")) {
            if (pfd == null) return null;

            long statSize = pfd.getStatSize();
            long byteLength = statSize > 0 ? statSize : queriedSize;

            byte[] header = readHeaderBytes(pfd.getFileDescriptor());
            SniffResult sniff = classifyHeader(header, resolvedMime, displayName);
            String slugId = "ext-" + shortSha256(uri.toString());
            boolean external = !isAppPrivateUri(appCtx, uri);

            return new ResolvedDocument(
                    sniff.kind,
                    RootType.URI,
                    null,
                    slugId,
                    displayName,
                    sniff.mimeType,
                    byteLength,
                    false,
                    external,
                    null,
                    uri,
                    null
            );
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Resolves a direct filesystem path (e.g., from file:// URI or native cache).
     * Marks isExternal = true only when the canonical path lies outside MedVix's private storage.
     */
    @Nullable
    public static ResolvedDocument resolveByAbsolutePath(@NonNull Context context,
                                                       @NonNull String absolutePath,
                                                       @Nullable String titleHint,
                                                       @Nullable String mimeHint) {
        if (TextUtils.isEmpty(absolutePath)) return null;
        try {
            File file = new File(absolutePath).getCanonicalFile();
            if (!file.isFile() || !file.canRead() || file.length() <= 0) {
                return null;
            }
            boolean removable = false;
            try {
                removable = Environment.isExternalStorageRemovable(file);
            } catch (IllegalArgumentException ignored) {
            }
            boolean external = !isAppPrivateFile(context, file);
            String slugId = "file-" + shortSha256(file.getAbsolutePath());
            return buildFromLocalFile(
                    file,
                    removable ? RootType.SDCARD : RootType.EXTERNAL_FILE,
                    null,
                    slugId,
                    titleHint,
                    mimeHint,
                    removable,
                    external,
                    null
            );
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isAppPrivateUri(@NonNull Context appCtx, @NonNull Uri uri) {
        String authority = uri.getAuthority();
        String pkg = appCtx.getPackageName();
        return authority != null && !TextUtils.isEmpty(pkg)
                && (authority.equals(pkg) || authority.startsWith(pkg + "."));
    }

    private static boolean isAppPrivateFile(@NonNull Context context, @NonNull File canonicalFile) {
        Context appCtx = context.getApplicationContext();
        if (isWithinDirectory(canonicalFile, appCtx.getFilesDir())) return true;
        if (isWithinDirectory(canonicalFile, appCtx.getCacheDir())) return true;

        File[] extFiles = appCtx.getExternalFilesDirs(null);
        if (extFiles != null) {
            for (File dir : extFiles) {
                if (isWithinDirectory(canonicalFile, dir)) return true;
            }
        }
        File[] extCaches = appCtx.getExternalCacheDirs();
        if (extCaches != null) {
            for (File dir : extCaches) {
                if (isWithinDirectory(canonicalFile, dir)) return true;
            }
        }
        return false;
    }

    private static boolean isWithinDirectory(@NonNull File targetCanonical, @Nullable File rootDir) {
        if (rootDir == null) return false;
        try {
            File canonicalRoot = rootDir.getCanonicalFile();
            String rootPath = canonicalRoot.getPath();
            String targetPath = targetCanonical.getPath();
            if (targetPath.equals(rootPath)) {
                return true;
            }
            String prefix = rootPath.endsWith(File.separator) ? rootPath : rootPath + File.separator;
            return targetPath.startsWith(prefix);
        } catch (IOException e) {
            return true; // Fail closed if path resolution fails
        }
    }

    // ── Internal File Builder & Magic-Byte Sniffer ───────────────────────────

    @Nullable
    private static ResolvedDocument buildFromLocalFile(@NonNull File file,
                                                       @NonNull RootType rootType,
                                                       @Nullable String resourceId,
                                                       @NonNull String slugId,
                                                       @Nullable String titleHint,
                                                       @Nullable String mimeHint,
                                                       boolean removable,
                                                       boolean isExternal,
                                                       @Nullable File itemDir) {
        byte[] header = readHeaderBytes(file);
        String displayName = !TextUtils.isEmpty(titleHint) ? titleHint : file.getName();
        SniffResult sniff = classifyHeader(header, mimeHint, displayName);

        return new ResolvedDocument(
                sniff.kind,
                rootType,
                resourceId,
                slugId,
                displayName,
                sniff.mimeType,
                file.length(),
                removable,
                isExternal,
                file,
                null,
                itemDir
        );
    }

    private static final class SniffResult {
        @NonNull final DocumentKind kind;
        @NonNull final String mimeType;

        SniffResult(@NonNull DocumentKind kind, @NonNull String mimeType) {
            this.kind = kind;
            this.mimeType = mimeType;
        }
    }

    /**
     * Inspects magic bytes to determine exact document/image format regardless of
     * filename ('blob.bin') or stale metadata.
     */
    @NonNull
    static SniffResult classifyHeader(@NonNull byte[] header,
                                      @Nullable String mimeHint,
                                      @Nullable String nameHint) {
        int len = header.length;

        // 1. PDF: "%PDF-" anywhere within the first 1024 bytes (per PDF 1.7 spec)
        if (containsAsciiSequence(header, new byte[]{ 0x25, 0x50, 0x44, 0x46, 0x2D }, HEADER_SNIFF_BYTES)) {
            return new SniffResult(DocumentKind.PDF, "application/pdf");
        }

        // 2. PNG: 89 50 4E 47 0D 0A 1A 0A
        if (len >= 8
                && (header[0] & 0xFF) == 0x89 && header[1] == 0x50
                && header[2] == 0x4E && header[3] == 0x47
                && header[4] == 0x0D && header[5] == 0x0A
                && header[6] == 0x1A && header[7] == 0x0A) {
            return new SniffResult(DocumentKind.RASTER_IMAGE, "image/png");
        }

        // 3. JPEG: FF D8 FF
        if (len >= 3
                && (header[0] & 0xFF) == 0xFF
                && (header[1] & 0xFF) == 0xD8
                && (header[2] & 0xFF) == 0xFF) {
            return new SniffResult(DocumentKind.RASTER_IMAGE, "image/jpeg");
        }

        // 4. WebP: "RIFF" + 4 bytes + "WEBP"
        if (len >= 12
                && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') {
            return new SniffResult(DocumentKind.RASTER_IMAGE, "image/webp");
        }

        // 5. GIF: "GIF87a" or "GIF89a"
        if (len >= 6
                && header[0] == 'G' && header[1] == 'I' && header[2] == 'F' && header[3] == '8'
                && (header[4] == '7' || header[4] == '9') && header[5] == 'a') {
            return new SniffResult(DocumentKind.RASTER_IMAGE, "image/gif");
        }

        // 6. BMP: "BM"
        if (len >= 2 && header[0] == 'B' && header[1] == 'M') {
            return new SniffResult(DocumentKind.RASTER_IMAGE, "image/bmp");
        }

        // 7. TIFF: "II*\0" or "MM\0*"
        if (len >= 4
                && ((header[0] == 'I' && header[1] == 'I' && header[2] == 0x2A && header[3] == 0x00)
                || (header[0] == 'M' && header[1] == 'M' && header[2] == 0x00 && header[3] == 0x2A))) {
            return new SniffResult(DocumentKind.RASTER_IMAGE, "image/tiff");
        }

        // 8. ISO Base Media File Format (HEIC / HEIF / AVIF): "ftyp" at offset 4
        if (len >= 12
                && header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p') {
            String brand = new String(header, 8, 4, StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);
            if (brand.startsWith("avif") || brand.startsWith("avis")) {
                return new SniffResult(DocumentKind.RASTER_IMAGE, "image/avif");
            }
            if (brand.startsWith("heic") || brand.startsWith("heix")
                    || brand.startsWith("hevc") || brand.startsWith("mif1")
                    || brand.startsWith("msf1")) {
                return new SniffResult(DocumentKind.RASTER_IMAGE, "image/heic");
            }
        }

        // 9. SVG / XML vector graphic
        if (len > 0) {
            String headText = new String(header, 0, Math.min(len, 512), StandardCharsets.UTF_8)
                    .trim()
                    .toLowerCase(Locale.ROOT);
            if (headText.startsWith("<svg")
                    || (headText.startsWith("<?xml") && headText.contains("<svg"))) {
                return new SniffResult(DocumentKind.SVG_IMAGE, "image/svg+xml");
            }
        }

        // 10. Fallback to explicit MIME / filename hints if header was inconclusive
        String normMime = mimeHint != null ? mimeHint.trim().toLowerCase(Locale.ROOT) : "";
        if ("application/pdf".equals(normMime) || "pdf".equals(normMime)) {
            return new SniffResult(DocumentKind.PDF, "application/pdf");
        }
        if ("image/svg+xml".equals(normMime) || "svg".equals(normMime)) {
            return new SniffResult(DocumentKind.SVG_IMAGE, "image/svg+xml");
        }
        if (normMime.startsWith("image/")) {
            return new SniffResult(DocumentKind.RASTER_IMAGE, normMime);
        }

        if (nameHint != null) {
            String lowerName = nameHint.toLowerCase(Locale.ROOT);
            if (lowerName.endsWith(".pdf")) {
                return new SniffResult(DocumentKind.PDF, "application/pdf");
            }
            if (lowerName.endsWith(".svg")) {
                return new SniffResult(DocumentKind.SVG_IMAGE, "image/svg+xml");
            }
            if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) {
                return new SniffResult(DocumentKind.RASTER_IMAGE, "image/jpeg");
            }
            if (lowerName.endsWith(".png")) {
                return new SniffResult(DocumentKind.RASTER_IMAGE, "image/png");
            }
            if (lowerName.endsWith(".webp")) {
                return new SniffResult(DocumentKind.RASTER_IMAGE, "image/webp");
            }
        }

        return new SniffResult(
                DocumentKind.UNSUPPORTED,
                !TextUtils.isEmpty(normMime) ? normMime : "application/octet-stream"
        );
    }

    // ── Low-Level FileDescriptor & Utility Helpers ───────────────────────────

    @NonNull
    private static byte[] readHeaderBytes(@NonNull File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[HEADER_SNIFF_BYTES];
            int total = 0;
            while (total < buf.length) {
                int read = fis.read(buf, total, buf.length - total);
                if (read <= 0) break;
                total += read;
            }
            if (total == buf.length) return buf;
            byte[] trimmed = new byte[Math.max(0, total)];
            System.arraycopy(buf, 0, trimmed, 0, trimmed.length);
            return trimmed;
        } catch (IOException e) {
            return new byte[0];
        }
    }

    @NonNull
    private static byte[] readHeaderBytes(@NonNull FileDescriptor fd) {
        byte[] buf = new byte[HEADER_SNIFF_BYTES];
        try {
            // pread reads from offset 0 without changing the FD's current seek pointer
            int read = Os.pread(fd, buf, 0, buf.length, 0);
            if (read <= 0) return new byte[0];
            if (read == buf.length) return buf;
            byte[] trimmed = new byte[read];
            System.arraycopy(buf, 0, trimmed, 0, read);
            return trimmed;
        } catch (ErrnoException | java.io.InterruptedIOException e) {
            // Non-seekable pipe FD: fall back to buffered stream read
            try {
                FileInputStream fis = new FileInputStream(fd);
                int read = fis.read(buf, 0, buf.length);
                if (read <= 0) return new byte[0];
                byte[] trimmed = new byte[read];
                System.arraycopy(buf, 0, trimmed, 0, read);
                return trimmed;
            } catch (IOException ioEx) {
                return new byte[0];
            }
        }
    }

    private static boolean isSeekable(@NonNull ParcelFileDescriptor pfd) {
        try {
            Os.lseek(pfd.getFileDescriptor(), 0, OsConstants.SEEK_CUR);
            return true;
        } catch (ErrnoException e) {
            return false;
        }
    }

    @NonNull
    private static File spoolPfdToTempFile(@NonNull Context context,
                                           @NonNull ParcelFileDescriptor pfd) throws IOException {
        File writeRoot = resolveActiveWriteRoot(context);
        File tmpDir = new File(writeRoot, DIR_TMP);
        if (!tmpDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            tmpDir.mkdirs();
        }
        File outFile = new File(tmpDir, "spool-" + UUID.randomUUID() + ".part");
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd.dup());
             FileOutputStream out = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[SPOOL_BUFFER_BYTES];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            out.getFD().sync();
        }
        return outFile;
    }

    /**
     * Sweeps temporary spooled files in cache/tmp older than maxAgeMs across all roots.
     */
    public static void cleanupTempFiles(@NonNull Context context, long maxAgeMs) {
        long cutoff = System.currentTimeMillis() - Math.max(0L, maxAgeMs);
        for (StorageRoot root : getAvailableRoots(context)) {
            if (!root.available) continue;
            File tmpDir = new File(root.directory, DIR_TMP);
            File[] files = tmpDir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (f.isFile() && f.getName().endsWith(".part") && f.lastModified() < cutoff) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
    }

    @Nullable
    private static String sanitizeRelativePath(@Nullable String rel) {
        if (TextUtils.isEmpty(rel)) return null;
        String clean = rel.replace('\\', '/').trim();
        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        if (clean.isEmpty() || clean.contains("..")) return null;
        return clean;
    }

    @NonNull
    private static String sanitizeFilename(@NonNull String filename) {
        String name = new File(filename).getName().trim();
        name = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        return name.isEmpty() ? "sidecar.json" : name;
    }

    private static boolean containsAsciiSequence(@NonNull byte[] haystack,
                                                 @NonNull byte[] needle,
                                                 int maxScan) {
        int limit = Math.min(haystack.length, maxScan) - needle.length;
        for (int i = 0; i <= limit; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) return true;
        }
        return false;
    }

    @NonNull
    private static String shortSha256(@NonNull String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(24);
            for (int i = 0; i < 12; i++) {
                sb.append(String.format(Locale.ROOT, "%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    public static void safeClose(@Nullable ParcelFileDescriptor pfd) {
        if (pfd != null) {
            try {
                pfd.close();
            } catch (IOException ignored) {
            }
        }
    }
}