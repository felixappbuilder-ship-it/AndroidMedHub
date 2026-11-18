// frontend-user/scripts/viewer/native-bridge.js

/**
 * Universal Document Viewer — Native Bridge
 * ============================================================================
 *
 * Every native call the viewer needs, over a single module. No dynamic
 * imports. Every plugin is a Medvix Java plugin, accessed safely via
 * `platform.js` and `globalThis.Capacitor.Plugins.*`.
 *
 * Backed by:
 *   MedvixPdfViewer   — openDocument, getDocumentInfo, generateThumbnail,
 *                       exportAnnotations, clearSidecarData;
 *                       viewerClosed, unlockRequested, externalDocumentOpened
 *   MedvixApp         — backButton, appStateChange, appUrlOpen events;
 *                       getLaunchUrl(), getInfo(), exitApp()
 *   MedvixStatusBar   — enterFullscreen, exitFullscreen, setVisible,
 *                       setColor, setLightIcons
 *   MedvixKeepAwake   — keepAwake, allowSleep
 *   MedvixShare       — share, shareFile
 *   MedvixHaptics     — light, medium, heavy, vibrate  (NOT used inside the
 *                       viewer — exported for other app modules; the
 *                       viewer does not vibrate for any reason)
 *
 * Design contract:
 *   1. No function ever throws — broken or absent plugin → silent safe fallback.
 *   2. Register / listener functions return a SYNCHRONOUS unsubscribe.
 *   3. Fullscreen state is tracked locally. The native isFullscreen query
 *      is async, and the viewer needs a synchronous check in the back
 *      button priority chain.
 *   4. Leaf-adjacent module — imports only from `./platform.js` so no
 *      circular dependency with `core.js` or `viewer.js` can ever form.
 *
 * @module viewer/native-bridge
 */

'use strict';

import {
  isNative,
  isWeb,
  hasNativePdfViewer,
  getNativePdfViewerPlugin,
  convertNativeFileSrc,
  getSafeAreaInsets,
} from './platform.js';

// ============================================================================
// 0. NATIVE PLUGIN ACCESS
// ============================================================================

/**
 * Synchronous access to a registered Medvix Capacitor plugin.
 * Delegates `MedvixPdfViewer` resolution to `platform.js`; resolves other
 * Medvix plugins via `globalThis.Capacitor.Plugins`. Never throws.
 *
 * @private
 * @param {string} name
 * @returns {any|null}
 */
function plugin(name) {
  try {
    if (!isNative()) return null;
    if (name === 'MedvixPdfViewer') {
      return getNativePdfViewerPlugin();
    }
    const C = globalThis.Capacitor;
    return C?.Plugins?.[name] ?? null;
  } catch {
    return null;
  }
}

/**
 * Safe async plugin invocation — never throws, always resolves to result or null.
 *
 * @private
 * @param {string} pluginName
 * @param {string} method
 * @param {object} [args]
 * @returns {Promise<any|null>}
 */
async function safeCall(pluginName, method, args) {
  const p = plugin(pluginName);
  if (!p || typeof p[method] !== 'function') return null;
  try {
    return await p[method](args);
  } catch {
    return null;
  }
}

/**
 * Subscribe synchronously to a Capacitor plugin event, returning an idempotent
 * unsubscribe function even if the underlying `addListener` resolves asynchronously.
 *
 * @private
 * @param {string} pluginName
 * @param {string} eventName
 * @param {(payload: any) => void} handler
 * @returns {() => void}
 */
function safeSubscribe(pluginName, eventName, handler) {
  if (typeof handler !== 'function') return () => {};
  const p = plugin(pluginName);
  if (!p || typeof p.addListener !== 'function') return () => {};

  let disposed = false;
  let nativeUnsub = null;

  (async () => {
    try {
      const sub = await p.addListener(eventName, (payload) => {
        if (disposed) return;
        try {
          handler(payload);
        } catch { /* ignore listener errors */ }
      });
      if (disposed) {
        try { sub && typeof sub.remove === 'function' && sub.remove(); } catch { /* ignore */ }
        return;
      }
      nativeUnsub = () => {
        try { sub && typeof sub.remove === 'function' && sub.remove(); } catch { /* ignore */ }
      };
    } catch { /* ignore */ }
  })();

  return () => {
    disposed = true;
    if (nativeUnsub) {
      nativeUnsub();
      nativeUnsub = null;
    }
  };
}

