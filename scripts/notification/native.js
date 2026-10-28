// scripts/notification/native.js

/**
 * Native notification bridge for MedVix Android.
 *
 * This module wraps window.MedVixNotifications (the JS bridge exposed
 * by MainActivity.addJavascriptInterface). It is NOT the in-app
 * notification center — that lives in ./notifications.js (plural),
 * which reads from the Convex backend.
 *
 * ─── IMPORT STRATEGY (READ THIS) ────────────────────────────────
 *
 * Only `utils` is imported eagerly. `auth`, `security`, and
 * `convex-client` are LAZY-imported inside the two async functions
 * that need them (syncFcmToBackend, unregisterFromBackend).
 *
 * WHY: Rollup's production bundler concatenates the module graph.
 * If this file imports auth/security/convex-client at the top level,
 * Rollup places those modules earlier in the bundle. That reorders a
 * pre-existing circular dependency in sync.js → auth.js → … and
 * produces a "Cannot access X before initialization" TDZ error that
 * never shows up in dev mode (Vite serves each module separately).
 *
 * Making these lazy breaks the top-level module graph edge.
 * Same behaviour, zero TDZ risk, no change to any other file.
 *
 * Do NOT add top-level imports of anything that imports sync.js or
 * auth.js. If you need another module here, ask first.
 */

import * as utils from '../utils.js';

const BRIDGE_NAME    = 'MedVixNotifications';
const CALLBACKS_NAME = 'MedVixNotificationCallbacks';
const FCM_LOCAL_KEY  = 'fcmToken';

// ── In-flight dedup only ───────────────────────────────────────
// If two callers arrive within the same tick (e.g. boot's
// onFcmToken callback and a very fast login both fire before either
// resolves), the second shares the first's promise instead of firing
// a second mutation. Reset once the promise settles.
let _syncInFlight  = null;    // { token, promise } | null
let _unregInFlight = null;    // Promise | null

// ---------------------------------------------------------------
// Module-level state
// ---------------------------------------------------------------
let _initialized   = false;
let _onNavigate    = null;
let _onFcmToken    = null;
let _pendingToken  = null;
let _permResolver  = null;

// ---------------------------------------------------------------
// Callback surface — installed synchronously at import time
// ---------------------------------------------------------------
if (typeof window !== 'undefined') {
  window[CALLBACKS_NAME] = {
    onPermissionResult(granted) {
      const r = _permResolver;
      if (typeof r === 'function') {
        _permResolver = null;
        try { r(!!granted); }
        catch (e) { console.error('[NativeNotify] onPermissionResult threw', e); }
      }
    },
    onFcmToken(token) {
      if (typeof _onFcmToken === 'function') {
        try { _onFcmToken(token); }
        catch (e) { console.error('[NativeNotify] onFcmToken threw', e); }
      } else {
        _pendingToken = token;
      }
    },
  };
}

// ---------------------------------------------------------------
// Bridge detection (uncached — picks up late-registered bridge)
// ---------------------------------------------------------------
function bridge() {
  if (typeof window === 'undefined') return null;
  const b = window[BRIDGE_NAME];
  if (!b) return null;
  try {
    if (typeof b.isSupported === 'function' && !b.isSupported()) return null;
  } catch {
    return null;
  }
  return b;
}

// ---------------------------------------------------------------
// Public API
// ---------------------------------------------------------------

export function isSupported() { return !!bridge(); }

export function hasPermission() {
  const b = bridge();
  if (!b) return false;
  try { return !!b.hasPermission(); } catch { return false; }
}

export function isChannelEnabled() {
  const b = bridge();
  if (!b) return false;
  try { return !!b.isChannelEnabled(); } catch { return false; }
}

export function requestPermission() {
  return new Promise((resolve) => {
    const b = bridge();
    if (!b) return resolve(false);
    if (hasPermission()) return resolve(true);

    let settled = false;
    const settle = (v) => {
      if (settled) return;
      settled = true;
      resolve(!!v);
    };

    _permResolver = settle;
    try { b.requestPermission(); }
    catch (e) { console.error('[NativeNotify] requestPermission threw', e); settle(false); }

    setTimeout(() => settle(false), 30000);
  });
}

export function openSettings() {
  const b = bridge();
  if (!b) return;
  try { b.openNotificationSettings(); } catch {}
}

export function show(title, body, route = '/') {
  const b = bridge();
  if (!b) return false;
  try { return !!b.show(title, body, route); } catch { return false; }
}

export function schedule(id, title, body, route, triggerAtMillis) {
  const b = bridge();
  if (!b) return false;
  try { return !!b.schedule(id, title, body, route, triggerAtMillis); }
  catch { return false; }
}

export function cancel(id) {
  const b = bridge();
  if (!b) return false;
  try { return !!b.cancel(id); } catch { return false; }
}

export function cancelAll() {
  const b = bridge();
  if (!b) return false;
  try { return !!b.cancelAll(); } catch { return false; }
}

