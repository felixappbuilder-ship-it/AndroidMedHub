package com.medhurb.app.viewer.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.medhurb.app.viewer.engine.ImageDocumentEngine;
import com.medhurb.app.viewer.engine.PdfDocumentEngine;
import com.medhurb.app.viewer.engine.PdfDocumentEngine.DocumentLink;
import com.medhurb.app.viewer.engine.PdfDocumentEngine.PdfPasswordRequiredException;
import com.medhurb.app.viewer.engine.PdfDocumentEngine.SearchMatch;
import com.medhurb.app.viewer.engine.PdfDocumentEngine.TextSelectionResult;
import com.medhurb.app.viewer.print.MedvixDocumentPrintAdapter;
import com.medhurb.app.viewer.render.BitmapMemoryPool;
import com.medhurb.app.viewer.storage.MedvixDocumentStore;
import com.medhurb.app.viewer.storage.MedvixDocumentStore.BookmarkItem;
import com.medhurb.app.viewer.storage.MedvixDocumentStore.FitMode;
import com.medhurb.app.viewer.storage.MedvixDocumentStore.ReaderState;
import com.medhurb.app.viewer.storage.MedvixDocumentStore.ReadingTheme;
import com.medhurb.app.viewer.storage.MedvixDocumentStore.ScrollMode;
import com.medhurb.app.viewer.storage.MedvixDocumentStore.TextMarkupAnnotation;
import com.medhurb.app.viewer.storage.MedvixDocumentStore.TextMarkupStyle;
import com.medhurb.app.viewer.storage.MedvixStorageResolver;
import com.medhurb.app.viewer.storage.MedvixStorageResolver.ResolvedDocument;
import com.medhurb.app.viewer.ui.DocumentCanvasView.InteractionMode;
import com.medhurb.app.viewer.ui.DocumentCanvasView.ViewerInteractionListener;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MedvixPdfActivity
 * ============================================================================
 * Full-screen native Android viewer Activity for MedVix PDFs and high-resolution
 * Images (raster, animated GIF/WebP, and vector SVG).
 *
 * Combines:
 *  1. Complete native Android viewer capabilities:
 *     - Edge-to-edge WindowCompat layout with smooth, hardware-composited 260ms
 *       top & bottom toolbar glide animations.
 *     - Zero-copy document loading from MedvixStorageResolver across internal,
 *       external, removable SD card, and external content:// URIs.
 *     - Encrypted PDF password prompt loop, streaming document search, native
 *       text selection with floating highlight/underline/note/copy actions,
 *       finger/stylus ink & eraser with Undo/Redo, bookmarks, print, share,
 *       and SD-card eject safety.
 *  2. Complete HTML/CSS viewer UI system (resource-browser.css / viewer.html):
 *     - Exact MedVix light (#f5f7fa / #ffffff) & dark (#1a1a2e / #25253a)
 *       surface palette, #2563eb accent, 3dp page gutter, and 1dp hairlines.
 *     - [← Back] pill button, centered title, and [☰] dropdown menu (Sidebar,
 *       Search, Page view toggle, Open local file, Fit to width, Reset zoom,
 *       Fullscreen, Theme, Keep Screen On, Print, Share).
 *     - Collapsible inline search bar with Match Case (Aa) & Whole Word (“”)
 *       toggles plus prev/next match arrows.
 *     - Left slide-over drawer (80% width, max 320dp, 40% scrim) with
 *       Bookmarks & Notes, Search Results, and Document Info panels.
 *     - Combined footer with [◀] Page [editable input] of N [▶], [−] 100% [+],
 *       SeekBar scrubber, and quick-action chips.
 *     - Centered .viewer-preview-blocked paywall card on locked preview pages.
 */
public class MedvixPdfActivity extends AppCompatActivity implements ViewerInteractionListener {

    // ── Intent Extras & Result Keys ──────────────────────────────────────────

    public static final String EXTRA_RESOURCE_ID            = "medvix.extra.RESOURCE_ID";
    public static final String EXTRA_RELATIVE_PATH          = "medvix.extra.RELATIVE_PATH";
    public static final String EXTRA_ABSOLUTE_PATH          = "medvix.extra.ABSOLUTE_PATH";
    public static final String EXTRA_TITLE                  = "medvix.extra.TITLE";
    public static final String EXTRA_MIME_TYPE              = "medvix.extra.MIME_TYPE";
    public static final String EXTRA_PREVIEW_MODE           = "medvix.extra.PREVIEW_MODE";
    public static final String EXTRA_INITIAL_PAGE           = "medvix.extra.INITIAL_PAGE";
    public static final String EXTRA_THEME                  = "medvix.extra.THEME";

    public static final String EXTRA_RESULT_ACTION          = "medvix.result.ACTION";
    public static final String EXTRA_RESULT_RESOURCE_ID     = "medvix.result.RESOURCE_ID";
    public static final String EXTRA_RESULT_LAST_PAGE       = "medvix.result.LAST_PAGE";
    public static final String EXTRA_RESULT_TOTAL_PAGES     = "medvix.result.TOTAL_PAGES";

    public static final String RESULT_ACTION_CLOSED         = "closed";
    public static final String RESULT_ACTION_UNLOCK_PREMIUM = "unlock_subscription";

    // ── Animation & Annotation Constants ─────────────────────────────────────

    private static final long CHROME_ANIM_DURATION_MS       = 260L;
    private static final long DRAWER_ANIM_DURATION_MS       = 250L;
    private static final long CHROME_INACTIVITY_TIMEOUT_MS  = 3500L;
    private static final FastOutSlowInInterpolator CHROME_INTERPOLATOR = new FastOutSlowInInterpolator();

    private static final int COLOR_HIGHLIGHT_YELLOW         = Color.argb(115, 255, 235, 59);
    private static final int COLOR_HIGHLIGHT_GREEN          = Color.argb(110, 76, 175, 80);

    private static final int COLOR_INK_RED                  = Color.rgb(220, 38, 38);
    private static final int COLOR_INK_BLUE                 = Color.rgb(37, 99, 235);
    private static final int COLOR_INK_GREEN                = Color.rgb(22, 163, 74);
    private static final int COLOR_INK_BLACK                = Color.rgb(17, 24, 39);

    // ── HTML/CSS Palette Tokens (resource-browser.css) ───────────────────────

    private static final class UiPalette {
        final boolean dark;
        final int bgPrimary;
        final int bgSecondary;
        final int bgCard;
        final int textPrimary;
        final int textSecondary;
        final int textMuted;
        final int border;
        final int borderLight;
        final int accent;
        final int warning;
        final int scrim;

        UiPalette(boolean dark) {
            this.dark = dark;
            if (dark) {
                bgPrimary     = Color.parseColor("#1a1a2e");
                bgSecondary   = Color.parseColor("#2a2a3e");
                bgCard        = Color.parseColor("#25253a");
                textPrimary   = Color.parseColor("#eeeeee");
                textSecondary = Color.parseColor("#cccccc");
                textMuted     = Color.parseColor("#999999");
                border        = Color.parseColor("#444444");
                borderLight   = Color.parseColor("#3a3a4e");
            } else {
                bgPrimary     = Color.parseColor("#f5f7fa");
                bgSecondary   = Color.parseColor("#e9ecf0");
                bgCard        = Color.parseColor("#ffffff");
                textPrimary   = Color.parseColor("#1e1e2f");
                textSecondary = Color.parseColor("#444444");
                textMuted     = Color.parseColor("#777777");
                border        = Color.parseColor("#d0d4dc");
                borderLight   = Color.parseColor("#e0e4ea");
            }
            accent  = Color.parseColor("#2563eb");
            warning = Color.parseColor("#f59e0b");
            scrim   = Color.argb(102, 0, 0, 0); // rgba(0, 0, 0, 0.4)
        }
    }

    private enum DrawerPanel {
        OUTLINE,
        NOTES_BOOKMARKS,
        SEARCH_RESULTS,
        DOCUMENT_INFO
    }

    // ── Static Intent Builder ────────────────────────────────────────────────

    @NonNull
    public static Intent createResourceIntent(@NonNull Context context,
                                              @NonNull String resourceId,
                                              @Nullable String title,
                                              @Nullable String mimeType,
                                              boolean previewMode,
                                              int initialPageIndex) {
        Intent intent = new Intent(context, MedvixPdfActivity.class);
        intent.putExtra(EXTRA_RESOURCE_ID, resourceId);
        if (!TextUtils.isEmpty(title)) intent.putExtra(EXTRA_TITLE, title);
        if (!TextUtils.isEmpty(mimeType)) intent.putExtra(EXTRA_MIME_TYPE, mimeType);
        intent.putExtra(EXTRA_PREVIEW_MODE, previewMode);
        if (initialPageIndex >= 0) intent.putExtra(EXTRA_INITIAL_PAGE, initialPageIndex);
        return intent;
    }

    // ── Core Engines & Storage State ─────────────────────────────────────────

    @Nullable private BitmapMemoryPool memoryPool;
    @Nullable private ResolvedDocument resolvedDoc;
    @Nullable private PdfDocumentEngine pdfEngine;
    @Nullable private ImageDocumentEngine imageEngine;
    @Nullable private MedvixDocumentStore documentStore;

    private final ExecutorService openExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MedvixViewer-Open");
        t.setPriority(Thread.NORM_PRIORITY + 1);
        return t;
    });
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable chromeAutoHideRunnable = () -> {
        if (this.chromeVisible && !shouldPauseChromeAutoHide()) {
            setChromeVisible(false, true);
        }
    };

    // ── Local File Picker Launcher (HTML #viewer-open-local) ─────────────────

    private ActivityResultLauncher<String[]> openLocalFileLauncher;

    // ── Reader & UI State ────────────────────────────────────────────────────

    private UiPalette palette;
    private boolean darkUiMode = false;

    private int currentPageIndex = 0;
    private int rotationDegrees = 0;
    private float currentZoomScale = 1.0f;
    @NonNull private ReadingTheme readingTheme = ReadingTheme.DAY;
    @NonNull private ScrollMode scrollMode = ScrollMode.VERTICAL_CONTINUOUS;
    @NonNull private FitMode fitMode = FitMode.FIT_WIDTH;
    private boolean keepScreenOn = true;
    private boolean previewMode = false;
    private boolean isExternalDocument = false;

    private boolean chromeVisible = true;
    private boolean searchBarOpen = false;
    private boolean inkBarOpen = false;
    private boolean overflowMenuOpen = false;
    private boolean drawerOpen = false;
    @NonNull private DrawerPanel activeDrawerPanel = DrawerPanel.OUTLINE;

    @NonNull private InteractionMode interactionMode = InteractionMode.READ;
    private int selectedInkColor = COLOR_INK_RED;
    private float selectedInkWidthPt = 2.4f;

    @NonNull private String resultAction = RESULT_ACTION_CLOSED;
    private boolean userDraggingScrubber = false;

    // ── Search State ─────────────────────────────────────────────────────────

    @Nullable private PdfDocumentEngine.TaskHandle activeSearchTask;
    private final List<SearchMatch> allSearchMatches = new ArrayList<>();
    private int currentSearchMatchCursor = -1;
    private boolean searchMatchCase = false;
    private boolean searchWholeWord = false;

    // ── Active Text Selection Context State ──────────────────────────────────

    @Nullable private DocumentCanvasView activeSelectionCanvas;
    @Nullable private TextSelectionResult activeSelectionResult;

    // ── SD-Card Unmount Safety Receiver ──────────────────────────────────────

    @Nullable private BroadcastReceiver mediaEjectReceiver;

    // ── View Hierarchy References ────────────────────────────────────────────

    private FrameLayout rootContainer;
    private RecyclerView pdfRecyclerView;
    private LinearLayoutManager pdfLayoutManager;
    private PagerSnapHelper pagerSnapHelper;
    @Nullable private PdfPageAdapter pdfPageAdapter;
    private DocumentCanvasView standaloneImageCanvas;

    private LinearLayout loadingContainer;
    private ProgressBar loadingSpinner;
    private TextView loadingLabel;

    // Top Chrome (#viewer-header)
    private LinearLayout topBarContainer;
    private View topHeaderDivider;
    private TextView btnBackPill;
    private TextView titleTextView;
    private TextView subtitleTextView;
    private TextView btnBookmark;
    private TextView btnSearch;
    private TextView btnInkToggle;
    private TextView btnHamburgerMenu;

    // HTML Dropdown Menu (#viewer-menu)
    private LinearLayout dropdownMenuCard;
    private TextView menuItemPageView;
    private TextView menuItemOpenFile;
    private TextView menuItemFullscreen;
    private TextView menuItemUiTheme;
    private TextView menuItemKeepAwake;
    private TextView menuItemPrint;
    private TextView menuItemShare;
    private TextView menuItemUnlock;

    // Collapsible Search Bar (#viewer-search-bar)
    private LinearLayout searchBarContainer;
    private View searchBarDivider;
    private EditText searchInput;
    private TextView searchCounterText;
    private TextView btnMatchCase;
    private TextView btnWholeWord;

    // Left Slide-Over Drawer & Scrim (#viewer-outline-drawer + #viewer-drawer-scrim)
    private View drawerScrimView;
    private LinearLayout sideDrawerContainer;
    private TextView tabDrawerOutline;
    private TextView tabDrawerNotes;
    private TextView tabDrawerSearch;
    private TextView tabDrawerInfo;
    private LinearLayout drawerBodyContainer;

    @Nullable private PdfDocumentEngine.TaskHandle activeOutlineTask;
    private final List<PdfDocumentEngine.OutlineItem> documentOutlineItems = new ArrayList<>();

    // Locked Preview Overlay Card (.viewer-preview-blocked)
    private FrameLayout previewBlockedOverlay;
    private LinearLayout previewBlockedCard;
    private TextView previewBlockedBodyText;

    // Bottom Chrome (#viewer-footer + Java scrubber & chips)
    private LinearLayout bottomBarContainer;
    private View bottomFooterDivider;
    private LinearLayout previewBannerContainer;
    private TextView previewBannerLabel;
    private LinearLayout inkToolbarContainer;
    private TextView btnUndo;
    private TextView btnRedo;
    private LinearLayout footerControlsContainer;

    // HTML Footer Page & Zoom Controls
    private TextView btnPrevPage;
    private TextView btnNextPage;
    private EditText pageNumberInput;
    private TextView pageTotalCountText;
    private TextView btnZoomOut;
    private TextView zoomLevelText;
    private TextView btnZoomIn;

    // Java Scrubber & Quick Chips
    private TextView pageCounterPill;
    private SeekBar pageSeekBar;
    private TextView btnRotate;
    private TextView btnTheme;
    private TextView btnScrollMode;
    private TextView btnNotesChip;
    private TextView btnPrintChip;

    // Floating Text Selection Context Menu
    private LinearLayout selectionActionPopup;
    private TextView btnSelectionCopy;

    // ── Activity Lifecycle ───────────────────────────────────────────────────

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        this.darkUiMode = resolveInitialDarkUiMode(getIntent());
        this.palette = new UiPalette(darkUiMode);

        this.memoryPool = new BitmapMemoryPool(this);
        registerComponentCallbacks(this.memoryPool);

        registerLocalFilePickerLauncher();
        buildUiHierarchy();
        setupBackNavigation();
        resolveAndOpenDocument(getIntent(), null);
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        closeOverflowMenu();
        closeDrawer(false);
        closeCurrentDocumentResources();
        resolveAndOpenDocument(intent, null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resetChromeAutoHideTimer();
    }

    @Override
    protected void onPause() {
        cancelChromeAutoHideTimer();
        super.onPause();
        if (documentStore != null) {
            documentStore.flushNowSync();
        }
    }

    @Override
    protected void onDestroy() {
        cancelChromeAutoHideTimer();
        unregisterMediaEjectReceiver();
        if (activeSearchTask != null) {
            activeSearchTask.cancel();
            activeSearchTask = null;
        }
        closeCurrentDocumentResources();
        openExecutor.shutdownNow();

        if (memoryPool != null) {
            unregisterComponentCallbacks(memoryPool);
            memoryPool.clearAll();
            memoryPool = null;
        }
        super.onDestroy();
    }