// ============================================================================
// 1. NATIVE ANDROID PDF & IMAGE VIEWER BRIDGE (MedvixPdfViewer)
// ============================================================================

/**
 * True when the native Android `MedvixPdfViewer` engine is available.
 * Re-exported from `platform.js` for caller convenience.
 *
 * @returns {boolean}
 */
export function isNativeViewerAvailable() {
  return hasNativePdfViewer();
}

/**
 * Launch full-screen native `MedvixPdfActivity` directly against device
 * storage or an external URI (zero base64 / blob transfer).
 *
 * Resolves with the structured ActivityResult payload when the user closes
 * the native viewer or taps "Unlock Full" in preview mode:
 *   `{ action, resourceId?, lastPageIndex, totalPages, unlockRequested }`
 * Returns `null` if the native plugin is unavailable or the call fails.
 *
 * @param {{
 *   resourceId?: string,
 *   relativePath?: string,
 *   absolutePath?: string,
 *   uri?: string,
 *   title?: string,
 *   mimeType?: string,
 *   previewMode?: boolean,
 *   initialPage?: number,
 * }} opts
 * @returns {Promise<{
 *   action: string,
 *   resourceId?: string,
 *   lastPageIndex: number,
 *   totalPages: number,
 *   unlockRequested: boolean,
 * }|null>}
 */
export async function openNativeDocument(opts) {
  if (!opts || !hasNativePdfViewer()) return null;

  const payload = {};
  if (opts.resourceId) payload.resourceId = String(opts.resourceId);
  if (opts.relativePath) payload.relativePath = String(opts.relativePath);
  if (opts.absolutePath) payload.absolutePath = String(opts.absolutePath);
  if (opts.uri) payload.uri = String(opts.uri);
  if (opts.title) payload.title = String(opts.title);
  if (opts.mimeType) payload.mimeType = String(opts.mimeType);
  if (typeof opts.previewMode === 'boolean') payload.previewMode = opts.previewMode;
  if (typeof opts.initialPage === 'number' && opts.initialPage >= 0) {
    payload.initialPage = Math.floor(opts.initialPage);
  }

  if (!payload.resourceId && !payload.relativePath && !payload.absolutePath && !payload.uri) {
    return null;
  }

  const res = await safeCall('MedvixPdfViewer', 'openDocument', payload);
  if (!res || typeof res !== 'object') return null;

  return {
    action: typeof res.action === 'string' ? res.action : 'closed',
    resourceId: typeof res.resourceId === 'string' ? res.resourceId : undefined,
    lastPageIndex: typeof res.lastPageIndex === 'number' ? res.lastPageIndex : 0,
    totalPages: typeof res.totalPages === 'number' ? res.totalPages : 0,
    unlockRequested: Boolean(res.unlockRequested || res.action === 'unlock_subscription'),
  };
}

/**
 * Inspect file existence, metadata, storage location, saved reading state,
 * bookmarks count, and annotations count without opening the viewer UI.
 * Never throws; returns `{ exists: false }` when missing or unavailable.
 *
 * @param {{
 *   resourceId?: string,
 *   relativePath?: string,
 *   absolutePath?: string,
 *   uri?: string,
 *   title?: string,
 *   mimeType?: string,
 * }} opts
 * @returns {Promise<{
 *   exists: boolean,
 *   slugId?: string,
 *   resourceId?: string,
 *   displayName?: string,
 *   mimeType?: string,
 *   byteLength?: number,
 *   isPdf?: boolean,
 *   isImage?: boolean,
 *   isRemovableStorage?: boolean,
 *   absolutePath?: string,
 *   readerState?: object,
 *   bookmarksCount?: number,
 *   annotationsCount?: number,
 *   hasAnnotations?: boolean,
 * }>}
 */
export async function getNativeDocumentInfo(opts) {
  if (!opts || !hasNativePdfViewer()) return { exists: false };

  const res = await safeCall('MedvixPdfViewer', 'getDocumentInfo', {
    resourceId: opts.resourceId || undefined,
    relativePath: opts.relativePath || undefined,
    absolutePath: opts.absolutePath || undefined,
    uri: opts.uri || undefined,
    title: opts.title || undefined,
    mimeType: opts.mimeType || undefined,
  });

  if (!res || typeof res !== 'object') return { exists: false };
  return {
    ...res,
    exists: Boolean(res.exists),
  };
}

