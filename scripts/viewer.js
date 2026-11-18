// frontend-user/scripts/viewer.js

/**
 * Universal Document Viewer - Entry Point (Hybrid Native Android + Web/PWA)
 * ============================================================================
 *
 * Public facade of the viewer subsystem. Shared by two pages:
 *
 *   * pages/viewer.html            - dedicated page for external files.
 *                                    No auth. Drains a pending file on init.
 *
 *   * pages/resource-browser.html  - catalogue page. Opens catalogue
 *                                    resources in the native Android viewer
 *                                    (on Capacitor Android) or an embedded
 *                                    DOM overlay (on Web/PWA).
 *
 * Dual-Path Execution Model:
 *   1. Native Android (Capacitor + MedvixPdfViewer plugin):
 *      Routes directly to native MedvixPdfActivity via zero-copy file/URI
 *      resolution (MedvixStorageResolver). Never mounts the 32-ID DOM viewer,
 *      never reads files into JS Blobs, and never dynamically imports
 *      ./viewer/core.js or ./viewer/ui-internal.js.
 *
 *   2. Web / PWA Fallback:
 *      Dynamically imports ./viewer/core.js and ./viewer/ui-internal.js on
 *      demand and mounts the full HTML5 Canvas + Web Worker viewer.
 *
 * Public exports:
 *   * loadDocumentInPage(docId)                             -> Promise<void>
 *   * openDocument(docId, title?, fileType?, opts?)         -> Promise<void>
 *   * showEmbeddedViewer(docId, title?, fileType?, opts?)   -> Promise<void>
 *   * closeEmbeddedViewer()                                 -> void
 *   * openDocumentModal(docId)                              -> Promise<void>  (legacy alias)
 *   * openPendingFile()                                     -> boolean       (host-page hook)
 *   * isNativeViewerAvailable()                             -> boolean
 *   * getNativeDocumentInfo(params)                         -> Promise<object>
 *   * getNativeThumbnailUrl(params, maxDimension?)          -> Promise<string|null>
 *   * exportNativeAnnotations(params)                       -> Promise<object|null>
 *
 * `opts` carries hint flags for the load:
 *   * previewMode: boolean - cap rendered pages to 10% and show a subscribe
 *     CTA after the last preview page. Set by resource-browser.js for premium
 *     catalogue resources opened without an active subscription. Never set by
 *     external-file paths (file picker, Android ACTION_VIEW intent).
 *   * initialPage: number  - optional 0-based page index jump target.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * ASYNC FAILURE PROPAGATION
 *
 *   The whole open-document chain is awaitable:
 *
 *     resource-browser.js Open handler
 *       → viewer.openDocument(id, title, type, { previewMode })
 *       → window.showViewer(...)  (set to resource-browser.js::showViewer)
 *       → viewer.showEmbeddedViewer(...)
 *       → native MedvixPdfViewer.openDocument(...) OR _loadDocumentIntoEmbedded(...)
 *
 *   Every hop returns a Promise that resolves when the load completes (or when
 *   the native viewer Activity closes) and rejects when pre-load checks fail
 *   (missing offline file, failed download, initialization error).
 *
 *   showEmbeddedViewer is written as a plain (non-async) function that returns
 *   the load Promise directly while attaching a silent `.catch(() => {})` so
 *   legacy fire-and-forget callers do not trigger unhandled-rejection warnings,
 *   while awaiting callers still receive the rejection.
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Design constraints:
 *   * Zero import-time side effects. All wiring happens on demand.
 *   * No static imports of ./viewer/core.js or ./viewer/ui-internal.js so
 *     Android WebView never parses or allocates memory for unused JS engines.
 *   * Every rendered error message is escaped via _escapeHtml.
 *
 * @module viewer
 */

'use strict';

// ============================================================================
// APP-LEVEL IMPORTS (only file permitted to import these)
// ============================================================================

import * as content from './content.js';
import * as subscription from './subscription.js';
import * as ui from './ui.js';
import * as router from './router.js';

// ============================================================================
// MODULE-PRIVATE STATE
// ============================================================================

/** Cached reference to Capacitor MedvixPdfViewer plugin (undefined = unprobed). @private */
let _nativePlugin;

/** True once Capacitor plugin listeners (e.g. externalDocumentOpened) are attached. @private */
let _nativeListenersWired = false;

/** Lazily loaded ./viewer/ui-internal.js namespace (Web/PWA fallback only). @private */
let _uiInternal = null;

/** Singleton core instance (Web/PWA fallback only). @private */
let _core = null;

/** Singleton core Events enum cache (Web/PWA fallback only). @private */
let _coreEvents = null;

/** Singleton core CONFIG cache (Web/PWA fallback only). @private */
let _coreConfig = null;

/** The docId currently being displayed, if any. @private @type {string|null} */
let _currentDocId = null;

/** True when the current document came from a local/external file. @private */
let _isLocalFile = false;

/** App-level bus subscription unsubscribe functions. @private @type {Array<() => void>} */
let _appSubscriptions = [];