@Override
    public boolean dispatchTouchEvent(@NonNull MotionEvent ev) {
        int action = ev.getActionMasked();
        if (chromeVisible && (action == MotionEvent.ACTION_DOWN
                || action == MotionEvent.ACTION_MOVE
                || action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_CANCEL)) {
            resetChromeAutoHideTimer();
        }
        return super.dispatchTouchEvent(ev);
    }
    @Override
    public void finish() {
        prepareActivityResult();
        super.finish();
    }

    private void registerLocalFilePickerLauncher() {
        openLocalFileLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri == null) return;
                    try {
                        getContentResolver().takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (SecurityException ignored) {
                    }
                    Intent localIntent = new Intent(Intent.ACTION_VIEW, uri);
                    localIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    closeCurrentDocumentResources();
                    resolveAndOpenDocument(localIntent, null);
                }
        );
    }

    private boolean resolveInitialDarkUiMode(@Nullable Intent intent) {
        if (intent != null) {
            String themeExtra = intent.getStringExtra(EXTRA_THEME);
            if ("dark".equalsIgnoreCase(themeExtra)) return true;
            if ("light".equalsIgnoreCase(themeExtra)) return false;
        }
        int nightFlags = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return nightFlags == Configuration.UI_MODE_NIGHT_YES;
    }

    private void prepareActivityResult() {
        Intent data = new Intent();
        data.putExtra(EXTRA_RESULT_ACTION, resultAction);
        if (resolvedDoc != null && !TextUtils.isEmpty(resolvedDoc.resourceId)) {
            data.putExtra(EXTRA_RESULT_RESOURCE_ID, resolvedDoc.resourceId);
        }
        data.putExtra(EXTRA_RESULT_LAST_PAGE, currentPageIndex);
        data.putExtra(EXTRA_RESULT_TOTAL_PAGES, getTotalPageCount());
        setResult(Activity.RESULT_OK, data);
    }

    private void setupBackNavigation() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (overflowMenuOpen) {
                    closeOverflowMenu();
                    return;
                }
                if (drawerOpen) {
                    closeDrawer(true);
                    return;
                }
                if (activeSelectionResult != null && activeSelectionCanvas != null) {
                    activeSelectionCanvas.clearTextSelection(true);
                    return;
                }
                if (searchBarOpen) {
                    toggleSearchBar(false);
                    return;
                }
                if (inkBarOpen || interactionMode != InteractionMode.READ) {
                    toggleInkToolbar(false);
                    return;
                }
                finish();
            }
        });
    }

    // ── Programmatic Combined HTML/CSS + Native Java View Hierarchy ──────────

    private void buildUiHierarchy() {
        rootContainer = new FrameLayout(this);
        rootContainer.setBackgroundColor(palette.bgPrimary);

        // 1. Primary Document Viewport Layer (#viewer-main, --page-gutter: 3px)
        pdfRecyclerView = new RecyclerView(this);
        pdfRecyclerView.setClipToPadding(false);
        pdfRecyclerView.setPadding(dp(3), dp(3), dp(3), dp(3));
        pdfRecyclerView.setItemAnimator(null);
        pdfLayoutManager = new LinearLayoutManager(this, RecyclerView.VERTICAL, false);
        pdfRecyclerView.setLayoutManager(pdfLayoutManager);
        pagerSnapHelper = new PagerSnapHelper();
        pdfRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                notifyVisiblePagesToEngine();
                if (!userDraggingScrubber) {
                    syncCurrentPageFromScroll();
                }
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                notifyVisiblePagesToEngine();
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    syncCurrentPageFromScroll();
                    persistCurrentViewportState();
                }
            }
        });
        rootContainer.addView(pdfRecyclerView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        standaloneImageCanvas = new DocumentCanvasView(this);
        standaloneImageCanvas.setVisibility(View.GONE);
        standaloneImageCanvas.setViewerInteractionListener(this);
        rootContainer.addView(standaloneImageCanvas, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 2. Locked Preview Overlay Card (.viewer-preview-blocked)
        previewBlockedOverlay = buildPreviewBlockedOverlay();
        previewBlockedOverlay.setVisibility(View.GONE);
        rootContainer.addView(previewBlockedOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 3. Left Slide-Over Drawer + 40% Scrim (#viewer-outline-drawer + #viewer-drawer-scrim)
        drawerScrimView = new View(this);
        drawerScrimView.setBackgroundColor(palette.scrim);
        drawerScrimView.setAlpha(0f);
        drawerScrimView.setVisibility(View.GONE);
        drawerScrimView.setOnClickListener(v -> closeDrawer(true));
        rootContainer.addView(drawerScrimView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        sideDrawerContainer = buildLeftSlideOverDrawer();
        sideDrawerContainer.setVisibility(View.GONE);
        int drawerWidth = Math.min(dp(320), Math.max(dp(240),
                Math.round(getResources().getDisplayMetrics().widthPixels * 0.80f)));
        FrameLayout.LayoutParams drawerLp = new FrameLayout.LayoutParams(
                drawerWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START);
        rootContainer.addView(sideDrawerContainer, drawerLp);

        // 4. Centered Loading Indicator (#viewer-loading)
        loadingContainer = new LinearLayout(this);
        loadingContainer.setOrientation(LinearLayout.VERTICAL);
        loadingContainer.setGravity(Gravity.CENTER);
        loadingContainer.setPadding(dp(24), dp(20), dp(24), dp(20));
        loadingContainer.setBackground(createBorderedRoundedDrawable(palette.bgCard, palette.border, dp(1), dp(12)));
        loadingContainer.setElevation(dp(8));

        loadingSpinner = new ProgressBar(this);
        loadingContainer.addView(loadingSpinner);

        loadingLabel = new TextView(this);
        loadingLabel.setText("Loading document…");
        loadingLabel.setTextColor(palette.textMuted);
        loadingLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        loadingLabel.setPadding(0, dp(12), 0, 0);
        loadingContainer.addView(loadingLabel);

        FrameLayout.LayoutParams loadingLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        rootContainer.addView(loadingContainer, loadingLp);

        // 5. Top Chrome Bar (#viewer-header + embedded #viewer-search-bar)
        topBarContainer = new LinearLayout(this);
        topBarContainer.setOrientation(LinearLayout.VERTICAL);
        topBarContainer.setBackgroundColor(palette.bgCard);
        topBarContainer.setElevation(dp(4));

        LinearLayout topMainRow = new LinearLayout(this);
        topMainRow.setOrientation(LinearLayout.HORIZONTAL);
        topMainRow.setGravity(Gravity.CENTER_VERTICAL);
        topMainRow.setPadding(dp(8), dp(6), dp(8), dp(6));

        // HTML .btn-back pill ("← Back")
        btnBackPill = new TextView(this);
        btnBackPill.setText("← Back");
        btnBackPill.setContentDescription("Close document");
        btnBackPill.setTextColor(palette.textPrimary);
        btnBackPill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        btnBackPill.setTypeface(Typeface.DEFAULT_BOLD);
        btnBackPill.setGravity(Gravity.CENTER);
        btnBackPill.setMinimumHeight(dp(38));
        btnBackPill.setPadding(dp(12), dp(6), dp(12), dp(6));
        btnBackPill.setBackground(createBorderedRoundedDrawable(palette.bgSecondary, palette.border, dp(1), dp(20)));
        btnBackPill.setOnClickListener(v -> finish());
        topMainRow.addView(btnBackPill);

        LinearLayout titleStack = new LinearLayout(this);
        titleStack.setOrientation(LinearLayout.VERTICAL);
        titleStack.setPadding(dp(10), 0, dp(6), 0);

        titleTextView = new TextView(this);
        titleTextView.setText("Loading…");
        titleTextView.setTextColor(palette.textPrimary);
        titleTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        titleTextView.setTypeface(Typeface.DEFAULT_BOLD);
        titleTextView.setSingleLine(true);
        titleTextView.setEllipsize(TextUtils.TruncateAt.END);
        titleStack.addView(titleTextView);

        subtitleTextView = new TextView(this);
        subtitleTextView.setTextColor(palette.textMuted);
        subtitleTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        subtitleTextView.setSingleLine(true);
        subtitleTextView.setEllipsize(TextUtils.TruncateAt.END);
        titleStack.addView(subtitleTextView);

        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        topMainRow.addView(titleStack, titleLp);

        // Quick-access Java icon buttons + HTML #viewer-menu-btn ("☰")
        btnSearch = createToolbarIconButton("🔍", "Search text", v -> {
            closeOverflowMenu();
            toggleSearchBar(!searchBarOpen);
        });
        btnBookmark = createToolbarIconButton("☆", "Bookmark page", v -> {
            closeOverflowMenu();
            handleToggleBookmark();
        });
        btnInkToggle = createToolbarIconButton("✎", "Annotate", v -> {
            closeOverflowMenu();
            toggleInkToolbar(!inkBarOpen);
        });
        btnHamburgerMenu = createToolbarIconButton("☰", "Open menu", v -> toggleOverflowMenu());

        topMainRow.addView(btnSearch);
        topMainRow.addView(btnBookmark);
        topMainRow.addView(btnInkToggle);
        topMainRow.addView(btnHamburgerMenu);
        topBarContainer.addView(topMainRow);

        // Embedded Search Row (#viewer-search-bar)
        searchBarContainer = buildSearchBarRow();
        searchBarContainer.setVisibility(View.GONE);
        topBarContainer.addView(searchBarContainer);

        topHeaderDivider = new View(this);
        topHeaderDivider.setBackgroundColor(palette.border);
        topBarContainer.addView(topHeaderDivider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        rootContainer.addView(topBarContainer, topLp);

        // 6. Floating HTML Hamburger Dropdown Menu (#viewer-menu)
        dropdownMenuCard = buildDropdownMenuCard();
        dropdownMenuCard.setVisibility(View.GONE);
        FrameLayout.LayoutParams menuLp = new FrameLayout.LayoutParams(
                dp(228), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        menuLp.setMargins(dp(8), dp(54), dp(8), 0);
        rootContainer.addView(dropdownMenuCard, menuLp);

        // 7. Bottom Docked Chrome (#viewer-footer + Preview Banner + Ink Bar + Scrubber)
        bottomBarContainer = new LinearLayout(this);
        bottomBarContainer.setOrientation(LinearLayout.VERTICAL);
        bottomBarContainer.setBackgroundColor(palette.bgCard);
        bottomBarContainer.setElevation(dp(4));

        bottomFooterDivider = new View(this);
        bottomFooterDivider.setBackgroundColor(palette.border);
        bottomBarContainer.addView(bottomFooterDivider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // 7a. Premium Preview Banner (shown when previewMode == true)
        previewBannerContainer = buildPreviewBannerRow();
        previewBannerContainer.setVisibility(View.GONE);
        bottomBarContainer.addView(previewBannerContainer);

        // 7b. Collapsible Ink / Highlighter / Eraser Toolbar
        inkToolbarContainer = buildInkToolbarRow();
        inkToolbarContainer.setVisibility(View.GONE);
        bottomBarContainer.addView(inkToolbarContainer);

        // 7c. Combined HTML Footer Controls + Java Scrubber & Quick Chips
        footerControlsContainer = buildCombinedFooterControls();
        bottomBarContainer.addView(footerControlsContainer);

        FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        rootContainer.addView(bottomBarContainer, bottomLp);

        // 8. Floating Text Selection Contextual Popup
        selectionActionPopup = buildSelectionPopupBar();
        selectionActionPopup.setVisibility(View.GONE);
        FrameLayout.LayoutParams selLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        rootContainer.addView(selectionActionPopup, selLp);

        setContentView(rootContainer);
        applyWindowInsetsHandling();
    }

    private void applyWindowInsetsHandling() {
        final int baseTopPad = topBarContainer.getPaddingTop();
        final int baseBottomPad = bottomBarContainer.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(rootContainer, (v, windowInsets) -> {
            Insets sysBars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime());

            topBarContainer.setPadding(
                    sysBars.left,
                    baseTopPad + sysBars.top,
                    sysBars.right,
                    topBarContainer.getPaddingBottom()
            );

            FrameLayout.LayoutParams menuLp = (FrameLayout.LayoutParams) dropdownMenuCard.getLayoutParams();
            menuLp.topMargin = baseTopPad + sysBars.top + dp(52);
            menuLp.rightMargin = sysBars.right + dp(8);
            dropdownMenuCard.setLayoutParams(menuLp);

            int bottomInset = Math.max(sysBars.bottom, imeInsets.bottom);
            bottomBarContainer.setPadding(
                    sysBars.left,
                    bottomBarContainer.getPaddingTop(),
                    sysBars.right,
                    baseBottomPad + bottomInset
            );

            sideDrawerContainer.setPadding(
                    sysBars.left,
                    sysBars.top + dp(54),
                    0,
                    bottomInset + dp(8)
            );

            return WindowInsetsCompat.CONSUMED;
        });

        updateSystemBarIconAppearance();
    }

    private void updateSystemBarIconAppearance() {
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), rootContainer);
        if (controller != null) {
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.setAppearanceLightStatusBars(!darkUiMode);
            controller.setAppearanceLightNavigationBars(!darkUiMode);
        }
    }

    // ── HTML Hamburger Dropdown Menu (#viewer-menu) ──────────────────────────

    @NonNull
    private LinearLayout buildDropdownMenuCard() {
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(6), dp(6), dp(6), dp(6));
        menu.setBackground(createBorderedRoundedDrawable(palette.bgCard, palette.border, dp(1), dp(10)));
        menu.setElevation(dp(12));

        menu.addView(createDropdownMenuItem("📑", "Book outline", v -> {
            closeOverflowMenu();
            openDrawer(DrawerPanel.OUTLINE);
        }));
        menu.addView(createDropdownMenuItem("☰", "Notes & Bookmarks", v -> {
            closeOverflowMenu();
            openDrawer(DrawerPanel.NOTES_BOOKMARKS);
        }));
        menu.addView(createDropdownMenuItem("🔍", "Search text", v -> {
            closeOverflowMenu();
            toggleSearchBar(true);
        }));
        menuItemPageView = createDropdownMenuItem("📄", "Paged view", v -> {
            closeOverflowMenu();
            toggleScrollMode();
        });
        menu.addView(menuItemPageView);
        menuItemOpenFile = createDropdownMenuItem("📂", "Open file", v -> {
            closeOverflowMenu();
            if (isExternalDocument && openLocalFileLauncher != null) {
                openLocalFileLauncher.launch(new String[]{
                        "application/pdf", "image/*"
                });
            }
        });
        menuItemOpenFile.setVisibility(View.GONE);
        menu.addView(menuItemOpenFile);

        menu.addView(createMenuDivider());

        menu.addView(createDropdownMenuItem("↔", "Fit to width", v -> {
            closeOverflowMenu();
            applyFitWidthZoom();
        }));
        menu.addView(createDropdownMenuItem("1:1", "Reset zoom", v -> {
            closeOverflowMenu();
            setDocumentZoomScale(1.0f);
        }));
        menuItemFullscreen = createDropdownMenuItem("⛶", "Fullscreen", v -> {
            closeOverflowMenu();
            setChromeVisible(false, true);
        });
        menu.addView(menuItemFullscreen);

        menu.addView(createMenuDivider());

        menuItemUiTheme = createDropdownMenuItem(darkUiMode ? "☀" : "🌙",
                darkUiMode ? "Light UI chrome" : "Dark UI chrome", v -> {
                    closeOverflowMenu();
                    toggleUiDarkLightMode();
                });
        menu.addView(menuItemUiTheme);

        menu.addView(createDropdownMenuItem("◐", "Page color filter", v -> {
            closeOverflowMenu();
            showThemePickerDialog();
        }));

        menuItemKeepAwake = createDropdownMenuItem("✓", "Keep screen on", v -> {
            closeOverflowMenu();
            keepScreenOn = !keepScreenOn;
            applyKeepScreenOnFlag();
            updateMenuDynamicLabels();
            if (documentStore != null) {
                documentStore.updateReadingPreferences(readingTheme, scrollMode, keepScreenOn);
            }
            Toast.makeText(this,
                    keepScreenOn ? "Screen will stay awake" : "Normal screen timeout restored",
                    Toast.LENGTH_SHORT).show();
        });
        menu.addView(menuItemKeepAwake);

        menuItemPrint = createDropdownMenuItem("⎙", "Print / Save as PDF", v -> {
            closeOverflowMenu();
            triggerNativePrint();
        });
        menuItemPrint.setVisibility(View.GONE);
        menu.addView(menuItemPrint);

        menuItemShare = createDropdownMenuItem("↗", "Share document", v -> {
            closeOverflowMenu();
            shareBackingDocumentFile();
        });
        menuItemShare.setVisibility(View.GONE);
        menu.addView(menuItemShare);

        menuItemUnlock = createDropdownMenuItem("⭐", "Unlock full document", v -> {
            closeOverflowMenu();
            triggerUnlockSubscription();
        });
        menuItemUnlock.setVisibility(View.GONE);
        menu.addView(menuItemUnlock);

        return menu;
    }

    @NonNull
    private TextView createDropdownMenuItem(@NonNull String icon,
                                            @NonNull String label,
                                            @NonNull View.OnClickListener listener) {
        TextView item = new TextView(this);
        item.setText(icon + "   " + label);
        item.setTextColor(palette.textPrimary);
        item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        item.setPadding(dp(10), dp(9), dp(10), dp(9));
        item.setOnClickListener(listener);
        return item;
    }

    @NonNull
    private View createMenuDivider() {
        View div = new View(this);
        div.setBackgroundColor(palette.borderLight);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMargins(dp(6), dp(4), dp(6), dp(4));
        div.setLayoutParams(lp);
        return div;
    }

    private void toggleOverflowMenu() {
        if (overflowMenuOpen) {
            closeOverflowMenu();
        } else {
            updateMenuDynamicLabels();
            overflowMenuOpen = true;
            cancelChromeAutoHideTimer();
            dropdownMenuCard.setVisibility(View.VISIBLE);
            dropdownMenuCard.setAlpha(0f);
            dropdownMenuCard.animate().alpha(1f).setDuration(150L).start();
        }
    }

    private void closeOverflowMenu() {
        if (!overflowMenuOpen) return;
        overflowMenuOpen = false;
        dropdownMenuCard.setVisibility(View.GONE);
        resetChromeAutoHideTimer();
    }

    private void updateMenuDynamicLabels() {
        if (menuItemPageView != null) {
            menuItemPageView.setText(scrollMode == ScrollMode.VERTICAL_CONTINUOUS
                    ? "📄   Paged view"
                    : "📜   Continuous scroll");
        }
        if (menuItemUiTheme != null) {
            menuItemUiTheme.setText(darkUiMode ? "☀   Light UI chrome" : "🌙   Dark UI chrome");
        }
        if (menuItemKeepAwake != null) {
            menuItemKeepAwake.setText((keepScreenOn ? "✓   " : "     ") + "Keep screen on");
        }
        applyExternalDocumentRestrictions();
        if (menuItemUnlock != null) {
            menuItemUnlock.setVisibility(previewMode ? View.VISIBLE : View.GONE);
        }
    }

    // ── Inline Search Bar (#viewer-search-bar + Aa / “” options) ─────────────

    @NonNull
    private LinearLayout buildSearchBarRow() {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setBackgroundColor(palette.bgSecondary);
        wrapper.setPadding(dp(10), dp(6), dp(8), dp(6));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        searchInput = new EditText(this);
        searchInput.setHint("Find…");
        searchInput.setHintTextColor(palette.textMuted);
        searchInput.setTextColor(palette.textPrimary);
        searchInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        searchInput.setSingleLine(true);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchInput.setInputType(InputType.TYPE_CLASS_TEXT);
        searchInput.setPadding(dp(10), dp(7), dp(10), dp(7));
        searchInput.setBackground(createBorderedRoundedDrawable(palette.bgCard, palette.border, dp(1), dp(6)));
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                executeDocumentSearch(searchInput.getText().toString());
                hideKeyboard(searchInput);
                return true;
            }
            return false;
        });

        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(searchInput, inputLp);

        searchCounterText = new TextView(this);
        searchCounterText.setText("0/0");
        searchCounterText.setTextColor(palette.textMuted);
        searchCounterText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        searchCounterText.setPadding(dp(6), 0, dp(4), 0);
        row.addView(searchCounterText);

        // Case Sensitive toggle pill ("AA,aa") + Whole Word toggle pill ("Word")
        btnMatchCase = createSmallTogglePill("AA,aa", "Case sensitive", false, v -> {
            searchMatchCase = !searchMatchCase;
            styleTogglePill(btnMatchCase, searchMatchCase);
            if (!TextUtils.isEmpty(searchInput.getText())) {
                executeDocumentSearch(searchInput.getText().toString());
            }
        });
        btnWholeWord = createSmallTogglePill("Word", "Whole word", false, v -> {
            searchWholeWord = !searchWholeWord;
            styleTogglePill(btnWholeWord, searchWholeWord);
            if (!TextUtils.isEmpty(searchInput.getText())) {
                executeDocumentSearch(searchInput.getText().toString());
            }
        });
        row.addView(btnMatchCase);
        row.addView(btnWholeWord);

        row.addView(createToolbarIconButton("↑", "Previous match", v -> stepSearchMatch(-1)));
        row.addView(createToolbarIconButton("↓", "Next match", v -> stepSearchMatch(1)));
        row.addView(createToolbarIconButton("✕", "Close search", v -> toggleSearchBar(false)));

        wrapper.addView(row);

        searchBarDivider = new View(this);
        searchBarDivider.setBackgroundColor(palette.border);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        divLp.topMargin = dp(4);
        wrapper.addView(searchBarDivider, divLp);

        return wrapper;
    }

    // ── Left Slide-Over Drawer (#viewer-outline-drawer) ──────────────────────

    @NonNull
    private LinearLayout buildLeftSlideOverDrawer() {
        LinearLayout drawer = new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setBackgroundColor(palette.bgCard);
        drawer.setElevation(dp(12));

        // 4-panel tab strip (Outline | Notes | Search | Info)
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setPadding(dp(6), dp(8), dp(6), dp(8));

        tabDrawerOutline = createDrawerTabButton("Outline", v -> switchDrawerPanel(DrawerPanel.OUTLINE));
        tabDrawerNotes = createDrawerTabButton("Notes", v -> switchDrawerPanel(DrawerPanel.NOTES_BOOKMARKS));
        tabDrawerSearch = createDrawerTabButton("Search", v -> switchDrawerPanel(DrawerPanel.SEARCH_RESULTS));
        tabDrawerInfo = createDrawerTabButton("Info", v -> switchDrawerPanel(DrawerPanel.DOCUMENT_INFO));

        tabRow.addView(tabDrawerOutline, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabRow.addView(tabDrawerNotes, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabRow.addView(tabDrawerSearch, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabRow.addView(tabDrawerInfo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        drawer.addView(tabRow);

        View div = new View(this);
        div.setBackgroundColor(palette.border);
        drawer.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        ScrollView scroll = new ScrollView(this);
        drawerBodyContainer = new LinearLayout(this);
        drawerBodyContainer.setOrientation(LinearLayout.VERTICAL);
        drawerBodyContainer.setPadding(dp(12), dp(10), dp(12), dp(16));
        scroll.addView(drawerBodyContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        drawer.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return drawer;
    }

    @NonNull
    private TextView createDrawerTabButton(@NonNull String label, @NonNull View.OnClickListener listener) {
        TextView tab = new TextView(this);
        tab.setText(label);
        tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tab.setTypeface(Typeface.DEFAULT_BOLD);
        tab.setGravity(Gravity.CENTER);
        tab.setPadding(dp(6), dp(8), dp(6), dp(8));
        tab.setOnClickListener(listener);
        return tab;
    }

    private void openDrawer(@NonNull DrawerPanel panel) {
        this.activeDrawerPanel = panel;
        renderDrawerPanelContent(panel);

        if (!drawerOpen) {
            drawerOpen = true;
            cancelChromeAutoHideTimer();
            drawerScrimView.setVisibility(View.VISIBLE);
            drawerScrimView.animate().alpha(1f).setDuration(DRAWER_ANIM_DURATION_MS).start();

            sideDrawerContainer.setVisibility(View.VISIBLE);
            int width = sideDrawerContainer.getLayoutParams().width;
            sideDrawerContainer.setTranslationX(-width);
            sideDrawerContainer.animate()
                    .translationX(0f)
                    .setDuration(DRAWER_ANIM_DURATION_MS)
                    .setInterpolator(CHROME_INTERPOLATOR)
                    .start();
        }
    }

    private void closeDrawer(boolean animate) {
        if (!drawerOpen) return;
        drawerOpen = false;
        resetChromeAutoHideTimer();
        int width = sideDrawerContainer.getLayoutParams().width;

        if (!animate) {
            drawerScrimView.setAlpha(0f);
            drawerScrimView.setVisibility(View.GONE);
            sideDrawerContainer.setTranslationX(-width);
            sideDrawerContainer.setVisibility(View.GONE);
            return;
        }

        drawerScrimView.animate()
                .alpha(0f)
                .setDuration(DRAWER_ANIM_DURATION_MS)
                .withEndAction(() -> drawerScrimView.setVisibility(View.GONE))
                .start();

        sideDrawerContainer.animate()
                .translationX(-width)
                .setDuration(DRAWER_ANIM_DURATION_MS)
                .setInterpolator(CHROME_INTERPOLATOR)
                .withEndAction(() -> sideDrawerContainer.setVisibility(View.GONE))
                .start();
    }

    private void switchDrawerPanel(@NonNull DrawerPanel panel) {
        this.activeDrawerPanel = panel;
        renderDrawerPanelContent(panel);
    }

    private void renderDrawerPanelContent(@NonNull DrawerPanel panel) {
        styleDrawerTab(tabDrawerOutline, panel == DrawerPanel.OUTLINE);
        styleDrawerTab(tabDrawerNotes, panel == DrawerPanel.NOTES_BOOKMARKS);
        styleDrawerTab(tabDrawerSearch, panel == DrawerPanel.SEARCH_RESULTS);
        styleDrawerTab(tabDrawerInfo, panel == DrawerPanel.DOCUMENT_INFO);

        drawerBodyContainer.removeAllViews();

        if (panel == DrawerPanel.OUTLINE) {
            renderOutlineInDrawer();
        } else if (panel == DrawerPanel.NOTES_BOOKMARKS) {
            renderBookmarksAndNotesInDrawer();
        } else if (panel == DrawerPanel.SEARCH_RESULTS) {
            renderSearchResultsInDrawer();
        } else {
            renderDocumentInfoInDrawer();
        }
    }

    private void renderOutlineInDrawer() {
        if (documentOutlineItems.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No table of contents found in this document.\nUse the page scrubber below or Search to jump to a topic.");
            empty.setTextColor(palette.textMuted);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(8), dp(24), dp(8), dp(24));
            drawerBodyContainer.addView(empty);
            return;
        }

        drawerBodyContainer.addView(createDrawerSectionHeader("BOOK OUTLINE"));
        appendOutlineNodesRecursive(documentOutlineItems);
    }

    private void appendOutlineNodesRecursive(@NonNull List<PdfDocumentEngine.OutlineItem> nodes) {
        for (PdfDocumentEngine.OutlineItem node : nodes) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int indentPx = dp(10 + Math.min(4, node.level) * 14);
            row.setPadding(indentPx, dp(9), dp(10), dp(9));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(4);
            row.setLayoutParams(lp);

            boolean isCurrentChapter = (node.pageIndex == currentPageIndex);
            row.setBackground(createRoundedRectDrawable(
                    isCurrentChapter ? palette.accent : palette.bgSecondary, dp(8)));

            TextView titleTv = new TextView(this);
            String prefix = node.level == 0 ? "▸ " : "• ";
            titleTv.setText(prefix + node.title);
            titleTv.setTextColor(isCurrentChapter ? Color.WHITE : palette.textPrimary);
            titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, node.level == 0 ? 13f : 12.5f);
            if (node.level == 0) {
                titleTv.setTypeface(Typeface.DEFAULT_BOLD);
            }
            titleTv.setMaxLines(2);
            titleTv.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(titleTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView pageBadge = new TextView(this);
            pageBadge.setText("p. " + (node.pageIndex + 1));
            pageBadge.setTextColor(isCurrentChapter ? Color.WHITE : palette.textMuted);
            pageBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            pageBadge.setPadding(dp(8), 0, 0, 0);
            row.addView(pageBadge);

            final int targetPage = node.pageIndex;
            row.setOnClickListener(v -> {
                closeDrawer(true);
                navigateToPage(targetPage, false);
            });

            drawerBodyContainer.addView(row);

            if (!node.children.isEmpty()) {
                appendOutlineNodesRecursive(node.children);
            }
        }
    }

    private void styleDrawerTab(@NonNull TextView tab, boolean active) {
        tab.setTextColor(active ? Color.WHITE : palette.textPrimary);
        tab.setBackground(createRoundedRectDrawable(
                active ? palette.accent : palette.bgSecondary, dp(8)));
    }

    private void renderBookmarksAndNotesInDrawer() {
        if (documentStore == null) return;
        List<BookmarkItem> bookmarks = documentStore.getAllBookmarksSorted();
        List<TextMarkupAnnotation> markups = documentStore.getAllTextMarkupsSorted();

        if (bookmarks.isEmpty() && markups.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No bookmarks or study notes yet.\nTap ☆ in the header to bookmark a page, or long-press text to highlight & add notes.");
            empty.setTextColor(palette.textMuted);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(8), dp(24), dp(8), dp(24));
            drawerBodyContainer.addView(empty);
            return;
        }

        if (!bookmarks.isEmpty()) {
            drawerBodyContainer.addView(createDrawerSectionHeader("BOOKMARKS (" + bookmarks.size() + ")"));
            for (BookmarkItem b : bookmarks) {
                drawerBodyContainer.addView(createDrawerRowItem(
                        "★ Page " + (b.pageIndex + 1) + (TextUtils.isEmpty(b.label) ? "" : " — " + b.label),
                        v -> {
                            closeDrawer(true);
                            navigateToPage(b.pageIndex, false);
                        }
                ));
            }
        }

        if (!markups.isEmpty()) {
            drawerBodyContainer.addView(createDrawerSectionHeader("STUDY NOTES & HIGHLIGHTS (" + markups.size() + ")"));
            for (TextMarkupAnnotation m : markups) {
                String desc = !TextUtils.isEmpty(m.note) ? m.note : trimSnippet(m.quotedText, 64);
                drawerBodyContainer.addView(createDrawerRowItem(
                        "✎ Page " + (m.pageIndex + 1) + " — " + desc,
                        v -> {
                            closeDrawer(true);
                            navigateToPage(m.pageIndex, false);
                        }
                ));
            }
        }
    }

    private void renderSearchResultsInDrawer() {
        if (allSearchMatches.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No active search matches.\nUse 🔍 in the top bar to search inside this PDF.");
            empty.setTextColor(palette.textMuted);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(8), dp(24), dp(8), dp(24));
            drawerBodyContainer.addView(empty);
            return;
        }

        drawerBodyContainer.addView(createDrawerSectionHeader(
                "MATCHES (" + allSearchMatches.size() + ")"));
        for (int i = 0; i < allSearchMatches.size(); i++) {
            final int idx = i;
            SearchMatch match = allSearchMatches.get(i);
            String label = "Page " + (match.pageIndex + 1) + " • Match #" + (match.matchIndexOnPage + 1);
            TextView row = createDrawerRowItem(label, v -> {
                currentSearchMatchCursor = idx;
                updateSearchHighlightsOnAdapter(true);
                closeDrawer(true);
            });
            if (i == currentSearchMatchCursor) {
                row.setTextColor(Color.WHITE);
                row.setBackground(createRoundedRectDrawable(palette.accent, dp(8)));
            }
            drawerBodyContainer.addView(row);
        }
    }

    private void renderDocumentInfoInDrawer() {
        drawerBodyContainer.addView(createDrawerSectionHeader("DOCUMENT DETAILS"));
        String title = resolvedDoc != null ? resolvedDoc.displayName : "Document";
        String mime = resolvedDoc != null ? resolvedDoc.mimeType : "application/pdf";
        String size = resolvedDoc != null ? formatByteSize(resolvedDoc.byteLength) : "Unknown";
        int pages = getTotalPageCount();

        addInfoPair("Title", title);
        addInfoPair("Format", mime);
        addInfoPair("Pages", String.valueOf(Math.max(1, pages)));
        addInfoPair("File Size", size);
        addInfoPair("Mode", previewMode ? "Preview (Locked)" : "Full Access");
    }

    private void addInfoPair(@NonNull String term, @NonNull String value) {
        TextView dt = new TextView(this);
        dt.setText(term);
        dt.setTextColor(palette.textMuted);
        dt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        dt.setTypeface(Typeface.DEFAULT_BOLD);
        dt.setPadding(0, dp(8), 0, dp(2));
        drawerBodyContainer.addView(dt);

        TextView dd = new TextView(this);
        dd.setText(value);
        dd.setTextColor(palette.textPrimary);
        dd.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        drawerBodyContainer.addView(dd);
    }

    @NonNull
    private TextView createDrawerSectionHeader(@NonNull String title) {
        TextView hdr = new TextView(this);
        hdr.setText(title);
        hdr.setTextColor(palette.textMuted);
        hdr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        hdr.setTypeface(Typeface.DEFAULT_BOLD);
        hdr.setPadding(dp(4), dp(10), dp(4), dp(4));
        return hdr;
    }

    @NonNull
    private TextView createDrawerRowItem(@NonNull String text, @NonNull View.OnClickListener listener) {
        TextView item = new TextView(this);
        item.setText(text);
        item.setTextColor(palette.textPrimary);
        item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        item.setPadding(dp(10), dp(9), dp(10), dp(9));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(4);
        item.setLayoutParams(lp);
        item.setBackground(createRoundedRectDrawable(palette.bgSecondary, dp(8)));
        item.setOnClickListener(listener);
        return item;
    }

    // ── Locked Preview Card Overlay (.viewer-preview-blocked) ────────────────

    @NonNull
    private FrameLayout buildPreviewBlockedOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.argb(180, 26, 26, 46));
        overlay.setClickable(true);

        previewBlockedCard = new LinearLayout(this);
        previewBlockedCard.setOrientation(LinearLayout.VERTICAL);
        previewBlockedCard.setGravity(Gravity.CENTER_HORIZONTAL);
        previewBlockedCard.setPadding(dp(24), dp(24), dp(24), dp(24));
        previewBlockedCard.setBackground(createBorderedRoundedDrawable(palette.bgCard, palette.border, dp(1), dp(12)));
        previewBlockedCard.setElevation(dp(8));

        TextView icon = new TextView(this);
        icon.setText("🔒");
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f);
        previewBlockedCard.addView(icon);

        TextView heading = new TextView(this);
        heading.setText("Preview Limit Reached");
        heading.setTextColor(palette.textPrimary);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setPadding(0, dp(10), 0, dp(8));
        previewBlockedCard.addView(heading);

        previewBlockedBodyText = new TextView(this);
        previewBlockedBodyText.setText("Subscribe to MedVix Pro to unlock all pages, offline downloads, and full study annotations.");
        previewBlockedBodyText.setTextColor(palette.textSecondary);
        previewBlockedBodyText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        previewBlockedBodyText.setGravity(Gravity.CENTER);
        previewBlockedBodyText.setPadding(0, 0, 0, dp(16));
        previewBlockedCard.addView(previewBlockedBodyText);

        TextView ctaBtn = new TextView(this);
        ctaBtn.setText("Unlock Full Document");
        ctaBtn.setTextColor(Color.WHITE);
        ctaBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        ctaBtn.setTypeface(Typeface.DEFAULT_BOLD);
        ctaBtn.setPadding(dp(20), dp(10), dp(20), dp(10));
        ctaBtn.setBackground(createRoundedRectDrawable(palette.accent, dp(8)));
        ctaBtn.setOnClickListener(v -> triggerUnlockSubscription());
        previewBlockedCard.addView(ctaBtn);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                dp(320), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        overlay.addView(previewBlockedCard, cardLp);
        return overlay;
    }

    // ── Combined Footer (#viewer-footer + Java Scrubber & Quick Chips) ────────

    @NonNull
    private LinearLayout buildPreviewBannerRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(palette.bgSecondary);
        row.setPadding(dp(12), dp(5), dp(10), dp(5));

        previewBannerLabel = new TextView(this);
        previewBannerLabel.setText("🔒 Preview — 10% unlocked");
        previewBannerLabel.setTextColor(palette.textSecondary);
        previewBannerLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        previewBannerLabel.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lblLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(previewBannerLabel, lblLp);

        TextView unlockBtn = new TextView(this);
        unlockBtn.setText("Unlock");
        unlockBtn.setTextColor(Color.WHITE);
        unlockBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        unlockBtn.setTypeface(Typeface.DEFAULT_BOLD);
        unlockBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
        unlockBtn.setBackground(createRoundedRectDrawable(palette.accent, dp(12)));
        unlockBtn.setOnClickListener(v -> triggerUnlockSubscription());
        row.addView(unlockBtn);

        return row;
    }

    @NonNull
    private LinearLayout buildInkToolbarRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(palette.bgSecondary);
        row.setPadding(dp(10), dp(7), dp(10), dp(7));

        row.addView(createBottomChipButton("Pen", v -> setActiveInteractionMode(InteractionMode.INK_PEN)));
        row.addView(createBottomChipButton("Marker", v -> setActiveInteractionMode(InteractionMode.INK_HIGHLIGHTER)));
        row.addView(createBottomChipButton("Eraser", v -> setActiveInteractionMode(InteractionMode.INK_ERASER)));

        int[] colors = { COLOR_INK_RED, COLOR_INK_BLUE, COLOR_INK_GREEN, COLOR_INK_BLACK };
        for (int color : colors) {
            View swatch = new View(this);
            LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(dp(22), dp(22));
            swLp.setMargins(dp(5), 0, dp(3), 0);
            swatch.setLayoutParams(swLp);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(color);
            circle.setStroke(dp(2), palette.border);
            swatch.setBackground(circle);
            swatch.setOnClickListener(v -> {
                selectedInkColor = color;
                applyInkBrushToViews();
                Toast.makeText(this, "Ink color updated", Toast.LENGTH_SHORT).show();
            });
            row.addView(swatch);
        }

        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        btnUndo = createBottomChipButton("↶", v -> handleUndo());
        btnRedo = createBottomChipButton("↷", v -> handleRedo());
        TextView btnDone = createBottomChipButton("Done", v -> toggleInkToolbar(false));

        row.addView(btnUndo);
        row.addView(btnRedo);
        row.addView(btnDone);
        return row;
    }

    @NonNull
    private LinearLayout buildCombinedFooterControls() {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(dp(10), dp(6), dp(10), dp(6));

        // Row 1: HTML Footer Bar ([◀] Page [input] of N [▶]  ·  [−] 100% [+])
        LinearLayout htmlFooterRow = new LinearLayout(this);
        htmlFooterRow.setOrientation(LinearLayout.HORIZONTAL);
        htmlFooterRow.setGravity(Gravity.CENTER_VERTICAL);

        btnPrevPage = createFooterPillButton("◀", "Previous page", v -> navigateToPage(currentPageIndex - 1, false));
        htmlFooterRow.addView(btnPrevPage);

        TextView pageLbl = new TextView(this);
        pageLbl.setText("Page");
        pageLbl.setTextColor(palette.textMuted);
        pageLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        pageLbl.setPadding(dp(6), 0, dp(4), 0);
        htmlFooterRow.addView(pageLbl);

        pageNumberInput = new EditText(this);
        pageNumberInput.setText("1");
        pageNumberInput.setTextColor(palette.textPrimary);
        pageNumberInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        pageNumberInput.setGravity(Gravity.CENTER);
        pageNumberInput.setSingleLine(true);
        pageNumberInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        pageNumberInput.setImeOptions(EditorInfo.IME_ACTION_GO);
        pageNumberInput.setPadding(dp(4), dp(3), dp(4), dp(3));
        pageNumberInput.setBackground(createBorderedRoundedDrawable(palette.bgPrimary, palette.border, dp(1), dp(6)));
        pageNumberInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                commitPageInputJump();
                hideKeyboard(pageNumberInput);
                pageNumberInput.clearFocus();
                resetChromeAutoHideTimer();
                return true;
            }
            return false;
        });
        pageNumberInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                cancelChromeAutoHideTimer();
            } else {
                resetChromeAutoHideTimer();
            }
        });
        htmlFooterRow.addView(pageNumberInput, new LinearLayout.LayoutParams(dp(46), dp(32)));

        pageTotalCountText = new TextView(this);
        pageTotalCountText.setText(" of 1");
        pageTotalCountText.setTextColor(palette.textMuted);
        pageTotalCountText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        pageTotalCountText.setPadding(dp(4), 0, dp(6), 0);
        htmlFooterRow.addView(pageTotalCountText);

        btnNextPage = createFooterPillButton("▶", "Next page", v -> navigateToPage(currentPageIndex + 1, false));
        htmlFooterRow.addView(btnNextPage);

        htmlFooterRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        // HTML Zoom Controls: [−] [100%] [+]
        btnZoomOut = createFooterPillButton("−", "Zoom out", v -> stepDocumentZoom(-0.25f));
        htmlFooterRow.addView(btnZoomOut);

        zoomLevelText = new TextView(this);
        zoomLevelText.setText("100%");
        zoomLevelText.setTextColor(palette.textPrimary);
        zoomLevelText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        zoomLevelText.setTypeface(Typeface.DEFAULT_BOLD);
        zoomLevelText.setGravity(Gravity.CENTER);
        zoomLevelText.setMinWidth(dp(46));
        zoomLevelText.setOnClickListener(v -> setDocumentZoomScale(1.0f));
        htmlFooterRow.addView(zoomLevelText);

        btnZoomIn = createFooterPillButton("+", "Zoom in", v -> stepDocumentZoom(0.25f));
        htmlFooterRow.addView(btnZoomIn);

        wrapper.addView(htmlFooterRow);

        // Row 2: Java Fast-Scrub SeekBar
        LinearLayout sliderLine = new LinearLayout(this);
        sliderLine.setOrientation(LinearLayout.HORIZONTAL);
        sliderLine.setGravity(Gravity.CENTER_VERTICAL);
        sliderLine.setPadding(0, dp(6), 0, dp(2));

        pageCounterPill = new TextView(this);
        pageCounterPill.setText("1 / 1");
        pageCounterPill.setTextColor(palette.textPrimary);
        pageCounterPill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        pageCounterPill.setTypeface(Typeface.DEFAULT_BOLD);
        pageCounterPill.setPadding(dp(8), dp(4), dp(8), dp(4));
        pageCounterPill.setBackground(createBorderedRoundedDrawable(palette.bgSecondary, palette.border, dp(1), dp(12)));
        pageCounterPill.setOnClickListener(v -> showJumpToPageDialog());
        sliderLine.addView(pageCounterPill);

        pageSeekBar = new SeekBar(this);
        pageSeekBar.setMax(0);
        pageSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    updatePageCounterLabel(progress, getTotalPageCount());
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                userDraggingScrubber = true;
                cancelChromeAutoHideTimer();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                userDraggingScrubber = false;
                navigateToPage(seekBar.getProgress(), false);
                resetChromeAutoHideTimer();
            }
        });
        LinearLayout.LayoutParams seekLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        seekLp.setMargins(dp(8), 0, dp(4), 0);
        sliderLine.addView(pageSeekBar, seekLp);
        wrapper.addView(sliderLine);

        // Row 3: Java Quick Action Chips (Rotate, Theme, Scroll Mode, Drawer Notes, Print)
        LinearLayout quickRow = new LinearLayout(this);
        quickRow.setOrientation(LinearLayout.HORIZONTAL);
        quickRow.setGravity(Gravity.CENTER);
        quickRow.setPadding(0, dp(4), 0, 0);

        btnRotate = createBottomChipButton("↻ Rotate", v -> cycleRotation());
        btnTheme = createBottomChipButton("◐ Theme", v -> showThemePickerDialog());
        btnScrollMode = createBottomChipButton("⇅ Vertical", v -> toggleScrollMode());
        btnNotesChip = createBottomChipButton("☰ Notes", v -> openDrawer(DrawerPanel.NOTES_BOOKMARKS));
        btnPrintChip = createBottomChipButton("⎙ Print", v -> triggerNativePrint());
        btnPrintChip.setVisibility(View.GONE);

        quickRow.addView(btnRotate);
        quickRow.addView(btnTheme);
        quickRow.addView(btnScrollMode);
        quickRow.addView(btnNotesChip);
        quickRow.addView(btnPrintChip);
        wrapper.addView(quickRow);

        return wrapper;
    }

    @NonNull
    private LinearLayout buildSelectionPopupBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(6), dp(8), dp(6));
        bar.setBackground(createBorderedRoundedDrawable(palette.bgCard, palette.border, dp(1), dp(20)));
        bar.setElevation(dp(10));

        bar.addView(createBottomChipButton("Highlight", v -> commitTextSelectionMarkup(TextMarkupStyle.HIGHLIGHT, COLOR_HIGHLIGHT_YELLOW, false)));
        bar.addView(createBottomChipButton("Underline", v -> commitTextSelectionMarkup(TextMarkupStyle.UNDERLINE, Color.rgb(37, 99, 235), false)));
        bar.addView(createBottomChipButton("+ Note", v -> commitTextSelectionMarkup(TextMarkupStyle.HIGHLIGHT, COLOR_HIGHLIGHT_GREEN, true)));
        btnSelectionCopy = createBottomChipButton("Copy", v -> copyActiveSelectionToClipboard());
        btnSelectionCopy.setVisibility(View.GONE);
        bar.addView(btnSelectionCopy);
        return bar;
    }

    // ── Live Light / Dark UI Theme Switcher (resource-browser.css tokens) ────

    private void toggleUiDarkLightMode() {
        this.darkUiMode = !this.darkUiMode;
        this.palette = new UiPalette(darkUiMode);
        applyPaletteToViews();
        updateSystemBarIconAppearance();
    }

    private void applyPaletteToViews() {
        rootContainer.setBackgroundColor(palette.bgPrimary);
        loadingContainer.setBackground(createBorderedRoundedDrawable(palette.bgCard, palette.border, dp(1), dp(12)));
        loadingLabel.setTextColor(palette.textMuted);

        topBarContainer.setBackgroundColor(palette.bgCard);
        topHeaderDivider.setBackgroundColor(palette.border);
        btnBackPill.setTextColor(palette.textPrimary);
        btnBackPill.setBackground(createBorderedRoundedDrawable(palette.bgSecondary, palette.border, dp(1), dp(20)));
        titleTextView.setTextColor(palette.textPrimary);
        subtitleTextView.setTextColor(palette.textMuted);

        btnSearch.setTextColor(palette.textPrimary);
        btnInkToggle.setTextColor(palette.textPrimary);
        btnHamburgerMenu.setTextColor(palette.textPrimary);
        updateBookmarkIconState();

        dropdownMenuCard.setBackground(createBorderedRoundedDrawable(palette.bgCard, palette.border, dp(1), dp(10)));
        for (int i = 0; i < dropdownMenuCard.getChildCount(); i++) {
            View child = dropdownMenuCard.getChildAt(i);
            if (child instanceof TextView) {
                ((TextView) child).setTextColor(palette.textPrimary);
            } else {
                child.setBackgroundColor(palette.borderLight);
            }
        }

        searchBarContainer.setBackgroundColor(palette.bgSecondary);
        searchBarDivider.setBackgroundColor(palette.border);
        searchInput.setTextColor(palette.textPrimary);
        searchInput.setHintTextColor(palette.textMuted);
        searchInput.setBackground(createBorderedRoundedDrawable(palette.bgCard, palette.border, dp(1), dp(6)));
        searchCounterText.setTextColor(palette.textMuted);

        sideDrawerContainer.setBackgroundColor(palette.bgCard);
        if (drawerOpen) {
            renderDrawerPanelContent(activeDrawerPanel);
        }

        bottomBarContainer.setBackgroundColor(palette.bgCard);
        bottomFooterDivider.setBackgroundColor(palette.border);
        previewBannerContainer.setBackgroundColor(palette.bgSecondary);
        previewBannerLabel.setTextColor(palette.textSecondary);
        inkToolbarContainer.setBackgroundColor(palette.bgSecondary);

        btnPrevPage.setTextColor(palette.textPrimary);
        btnPrevPage.setBackground(createBorderedRoundedDrawable(palette.bgSecondary, palette.border, dp(1), dp(20)));
        btnNextPage.setTextColor(palette.textPrimary);
        btnNextPage.setBackground(createBorderedRoundedDrawable(palette.bgSecondary, palette.border, dp(1), dp(20)));
        pageNumberInput.setTextColor(palette.textPrimary);
        pageNumberInput.setBackground(createBorderedRoundedDrawable(palette.bgPrimary, palette.border, dp(1), dp(6)));
        pageTotalCountText.setTextColor(palette.textMuted);

        btnZoomOut.setTextColor(palette.textPrimary);
        btnZoomOut.setBackground(createBorderedRoundedDrawable(palette.bgSecondary, palette.border, dp(1), dp(20)));
        zoomLevelText.setTextColor(palette.textPrimary);
        btnZoomIn.setTextColor(palette.textPrimary);
        btnZoomIn.setBackground(createBorderedRoundedDrawable(palette.bgSecondary, palette.border, dp(1), dp(20)));

        pageCounterPill.setTextColor(palette.textPrimary);
        pageCounterPill.setBackground(createBorderedRoundedDrawable(palette.bgSecondary, palette.border, dp(1), dp(12)));

        TextView[] chips = { btnRotate, btnTheme, btnScrollMode, btnNotesChip, btnPrintChip };
        for (TextView chip : chips) {
            if (chip != null) {
                chip.setTextColor(palette.textPrimary);
                chip.setBackground(createBorderedRoundedDrawable(palette.bgSecondary, palette.border, dp(1), dp(10)));
            }
        }
    }

    // ── Smooth Top & Bottom Chrome Transitions (Non-Abrupt) ──────────────────

    private void setChromeVisible(boolean show, boolean animate) {
        if (this.chromeVisible == show && animate) {
            if (show) {
                resetChromeAutoHideTimer();
            }
            return;
        }
        this.chromeVisible = show;
        if (show) {
            resetChromeAutoHideTimer();
        } else {
            cancelChromeAutoHideTimer();
        }
        closeOverflowMenu();

        if (!show && selectionActionPopup.getVisibility() == View.VISIBLE) {
            selectionActionPopup.animate()
                    .alpha(0f)
                    .setDuration(150L)
                    .withEndAction(() -> selectionActionPopup.setVisibility(View.GONE))
                    .start();
        }

        WindowInsetsControllerCompat insetsController =
                WindowCompat.getInsetsController(getWindow(), rootContainer);

        if (show) {
            topBarContainer.animate().cancel();
            bottomBarContainer.animate().cancel();

            topBarContainer.setVisibility(View.VISIBLE);
            bottomBarContainer.setVisibility(View.VISIBLE);

            if (!animate) {
                topBarContainer.setTranslationY(0f);
                topBarContainer.setAlpha(1f);
                bottomBarContainer.setTranslationY(0f);
                bottomBarContainer.setAlpha(1f);
            } else {
                if (topBarContainer.getHeight() > 0 && topBarContainer.getTranslationY() == 0f && topBarContainer.getAlpha() < 0.1f) {
                    topBarContainer.setTranslationY(-topBarContainer.getHeight());
                }
                if (bottomBarContainer.getHeight() > 0 && bottomBarContainer.getTranslationY() == 0f && bottomBarContainer.getAlpha() < 0.1f) {
                    bottomBarContainer.setTranslationY(bottomBarContainer.getHeight());
                }

                topBarContainer.animate()
                        .translationY(0f)
                        .alpha(1f)
                        .setDuration(CHROME_ANIM_DURATION_MS)
                        .setInterpolator(CHROME_INTERPOLATOR)
                        .withLayer()
                        .start();

                bottomBarContainer.animate()
                        .translationY(0f)
                        .alpha(1f)
                        .setDuration(CHROME_ANIM_DURATION_MS)
                        .setInterpolator(CHROME_INTERPOLATOR)
                        .withLayer()
                        .start();
            }

            if (insetsController != null) {
                insetsController.show(WindowInsetsCompat.Type.systemBars());
            }
        } else {
            if (searchBarOpen) {
                hideKeyboard(searchInput);
            }

            topBarContainer.animate().cancel();
            bottomBarContainer.animate().cancel();

            float topTargetY = -(topBarContainer.getHeight() > 0 ? topBarContainer.getHeight() : dp(96));
            float bottomTargetY = (bottomBarContainer.getHeight() > 0 ? bottomBarContainer.getHeight() : dp(120));

            if (!animate) {
                topBarContainer.setTranslationY(topTargetY);
                topBarContainer.setAlpha(0f);
                topBarContainer.setVisibility(View.INVISIBLE);

                bottomBarContainer.setTranslationY(bottomTargetY);
                bottomBarContainer.setAlpha(0f);
                bottomBarContainer.setVisibility(View.INVISIBLE);
            } else {
                topBarContainer.animate()
                        .translationY(topTargetY)
                        .alpha(0f)
                        .setDuration(CHROME_ANIM_DURATION_MS)
                        .setInterpolator(CHROME_INTERPOLATOR)
                        .withLayer()
                        .withEndAction(() -> {
                            if (!chromeVisible) {
                                topBarContainer.setVisibility(View.INVISIBLE);
                            }
                        })
                        .start();

                bottomBarContainer.animate()
                        .translationY(bottomTargetY)
                        .alpha(0f)
                        .setDuration(CHROME_ANIM_DURATION_MS)
                        .setInterpolator(CHROME_INTERPOLATOR)
                        .withLayer()
                        .withEndAction(() -> {
                            if (!chromeVisible) {
                                bottomBarContainer.setVisibility(View.INVISIBLE);
                            }
                        })
                        .start();
            }

            if (insetsController != null) {
                insetsController.hide(WindowInsetsCompat.Type.systemBars());
            }
        }
    }

    private boolean shouldPauseChromeAutoHide() {
        return searchBarOpen
                || inkBarOpen
                || overflowMenuOpen
                || drawerOpen
                || userDraggingScrubber
                || activeSelectionResult != null
                || (loadingContainer != null && loadingContainer.getVisibility() == View.VISIBLE)
                || (previewBlockedOverlay != null && previewBlockedOverlay.getVisibility() == View.VISIBLE)
                || (pageNumberInput != null && pageNumberInput.hasFocus())
                || (searchInput != null && searchInput.hasFocus());
    }

    private void resetChromeAutoHideTimer() {
        mainHandler.removeCallbacks(chromeAutoHideRunnable);
        if (chromeVisible && !shouldPauseChromeAutoHide()) {
            mainHandler.postDelayed(chromeAutoHideRunnable, CHROME_INACTIVITY_TIMEOUT_MS);
        }
    }

    private void cancelChromeAutoHideTimer() {
        mainHandler.removeCallbacks(chromeAutoHideRunnable);
    }

    // ── Document Resolution & Opening Pipeline ───────────────────────────────

    private void resolveAndOpenDocument(@NonNull Intent intent, @Nullable String passwordAttempt) {
        loadingContainer.setVisibility(View.VISIBLE);
        loadingLabel.setText(passwordAttempt == null ? "Loading document…" : "Decrypting PDF…");

        final String resourceId = intent.getStringExtra(EXTRA_RESOURCE_ID);
        final String relativePath = intent.getStringExtra(EXTRA_RELATIVE_PATH);
        final String absolutePath = intent.getStringExtra(EXTRA_ABSOLUTE_PATH);
        final String titleHint = intent.getStringExtra(EXTRA_TITLE);
        final String mimeHint = intent.getStringExtra(EXTRA_MIME_TYPE);
        final Uri dataUri = extractIncomingUri(intent);
        final boolean reqPreview = intent.getBooleanExtra(EXTRA_PREVIEW_MODE, false);
        final int requestedInitialPage = intent.getIntExtra(EXTRA_INITIAL_PAGE, -1);
        final boolean isExternalActionView = Intent.ACTION_VIEW.equals(intent.getAction())
                && dataUri != null
                && TextUtils.isEmpty(resourceId)
                && TextUtils.isEmpty(relativePath)
                && TextUtils.isEmpty(absolutePath);

        this.isExternalDocument = false;
        applyExternalDocumentRestrictions();

        openExecutor.submit(() -> {
            try {
                ResolvedDocument doc = null;
                if (!TextUtils.isEmpty(resourceId)) {
                    doc = MedvixStorageResolver.resolveByResourceId(this, resourceId, titleHint, mimeHint);
                }
                if (doc == null && !TextUtils.isEmpty(relativePath)) {
                    doc = MedvixStorageResolver.resolveByRelativePath(this, relativePath, titleHint, mimeHint);
                }
                if (doc == null && !TextUtils.isEmpty(absolutePath)) {
                    doc = MedvixStorageResolver.resolveByAbsolutePath(this, absolutePath, titleHint, mimeHint);
                }
                if (doc == null && dataUri != null) {
                    doc = MedvixStorageResolver.resolveByUri(this, dataUri, titleHint, mimeHint);
                }

                if (doc == null) {
                    throw new IOException("Document file could not be located on device storage.");
                }

                BitmapMemoryPool pool = this.memoryPool;
                if (pool == null) {
                    throw new IOException("Viewer memory pool unavailable.");
                }

                MedvixDocumentStore store = new MedvixDocumentStore(this, doc);

                final boolean externalDoc = isExternalActionView && doc.isExternal;
                if (doc.isPdf()) {
                    PdfDocumentEngine engine = PdfDocumentEngine.open(
                            this, doc, pool, passwordAttempt, reqPreview, null);
                    final ResolvedDocument finalDoc = doc;
                    mainHandler.post(() -> onPdfOpenedSuccess(
                            finalDoc, engine, store, reqPreview, requestedInitialPage, externalDoc));
                } else if (doc.isImage()) {
                    ImageDocumentEngine imgEngine = ImageDocumentEngine.open(this, doc, pool, null);
                    final ResolvedDocument finalDoc = doc;
                    mainHandler.post(() -> onImageOpenedSuccess(
                            finalDoc, imgEngine, store, externalDoc));
                } else {
                    store.close();
                    throw new IOException("Unsupported document format: " + doc.mimeType);
                }
            } catch (PdfPasswordRequiredException pwdEx) {
                mainHandler.post(() -> {
                    loadingContainer.setVisibility(View.GONE);
                    promptForPdfPassword(intent, passwordAttempt != null);
                });
            } catch (Throwable error) {
                mainHandler.post(() -> {
                    loadingContainer.setVisibility(View.GONE);
                    showFatalOpenError(error.getMessage());
                });
            }
        });
    }

    @Nullable
    private static Uri extractIncomingUri(@NonNull Intent intent) {
        Uri direct = intent.getData();
        if (direct != null) {
            return direct;
        }
        ClipData clipData = intent.getClipData();
        if (clipData != null && clipData.getItemCount() > 0) {
            return clipData.getItemAt(0).getUri();
        }
        return null;
    }

    private void onPdfOpenedSuccess(@NonNull ResolvedDocument doc,
                                    @NonNull PdfDocumentEngine engine,
                                    @NonNull MedvixDocumentStore store,
                                    boolean reqPreview,
                                    int requestedInitialPage,
                                    boolean externalDoc) {
        if (isFinishing() || isDestroyed()) {
            engine.close();
            store.close();
            return;
        }

        this.resolvedDoc = doc;
        this.pdfEngine = engine;
        this.documentStore = store;
        this.previewMode = reqPreview;
        this.isExternalDocument = externalDoc;
        applyExternalDocumentRestrictions();

        registerSdCardProtectionIfNeeded(doc);
        setupStoreChangeListener(store);

        ReaderState saved = store.getReaderState();
        this.rotationDegrees = saved.rotationDegrees;
        this.readingTheme = saved.readingTheme;
        this.scrollMode = saved.scrollMode;
        this.fitMode = saved.fitMode;
        this.keepScreenOn = saved.keepScreenOn;
        this.currentZoomScale = Math.max(1.0f, saved.zoomScale);
        applyKeepScreenOnFlag();

        // Sync dark UI chrome if saved reading theme is Night and no explicit theme was forced
        if (readingTheme == ReadingTheme.NIGHT && !darkUiMode
                && getIntent().getStringExtra(EXTRA_THEME) == null) {
            toggleUiDarkLightMode();
        }

        int total = engine.getTotalPageCount();
        int maxAccessible = engine.getAccessiblePageCount();
        int startPage = (requestedInitialPage >= 0) ? requestedInitialPage : saved.lastPageIndex;
        startPage = Math.max(0, Math.min(Math.max(0, maxAccessible - 1), startPage));
        this.currentPageIndex = startPage;

        titleTextView.setText(doc.displayName);
        subtitleTextView.setText(buildSubtitleString(doc, total));

        btnSearch.setVisibility(View.VISIBLE);
        btnScrollMode.setVisibility(View.VISIBLE);
        pageSeekBar.setVisibility(View.VISIBLE);

        if (previewMode) {
            previewBannerContainer.setVisibility(View.VISIBLE);
            previewBannerLabel.setText(String.format(Locale.ROOT,
                    "Preview Mode — %d of %d pages unlocked", maxAccessible, total));
            previewBlockedBodyText.setText(String.format(Locale.ROOT,
                    "Free preview includes the first %d of %d pages. Subscribe to unlock the full document.",
                    maxAccessible, total));
        } else {
            previewBannerContainer.setVisibility(View.GONE);
            previewBlockedOverlay.setVisibility(View.GONE);
        }

        pageSeekBar.setMax(Math.max(0, total - 1));
        pageSeekBar.setProgress(startPage);
        updatePageCounterLabel(startPage, total);
        updateZoomLabel(currentZoomScale);
        updateBookmarkIconState();
        updateUndoRedoButtons();
        updateMenuDynamicLabels();

        BitmapMemoryPool pool = this.memoryPool;
        if (pool != null) {
            pdfPageAdapter = new PdfPageAdapter(
                    this, engine, store, rotationDegrees, readingTheme, scrollMode, fitMode);
            pdfPageAdapter.setViewerInteractionListener(this);
            if (requestedInitialPage < 0 && startPage == saved.lastPageIndex) {
                pdfPageAdapter.scheduleInitialViewportRestore(
                        startPage, saved.zoomScale, saved.normalizedScrollX, saved.normalizedScrollY);
            }

            applyLayoutManagerForScrollMode(scrollMode);
            pdfRecyclerView.setAdapter(pdfPageAdapter);
            pdfRecyclerView.setVisibility(View.VISIBLE);
            standaloneImageCanvas.setVisibility(View.GONE);

            if (startPage > 0) {
                pdfRecyclerView.scrollToPosition(startPage);
            }
            pdfPageAdapter.onFocusedPageChanged(startPage);
            pdfRecyclerView.post(this::notifyVisiblePagesToEngine);
        }

        documentOutlineItems.clear();
        if (activeOutlineTask != null) {
            activeOutlineTask.cancel();
        }
        activeOutlineTask = engine.extractOutlineAsync(new PdfDocumentEngine.OutlineCallback() {
            @Override
            public void onOutlineLoaded(@NonNull List<PdfDocumentEngine.OutlineItem> items) {
                documentOutlineItems.clear();
                documentOutlineItems.addAll(items);
                if (drawerOpen && activeDrawerPanel == DrawerPanel.OUTLINE) {
                    renderDrawerPanelContent(DrawerPanel.OUTLINE);
                }
            }

            @Override
            public void onOutlineError(@NonNull Throwable error) {
                documentOutlineItems.clear();
            }
        });

        loadingContainer.setVisibility(View.GONE);
        resetChromeAutoHideTimer();
    }

    private void onImageOpenedSuccess(@NonNull ResolvedDocument doc,
                                      @NonNull ImageDocumentEngine imgEngine,
                                      @NonNull MedvixDocumentStore store,
                                      boolean externalDoc) {
        if (isFinishing() || isDestroyed()) {
            imgEngine.close();
            store.close();
            return;
        }

        this.resolvedDoc = doc;
        this.imageEngine = imgEngine;
        this.documentStore = store;
        this.previewMode = false;
        this.isExternalDocument = externalDoc;
        this.currentPageIndex = 0;
        applyExternalDocumentRestrictions();

        registerSdCardProtectionIfNeeded(doc);
        setupStoreChangeListener(store);

        ReaderState saved = store.getReaderState();
        this.rotationDegrees = saved.rotationDegrees;
        this.readingTheme = saved.readingTheme;
        this.fitMode = (saved.fitMode == FitMode.CUSTOM_ZOOM) ? FitMode.FIT_PAGE : saved.fitMode;
        this.keepScreenOn = saved.keepScreenOn;
        this.currentZoomScale = Math.max(1.0f, saved.zoomScale);
        applyKeepScreenOnFlag();

        ImageDocumentEngine.ImageMetrics m = imgEngine.getMetrics();
        titleTextView.setText(doc.displayName);
        subtitleTextView.setText(String.format(Locale.ROOT, "%d × %d px • %s",
                m.getNormalizedWidthPx(), m.getNormalizedHeightPx(), doc.mimeType));

        btnSearch.setVisibility(View.GONE);
        btnScrollMode.setVisibility(View.GONE);
        pageSeekBar.setVisibility(View.INVISIBLE);
        previewBlockedOverlay.setVisibility(View.GONE);
        updatePageCounterLabel(0, 1);
        updateZoomLabel(currentZoomScale);
        updateBookmarkIconState();
        updateUndoRedoButtons();
        updateMenuDynamicLabels();

        pdfRecyclerView.setVisibility(View.GONE);
        standaloneImageCanvas.setVisibility(View.VISIBLE);
        standaloneImageCanvas.bindImageDocument(imgEngine, store, rotationDegrees, readingTheme, fitMode);
        if (saved.zoomScale > 1.01f) {
            standaloneImageCanvas.restoreViewportNormalized(
                    saved.zoomScale, saved.normalizedScrollX, saved.normalizedScrollY);
        }

        imgEngine.ensureThumbnailCachedAsync(360, null);
        loadingContainer.setVisibility(View.GONE);
        resetChromeAutoHideTimer();
    }

    private void setupStoreChangeListener(@NonNull MedvixDocumentStore store) {
        store.setStoreChangeListener(new MedvixDocumentStore.StoreChangeListener() {
            @Override
            public void onAnnotationsChanged(int affectedPageIndex) {
                updateUndoRedoButtons();
                if (pdfPageAdapter != null) {
                    pdfPageAdapter.notifyAnnotationsUpdated(affectedPageIndex);
                }
                if (standaloneImageCanvas.getVisibility() == View.VISIBLE) {
                    standaloneImageCanvas.invalidate();
                }
                if (drawerOpen && activeDrawerPanel == DrawerPanel.NOTES_BOOKMARKS) {
                    renderDrawerPanelContent(DrawerPanel.NOTES_BOOKMARKS);
                }
            }

            @Override
            public void onBookmarksChanged() {
                updateBookmarkIconState();
                if (drawerOpen && activeDrawerPanel == DrawerPanel.NOTES_BOOKMARKS) {
                    renderDrawerPanelContent(DrawerPanel.NOTES_BOOKMARKS);
                }
            }
        });
    }

    // ── Password & Error Dialogs ─────────────────────────────────────────────

    private void promptForPdfPassword(@NonNull Intent intent, boolean previousAttemptWrong) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("Enter document password");

        FrameLayout container = new FrameLayout(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(20), dp(8), dp(20), 0);
        container.addView(input, lp);

        new AlertDialog.Builder(this)
                .setTitle(previousAttemptWrong ? "Incorrect Password" : "Password Protected PDF")
                .setMessage(previousAttemptWrong
                        ? "That password was rejected. Please try again."
                        : "This PDF requires a password to unlock and read.")
                .setView(container)
                .setCancelable(false)
                .setPositiveButton("Unlock", (dialog, which) -> {
                    String pwd = input.getText().toString();
                    resolveAndOpenDocument(intent, pwd);
                })
                .setNegativeButton("Cancel", (dialog, which) -> finish())
                .show();
    }

    private void showFatalOpenError(@Nullable String message) {
        new AlertDialog.Builder(this)
                .setTitle("Unable to Open Document")
                .setMessage(!TextUtils.isEmpty(message) ? message : "An unexpected error occurred while loading this file.")
                .setCancelable(false)
                .setPositiveButton("Close", (d, w) -> finish())
                .show();
    }

    // ── Navigation, Zoom, Scroll Mode & Page Sync ────────────────────────────

    private int getTotalPageCount() {
        if (pdfEngine != null) return pdfEngine.getTotalPageCount();
        return imageEngine != null ? 1 : 0;
    }

    private void navigateToPage(int targetPage, boolean smooth) {
        int total = getTotalPageCount();
        if (total <= 0) return;
        int clamped = Math.max(0, Math.min(total - 1, targetPage));

        this.currentPageIndex = clamped;
        pageSeekBar.setProgress(clamped);
        updatePageCounterLabel(clamped, total);
        updateBookmarkIconState();
        updatePreviewBlockedOverlayState(clamped);

        if (pdfEngine != null && pdfRecyclerView.getVisibility() == View.VISIBLE) {
            if (smooth) {
                pdfRecyclerView.smoothScrollToPosition(clamped);
            } else {
                pdfLayoutManager.scrollToPositionWithOffset(clamped, 0);
            }
            if (pdfPageAdapter != null) {
                pdfPageAdapter.onFocusedPageChanged(clamped);
            }
            pdfRecyclerView.post(this::notifyVisiblePagesToEngine);
        }
        persistCurrentViewportState();
    }

    private void notifyVisiblePagesToEngine() {
        if (pdfEngine == null || pdfLayoutManager == null || pdfRecyclerView.getVisibility() != View.VISIBLE) {
            return;
        }
        int firstVisible = pdfLayoutManager.findFirstVisibleItemPosition();
        int lastVisible = pdfLayoutManager.findLastVisibleItemPosition();
        if (firstVisible == RecyclerView.NO_POSITION || lastVisible == RecyclerView.NO_POSITION) {
            firstVisible = currentPageIndex;
            lastVisible = Math.min(Math.max(0, getTotalPageCount() - 1), currentPageIndex + 1);
        }

        int paddedWidth = pdfRecyclerView.getWidth()
                - pdfRecyclerView.getPaddingLeft()
                - pdfRecyclerView.getPaddingRight();
        float viewWidthPx = paddedWidth > 0
                ? paddedWidth
                : Math.max(1f, getResources().getDisplayMetrics().widthPixels - dp(6));

        PdfDocumentEngine.PageMetrics refMetrics = pdfEngine.getEstimatedPageMetrics(firstVisible);
        float rotWidthPt = Math.max(1f, refMetrics.getRotatedWidthPt(rotationDegrees));
        float targetScale = (viewWidthPx / rotWidthPt) * Math.max(1.0f, currentZoomScale);

        pdfEngine.setVisiblePageRange(firstVisible, lastVisible, targetScale, rotationDegrees);
    }

    private void updatePreviewBlockedOverlayState(int pageIndex) {
        if (!previewMode || pdfEngine == null) {
            previewBlockedOverlay.setVisibility(View.GONE);
            return;
        }
        int accessible = pdfEngine.getAccessiblePageCount();
        previewBlockedOverlay.setVisibility(pageIndex >= accessible ? View.VISIBLE : View.GONE);
    }

    private void commitPageInputJump() {
        if (pageNumberInput == null) return;
        try {
            int entered = Integer.parseInt(pageNumberInput.getText().toString().trim());
            navigateToPage(entered - 1, false);
        } catch (NumberFormatException ignored) {
            updatePageCounterLabel(currentPageIndex, getTotalPageCount());
        }
    }

    private void stepDocumentZoom(float delta) {
        setDocumentZoomScale(currentZoomScale + delta);
    }

    private void applyFitWidthZoom() {
        this.fitMode = FitMode.FIT_WIDTH;
        setDocumentZoomScale(1.0f);
    }

    private void setDocumentZoomScale(float targetScale) {
        float clamped = Math.max(1.0f, Math.min(5.0f, targetScale));
        this.currentZoomScale = clamped;
        this.fitMode = (clamped > 1.01f) ? FitMode.CUSTOM_ZOOM : FitMode.FIT_WIDTH;
        updateZoomLabel(clamped);

        float normX = 0.5f;
        float normY = 0.5f;
        if (documentStore != null) {
            ReaderState cur = documentStore.getReaderState();
            normX = cur.normalizedScrollX;
            normY = cur.normalizedScrollY;
            documentStore.updateViewportPosition(currentPageIndex, clamped, normX, normY, fitMode);
        }

        if (standaloneImageCanvas.getVisibility() == View.VISIBLE) {
            standaloneImageCanvas.restoreViewportNormalized(clamped, normX, normY);
        } else if (pdfRecyclerView.getVisibility() == View.VISIBLE && pdfPageAdapter != null) {
            pdfPageAdapter.setSharedZoomScale(clamped);
        }
    }

    private void updateZoomLabel(float zoomFactor) {
        if (zoomLevelText != null) {
            int pct = Math.round(Math.max(0.25f, zoomFactor) * 100f);
            zoomLevelText.setText(pct + "%");
        }
    }

    private void syncCurrentPageFromScroll() {
        if (pdfEngine == null || pdfLayoutManager == null) return;
        int firstVisible = pdfLayoutManager.findFirstVisibleItemPosition();
        if (firstVisible == RecyclerView.NO_POSITION) return;

        int focused = firstVisible;
        if (scrollMode == ScrollMode.VERTICAL_CONTINUOUS) {
            int midY = pdfRecyclerView.getHeight() / 2;
            View centerChild = pdfRecyclerView.findChildViewUnder(pdfRecyclerView.getWidth() * 0.5f, midY);
            if (centerChild != null) {
                int pos = pdfRecyclerView.getChildAdapterPosition(centerChild);
                if (pos != RecyclerView.NO_POSITION) {
                    focused = pos;
                }
            }
        }

        if (focused != this.currentPageIndex && focused >= 0 && focused < getTotalPageCount()) {
            this.currentPageIndex = focused;
            pageSeekBar.setProgress(focused);
            updatePageCounterLabel(focused, getTotalPageCount());
            updateBookmarkIconState();
            updatePreviewBlockedOverlayState(focused);
            if (pdfPageAdapter != null) {
                pdfPageAdapter.onFocusedPageChanged(focused);
            }
        }
    }

    private void applyLayoutManagerForScrollMode(@NonNull ScrollMode mode) {
        if (mode == ScrollMode.VERTICAL_CONTINUOUS) {
            pagerSnapHelper.attachToRecyclerView(null);
            pdfLayoutManager.setOrientation(RecyclerView.VERTICAL);
            btnScrollMode.setText("⇅ Vertical");
        } else {
            pdfLayoutManager.setOrientation(RecyclerView.HORIZONTAL);
            pagerSnapHelper.attachToRecyclerView(pdfRecyclerView);
            btnScrollMode.setText("⇄ Paged");
        }
        updateMenuDynamicLabels();
    }

    private void toggleScrollMode() {
        ScrollMode next = (scrollMode == ScrollMode.VERTICAL_CONTINUOUS)
                ? ScrollMode.HORIZONTAL_PAGED
                : ScrollMode.VERTICAL_CONTINUOUS;
        this.scrollMode = next;
        applyLayoutManagerForScrollMode(next);
        if (pdfPageAdapter != null) {
            pdfPageAdapter.setScrollMode(next);
            pdfRecyclerView.scrollToPosition(currentPageIndex);
        }
        if (documentStore != null) {
            documentStore.updateReadingPreferences(readingTheme, scrollMode, keepScreenOn);
        }
    }

    private void cycleRotation() {
        this.rotationDegrees = (this.rotationDegrees + 90) % 360;
        if (pdfPageAdapter != null) {
            pdfPageAdapter.setRotationDegrees(rotationDegrees);
        }
        if (standaloneImageCanvas.getVisibility() == View.VISIBLE) {
            standaloneImageCanvas.setRotationDegrees(rotationDegrees);
        }
        if (documentStore != null) {
            documentStore.updateRotation(rotationDegrees);
        }
    }

    // ── Search Execution & Match Navigation ──────────────────────────────────

    private void toggleSearchBar(boolean open) {
        if (pdfEngine == null) return;
        this.searchBarOpen = open;
        if (open) {
            if (!chromeVisible) setChromeVisible(true, true);
            cancelChromeAutoHideTimer();
            searchBarContainer.setVisibility(View.VISIBLE);
            searchInput.requestFocus();
            showKeyboard(searchInput);
        } else {
            hideKeyboard(searchInput);
            searchInput.clearFocus();
            searchBarContainer.setVisibility(View.GONE);
            if (activeSearchTask != null) {
                activeSearchTask.cancel();
                activeSearchTask = null;
            }
            allSearchMatches.clear();
            currentSearchMatchCursor = -1;
            searchCounterText.setText("0/0");
            if (pdfPageAdapter != null) {
                pdfPageAdapter.clearSearchMatches();
            }
            if (drawerOpen && activeDrawerPanel == DrawerPanel.SEARCH_RESULTS) {
                renderDrawerPanelContent(DrawerPanel.SEARCH_RESULTS);
            }
            resetChromeAutoHideTimer();
        }
    }

    private void executeDocumentSearch(@NonNull String rawQuery) {
        PdfDocumentEngine engine = this.pdfEngine;
        if (engine == null) return;

        String query = rawQuery.trim();
        if (activeSearchTask != null) {
            activeSearchTask.cancel();
            activeSearchTask = null;
        }
        allSearchMatches.clear();
        currentSearchMatchCursor = -1;
        if (pdfPageAdapter != null) {
            pdfPageAdapter.clearSearchMatches();
        }

        if (query.isEmpty()) {
            searchCounterText.setText("0/0");
            return;
        }

        searchCounterText.setText("…");
        activeSearchTask = engine.searchDocumentAsync(
                query, searchMatchCase, searchWholeWord, new PdfDocumentEngine.SearchCallback() {
                    @Override
                    public void onPageMatchesFound(int pageIndex,
                                                   @NonNull List<SearchMatch> pageMatches,
                                                   int runningTotal) {
                        boolean wasEmpty = allSearchMatches.isEmpty();
                        allSearchMatches.addAll(pageMatches);
                        if (wasEmpty && !allSearchMatches.isEmpty()) {
                            currentSearchMatchCursor = 0;
                        }
                        updateSearchHighlightsOnAdapter(wasEmpty);
                    }

                    @Override
                    public void onSearchCompleted(int totalMatches) {
                        if (totalMatches == 0) {
                            searchCounterText.setText("0/0");
                            Toast.makeText(MedvixPdfActivity.this, "No matches found", Toast.LENGTH_SHORT).show();
                        } else {
                            updateSearchCounterLabel();
                        }
                        if (drawerOpen && activeDrawerPanel == DrawerPanel.SEARCH_RESULTS) {
                            renderDrawerPanelContent(DrawerPanel.SEARCH_RESULTS);
                        }
                    }

                    @Override
                    public void onSearchError(@NonNull Throwable error) {
                        searchCounterText.setText("0/0");
                    }
                });
    }

    private void stepSearchMatch(int delta) {
        if (allSearchMatches.isEmpty()) return;
        int size = allSearchMatches.size();
        currentSearchMatchCursor = (currentSearchMatchCursor + delta + size) % size;
        updateSearchHighlightsOnAdapter(true);
    }

    private void updateSearchHighlightsOnAdapter(boolean scrollToActiveMatch) {
        updateSearchCounterLabel();
        if (allSearchMatches.isEmpty() || pdfPageAdapter == null) return;

        SearchMatch active = allSearchMatches.get(Math.max(0, currentSearchMatchCursor));
        pdfPageAdapter.setSearchMatches(allSearchMatches, active.pageIndex, active.matchIndexOnPage);
        if (scrollToActiveMatch) {
            navigateToPage(active.pageIndex, true);
        }
    }

    private void updateSearchCounterLabel() {
        if (allSearchMatches.isEmpty()) {
            searchCounterText.setText("0/0");
        } else {
            searchCounterText.setText(String.format(Locale.ROOT, "%d/%d",
                    currentSearchMatchCursor + 1, allSearchMatches.size()));
        }
    }

    // ── Ink Toolbar, Undo/Redo & Bookmarks ───────────────────────────────────

    private void toggleInkToolbar(boolean open) {
        this.inkBarOpen = open;
        if (open) {
            if (!chromeVisible) setChromeVisible(true, true);
            cancelChromeAutoHideTimer();
            inkToolbarContainer.setVisibility(View.VISIBLE);
            setActiveInteractionMode(InteractionMode.INK_PEN);
        } else {
            inkToolbarContainer.setVisibility(View.GONE);
            setActiveInteractionMode(InteractionMode.READ);
            resetChromeAutoHideTimer();
        }
    }

    private void setActiveInteractionMode(@NonNull InteractionMode mode) {
        this.interactionMode = mode;
        applyInkBrushToViews();
        if (pdfPageAdapter != null) {
            pdfPageAdapter.setInteractionMode(mode);
        }
        if (standaloneImageCanvas.getVisibility() == View.VISIBLE) {
            standaloneImageCanvas.setInteractionMode(mode);
        }
    }

    private void applyInkBrushToViews() {
        float width = (interactionMode == InteractionMode.INK_HIGHLIGHTER) ? 14f : selectedInkWidthPt;
        if (pdfPageAdapter != null) {
            pdfPageAdapter.setInkBrush(selectedInkColor, width);
        }
        if (standaloneImageCanvas.getVisibility() == View.VISIBLE) {
            standaloneImageCanvas.setInkBrush(selectedInkColor, width);
        }
    }

    private void handleUndo() {
        if (documentStore != null && documentStore.canUndo()) {
            int page = documentStore.undo();
            if (page >= 0 && page != currentPageIndex) {
                navigateToPage(page, true);
            }
        }
    }

    private void handleRedo() {
        if (documentStore != null && documentStore.canRedo()) {
            int page = documentStore.redo();
            if (page >= 0 && page != currentPageIndex) {
                navigateToPage(page, true);
            }
        }
    }

    private void updateUndoRedoButtons() {
        boolean canU = documentStore != null && documentStore.canUndo();
        boolean canR = documentStore != null && documentStore.canRedo();
        if (btnUndo != null) btnUndo.setAlpha(canU ? 1.0f : 0.4f);
        if (btnRedo != null) btnRedo.setAlpha(canR ? 1.0f : 0.4f);
    }

    private void handleToggleBookmark() {
        if (documentStore == null) return;
        boolean added = documentStore.toggleBookmark(currentPageIndex, null);
        Toast.makeText(this,
                added ? ("Bookmarked Page " + (currentPageIndex + 1)) : "Bookmark removed",
                Toast.LENGTH_SHORT).show();
    }

    private void updateBookmarkIconState() {
        if (btnBookmark == null) return;
        boolean bookmarked = documentStore != null && documentStore.isPageBookmarked(currentPageIndex);
        btnBookmark.setText(bookmarked ? "★" : "☆");
        btnBookmark.setTextColor(bookmarked ? palette.warning : palette.textPrimary);
    }

    // ── Text Selection Actions & Annotation Editing ──────────────────────────

    private void commitTextSelectionMarkup(@NonNull TextMarkupStyle style,
                                           int argbColor,
                                           boolean promptForNote) {
        TextSelectionResult sel = this.activeSelectionResult;
        DocumentCanvasView canvas = this.activeSelectionCanvas;
        MedvixDocumentStore store = this.documentStore;
        if (sel == null || store == null) return;

        if (!promptForNote) {
            store.addTextMarkup(sel.pageIndex, style, argbColor, sel.selectedText, "", sel.boundsPt);
            if (canvas != null) canvas.clearTextSelection(true);
            return;
        }

        EditText noteInput = new EditText(this);
        noteInput.setHint("Write study note…");
        new AlertDialog.Builder(this)
                .setTitle("Add Study Note")
                .setMessage("\"" + trimSnippet(sel.selectedText, 90) + "\"")
                .setView(noteInput)
                .setPositiveButton("Save", (d, w) -> {
                    store.addTextMarkup(sel.pageIndex, style, argbColor,
                            sel.selectedText, noteInput.getText().toString(), sel.boundsPt);
                    if (canvas != null) canvas.clearTextSelection(true);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void applyExternalDocumentRestrictions() {
        int externalVisibility = isExternalDocument ? View.VISIBLE : View.GONE;

        if (btnPrintChip != null) {
            btnPrintChip.setVisibility(externalVisibility);
            btnPrintChip.setEnabled(isExternalDocument);
        }
        if (menuItemPrint != null) {
            menuItemPrint.setVisibility(externalVisibility);
            menuItemPrint.setEnabled(isExternalDocument);
        }
        if (menuItemOpenFile != null) {
            menuItemOpenFile.setVisibility(externalVisibility);
            menuItemOpenFile.setEnabled(isExternalDocument);
        }
        if (btnSelectionCopy != null) {
            boolean canCopy = !previewMode;
            btnSelectionCopy.setVisibility(canCopy ? View.VISIBLE : View.GONE);
            btnSelectionCopy.setEnabled(canCopy);
        }
        if (menuItemShare != null) {
            boolean hasShareableSource = resolvedDoc != null
                    && (resolvedDoc.hasBackingFile() || resolvedDoc.getSourceUri() != null);
            boolean canShare = isExternalDocument && hasShareableSource && !previewMode;
            menuItemShare.setVisibility(canShare ? View.VISIBLE : View.GONE);
            menuItemShare.setEnabled(canShare);
        }
    }

    private void copyActiveSelectionToClipboard() {
        if (previewMode) return;
        TextSelectionResult sel = this.activeSelectionResult;
        if (sel == null || TextUtils.isEmpty(sel.selectedText)) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("MedVix Excerpt", sel.selectedText));
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show();
        }
        if (activeSelectionCanvas != null) {
            activeSelectionCanvas.clearTextSelection(true);
        }
    }

    // ── Dialogs (Theme, Jump-to-Page, Print, Share) ──────────────────────────

    private void showThemePickerDialog() {
        String[] labels = {
                "Day (Original White)",
                "Sepia (Warm Eye-Comfort)",
                "Night (Smart Dark)",
                "High Contrast (Scans)"
        };
        ReadingTheme[] themes = {
                ReadingTheme.DAY,
                ReadingTheme.SEPIA,
                ReadingTheme.NIGHT,
                ReadingTheme.HIGH_CONTRAST
        };
        int checked = 0;
        for (int i = 0; i < themes.length; i++) {
            if (themes[i] == readingTheme) checked = i;
        }

        new AlertDialog.Builder(this)
                .setTitle("Page Reading Filter")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    this.readingTheme = themes[which];
                    if (pdfPageAdapter != null) {
                        pdfPageAdapter.setReadingTheme(readingTheme);
                    }
                    if (standaloneImageCanvas.getVisibility() == View.VISIBLE) {
                        standaloneImageCanvas.setReadingTheme(readingTheme);
                    }
                    if (documentStore != null) {
                        documentStore.updateReadingPreferences(readingTheme, scrollMode, keepScreenOn);
                    }
                    // Sync dark UI chrome automatically with Night reading theme
                    if ((readingTheme == ReadingTheme.NIGHT) != darkUiMode) {
                        toggleUiDarkLightMode();
                    }
                    dialog.dismiss();
                })
                .show();
    }

    private void showJumpToPageDialog() {
        int total = getTotalPageCount();
        if (total <= 1) return;

        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("1 – " + total);
        input.setText(String.valueOf(currentPageIndex + 1));
        input.selectAll();

        new AlertDialog.Builder(this)
                .setTitle("Go to Page (1 – " + total + ")")
                .setView(input)
                .setPositiveButton("Go", (d, w) -> {
                    try {
                        int pageNum = Integer.parseInt(input.getText().toString().trim());
                        navigateToPage(pageNum - 1, false);
                    } catch (NumberFormatException ignored) {
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

private void triggerNativePrint() {
        if (!isExternalDocument) return;
        String title = resolvedDoc != null ? resolvedDoc.displayName : "MedVix Document";
        if (pdfEngine != null) {
            MedvixDocumentPrintAdapter.printPdf(
                    this, pdfEngine, documentStore, title, rotationDegrees, true, isExternalDocument);
        } else if (imageEngine != null) {
            MedvixDocumentPrintAdapter.printImage(
                    this, imageEngine, documentStore, title, rotationDegrees, true, isExternalDocument);
        }
    }

    private void shareBackingDocumentFile() {
        if (!isExternalDocument || resolvedDoc == null || previewMode) return;

        try {
            Uri shareUri = null;
            File file = resolvedDoc.getBackingFile();
            if (file != null && file.exists()) {
                shareUri = FileProvider.getUriForFile(
                        this, getPackageName() + ".fileprovider", file);
            } else if (resolvedDoc.getSourceUri() != null) {
                shareUri = resolvedDoc.getSourceUri();
            }
            if (shareUri == null) return;

            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType(resolvedDoc.mimeType);
            share.putExtra(Intent.EXTRA_STREAM, shareUri);
            share.putExtra(Intent.EXTRA_SUBJECT, resolvedDoc.displayName);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Share " + resolvedDoc.displayName));
        } catch (Throwable t) {
            Toast.makeText(this, "Unable to share this file", Toast.LENGTH_SHORT).show();
        }
    }

    private void triggerUnlockSubscription() {
        this.resultAction = RESULT_ACTION_UNLOCK_PREMIUM;
        finish();
    }

    // ── DocumentCanvasView.ViewerInteractionListener Implementation ──────────

    @Override
    public void onSingleTap(@NonNull DocumentCanvasView view, int pageIndex) {
        if (overflowMenuOpen) {
            closeOverflowMenu();
            return;
        }
        setChromeVisible(!chromeVisible, true);
    }

    @Override
    public void onLinkActivated(@NonNull DocumentCanvasView view,
                                int pageIndex,
                                @NonNull DocumentLink link) {
        if (link.isInternalGoto) {
            navigateToPage(link.targetPageIndex, true);
        } else if (link.externalUri != null) {
            new AlertDialog.Builder(this)
                    .setTitle("Open External Link?")
                    .setMessage(link.externalUri.toString())
                    .setPositiveButton("Open", (d, w) -> {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, link.externalUri));
                        } catch (Throwable ignored) {
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }

    @Override
    public void onTextSelected(@NonNull DocumentCanvasView view,
                               int pageIndex,
                               @NonNull TextSelectionResult selection,
                               @NonNull RectF anchorScreenRect) {
        this.activeSelectionCanvas = view;
        this.activeSelectionResult = selection;

        selectionActionPopup.setVisibility(View.VISIBLE);
        selectionActionPopup.setAlpha(0f);
        selectionActionPopup.post(() -> {
            float targetY = Math.max(
                    topBarContainer.getBottom() + dp(8),
                    anchorScreenRect.top - selectionActionPopup.getHeight() - dp(10)
            );
            selectionActionPopup.setTranslationY(targetY);
            selectionActionPopup.animate()
                    .alpha(1f)
                    .setDuration(180L)
                    .setInterpolator(CHROME_INTERPOLATOR)
                    .start();
        });
    }

    @Override
    public void onTextSelectionCleared(@NonNull DocumentCanvasView view, int pageIndex) {
        if (this.activeSelectionCanvas == view) {
            this.activeSelectionCanvas = null;
            this.activeSelectionResult = null;
            resetChromeAutoHideTimer();
        }
        selectionActionPopup.animate()
                .alpha(0f)
                .setDuration(140L)
                .withEndAction(() -> selectionActionPopup.setVisibility(View.GONE))
                .start();
    }

    @Override
    public void onMarkupAnnotationTapped(@NonNull DocumentCanvasView view,
                                         int pageIndex,
                                         @NonNull TextMarkupAnnotation annotation,
                                         @NonNull RectF anchorScreenRect) {
        if (documentStore == null) return;
        EditText noteInput = new EditText(this);
        noteInput.setText(annotation.note);
        noteInput.setHint("Add or edit study note…");

        new AlertDialog.Builder(this)
                .setTitle("Annotation")
                .setMessage("\"" + trimSnippet(annotation.quotedText, 90) + "\"")
                .setView(noteInput)
                .setPositiveButton("Save Note", (d, w) ->
                        documentStore.updateTextMarkupNoteAndColor(
                                pageIndex, annotation.id, noteInput.getText().toString(), annotation.argbColor))
                .setNeutralButton("Delete", (d, w) ->
                        documentStore.deleteTextMarkup(pageIndex, annotation.id))
                .setNegativeButton("Close", null)
                .show();
    }

    @Override
    public void onViewportTransformChanged(@NonNull DocumentCanvasView view,
                                           int pageIndex,
                                           float zoomFactor,
                                           float normalizedScrollX,
                                           float normalizedScrollY) {
        this.currentZoomScale = zoomFactor;
        this.fitMode = (zoomFactor > 1.01f) ? FitMode.CUSTOM_ZOOM : FitMode.FIT_WIDTH;
        updateZoomLabel(zoomFactor);

        if (pdfPageAdapter != null && pdfRecyclerView.getVisibility() == View.VISIBLE) {
            pdfPageAdapter.setViewportState(zoomFactor, normalizedScrollX, normalizedScrollY, view);
        }

        if (documentStore != null) {
            documentStore.updateViewportPosition(
                    pageIndex, zoomFactor, normalizedScrollX, normalizedScrollY, fitMode);
        }
    }

    @Override
    public void onUpgradePreviewRequested(int pageIndex) {
        triggerUnlockSubscription();
    }

    // ── SD-Card Unmount Protection & Resource Teardown ───────────────────────

    private void registerSdCardProtectionIfNeeded(@NonNull ResolvedDocument doc) {
        unregisterMediaEjectReceiver();
        if (!doc.isRemovableStorage) return;

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_EJECT);
        filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
        filter.addAction(Intent.ACTION_MEDIA_REMOVED);
        filter.addDataScheme("file");

        mediaEjectReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                closeCurrentDocumentResources();
                Toast.makeText(MedvixPdfActivity.this,
                        "SD card removed — document closed safely", Toast.LENGTH_LONG).show();
                finish();
            }
        };
        registerReceiver(mediaEjectReceiver, filter);
    }

    private void unregisterMediaEjectReceiver() {
        if (mediaEjectReceiver != null) {
            try {
                unregisterReceiver(mediaEjectReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            mediaEjectReceiver = null;
        }
    }

    private void closeCurrentDocumentResources() {
        if (activeOutlineTask != null) {
            activeOutlineTask.cancel();
            activeOutlineTask = null;
        }
        documentOutlineItems.clear();
        if (pdfPageAdapter != null) {
            pdfPageAdapter.shutdown();
            pdfRecyclerView.setAdapter(null);
            pdfPageAdapter = null;
        }
        standaloneImageCanvas.unbind();

        if (documentStore != null) {
            documentStore.close();
            documentStore = null;
        }
        if (pdfEngine != null) {
            pdfEngine.close();
            pdfEngine = null;
        }
        if (imageEngine != null) {
            imageEngine.close();
            imageEngine = null;
        }
    }

    // ── UI & Formatting Helpers ──────────────────────────────────────────────

    private void persistCurrentViewportState() {
        if (documentStore != null) {
            ReaderState cur = documentStore.getReaderState();
            documentStore.updateViewportPosition(
                    currentPageIndex, cur.zoomScale, cur.normalizedScrollX, cur.normalizedScrollY, fitMode);
        }
    }

    private void applyKeepScreenOnFlag() {
        if (keepScreenOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void updatePageCounterLabel(int pageIndex, int totalPages) {
        int safeTotal = Math.max(1, totalPages);
        int humanPage = Math.max(1, Math.min(safeTotal, pageIndex + 1));

        if (pageCounterPill != null) {
            pageCounterPill.setText(String.format(Locale.ROOT, "%d / %d", humanPage, safeTotal));
        }
        if (pageNumberInput != null && !pageNumberInput.hasFocus()) {
            pageNumberInput.setText(String.valueOf(humanPage));
        }
        if (pageTotalCountText != null) {
            pageTotalCountText.setText(" of " + safeTotal);
        }
        if (btnPrevPage != null) {
            boolean canPrev = pageIndex > 0;
            btnPrevPage.setEnabled(canPrev);
            btnPrevPage.setAlpha(canPrev ? 1.0f : 0.4f);
        }
        if (btnNextPage != null) {
            boolean canNext = pageIndex < (safeTotal - 1);
            btnNextPage.setEnabled(canNext);
            btnNextPage.setAlpha(canNext ? 1.0f : 0.4f);
        }
    }

    @NonNull
    private static String buildSubtitleString(@NonNull ResolvedDocument doc, int totalPages) {
        String sizeStr = formatByteSize(doc.byteLength);
        if (totalPages > 0) {
            return String.format(Locale.ROOT, "%d pages • %s", totalPages, sizeStr);
        }
        return sizeStr;
    }

    @NonNull
    private static String formatByteSize(long bytes) {
        if (bytes <= 0) return "PDF";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.ROOT, "%.0f KB", kb);
        double mb = kb / 1024.0;
        return String.format(Locale.ROOT, "%.1f MB", mb);
    }

    @NonNull
    private static String trimSnippet(@NonNull String text, int maxLen) {
        String clean = text.replace('\n', ' ').trim();
        if (clean.length() <= maxLen) return clean;
        return clean.substring(0, Math.max(1, maxLen - 1)) + "…";
    }

    @NonNull
    private TextView createToolbarIconButton(@NonNull String label,
                                             @NonNull String contentDesc,
                                             @NonNull View.OnClickListener listener) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setContentDescription(contentDesc);
        btn.setTextColor(palette.textPrimary);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        btn.setGravity(Gravity.CENTER);
        btn.setMinimumWidth(dp(40));
        btn.setMinimumHeight(dp(40));
        btn.setPadding(dp(6), dp(6), dp(6), dp(6));
        btn.setOnClickListener(listener);
        return btn;
    }

    @NonNull
    private TextView createFooterPillButton(@NonNull String label,
                                            @NonNull String contentDesc,
                                            @NonNull View.OnClickListener listener) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setContentDescription(contentDesc);
        btn.setTextColor(palette.textPrimary);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setMinimumWidth(dp(38));
        btn.setMinimumHeight(dp(36));
        btn.setPadding(dp(10), dp(4), dp(10), dp(4));
        btn.setBackground(createBorderedRoundedDrawable(palette.bgSecondary, palette.border, dp(1), dp(20)));
        btn.setOnClickListener(listener);
        return btn;
    }

    @NonNull
    private TextView createSmallTogglePill(@NonNull String label,
                                           @NonNull String contentDesc,
                                           boolean active,
                                           @NonNull View.OnClickListener listener) {
        TextView pill = new TextView(this);
        pill.setText(label);
        pill.setContentDescription(contentDesc);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(7), dp(5), dp(7), dp(5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(3), 0, dp(2), 0);
        pill.setLayoutParams(lp);
        styleTogglePill(pill, active);
        pill.setOnClickListener(listener);
        return pill;
    }

    private void styleTogglePill(@NonNull TextView pill, boolean active) {
        pill.setTextColor(active ? Color.WHITE : palette.textPrimary);
        pill.setBackground(createBorderedRoundedDrawable(
                active ? palette.accent : palette.bgCard,
                active ? palette.accent : palette.border,
                dp(1),
                dp(6)));
    }

    @NonNull
    private TextView createBottomChipButton(@NonNull String label,
                                            @NonNull View.OnClickListener listener) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextColor(palette.textPrimary);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(9), dp(5), dp(9), dp(5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(3), 0, dp(3), 0);
        btn.setLayoutParams(lp);
        btn.setBackground(createBorderedRoundedDrawable(palette.bgSecondary, palette.border, dp(1), dp(10)));
        btn.setOnClickListener(listener);
        return btn;
    }

    @NonNull
    private GradientDrawable createRoundedRectDrawable(int fillColor, int radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fillColor);
        d.setCornerRadius(radiusPx);
        return d;
    }

    @NonNull
    private GradientDrawable createBorderedRoundedDrawable(int fillColor,
                                                         int strokeColor,
                                                         int strokeWidthPx,
                                                         int radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fillColor);
        d.setStroke(strokeWidthPx, strokeColor);
        d.setCornerRadius(radiusPx);
        return d;
    }

    private void showKeyboard(@NonNull View view) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void hideKeyboard(@NonNull View view) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private int dp(float dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}