/**
 * Generate or fetch a cached WebP cover thumbnail in `cache/thumbnails/`
 * and attach a WebView-displayable `src` via `Capacitor.convertFileSrc()`.
 * Never throws; returns `null` on failure or non-Android platforms.
 *
 * @param {{
 *   resourceId?: string,
 *   relativePath?: string,
 *   absolutePath?: string,
 *   uri?: string,
 *   title?: string,
 *   mimeType?: string,
 *   password?: string,
 *   maxDimension?: number,
 * }} opts
 * @returns {Promise<{
 *   path: string,
 *   src: string,
 *   width?: number,
 *   height?: number,
 *   pageCount?: number,
 *   cached: boolean,
 * }|null>}
 */
export async function generateNativeThumbnail(opts) {
  if (!opts || !hasNativePdfViewer()) return null;

  const res = await safeCall('MedvixPdfViewer', 'generateThumbnail', {
    resourceId: opts.resourceId || undefined,
    relativePath: opts.relativePath || undefined,
    absolutePath: opts.absolutePath || undefined,
    uri: opts.uri || undefined,
    title: opts.title || undefined,
    mimeType: opts.mimeType || undefined,
    password: opts.password || undefined,
    maxDimension: typeof opts.maxDimension === 'number' ? opts.maxDimension : 360,
  });

  if (!res || typeof res.path !== 'string' || !res.path) return null;

  return {
    path: res.path,
    src: convertNativeFileSrc(res.path),
    width: typeof res.width === 'number' ? res.width : undefined,
    height: typeof res.height === 'number' ? res.height : undefined,
    pageCount: typeof res.pageCount === 'number' ? res.pageCount : undefined,
    cached: Boolean(res.cached),
  };
}

/**
 * Export all saved bookmarks, highlights, study notes, and freehand ink
 * strokes from the document's sidecar store as JSON for cloud backup/sync.
 *
 * @param {{
 *   resourceId?: string,
 *   relativePath?: string,
 *   absolutePath?: string,
 *   uri?: string,
 * }} opts
 * @returns {Promise<{ slugId: string, annotationsJson: string }|null>}
 */
export async function exportNativeAnnotations(opts) {
  if (!opts || !hasNativePdfViewer()) return null;

  const res = await safeCall('MedvixPdfViewer', 'exportAnnotations', {
    resourceId: opts.resourceId || undefined,
    relativePath: opts.relativePath || undefined,
    absolutePath: opts.absolutePath || undefined,
    uri: opts.uri || undefined,
  });

  if (!res || typeof res.annotationsJson !== 'string') return null;
  return {
    slugId: String(res.slugId || ''),
    annotationsJson: res.annotationsJson,
  };
}

/**
 * Delete `reader_state.json` and `annotations.json` sidecar files for a
 * document on native Android storage.
 *
 * @param {{
 *   resourceId?: string,
 *   relativePath?: string,
 *   absolutePath?: string,
 *   uri?: string,
 * }} opts
 * @returns {Promise<boolean>}
 */
export async function clearNativeSidecarData(opts) {
  if (!opts || !hasNativePdfViewer()) return false;

  const res = await safeCall('MedvixPdfViewer', 'clearSidecarData', {
    resourceId: opts.resourceId || undefined,
    relativePath: opts.relativePath || undefined,
    absolutePath: opts.absolutePath || undefined,
    uri: opts.uri || undefined,
  });

  return Boolean(res && res.cleared);
}

/**
 * Subscribe to `MedvixPdfViewer` closing (`viewerClosed` event).
 * Returns a synchronous unsubscribe function.
 *
 * @param {(payload: {
 *   action: string,
 *   resourceId?: string,
 *   lastPageIndex: number,
 *   totalPages: number,
 *   unlockRequested: boolean,
 * }) => void} handler
 * @returns {() => void}
 */