/**
 * Singleton promise for the Web core init sequence. Prevents concurrent entry
 * calls from double-constructing the core.
 * @private
 * @type {Promise<any>|null}
 */
let _coreInitPromise = null;

/**
 * A File object awaiting a viewer host page. Set when a user-selected File
 * (file input, drag-drop) arrives before the viewer chrome is mounted. File
 * objects cannot be serialized to sessionStorage, so they live here until
 * `openPendingFile()` drains them.
 *
 * @private
 * @type {File|null}
 */
let _pendingLocalFile = null;

/**
 * sessionStorage key used to stash a native FileOpen payload between the
 * page that received it and the viewer page that will load it.
 *
 * @private
 * @constant {string}
 */
const PENDING_FILE_KEY = 'pendingFileOpen';

/** Extension-to-MIME lookup for native viewer hints. @private */
const EXTENSION_MIME_MAP = Object.freeze({
  pdf: 'application/pdf',
  png: 'image/png',
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  webp: 'image/webp',
  gif: 'image/gif',
  svg: 'image/svg+xml',
  bmp: 'image/bmp',
  heic: 'image/heic',
  heif: 'image/heif',
});

// ============================================================================
// 1. CAPACITOR NATIVE BRIDGE RESOLUTION
// ============================================================================

/**
 * Safely retrieve `window.Capacitor` without throwing in SSR/worker contexts.
 * @private
 * @returns {any|null}
 */
function _getCapacitor() {
  try {
    if (typeof window !== 'undefined' && window.Capacitor) {
      return window.Capacitor;
    }
  } catch { /* ignore */ }
  return null;
}

/**
 * Resolve the native Android `MedvixPdfViewer` Capacitor plugin if running on
 * Android and registered in MainActivity. Cached after first probe.
 *
 * @private
 * @returns {any|null}
 */
function _getNativeViewerPlugin() {
  if (_nativePlugin !== undefined) return _nativePlugin;

  const cap = _getCapacitor();
  if (!cap) {
    _nativePlugin = null;
    return null;
  }

  try {
    const platform = typeof cap.getPlatform === 'function' ? cap.getPlatform() : '';
    const isNative = typeof cap.isNativePlatform === 'function'
      ? cap.isNativePlatform()
      : platform === 'android';

    if (!isNative || platform !== 'android') {
      _nativePlugin = null;
      return null;
    }

    const available = typeof cap.isPluginAvailable === 'function'
      ? cap.isPluginAvailable('MedvixPdfViewer')
      : Boolean(cap.Plugins && cap.Plugins.MedvixPdfViewer);

    if (!available) {
      _nativePlugin = null;
      return null;
    }

    let plugin = null;
    if (cap.Plugins && cap.Plugins.MedvixPdfViewer) {
      plugin = cap.Plugins.MedvixPdfViewer;
    } else if (typeof cap.registerPlugin === 'function') {
      plugin = cap.registerPlugin('MedvixPdfViewer');
    }

    _nativePlugin = plugin || null;
    if (_nativePlugin) {
      _wireNativePluginListeners(_nativePlugin);
    }
    return _nativePlugin;
  } catch {
    _nativePlugin = null;
    return null;
  }
}

/**
 * Attach one-time native Capacitor event listeners for warm-start ACTION_VIEW
 * intents delivered while the app is already running.
 *
 * @private
 * @param {any} plugin
 */
function _wireNativePluginListeners(plugin) {
  if (_nativeListenersWired || !plugin || typeof plugin.addListener !== 'function') {
    return;
  }
  _nativeListenersWired = true;

  try {
    plugin.addListener('externalDocumentOpened', (eventData) => {
      if (!eventData || !eventData.uri) return;
      _openExternalPayloadNative(plugin, {
        uri: eventData.uri,
        mimeType: eventData.mimeType || null,
        name: eventData.name || 'Document',
      }).catch(() => { /* handled internally */ });
    });
  } catch { /* ignore */ }
}

/**
 * True when the native Android `MedvixPdfViewer` engine is available.
 *
 * @returns {boolean}
 */
export function isNativeViewerAvailable() {
  return _getNativeViewerPlugin() !== null;
}

/**
 * Query file existence, byte size, saved reading progress, bookmarks count,
 * and annotations count on native Android without opening the viewer UI.
 *
 * @param {{ resourceId?: string, relativePath?: string, absolutePath?: string, uri?: string }} params
 * @returns {Promise<object>}
 */
export async function getNativeDocumentInfo(params) {
  const plugin = _getNativeViewerPlugin();
  if (!plugin || !params) return { exists: false };
  try {
    return await plugin.getDocumentInfo(params);
  } catch {
    return { exists: false };
  }
}

/**
 * Generate or fetch a cached WebP cover thumbnail on native Android and
 * return a WebView-renderable URL via `Capacitor.convertFileSrc()`.
 *
 * @param {{ resourceId?: string, relativePath?: string, absolutePath?: string, uri?: string, password?: string }} params
 * @param {number} [maxDimension=360]
 * @returns {Promise<string|null>}
 */
