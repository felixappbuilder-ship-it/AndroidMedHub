// scripts/notification/native.js

/**
 * Native notification bridge for MedVix Android.
 *
 * This module wraps window.MedVixNotifications (the JS bridge exposed
 * by MainActivity.addJavascriptInterface). It is NOT the in-app
 * notification center — that lives in ./notifications.js (plural),
 * which reads from the Convex backend. This module handles:
 *
 *   • Local notifications (schedule, cancel, immediate show)
 *   • POST_NOTIFICATIONS permission flow
 *   • Notification-tap navigation (cold start + warm start)
 *   • FCM token retrieval + sync to Convex
 *
 * Directory note:
 *
 *   scripts/notifications.js   ← in-app notification center (existing)
 *   scripts/notification/*.js  ← native bridge + scheduling helpers
 *
 * Design:
 *
 *   • Callbacks installed at import time. Native code calls
 *     window.MedVixNotificationCallbacks.onFcmToken(token) and
 *     .onPermissionResult(granted) via evaluateJavascript. If those
 *     don't exist yet, the result is lost. Installing at import time
 *     guarantees the surface exists before any native call lands.
 *
 *   • FCM tokens buffer until init(). Firebase may deliver the token
 *     before init() runs (which happens after the router mounts).
 *     Buffered tokens are delivered inside init().
 *
 *   • init() is idempotent and safe to call on every boot.
 *
 *   • All methods safe to call on web / without native bridge — they
 *     return safe defaults rather than throwing.
 */

import * as utils from '../utils.js';
import * as auth from '../auth.js';
import * as security from '../security.js';
import { convexHttpClient } from '../convex-client.js';

const BRIDGE_NAME    = 'MedVixNotifications';
const CALLBACKS_NAME = 'MedVixNotificationCallbacks';
const FCM_LOCAL_KEY  = 'fcmToken';

// ── In-flight dedup only ───────────────────────────────────────
// If two callers arrive within the same tick (e.g. boot's
// onFcmToken callback and a very fast login both fire before either
// resolves), the second shares the first's promise instead of firing
// a second mutation. Reset once the promise settles.
//
// No cooldown on purpose. The two call paths are for fundamentally
// different situations:
//
//   • native.js         — automatic (boot, token rotation, login)
//   • notifications.js  — user tap on the bell
//
// Duplicate mutations are idempotent on the backend
// (push/mutations:registerDevice upserts by deviceId), so a
// duplicate costs one extra audit log line and nothing else.
// A cooldown was tried and removed — it made the user-tap path
// require a `force` flag and left a window where a legitimate
// token rotation could be skipped.
let _syncInFlight  = null;    // { token, promise } | null
let _unregInFlight = null;    // Promise | null

// ---------------------------------------------------------------
// Module-level state
// ---------------------------------------------------------------
let _initialized   = false;
let _onNavigate    = null;
let _onFcmToken    = null;
let _pendingToken  = null;
let _permResolver  = null; // one-shot resolver for requestPermission()

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

    // Failsafe — never leave the promise hanging
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

/**
 * Initialize the module. Call ONCE after the router is ready.
 *
 * @param {object}   options
 * @param {function} [options.onNavigate]  function(routePath) — called
 *     for a cold-start pending route and for every warm-start tap event.
 * @param {function} [options.onFcmToken]  function(token) — called
 *     whenever the token is issued, refreshed, or delivered from cache.
 */
export function init(options = {}) {
  if (_initialized) return;
  _initialized = true;

  _onNavigate = typeof options.onNavigate === 'function' ? options.onNavigate : null;
  _onFcmToken = typeof options.onFcmToken === 'function' ? options.onFcmToken : null;

  // Drain cold-start route (set by MainActivity PATCH A)
  if (_onNavigate) {
    const route = consumePendingRoute();
    if (route) {
      try { _onNavigate(route); }
      catch (e) { console.error('[NativeNotify] cold-start navigation threw', e); }
    }
  }

  // Warm-start tap (dispatched by MainActivity PATCH C)
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

  // Deliver FCM token
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

/**
 * Register this device's FCM token with the Convex backend.
 *
 * Persists the token to localStorage regardless of auth state (so
 * it survives reloads and is available when the user logs in).
 *
 * Only sends the Convex mutation when the user is authenticated —
 * on anonymous sessions it just caches locally.
 *
 * ─── GUARDS ────────────────────────────────────────────────────
 *
 *   1. Opt-out: if the user explicitly disabled push via the
 *      notifications page (localStorage['medvix.notification.pushEnabled']
 *      === 'false'), return false without calling the mutation.
 *      Without this, the boot-time token sync would flip
 *      pushEnabled back to true on the backend on every cold start,
 *      silently re-enabling push after the user turned it off.
 *
 *   2. In-flight dedup: if another syncFcmToBackend call with the
 *      same token is already awaiting the mutation, return that
 *      promise instead of firing a second mutation. Covers the
 *      boot + login same-tick case.
 *
 * No cooldown. Duplicate mutations are idempotent on the backend
 * (registerDevice upserts by deviceId), so a duplicate costs one
 * extra audit log line and nothing else. The two call paths (native
 * automatic + notifications-page user tap) are genuinely different
 * situations — a cooldown would either block user intent or be
 * irrelevant, depending on which path fired.
 *
 * `force = true` bypasses the opt-out guard. Used by
 * notifications.js:setPushEnabled(true) when the user explicitly
 * taps the bell to opt back in — without it, the opt-out guard
 * would silently refuse the request.
 *
 * @param {string}  [explicitToken]  — bypass getCachedToken() when the
 *     caller already has the token (e.g. from an onFcmToken callback).
 * @param {boolean} [force=false]    — bypass the opt-out guard. Only
 *     set when the user is explicitly opting back in.
 * @returns {Promise<boolean>}       — true iff the backend confirms the token.
 */
export async function syncFcmToBackend(explicitToken, force = false) {
  const token =
    explicitToken ||
    getCachedToken() ||
    utils.getLocalStorage(FCM_LOCAL_KEY, '');

  if (!token) return false;

  // Always persist locally — even if not authenticated yet
  utils.setLocalStorage(FCM_LOCAL_KEY, token);

  if (!auth.checkAuth || !auth.checkAuth()) return false;
  const authToken = auth.getToken?.();
  if (!authToken) return false;

  // ── Guard 1: opt-out ────────────────────────────────────────
  // Skipped when force=true (user is explicitly opting back in).
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

  // ── Actual mutation ─────────────────────────────────────────
  const promise = (async () => {
    try {
      const deviceId = await security.getDeviceId();
      // Server function is action → use .action()
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

/**
 * Deactivate this device's push registration. Call on logout so the
 * backend stops sending pushes to a logged-out install.
 *
 * Deduped against concurrent calls (rare — user double-tapping
 * logout) so two mutations don't land from one intent.
 */
export async function unregisterFromBackend() {
  if (!auth.checkAuth || !auth.checkAuth()) return false;
  const authToken = auth.getToken?.();
  if (!authToken) return false;

  if (_unregInFlight) {
    return _unregInFlight;
  }

  const promise = (async () => {
    try {
      const deviceId = await security.getDeviceId();
      // Server function is action → use .action()
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