export function onNativeViewerClosed(handler) {
  return safeSubscribe('MedvixPdfViewer', 'viewerClosed', (raw) => {
    const r = raw || {};
    handler({
      action: typeof r.action === 'string' ? r.action : 'closed',
      resourceId: typeof r.resourceId === 'string' ? r.resourceId : undefined,
      lastPageIndex: typeof r.lastPageIndex === 'number' ? r.lastPageIndex : 0,
      totalPages: typeof r.totalPages === 'number' ? r.totalPages : 0,
      unlockRequested: Boolean(r.unlockRequested || r.action === 'unlock_subscription'),
    });
  });
}

/**
 * Subscribe to `MedvixPdfViewer` paywall upgrade requests (`unlockRequested`
 * event fired when a user taps "Unlock Full" inside preview mode).
 * Returns a synchronous unsubscribe function.
 *
 * @param {(payload: {
 *   resourceId?: string,
 *   lastPageIndex: number,
 *   totalPages: number,
 * }) => void} handler
 * @returns {() => void}
 */
export function onNativeUnlockRequested(handler) {
  return safeSubscribe('MedvixPdfViewer', 'unlockRequested', (raw) => {
    const r = raw || {};
    handler({
      resourceId: typeof r.resourceId === 'string' ? r.resourceId : undefined,
      lastPageIndex: typeof r.lastPageIndex === 'number' ? r.lastPageIndex : 0,
      totalPages: typeof r.totalPages === 'number' ? r.totalPages : 0,
    });
  });
}

/**
 * Subscribe to warm-start external `ACTION_VIEW` document intents delivered
 * to `MedvixPdfViewerPlugin` (`externalDocumentOpened`).
 * Returns a synchronous unsubscribe function.
 *
 * @param {(payload: { uri: string, mimeType?: string }) => void} handler
 * @returns {() => void}
 */
export function onExternalDocumentOpened(handler) {
  return safeSubscribe('MedvixPdfViewer', 'externalDocumentOpened', (raw) => {
    if (!raw || typeof raw.uri !== 'string' || !raw.uri) return;
    handler({
      uri: raw.uri,
      mimeType: typeof raw.mimeType === 'string' ? raw.mimeType : undefined,
    });
  });
}

/**
 * Grouped namespace object for callers preferring object-style imports.
 */
export const NativePdfViewer = Object.freeze({
  isAvailable: isNativeViewerAvailable,
  openDocument: openNativeDocument,
  getDocumentInfo: getNativeDocumentInfo,
  generateThumbnail: generateNativeThumbnail,
  exportAnnotations: exportNativeAnnotations,
  clearSidecarData: clearNativeSidecarData,
  onViewerClosed: onNativeViewerClosed,
  onUnlockRequested: onNativeUnlockRequested,
  onExternalDocumentOpened,
});

// ============================================================================
// 2. BACK BUTTON / LIFECYCLE (MedvixApp)
// ============================================================================

/**
 * Register a handler for the hardware/gesture back button.
 *
 *   Android — intercepts the system back button via MedvixApp.
 *             If `onBack()` returns true, the event is consumed. If it
 *             returns false, the app exits.
 *   Web     — binds the Escape key as a development proxy. Same
 *             consumption semantics.
 *
 * @param {() => boolean} onBack
 * @returns {() => void}  unsubscribe
 */
export function registerBackButton(onBack) {
  if (typeof onBack !== 'function') return () => {};

  if (isWeb()) {
    const handler = (e) => {
      if (e.key !== 'Escape') return;
      try { if (onBack() === true) e.preventDefault(); } catch { /* ignore */ }
    };
    try { window.addEventListener('keydown', handler); } catch { /* ignore */ }
    return () => { try { window.removeEventListener('keydown', handler); } catch { /* ignore */ } };
  }

  const p = plugin('MedvixApp');
  let disposed = false;
  let nativeUnsub = null;

  (async () => {
    try {
      if (!p || typeof p.addListener !== 'function') return;
      const sub = await p.addListener('backButton', () => {
        try { if (onBack() === true) return; } catch { /* ignore */ }
        try { p.exitApp && p.exitApp(); } catch { /* ignore */ }
      });
      if (disposed) { try { sub.remove(); } catch { /* ignore */ } return; }
      nativeUnsub = () => { try { sub.remove(); } catch { /* ignore */ } };
    } catch { /* ignore */ }
  })();

  return () => { disposed = true; if (nativeUnsub) nativeUnsub(); };
}