export async function getNativeThumbnailUrl(params, maxDimension = 360) {
  const plugin = _getNativeViewerPlugin();
  const cap = _getCapacitor();
  if (!plugin || !cap || !params) return null;

  try {
    const res = await plugin.generateThumbnail({
      ...params,
      maxDimension,
    });
    if (!res || !res.path) return null;
    return typeof cap.convertFileSrc === 'function'
      ? cap.convertFileSrc(res.path)
      : res.path;
  } catch {
    return null;
  }
}

/**
 * Export all saved bookmarks, highlights, notes, and ink strokes for a
 * document from its native sidecar store as JSON for cloud backup/sync.
 *
 * @param {{ resourceId?: string, relativePath?: string, absolutePath?: string, uri?: string }} params
 * @returns {Promise<{ slugId: string, annotationsJson: string }|null>}
 */
export async function exportNativeAnnotations(params) {
  const plugin = _getNativeViewerPlugin();
  if (!plugin || !params) return null;
  try {
    return await plugin.exportAnnotations(params);
  } catch {
    return null;
  }
}

// ============================================================================
// 2. LAZY WEB ENGINE & UI-INTERNAL LOADERS (Web/PWA Fallback Only)
// ============================================================================

/**
 * Lazily import `./viewer/ui-internal.js` only when the Web/PWA DOM viewer
 * path is actually needed. Never executed on native Android.
 *
 * @private
 * @returns {Promise<any>}
 */
async function _ensureUiInternal() {
  if (_uiInternal) return _uiInternal;
  _uiInternal = await import('./viewer/ui-internal.js');
  return _uiInternal;
}

/**
 * Return the singleton Web viewer core, dynamically importing `./viewer/core.js`
 * and initialising it on first call. Concurrent calls share a single promise.
 * Never executed on native Android.
 *
 * @private
 * @returns {Promise<any>}
 */
function _ensureCore() {
  if (_coreInitPromise) return _coreInitPromise;

  _coreInitPromise = (async () => {
    const [coreMod] = await Promise.all([
      import('./viewer/core.js'),
      _ensureUiInternal(),
    ]);

    const { createCore, Events, CONFIG } = coreMod;
    _coreEvents = Events;
    _coreConfig = CONFIG;

    const core = createCore();
    _core = core;
    await core.init();
    _wireAppSubscriptions(core, Events);
    _maybeInstallDebugShortcut(CONFIG);
    return core;
  })();

  _coreInitPromise.catch(() => {
    _coreInitPromise = null;
  });

  return _coreInitPromise;
}

/**
 * Register app-level bus subscriptions for the Web viewer core.
 *
 * @private
 * @param {any} core
 * @param {any} Events
 */
function _wireAppSubscriptions(core, Events) {
  const bus = core.getBus();

  _appSubscriptions.push(bus.on(Events.NAV_BACK_REQUESTED, () => {
    _handleBack();
  }));

  _appSubscriptions.push(bus.on(Events.LOCAL_FILE_OPEN_REQUESTED, (payload) => {
    if (!payload || !payload.file) return;
    _handleLocalFileOpen(payload.file).catch(() => { /* handled internally */ });
  }));

  _appSubscriptions.push(bus.on(Events.PREVIEW_SUBSCRIBE_REQUESTED, () => {
    try { router.navigateTo('subscription'); } catch { /* ignore */ }
  }));
}

/**
 * @private
 * @param {any} CONFIG
 */
function _maybeInstallDebugShortcut(CONFIG) {
  if (!CONFIG || !CONFIG.DEBUG_VIEWER) return;
  try {
    if (typeof window !== 'undefined') {
      window.__viewer = {
        ...__debug,
        openPendingFile,
      };
    }
  } catch { /* ignore */ }
}

// ============================================================================
// 3. ENVIRONMENT, MIME & HTML ESCAPE HELPERS
// ============================================================================

/**
 * Escape HTML special characters for safe error rendering.
 * @private
 * @param {string} str
 * @returns {string}
 */