export function getCachedToken() {
  const b = bridge();
  if (!b) return '';
  try { return String(b.getCachedFcmToken() || ''); } catch { return ''; }
}

export function refreshToken() {
  const b = bridge();
  if (!b) return;
  try { b.refreshFcmToken(); } catch {}
}

export function consumePendingRoute() {
  const b = bridge();
  if (!b) return '';
  try { return String(b.consumePendingRoute() || ''); } catch { return ''; }
}

export function init(options = {}) {
  if (_initialized) return;
  _initialized = true;

  _onNavigate = typeof options.onNavigate === 'function' ? options.onNavigate : null;
  _onFcmToken = typeof options.onFcmToken === 'function' ? options.onFcmToken : null;

  if (_onNavigate) {
    const route = consumePendingRoute();
    if (route) {
      try { _onNavigate(route); }
      catch (e) { console.error('[NativeNotify] cold-start navigation threw', e); }
    }
  }

  if (typeof window !== 'undefined') {
    window.addEventListener('medvix:notification-tapped', () => {
      if (!_onNavigate) return;
      const route = consumePendingRoute();
      if (route) {
        try { _onNavigate(route); }
        catch (e) { console.error('[NativeNotify] warm-start navigation threw', e); }
      }
    });
  }

  if (_onFcmToken) {
    if (_pendingToken) {
      const t = _pendingToken;
      _pendingToken = null;
      try { _onFcmToken(t); } catch (e) { console.error(e); }
    } else {
      const cached = getCachedToken();
      if (cached) {
        try { _onFcmToken(cached); } catch (e) { console.error(e); }
      }
    }
  }

  console.log('[NativeNotify] Initialized. Supported:', isSupported());
}

// ---------------------------------------------------------------
// FCM token ↔ Convex sync
// ---------------------------------------------------------------
//
// auth, security, and convex-client are LAZY-imported here.
// See file-header comment. Do not move them to the top.

export async function syncFcmToBackend(explicitToken, force = false) {
  const token =
    explicitToken ||
    getCachedToken() ||
    utils.getLocalStorage(FCM_LOCAL_KEY, '');

  if (!token) return false;

  utils.setLocalStorage(FCM_LOCAL_KEY, token);

  // Lazy imports — break the cycle that causes TDZ in prod builds
  const auth = await import('../auth.js');
  if (!auth.checkAuth || !auth.checkAuth()) return false;
  const authToken = auth.getToken?.();
  if (!authToken) return false;

  // ── Guard 1: opt-out ────────────────────────────────────────
  if (!force) {
    const local = utils.getLocalStorage('medvix.notification.pushEnabled', null);
    if (local === 'false' || local === false) {
      return false;
    }
  }

  // ── Guard 2: in-flight dedup ────────────────────────────────
  if (_syncInFlight && _syncInFlight.token === token) {
    return _syncInFlight.promise;
  }

  const security = await import('../security.js');
  const { convexHttpClient } = await import('../convex-client.js');

  const promise = (async () => {
    try {
      const deviceId = await security.getDeviceId();
      const result = await convexHttpClient.action('push/mutations:registerDevice', {
        token: authToken,
        fcmToken: token,
        deviceId,
        platform: 'android',
      });
      if (result?.success) {
        console.log('[NativeNotify] FCM token registered with backend');
        return true;
      }
      console.warn('[NativeNotify] FCM registration rejected:', result?.message);
      return false;
    } catch (e) {
      console.warn('[NativeNotify] FCM registration failed:', e);
      return false;
    } finally {
      if (_syncInFlight && _syncInFlight.token === token) {
        _syncInFlight = null;
      }
    }
  })();

  _syncInFlight = { token, promise };
  return promise;
}

export async function unregisterFromBackend() {
  const auth = await import('../auth.js');
  if (!auth.checkAuth || !auth.checkAuth()) return false;
  const authToken = auth.getToken?.();
  if (!authToken) return false;

  if (_unregInFlight) {
    return _unregInFlight;
  }

  const security = await import('../security.js');
  const { convexHttpClient } = await import('../convex-client.js');

  const promise = (async () => {
    try {
      const deviceId = await security.getDeviceId();
      await convexHttpClient.action('push/mutations:deactivateDevice', {
        token: authToken,
        deviceId,
      });
      console.log('[NativeNotify] Device deactivated from backend');
      return true;
    } catch (e) {
      console.warn('[NativeNotify] Device deactivation failed:', e);
      return false;
    } finally {
      _unregInFlight = null;
    }
  })();

  _unregInFlight = promise;
  return promise;
}

// ---------------------------------------------------------------
// Global exposure (DevTools / QA)
// ---------------------------------------------------------------
if (typeof window !== 'undefined') {
  window.nativeNotifications = {
    isSupported,
    hasPermission,
    isChannelEnabled,
    requestPermission,
    openSettings,
    show,
    schedule,
    cancel,
    cancelAll,
    getCachedToken,
    refreshToken,
    consumePendingRoute,
    init,
    syncFcmToBackend,
    unregisterFromBackend,
  };
}