/**
 * Register a handler for app foreground/background transitions.
 *
 *   Android — fires from MedvixApp's appStateChange event.
 *   Web     — bridged from `document.visibilitychange`.
 *
 * @param {(state: { isActive: boolean }) => void} handler
 * @returns {() => void}  unsubscribe
 */
export function onAppStateChange(handler) {
  if (typeof handler !== 'function') return () => {};

  if (isWeb()) {
    const onVis = () => {
      try { handler({ isActive: document.visibilityState === 'visible' }); }
      catch { /* ignore */ }
    };
    try {
      document.addEventListener('visibilitychange', onVis);
      Promise.resolve().then(onVis);
    } catch { /* ignore */ }
    return () => { try { document.removeEventListener('visibilitychange', onVis); } catch { /* ignore */ } };
  }

  const p = plugin('MedvixApp');
  let disposed = false;
  let nativeUnsub = null;

  (async () => {
    try {
      if (!p || typeof p.addListener !== 'function') return;
      const sub = await p.addListener('appStateChange', (state) => {
        try { handler({ isActive: !!(state && state.isActive) }); } catch { /* ignore */ }
      });
      if (disposed) { try { sub.remove(); } catch { /* ignore */ } return; }
      nativeUnsub = () => { try { sub.remove(); } catch { /* ignore */ } };
    } catch { /* ignore */ }
  })();

  return () => { disposed = true; if (nativeUnsub) nativeUnsub(); };
}

// ============================================================================
// 3. INTENTS (MedvixApp)
// ============================================================================

/**
 * Register a handler for URLs/files delivered to the app by the OS.
 *
 *   Android — fires from MedvixApp's appUrlOpen event, and checks
 *             getLaunchUrl() for the cold-start payload.
 *   Web     — silent no-op.
 *
 * @param {(url: string) => void} handler
 * @returns {() => void}  unsubscribe
 */
export function registerAppUrlOpen(handler) {
  if (typeof handler !== 'function') return () => {};
  if (!isNative()) return () => {};

  const p = plugin('MedvixApp');
  let disposed = false;
  let nativeUnsub = null;

  (async () => {
    try {
      if (!p || typeof p.addListener !== 'function') return;
      const sub = await p.addListener('appUrlOpen', (event) => {
        if (event && typeof event.url === 'string') {
          try { handler(event.url); } catch { /* ignore */ }
        }
      });
      try {
        const launch = await p.getLaunchUrl();
        if (!disposed && launch && typeof launch.url === 'string') {
          try { handler(launch.url); } catch { /* ignore */ }
        }
      } catch { /* ignore */ }
      if (disposed) { try { sub.remove(); } catch { /* ignore */ } return; }
      nativeUnsub = () => { try { sub.remove(); } catch { /* ignore */ } };
    } catch { /* ignore */ }
  })();

  return () => { disposed = true; if (nativeUnsub) nativeUnsub(); };
}

// ============================================================================
// 4. FULLSCREEN / IMMERSIVE (MedvixStatusBar)
// ============================================================================

/** Local fullscreen tracker — updated by enter/exit, queried synchronously. */
let _fullscreen = false;

/**
 * Enter immersive fullscreen. Hides status bar + navigation bar on the
 * Android side via MedvixStatusBar.
 *
 * @returns {Promise<void>}
 */
export async function enterFullscreen() {
  _fullscreen = true;
  await safeCall('MedvixStatusBar', 'enterFullscreen');
}

/**
 * Exit fullscreen. Restores status bar + navigation bar.
 *
 * @returns {Promise<void>}
 */
export async function exitFullscreen() {
  _fullscreen = false;
  await safeCall('MedvixStatusBar', 'exitFullscreen');
}

/**
 * Whether the viewer is currently in fullscreen / immersive mode.
 *
 * Synchronous — reads the local flag. Correct because enter/exit are the
 * only ways the state can change, and both update the flag before calling
 * the native method.
 *
 * @returns {boolean}
 */
export function isFullscreen() {
  return _fullscreen;
}

// ============================================================================
// 5. STATUS BAR (MedvixStatusBar)
// ============================================================================