function _escapeHtml(str) {
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/**
 * Normalize an extension or MIME hint into a valid MIME type string.
 * @private
 * @param {string|null|undefined} fileTypeOrExt
 * @param {string|null|undefined} [fallbackMime=null]
 * @returns {string|undefined}
 */
function _resolveMimeType(fileTypeOrExt, fallbackMime = null) {
  if (fileTypeOrExt && typeof fileTypeOrExt === 'string') {
    const trimmed = fileTypeOrExt.trim().toLowerCase();
    if (trimmed.includes('/')) return trimmed;
    const ext = trimmed.startsWith('.') ? trimmed.slice(1) : trimmed;
    if (EXTENSION_MIME_MAP[ext]) return EXTENSION_MIME_MAP[ext];
  }
  if (fallbackMime && typeof fallbackMime === 'string' && fallbackMime.includes('/')) {
    return fallbackMime.trim().toLowerCase();
  }
  return undefined;
}

/**
 * True when the DOM is fully parsed and safe to query.
 * @private
 * @returns {boolean}
 */
function _domReady() {
  return typeof document !== 'undefined' && document.readyState !== 'loading';
}

/**
 * Resolve once the DOM is ready. No-op when already ready.
 * @private
 * @returns {Promise<void>}
 */
function _waitForDom() {
  if (_domReady()) return Promise.resolve();
  return new Promise((resolve) => {
    document.addEventListener('DOMContentLoaded', () => resolve(), { once: true });
  });
}

/**
 * True when the Web viewer chrome (#viewer with its 32 IDs) is mounted in DOM.
 * @private
 * @returns {boolean}
 */
function _chromeMounted() {
  try {
    if (_uiInternal && typeof _uiInternal.refreshElementCache === 'function') {
      const els = _uiInternal.refreshElementCache();
      return !!(els && els.main);
    }
    return typeof document !== 'undefined' && !!document.getElementById('viewer');
  } catch {
    return false;
  }
}

/**
 * Mount the Web viewer chrome and clear any residue from a previous document.
 * @private
 * @param {any} uiMod
 * @param {any} core
 */
function _prepareChromeForFileOpen(uiMod, core) {
  try { uiMod.mountChrome(core); } catch { /* ignore */ }
  try { uiMod.clearViewerContent(); } catch { /* ignore */ }
}

// ============================================================================
// 4. BACK-NAVIGATION CHAIN & NATIVE RESULT DISPATCH
// ============================================================================

/**
 * Fallback chain for the viewer's back button or closing standalone viewer page.
 * @private
 */
function _handleBack() {
  // 1. Host-app-provided hook.
  try {
    if (typeof window !== 'undefined' && typeof window.closeViewer === 'function') {
      window.closeViewer();
      return;
    }
  } catch { /* ignore */ }

  // 2. Browser history, if any.
  try {
    if (typeof window !== 'undefined' && window.history && window.history.length > 1) {
      window.history.back();
      return;
    }
  } catch { /* ignore */ }

  // 3. Fallback route.
  try {
    router.navigateTo('subjects');
  } catch { /* ignore */ }
}

/**
 * Handle the structured ActivityResult returned when `MedvixPdfActivity` closes.
 * Emits DOM custom events for UI progress updates and triggers subscription
 * navigation if the user tapped "Unlock Full" inside preview mode.
 *
 * @private
 * @param {string|null} docId
 * @param {any} result
 * @returns {boolean} True if subscription navigation was triggered
 */
function _handleNativeViewerResult(docId, result) {
  const lastPageIndex = result && typeof result.lastPageIndex === 'number' ? result.lastPageIndex : 0;
  const totalPages = result && typeof result.totalPages === 'number' ? result.totalPages : 0;
  const unlockRequested = Boolean(
    result && (result.unlockRequested || result.action === 'unlock_subscription')
  );

  try {
    if (typeof window !== 'undefined') {
      window.dispatchEvent(new CustomEvent('medvix:viewer-closed', {
        detail: {
          docId: docId || (result && result.resourceId) || null,
          lastPageIndex,
          totalPages,
          unlockRequested,
        },
      }));
    }
  } catch { /* ignore */ }

  if (unlockRequested) {
    try {
      if (typeof window !== 'undefined') {
        window.dispatchEvent(new CustomEvent('medvix:preview-subscribe-requested', {
          detail: {
            docId: docId || (result && result.resourceId) || null,
            lastPageIndex,
            totalPages,
          },
        }));
      }
    } catch { /* ignore */ }

    try {
      router.navigateTo('subscription');
    } catch { /* ignore */ }
    return true;
  }

  return false;
}

// ============================================================================
// 5. NATIVE DOCUMENT OPEN HELPERS (Zero-Copy Disk & URI Paths)
// ============================================================================

/**
 * Resolve and open a catalogue resource in native `MedvixPdfActivity`.
 * Downloads the resource first if not yet present on device storage.
 *
 * @private
 * @param {any} plugin
 * @param {string} docId
 * @param {string} title
 * @param {string|null} fileType
 * @param {{ previewMode?: boolean, initialPage?: number }|null} opts
 * @returns {Promise<{ handled: boolean, unlockTriggered: boolean }>}
 */
async function _openCatalogueDocumentNative(plugin, docId, title, fileType, opts) {
  const mimeHint = _resolveMimeType(fileType);

  let info = null;
  try {
    info = await plugin.getDocumentInfo({
      resourceId: docId,
      title: title || undefined,
      mimeType: mimeHint,
    });
  } catch {
    info = null;
  }

  if (!info || !info.exists) {
    if (typeof navigator !== 'undefined' && navigator.onLine === false) {
      throw new Error('File missing from device storage. Please delete and re-download when online.');
    }

    try { ui.showLoading('Downloading document...'); } catch { /* ignore */ }
    let success = false;
    try {
      success = await content.downloadResource(docId);
    } finally {
      try { ui.hideLoading(); } catch { /* ignore */ }
    }

    if (!success) {
      throw new Error('Download failed');
    }

    try {
      info = await plugin.getDocumentInfo({
        resourceId: docId,
        title: title || undefined,
        mimeType: mimeHint,
      });
    } catch {
      info = null;
    }
  }

  // If the file is not on native filesystem storage, check whether a legacy
  // IndexedDB blob exists so we can seamlessly fall back to the Web viewer.
  if (!info || !info.exists) {
    const legacyBlob = await content.getLocalFile(docId);
    if (legacyBlob) {
      return { handled: false, unlockTriggered: false };
    }
    throw new Error('File not found after download');
  }

  const resolvedTitle = title && title !== 'Document'
    ? title
    : (info.displayName || title || 'Document');

  const result = await plugin.openDocument({
    resourceId: docId,
    title: resolvedTitle,
    mimeType: _resolveMimeType(fileType, info.mimeType),
    previewMode: Boolean(opts && opts.previewMode),
    initialPage: opts && typeof opts.initialPage === 'number' ? opts.initialPage : undefined,
  });

  const unlockTriggered = _handleNativeViewerResult(docId, result);
  return { handled: true, unlockTriggered };
}

/**
 * Open an external Android intent payload (`uri` or `path`) directly in
 * native `MedvixPdfActivity` without reading the file into WebView memory.
 *
 * @private
 * @param {any} plugin
 * @param {{ path?: string, uri?: string, name?: string, mimeType?: string|null }} payload
 * @returns {Promise<void>}
 */
async function _openExternalPayloadNative(plugin, payload) {
  if (!payload || (!payload.uri && !payload.path)) return;

  _currentDocId = null;
  _isLocalFile = true;

  let ext = '';
  try {
    if (payload.name && typeof payload.name === 'string') {
      const parts = payload.name.split('.');
      if (parts.length > 1) ext = parts.pop();
    }
  } catch { /* ignore */ }

  try {
    const result = await plugin.openDocument({
      uri: payload.uri || undefined,
      absolutePath: payload.path || undefined,
      title: payload.name || 'Document',
      mimeType: _resolveMimeType(ext, payload.mimeType),
      previewMode: false,
    });
    _handleNativeViewerResult(null, result);
  } catch (err) {
    const message = err && err.message ? err.message : 'Failed to open file';
    try { ui.showToast('Failed to open file: ' + message, 'error'); } catch { /* ignore */ }
  }
}

// ============================================================================
// 6. EXTERNAL FILE - LOCAL FILE OBJECT (file input, drag-drop)
// ============================================================================

/**
 * Open a browser `File` object in the viewer.
 *
 * External files are never subject to the catalogue preview policy.
 * If the Web viewer chrome is already mounted, the file loads in place.
 * Otherwise the File object is stashed in `_pendingLocalFile` and the app
 * routes to `viewer`, whose init() calls `openPendingFile()`.
 *
 * @private
 * @param {File} file
 * @returns {Promise<void>}
 */
async function _handleLocalFileOpen(file) {
  if (!file) return;

  await _waitForDom();

  if (!_chromeMounted()) {
    _pendingLocalFile = file;
    try { router.navigateTo('viewer'); } catch { /* ignore */ }
    return;
  }

  await _loadLocalFileIntoViewer(file);
}

/**
 * Load a user-selected browser `File` into the Web viewer.
 *
 * @private
 * @param {File} file
 * @returns {Promise<void>}
 */
async function _loadLocalFileIntoViewer(file) {
  _currentDocId = null;
  _isLocalFile = true;

  let fileType = '';
  try {
    if (file.name && typeof file.name === 'string') {
      const parts = file.name.split('.');
      if (parts.length > 1) fileType = parts.pop();
    }
  } catch { /* ignore */ }

  try {
    const [core, uiMod] = await Promise.all([
      _ensureCore(),
      _ensureUiInternal(),
    ]);
    _prepareChromeForFileOpen(uiMod, core);
    await core.loadDocument(file, fileType, file.name || 'Document');
  } catch (err) {
    const message = err && err.message ? err.message : 'Failed to open file';
    try { ui.showToast('Failed to open file: ' + message, 'error'); } catch { /* ignore */ }
  }
}

// ============================================================================
// 7. PUBLIC API - loadDocumentInPage
// ============================================================================

/**
 * Load a document identified by `docId` into the current page's viewer.
 *
 * This is the internal-document path: it enforces the active subscription
 * gate. Preview mode is NOT applied here.
 *
 * @param {string} docId
 * @returns {Promise<void>}
 */
export async function loadDocumentInPage(docId) {
  _currentDocId = docId;
  _isLocalFile = false;

  const nativePlugin = _getNativeViewerPlugin();
  if (nativePlugin) {
    try {
      const hasActive = await subscription.hasActiveSubscription();
      if (!hasActive) {
        try { ui.showToast('Subscription required', 'warning'); } catch { /* ignore */ }
        try { router.navigateTo('subscription'); } catch { /* ignore */ }
        return;
      }

      const outcome = await _openCatalogueDocumentNative(
        nativePlugin, docId, 'Document', null, { previewMode: false }
      );
      if (outcome.handled) {
        if (!outcome.unlockTriggered) {
          _handleBack();
        }
        return;
      }
    } catch (err) {
      await _renderErrorContainer(err);
      return;
    } finally {
      _currentDocId = null;
    }
  }

  // Web / PWA fallback path
  const uiMod = await _ensureUiInternal();
  const els = uiMod.refreshElementCache();
  uiMod.injectViewerStyles();

  if (!els || !els.main) return;

  uiMod.clearViewerContent();

  if (els.loading) els.loading.style.display = 'block';
  if (els.progress) els.progress.style.display = 'none';

  try {
    const hasActive = await subscription.hasActiveSubscription();
    if (!hasActive) {
      try { ui.showToast('Subscription required', 'warning'); } catch { /* ignore */ }
      try { router.navigateTo('subscription'); } catch { /* ignore */ }
      return;
    }

    let blob = await content.getLocalFile(docId);

    if (!blob) {
      try { ui.showLoading('Fetching document...'); } catch { /* ignore */ }
      const success = await content.downloadResource(docId);
      try { ui.hideLoading(); } catch { /* ignore */ }
      if (!success) throw new Error('Download failed');
      blob = await content.getLocalFile(docId);
      if (!blob) throw new Error('File not found after download');
    }

    const core = await _ensureCore();
    await core.loadDocument(blob, null, 'Document', { previewMode: false });
  } catch (err) {
    await _renderErrorContainer(err);
  } finally {
    if (els.loading) els.loading.style.display = 'none';
    if (els.progress) els.progress.style.display = 'none';
  }
}

// ============================================================================
// 8. PUBLIC API - openDocument
// ============================================================================

/**
 * Open a document from another page. Delegates to `window.showViewer` if
 * the host app defines it (e.g. `resource-browser.js`); otherwise opens via
 * native Android viewer if the file is locally cached, or routes to the
 * catalogue page.
 *
 * @param {string} docId
 * @param {string} [title='Document']
 * @param {string|null} [fileType=null]
 * @param {{ previewMode?: boolean, initialPage?: number }|null} [opts=null]
 * @returns {Promise<void>}
 */
export async function openDocument(docId, title = 'Document', fileType = null, opts = null) {
  if (typeof window !== 'undefined' && typeof window.showViewer === 'function') {
    await window.showViewer(docId, title, fileType, opts);
    return;
  }

  const nativePlugin = _getNativeViewerPlugin();
  if (nativePlugin) {
    try {
      const info = await getNativeDocumentInfo({ resourceId: docId });
      if (info && info.exists) {
        const hasActive = await subscription.hasActiveSubscription();
        const previewMode = opts && typeof opts.previewMode === 'boolean'
          ? opts.previewMode
          : !hasActive;
        const outcome = await _openCatalogueDocumentNative(
          nativePlugin, docId, title, fileType, { ...opts, previewMode }
        );
        if (outcome.handled) return;
      }
    } catch { /* fall back to catalogue route */ }
  } else {
    try {
      const uiMod = await _ensureUiInternal();
      uiMod.injectViewerStyles();
    } catch { /* ignore */ }
  }

  try {
    router.navigateTo('resource-browser');
  } catch { /* ignore */ }
}

// ============================================================================
// 9. PUBLIC API - showEmbeddedViewer
// ============================================================================

/**
 * Open a catalogue resource in the viewer.
 *
 * On native Android (Capacitor), launches `MedvixPdfActivity` directly over
 * the on-disk file without mounting any DOM overlay or reading blobs into JS.
 * On Web/PWA, mounts the fixed-position `#viewer` overlay and loads via
 * `./viewer/core.js`.
 *
 * Written as a plain (non-async) function returning the load Promise with a
 * silent `.catch(() => {})` attached so fire-and-forget callers never emit
 * unhandled-rejection warnings while awaiting callers still catch failures.
 *
 * @param {string} docId
 * @param {string} [title='Document']
 * @param {string|null} [fileType=null]
 * @param {{ previewMode?: boolean, initialPage?: number }|null} [opts=null]
 * @returns {Promise<void>}
 */
export function showEmbeddedViewer(docId, title = 'Document', fileType = null, opts = null) {
  _currentDocId = docId;
  _isLocalFile = false;

  const nativePlugin = _getNativeViewerPlugin();
  if (nativePlugin) {
    const nativePromise = (async () => {
      try {
        const outcome = await _openCatalogueDocumentNative(
          nativePlugin, docId, title, fileType, opts
        );
        if (outcome.handled) {
          return;
        }
        // Fallback if resource exists only in legacy IndexedDB storage
        await _mountAndLoadWebEmbeddedViewer(docId, title, fileType, opts);
      } finally {
        _currentDocId = null;
      }
    })();

    nativePromise.catch(() => { /* silence for fire-and-forget callers */ });
    return nativePromise;
  }

  const webPromise = _mountAndLoadWebEmbeddedViewer(docId, title, fileType, opts);
  webPromise.catch(() => { /* silence for fire-and-forget callers */ });
  return webPromise;
}

/**
 * Mount the Web DOM overlay and load a catalogue document into it.
 *
 * @private
 * @param {string} docId
 * @param {string} title
 * @param {string|null} fileType
 * @param {{ previewMode?: boolean }|null} opts
 * @returns {Promise<void>}
 */
async function _mountAndLoadWebEmbeddedViewer(docId, title, fileType, opts) {
  const uiMod = await _ensureUiInternal();
  const els = uiMod.refreshElementCache();
  uiMod.injectViewerStyles();

  if (!els || !els.main) {
    return;
  }

  try { uiMod.mountChrome(_core); } catch { /* ignore */ }
  uiMod.clearViewerContent();

  if (els.loading) els.loading.style.display = 'block';
  if (els.progress) els.progress.style.display = 'none';
  if (els.title) els.title.textContent = title || 'Document';

  await _loadDocumentIntoEmbedded(docId, fileType, title, opts);
}

/**
 * Load a catalogue document into the mounted Web embedded viewer.
 *
 * @private
 * @param {string} docId
 * @param {string|null} fileType
 * @param {string} [title='Document']
 * @param {{ previewMode?: boolean }|null} [opts=null]
 * @returns {Promise<void>}
 */
async function _loadDocumentIntoEmbedded(docId, fileType, title = 'Document', opts = null) {
  const uiMod = await _ensureUiInternal();
  const els = uiMod.refreshElementCache();
  const loadSessionId = Symbol();
  window._activeLoadSession = loadSessionId;

  try {
    let blob = await content.getLocalFile(docId);
    if (!blob) {
      if (typeof navigator !== 'undefined' && navigator.onLine === false) {
        throw new Error('File missing from device storage. Please delete and re-download when online.');
      }
      try { ui.showLoading('Downloading document...'); } catch { /* ignore */ }
      const success = await content.downloadResource(docId);
      try { ui.hideLoading(); } catch { /* ignore */ }
      if (!success) throw new Error('Download failed');
      blob = await content.getLocalFile(docId);
      if (!blob) throw new Error('File not found after download');
    }

    const core = await _ensureCore();
    await core.loadDocument(blob, fileType, title, opts);
  } catch (err) {
    setTimeout(() => {
      if (window._activeLoadSession === loadSessionId && els && els.main) {
        const message = err && err.message ? err.message : 'Failed to load document';
        const safe = _escapeHtml(String(message));
        els.main.innerHTML =
          `<div class="error-container" role="alert"><p>${safe}</p></div>`;
      }
    }, 2500);

    throw err;
  } finally {
    if (window._activeLoadSession === loadSessionId) {
      if (els && els.loading) els.loading.style.display = 'none';
      if (els && els.progress) els.progress.style.display = 'none';
    }
  }
}

// ============================================================================
// 10. PUBLIC API - closeEmbeddedViewer & openDocumentModal
// ============================================================================

/**
 * Close the Web embedded viewer overlay and release the current document.
 * Safe no-op when running via native Android Activity. Idempotent.
 *
 * @returns {void}
 */
export function closeEmbeddedViewer() {
  try {
    if (_core && typeof _core.destroy === 'function') {
      _core.destroy();
    }
  } catch { /* ignore */ }

  if (_uiInternal) {
    try { _uiInternal.unmountChrome(_core); } catch { /* ignore */ }
    try { _uiInternal.clearViewerContent(); } catch { /* ignore */ }
  }

  _currentDocId = null;
  _isLocalFile = false;
  _pendingLocalFile = null;
}

/**
 * Legacy alias for `openDocument(docId)`.
 *
 * @param {string} docId
 * @returns {Promise<void>}
 */
export async function openDocumentModal(docId) {
  return openDocument(docId);
}

// ============================================================================
// 11. ERROR RENDERING
// ============================================================================

/**
 * @private
 * @param {unknown} err
 * @returns {Promise<void>}
 */
async function _renderErrorContainer(err) {
  const uiMod = await _ensureUiInternal();
  const els = uiMod.refreshElementCache();
  if (!els || !els.main) return;

  const message = err && typeof err === 'object' && 'message' in err
    ? String(/** @type {{ message: unknown }} */ (err).message)
    : 'Unknown error';
  const safe = _escapeHtml(message);

  els.main.innerHTML = `<div class="error-container" role="alert">
    <p>Failed to load document: ${safe}</p>
    <button class="btn-secondary" data-viewer-action="back">Go Back</button>
  </div>`;

  const backBtn = els.main.querySelector('[data-viewer-action="back"]');
  if (!backBtn) return;

  backBtn.addEventListener('click', () => {
    try {
      if (_core && _coreEvents) {
        _core.getBus().emit(_coreEvents.NAV_BACK_REQUESTED, {});
        return;
      }
    } catch { /* ignore */ }
    _handleBack();
  });
}

// ============================================================================
// 12. DEBUG NAMESPACE
// ============================================================================

/**
 * QA / dev-tools diagnostics.
 */
export const __debug = {
  state() {
    try {
      if (!_core) return null;
      const s = _core.getState();
      return s && typeof s.snapshot === 'function' ? s.snapshot() : null;
    } catch { return null; }
  },

  flags() {
    try {
      if (!_core) return null;
      const s = _core.getState();
      return s ? s.get('flags') || null : null;
    } catch { return null; }
  },

  report() {
    const base = {
      engineMode: isNativeViewerAvailable() ? 'native-android' : 'web-js',
      nativePluginAvailable: isNativeViewerAvailable(),
      docId: _currentDocId,
      isLocalFile: _isLocalFile,
      webCoreInited: _core !== null,
      pendingLocalFile: !!_pendingLocalFile,
      chromeMounted: _chromeMounted(),
      state: null,
      flags: null,
      scheduler: null,
      memory: null,
      workers: null,
      cache: null,
    };
    if (!_core) return base;
    try {
      const s = _core.getState();
      base.state = s && typeof s.snapshot === 'function' ? s.snapshot() : null;
      base.flags = s ? s.get('flags') || null : null;
    } catch { /* ignore */ }
    try {
      const scheduler = _core.getScheduler();
      base.scheduler = scheduler && typeof scheduler.getStats === 'function'
        ? scheduler.getStats() : null;
    } catch { /* ignore */ }
    try {
      const memory = _core.getMemory();
      base.memory = memory && typeof memory.report === 'function'
        ? memory.report() : null;
    } catch { /* ignore */ }
    try {
      const workers = _core.getWorkers();
      base.workers = workers && typeof workers.stats === 'function'
        ? workers.stats() : null;
    } catch { /* ignore */ }
    try {
      const cache = _core.getCache();
      base.cache = cache && typeof cache.report === 'function'
        ? cache.report() : null;
    } catch { /* ignore */ }
    return base;
  },
};

// ============================================================================
// 13. NATIVE FILE PAYLOAD LOADER (Web/PWA Fallback) & openPendingFile
// ============================================================================

/**
 * Web/PWA fallback loader when a path-based payload arrives without the
 * native Android `MedvixPdfViewer` plugin.
 *
 * @private
 * @param {{ path: string, name?: string, mimeType?: string|null, size?: number }} payload
 * @returns {Promise<void>}
 */
async function _openNativeFilePayloadWebFallback(payload) {
  if (!payload || !payload.path) return;

  await _waitForDom();

  if (!_chromeMounted()) {
    console.warn('[Viewer] File payload arrived but viewer chrome is not mounted.');
    return;
  }

  _currentDocId = null;
  _isLocalFile = true;

  let fileType = '';
  try {
    if (payload.name && typeof payload.name === 'string') {
      const parts = payload.name.split('.');
      if (parts.length > 1) fileType = parts.pop();
    }
  } catch { /* ignore */ }

  try {
    const bridge = _getCapacitor();
    const fetchUrl = bridge && typeof bridge.convertFileSrc === 'function'
      ? bridge.convertFileSrc(payload.path)
      : payload.path;

    const response = await fetch(fetchUrl);
    if (!response.ok) throw new Error('HTTP ' + response.status);
    const blob = await response.blob();

    const typedBlob = payload.mimeType && blob.type !== payload.mimeType
      ? blob.slice(0, blob.size, payload.mimeType)
      : blob;

    const [core, uiMod] = await Promise.all([
      _ensureCore(),
      _ensureUiInternal(),
    ]);
    _prepareChromeForFileOpen(uiMod, core);
    await core.loadDocument(typedBlob, fileType, payload.name || 'Document');
  } catch (err) {
    const message = err && err.message ? err.message : 'Failed to open file';
    try { ui.showToast('Failed to open file: ' + message, 'error'); } catch { /* ignore */ }
  }
}

/**
 * Drain a pending external file and open it in the viewer.
 *
 * Called by `pages/viewer.js` from its `init()`. Reads two sources:
 *   1. `sessionStorage.pendingFileOpen` — Android intent / FileOpen payload
 *      stashed by `app.js`. On native Android, launches `MedvixPdfActivity`
 *      directly via URI/path with zero blob copying, then navigates back
 *      when the user closes the native viewer.
 *   2. `_pendingLocalFile` — a browser `File` object from `<input type="file">`
 *      or drag-drop that arrived while the viewer chrome wasn't mounted.
 *
 * @returns {boolean} True if a pending file was found and initiated
 */
export function openPendingFile() {
  // -- Source 1: native Android intent / FileOpen payload ------------------
  try {
    const raw = sessionStorage.getItem(PENDING_FILE_KEY);
    if (raw) {
      sessionStorage.removeItem(PENDING_FILE_KEY);
      const payload = JSON.parse(raw);
      if (payload && (payload.uri || payload.path)) {
        const nativePlugin = _getNativeViewerPlugin();
        if (nativePlugin) {
          _openExternalPayloadNative(nativePlugin, payload)
            .finally(() => {
              _handleBack();
            });
          return true;
        }
        _openNativeFilePayloadWebFallback(payload).catch(() => { /* handled internally */ });
        return true;
      }
    }
  } catch { /* ignore parse errors */ }

  // -- Source 2: local browser File object ---------------------------------
  if (_pendingLocalFile) {
    const file = _pendingLocalFile;
    _pendingLocalFile = null;
    _loadLocalFileIntoViewer(file).catch(() => { /* handled internally */ });
    return true;
  }

  return false;
}