/**
 * Configure the status bar.
 *
 * @param {{ visible?: boolean, style?: 'dark'|'light', color?: string }} [opts]
 * @returns {Promise<void>}
 */
export async function setStatusBarStyle(opts) {
  const o = opts || {};
  if (typeof o.visible === 'boolean') {
    await safeCall('MedvixStatusBar', 'setVisible', { visible: o.visible });
  }
  if (typeof o.color === 'string') {
    await safeCall('MedvixStatusBar', 'setColor', { color: o.color });
  }
  if (o.style === 'dark') {
    await safeCall('MedvixStatusBar', 'setLightIcons', { light: false });
  } else if (o.style === 'light') {
    await safeCall('MedvixStatusBar', 'setLightIcons', { light: true });
  }
}

// ============================================================================
// 6. KEEP AWAKE (MedvixKeepAwake)
// ============================================================================

/**
 * Prevent the screen from sleeping while the viewer is open.
 * Idempotent — repeated calls while awake are cheap no-ops.
 *
 * @returns {Promise<void>}
 */
export async function keepAwake() {
  await safeCall('MedvixKeepAwake', 'keepAwake');
}

/**
 * Re-allow screen sleep. Counterpart to `keepAwake()`.
 * @returns {Promise<void>}
 */
export async function allowSleep() {
  await safeCall('MedvixKeepAwake', 'allowSleep');
}

// ============================================================================
// 7. SHARE / OPEN-WITH (MedvixShare)
// ============================================================================

/**
 * Share text or a link via the system share sheet.
 *
 * @param {{ title?: string, text?: string, url?: string, dialogTitle?: string }} opts
 * @returns {Promise<void>}
 */
export async function share(opts) {
  const o = opts || {};
  await safeCall('MedvixShare', 'share', {
    title:       o.title,
    text:        o.text,
    url:         o.url,
    dialogTitle: o.dialogTitle || 'Share',
  });
}

/**
 * Share a Blob via the system share sheet (Web/PWA fallback path).
 * Note: On native Android, `MedvixPdfActivity` shares files directly via
 * Android `FileProvider` without base64 encoding.
 *
 * @param {{
 *   blob: Blob,
 *   filename?: string,
 *   title?: string,
 *   text?: string,
 *   dialogTitle?: string,
 * }} opts
 * @returns {Promise<boolean>} true when the file was handed off
 */
export async function shareFile(opts) {
  const o = opts || {};
  if (!o.blob) return false;
  const filename = o.filename || 'document.pdf';

  try {
    const base64 = await _blobToBase64(o.blob);
    const result = await safeCall('MedvixShare', 'shareFile', {
      base64,
      filename,
      mimeType:    o.blob.type || 'application/octet-stream',
      title:       o.title,
      text:        o.text,
      dialogTitle: o.dialogTitle || 'Share',
    });
    return !!(result && result.shared);
  } catch {
    return false;
  }
}

/**
 * Read a Blob as base64 (payload only, no data-URI prefix).
 * @private
 */
function _blobToBase64(blob) {
  return new Promise((resolve, reject) => {
    try {
      const reader = new FileReader();
      reader.onload = () => {
        const result = String(reader.result || '');
        const comma = result.indexOf(',');
        resolve(comma >= 0 ? result.slice(comma + 1) : result);
      };
      reader.onerror = () => reject(reader.error || new Error('FileReader error'));
      reader.readAsDataURL(blob);
    } catch (err) { reject(err); }
  });
}

// ============================================================================
// 8. HAPTICS (MedvixHaptics)
// ============================================================================
//
// Exported for use elsewhere in the app. THE VIEWER DOES NOT CALL THESE.
// No navigation feedback, no page-snap vibration, no gesture haptics —
// the document viewer is silent. If a future feature needs haptics
// (destructive-action confirmations, form validation, etc.), import the
// functions from this module and call them from that feature's code.

/** @returns {Promise<void>} */
export async function vibrate(pattern) {
  const ms = typeof pattern === 'number' ? pattern : 20;
  await safeCall('MedvixHaptics', 'vibrate', { duration: ms });
}

/** Light impact. @returns {Promise<void>} */
export async function hapticLight()  { await safeCall('MedvixHaptics', 'light');  }

/** Medium impact. @returns {Promise<void>} */
export async function hapticMedium() { await safeCall('MedvixHaptics', 'medium'); }

/** Heavy impact. @returns {Promise<void>} */
export async function hapticHeavy()  { await safeCall('MedvixHaptics', 'heavy');  }

// ============================================================================
// 9. ENVIRONMENT SUBSCRIPTIONS
// ============================================================================

/**
 * Subscribe to color-scheme changes (light ↔ dark).
 *
 * @param {(scheme: 'light'|'dark') => void} handler
 * @returns {() => void}  unsubscribe
 */
export function onColorSchemeChange(handler) {
  if (typeof handler !== 'function') return () => {};
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return () => {};
  try {
    const mql = window.matchMedia('(prefers-color-scheme: dark)');
    const listener = (event) => {
      try { handler(event.matches ? 'dark' : 'light'); } catch { /* ignore */ }
    };
    if (typeof mql.addEventListener === 'function') {
      mql.addEventListener('change', listener);
      return () => { try { mql.removeEventListener('change', listener); } catch { /* ignore */ } };
    }
    if (typeof mql.addListener === 'function') {
      mql.addListener(listener);
      return () => { try { mql.removeListener(listener); } catch { /* ignore */ } };
    }
  } catch { /* ignore */ }
  return () => {};
}

/**
 * Subscribe to network status changes (online ↔ offline).
 * Fires once on subscribe with the initial value.
 *
 * @param {(online: boolean) => void} handler
 * @returns {() => void}  unsubscribe
 */
export function onNetworkStatusChange(handler) {
  if (typeof handler !== 'function') return () => {};
  if (typeof window === 'undefined') return () => {};

  const onOnline = () => { try { handler(true); } catch { /* ignore */ } };
  const onOffline = () => { try { handler(false); } catch { /* ignore */ } };

  try {
    window.addEventListener('online', onOnline);
    window.addEventListener('offline', onOffline);
    Promise.resolve().then(() => {
      const online = typeof navigator !== 'undefined' ? navigator.onLine !== false : true;
      try { handler(online); } catch { /* ignore */ }
    });
  } catch { /* ignore */ }

  return () => {
    try { window.removeEventListener('online', onOnline); } catch { /* ignore */ }
    try { window.removeEventListener('offline', onOffline); } catch { /* ignore */ }
  };
}

/**
 * Subscribe to safe-area changes (rotation, notch relocation, gesture-bar
 * relocation). Fires once on subscribe. Fires only on genuine change.
 *
 * @param {(insets: { top: number, right: number, bottom: number, left: number }) => void} handler
 * @returns {() => void}  unsubscribe
 */
export function onSafeAreaChange(handler) {
  if (typeof handler !== 'function') return () => {};
  if (typeof window === 'undefined') return () => {};

  let last = '';
  let rafId = null;

  const fire = () => {
    rafId = null;
    try {
      const insets = getSafeAreaInsets();
      const key = `${insets.top}|${insets.right}|${insets.bottom}|${insets.left}`;
      if (key === last) return;
      last = key;
      handler(insets);
    } catch { /* ignore */ }
  };

  const schedule = () => {
    if (rafId !== null) return;
    try { rafId = requestAnimationFrame(fire); }
    catch { fire(); }
  };

  try {
    window.addEventListener('orientationchange', schedule);
    window.addEventListener('resize', schedule);
    schedule();
  } catch { /* ignore */ }

  return () => {
    try { window.removeEventListener('orientationchange', schedule); } catch { /* ignore */ }
    try { window.removeEventListener('resize', schedule); } catch { /* ignore */ }
    if (rafId !== null) {
      try { cancelAnimationFrame(rafId); } catch { /* ignore */ }
      rafId = null;
    }
  };
}

// ============================================================================
// 10. BOOTSTRAP (Web/PWA DOM Viewer Core Only)
// ============================================================================

/**
 * Wire every native-only feature the Web DOM viewer core needs. Returns a
 * single teardown function that unsubscribes everything.
 *
 * Note: When `hasNativePdfViewer()` is true on Android, `viewer.js` routes
 * documents directly to `MedvixPdfActivity` and never boots `core.js`, so
 * this bootstrap is only invoked for the Web/PWA fallback viewer.
 *
 * @param {import('./core.js').ViewerCore} core
 * @returns {() => void}  teardown
 */
export function setupNativeBridge(core) {
  if (!core || typeof core.getBus !== 'function') return () => {};

  const teardowns = [];
  let bus = null;
  try { bus = core.getBus(); } catch { /* ignore */ }

  // Event name constants — duplicated from core.js to keep this module
  // cycle-free. Values must match core.js's Events object exactly.
  const EV = Object.freeze({
    PREVIEW_SUBSCRIBE_REQUESTED: 'preview:subscribe-requested',
    LOCAL_FILE_OPEN_REQUESTED:   'local-file:open-requested',
    DOCUMENT_LOADED:             'document:loaded',
    DOCUMENT_DESTROYED:          'document:destroyed',
  });

  // ── Back button — drawer → search → fullscreen → exit ────────────────
  teardowns.push(registerBackButton(() => {
    try {
      const drawer = document.getElementById('viewer-outline-drawer');
      if (drawer && drawer.classList.contains('open')) {
        drawer.classList.remove('open');
        const scrim = document.getElementById('viewer-drawer-scrim');
        if (scrim) scrim.classList.remove('open');
        const btn = document.getElementById('viewer-outline-btn');
        if (btn) btn.setAttribute('aria-expanded', 'false');
        return true;
      }
      const searchBar = document.getElementById('viewer-search-bar');
      if (searchBar && searchBar.classList.contains('active')) {
        searchBar.classList.remove('active');
        return true;
      }
      if (isFullscreen()) {
        exitFullscreen().catch(() => {});
        return true;
      }
    } catch { /* ignore */ }
    return false;
  }));

  // ── App state → render scheduler pause/resume ────────────────────────
  teardowns.push(onAppStateChange((state) => {
    try {
      const scheduler = core.getScheduler && core.getScheduler();
      if (!scheduler) return;
      if (state.isActive === false && typeof scheduler.pause === 'function')  scheduler.pause();
      if (state.isActive === true  && typeof scheduler.resume === 'function') scheduler.resume();
    } catch { /* ignore */ }
  }));

  // ── File intents → LOCAL_FILE_OPEN_REQUESTED ─────────────────────────
  teardowns.push(registerAppUrlOpen((url) => {
    try {
      if (bus && typeof bus.emit === 'function') {
        bus.emit(EV.LOCAL_FILE_OPEN_REQUESTED, { url });
      }
    } catch { /* ignore */ }
  }));

  // ── Color scheme → body.dark-theme ───────────────────────────────────
  teardowns.push(onColorSchemeChange((scheme) => {
    try {
      const isDark = scheme === 'dark';
      document.body.classList.toggle('dark-theme', isDark);
      const stored = (() => { try { return localStorage.getItem('viewer-theme'); } catch { return null; } })();
      if (stored === 'light' || stored === 'dark') {
        document.body.classList.toggle('dark-theme', stored === 'dark');
      }
    } catch { /* ignore */ }
  }));

  // ── Online/offline → bus event for UI toasts ─────────────────────────
  teardowns.push(onNetworkStatusChange((online) => {
    if (online) return;
    try {
      if (bus && typeof bus.emit === 'function') {
        bus.emit('network:offline', {});
      }
    } catch { /* ignore */ }
  }));

  // ── Document lifecycle → keep-awake cycle ────────────────────────────
  if (bus && typeof bus.on === 'function') {
    try {
      const offLoaded = bus.on(EV.DOCUMENT_LOADED, () => { keepAwake().catch(() => {}); });
      if (typeof offLoaded === 'function') teardowns.push(offLoaded);
    } catch { /* ignore */ }
    try {
      const offDestroyed = bus.on(EV.DOCUMENT_DESTROYED, () => { allowSleep().catch(() => {}); });
      if (typeof offDestroyed === 'function') teardowns.push(offDestroyed);
    } catch { /* ignore */ }
  }

  // ── Compose teardown ─────────────────────────────────────────────────
  return () => {
    for (const fn of teardowns.splice(0)) {
      try { fn(); } catch { /* ignore */ }
    }
  };
}

// ============================================================================
// 11. TEST HOOK
// ============================================================================

/**
 * Test-only: force-release the wake lock.
 * @private
 */
export async function __forceReleaseWakeLockForTests() {
  await allowSleep();
}