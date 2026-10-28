// scripts/notifications.js

/**
 * MedVix Notification Engine – Fully Dynamic, Extensible
 *
 * - Loads notifications from IndexedDB (fast) and Convex (backend).
 * - Renders notifications with HTML bodies directly (if they contain HTML).
 * - Uses event delegation on the container to handle all [data-action] buttons.
 * - Generic action handler: navigate, call API, or run registered custom action.
 * - No hard-coded action cases; actions are resolved dynamically.
 *
 * FILTERING (as of the category-removal pass):
 *   Five filter tabs:  all / unread / pinned / archived / important
 *   Free-text search across title, body, subtitle.
 *   Three sort modes:  newest / oldest / priority.
 *
 *   The category dimension (Study / Exams / Community / … chips) has
 *   been removed. The `type` field on each notification is still used
 *   internally for the icon, priority, and type-specific CSS classes,
 *   but it is no longer a user-facing filter and there is no chip row.
 *
 * GLOBAL EXPOSURE:
 *   At module load this file installs a rich `window.notifications`
 *   object with convenience methods (refresh, registerAction, etc.).
 *   Pages that import this module must NOT overwrite `window.notifications`
 *   with the module namespace — the namespace has fewer methods than the
 *   object installed here. If a page does `window.notifications = notifModule`,
 *   `window.notifications.refresh` and `window.notifications.registerAction`
 *   will disappear.
 */

import * as ui from './ui.js';
import * as router from './router.js';
import * as utils from './utils.js';
import * as db from './db.js';
import * as auth from './auth.js';
import * as events from './events.js';
import { convexHttpClient } from './convex-client.js';
import { getToken } from './auth.js';

// ==================== CONSTANTS ====================
const NOTIFICATION_COOLDOWN_MS = 3 * 60 * 1000; // 3 hours
const NOTIFICATION_TIMER_KEY = 'notification_timer';
const POLL_INTERVAL_MS = 30000; // 30 seconds

// Persisted offline queue for read receipts — array of { id, at }.
// Populated when the backend call fails (offline or transient error),
// drained on the next online event or app boot.
const PENDING_READS_KEY = 'medvix.notification.pendingReads';

// Local push-enabled state for this device. Defaults to true (matches
// the backend default: every device that logs in has pushEnabled=true).
// Written to false when the user taps the opt-out bell. Cleared on
// logout (see auth.js) so a fresh login starts with push on.
const PUSH_ENABLED_KEY = 'medvix.notification.pushEnabled';

// Ids of cards the user has expanded this session. Preserved across
// re-renders so expanding a card (which marks it read and triggers a
// re-render) doesn't collapse it back.
const expandedIds = new Set();

// One-time delegation guard — prevents stacking click listeners on
// every render() call. Tracked by element reference so a page swap
// (new container) re-attaches.
let _delegationEl = null;

// ==================== ACTION REGISTRY ====================
// Custom actions can be registered here: actionHandlers['myAction'] = (element, notif) => {...}
const actionHandlers = {};

export function registerAction(name, handler) {
    if (typeof handler === 'function') {
        actionHandlers[name] = handler;
    }
}

// ==================== TIMER MANAGEMENT ====================
function getLastNotificationFetch() {
    const stored = localStorage.getItem(NOTIFICATION_TIMER_KEY);
    return stored ? parseInt(stored, 10) : 0;
}

function setLastNotificationFetch(ts) {
    localStorage.setItem(NOTIFICATION_TIMER_KEY, String(ts));
}

function isNotificationFetchAllowed() {
    const last = getLastNotificationFetch();
    const now = Date.now();
    return (now - last) > NOTIFICATION_COOLDOWN_MS;
}

// ==================== STATE ====================
let notifications = [];
let filteredNotifications = [];
let currentFilter = 'all';
let currentSort = 'newest';
let searchQuery = '';
let isLoaded = false;
let isInitialized = false;
let pollingInterval = null;

// DOM refs
let container;
let unreadBadge;
let loadingState;
let emptyState;
let errorState;
let searchInput;
let clearSearch;
let filterBtns;
let sortSelect;
let stats;

// ==================== DOM REFS ====================
function getDomRefs() {
    return {
        container: document.getElementById('notificationContainer'),
        unreadBadge: document.getElementById('unreadBadge'),
        loadingState: document.getElementById('loadingState'),
        emptyState: document.getElementById('emptyState'),
        errorState: document.getElementById('errorState'),
        searchInput: document.getElementById('searchInput'),
        clearSearch: document.getElementById('clearSearch'),
        filterBtns: document.querySelectorAll('.filter-btn'),
        sortSelect: document.getElementById('sortSelect'),
        stats: {
            unread: document.getElementById('statUnread'),
            read: document.getElementById('statRead'),
            pinned: document.getElementById('statPinned'),
            archived: document.getElementById('statArchived'),
            today: document.getElementById('statToday')
        }
    };
}

// ==================== MAP BACKEND TO FRONTEND ====================
function mapBackendToFrontend(backendNotif) {
    const type = backendNotif.type || 'general';
    return {
        id: backendNotif._id || backendNotif.id,
        title: backendNotif.title || 'Notification',
        body: backendNotif.message || backendNotif.body || '',
        timestamp: backendNotif.createdAt || backendNotif.timestamp || Date.now(),
        read: backendNotif.read || false,
        category: type,  // kept internally for icons/priority; not used as a filter
        data: backendNotif.data || null,
        senderId: backendNotif.senderId || null,
        pinned: backendNotif.pinned || false,
        archived: backendNotif.archived || false,
        important: backendNotif.important || false,
        priority: getPriority(type),
        icon: getIconForType(type),
        subtitle: getSubtitleForType(type, backendNotif.data),
        actions: [],
        media: backendNotif.media || null,
        progress: backendNotif.progress || null,
        userId: backendNotif.userId || null,
    };
}

function getPriority(type) {
    const high = ['admin_broadcast', 'payment_failed', 'subscription_expiry', 'security'];
    if (high.includes(type)) return 'high';
    if (type === 'critical') return 'critical';
    return 'normal';
}

function getIconForType(type) {
    const safeType = type || 'general';
    const icons = {
        'admin_broadcast': '📢',
        'exam_shared': '📤',
        'note_shared': '📝',
        'note_shared_with_user': '📝',
        'subscription_expiry': '⏰',
        'subscription_expiry_warning': '⏰',
        'subscription_renewed': '✅',
        'subscription_cancelled': '❌',
        'challenge_invite': '🏆',
        'challenge_created': '🏆',
        'challenge_results': '📊',
        'challenge_timeout': '⏳',
        'payment_success': '💳',
        'payment_failed': '❌',
        'trial_started': '🎉',
        'exam_result': '📊',
        'account_created': '👋',
        'new_device': '🔐',
        'password_changed': '🔑',
        'password_reset_requested': '🔐',
        'password_reset_completed': '🔓',
        'system': '⚙️',
        'security': '🛡️',
        'referral_reward': '💰',
        'admin_account_locked': '🔒',
        'admin_account_unlocked': '🔓',
        'admin_role_changed': '👤',
        'admin_force_logout': '🚪',
        'admin_password_reset': '🔑',
        'admin_subscription_extended': '✅',
        'admin_subscription_terminated': '⛔',
        'admin_trial_granted': '🎉',
        'admin_manual_payment': '💳',
        'admin_withdrawal_processed': '💸',
        'admin_withdrawal_rejected': '❌',
        'admin_reversal_processed': '↩️',
        'admin_reversal_rejected': '↩️',
        'admin_agent_verified': '✅',
        'admin_system_lockdown': '🔧',
    };
    return icons[safeType] || '📩';
}

function getSubtitleForType(type, data) {
    const safeType = type || 'general';
    if (safeType === 'payment_success' && data?.plan) return `Plan: ${data.plan}`;
    if (safeType === 'payment_failed' && data?.reason) return `Reason: ${data.reason}`;
    if (safeType === 'exam_result' && data?.subject) return `Subject: ${data.subject}`;
    if (safeType === 'challenge_invite' && data?.challengeCode) return `Code: ${data.challengeCode}`;
    if (safeType === 'note_shared' && data?.noteTitle) return `Note: ${data.noteTitle}`;
    if (safeType === 'admin_broadcast') return 'Admin Announcement';
    return '';
}

// ==================== ENSURE USER READY ====================
async function ensureUserReady() {
    if (auth.getUser()) return;
    await new Promise(resolve => setTimeout(resolve, 100));
}

// ==================== LOAD NOTIFICATIONS ====================
export async function loadNotifications(force = false) {
    await ensureUserReady();

    const refs = getDomRefs();
    container = refs.container;
    unreadBadge = refs.unreadBadge;
    loadingState = refs.loadingState;
    emptyState = refs.emptyState;
    errorState = refs.errorState;
    searchInput = refs.searchInput;
    clearSearch = refs.clearSearch;
    filterBtns = refs.filterBtns;
    sortSelect = refs.sortSelect;
    stats = refs.stats;

    showLoading();

    try {
        const user = auth.getUser();
        if (!user) {
            showEmpty('Please log in to view notifications');
            return;
        }

        // 1. Load from IndexedDB immediately (fast)
        let localNotifs = await db.getNotifications(user._id, { limit: 50 });
        if (localNotifs && localNotifs.length > 0) {
            notifications = localNotifs.map(mapBackendToFrontend);
            isLoaded = true;
            applyFilters();
            render();
            updateStats();
            updateBadge();
            hideAllStates();
        }

        // 2. Fetch from backend only if allowed by cooldown (or forced)
        if (navigator.onLine && (force || isNotificationFetchAllowed())) {
            const token = getToken();
            if (token) {
                const result = await convexHttpClient.action("notifications/queries:getNotifications", {
                    token,
                    limit: 50,
                });
                if (result.success && result.data && result.data.notifications) {
                    const serverNotifs = result.data.notifications;
                    for (const notif of serverNotifs) {
                        const frontendNotif = mapBackendToFrontend(notif);
                        await db.saveNotification(frontendNotif);
                    }
                    localNotifs = await db.getNotifications(user._id, { limit: 50 });
                    notifications = localNotifs.map(mapBackendToFrontend);
                    isLoaded = true;
                    applyFilters();
                    render();
                    updateStats();
                    updateBadge();
                    hideAllStates();
                    setLastNotificationFetch(Date.now());
                }
            }
        } else if (!force) {
            console.log('[Notifications] Cooldown active – using cached data.');
        }

        if (!isLoaded) {
            showEmpty('No notifications found.');
        }
    } catch (err) {
        console.error('[Notifications] Load error:', err);
        showError();
    }
}

// ==================== POLLING ====================
export function startPolling() {
    if (pollingInterval) {
        clearInterval(pollingInterval);
        pollingInterval = null;
    }

    const user = auth.getUser();
    if (!user || !navigator.onLine) {
        console.log('[Notifications] Not starting polling: offline or no user');
        return;
    }

    const token = getToken();
    if (!token) {
        console.log('[Notifications] No token, cannot poll');
        return;
    }

    console.log('[Notifications] Starting polling (every 30s, cooldown 3h)');

    pollingInterval = setInterval(async () => {
        try {
            const userNow = auth.getUser();
            if (!userNow || !navigator.onLine) {
                stopPolling();
                return;
            }
            const tokenNow = getToken();
            if (!tokenNow) {
                stopPolling();
                return;
            }

            if (!isNotificationFetchAllowed()) {
                return;
            }

            const lastTimestamp = notifications.length > 0
                ? Math.max(...notifications.map(n => n.timestamp))
                : 0;

            const result = await convexHttpClient.action("notifications/queries:getNotificationsSince", {
                token: tokenNow,
                since: lastTimestamp || 0,
                limit: 20,
            });

            if (result.success && result.data && result.data.notifications && result.data.notifications.length > 0) {
                const newNotifs = result.data.notifications;
                handleNewNotifications(newNotifs);
                setLastNotificationFetch(Date.now());
            }
        } catch (err) {
            console.warn('[Notifications] Polling error:', err);
        }
    }, POLL_INTERVAL_MS);
}

export function stopPolling() {
    if (pollingInterval) {
        clearInterval(pollingInterval);
        pollingInterval = null;
        console.log('[Notifications] Polling stopped.');
    }
}

// ==================== HANDLE NEW NOTIFICATIONS ====================
function handleNewNotifications(newNotifs) {
    if (!newNotifs || newNotifs.length === 0) return;

    const latest = newNotifs[newNotifs.length - 1];
    showNotificationToast(latest);

    addNotifications(newNotifs);
    updateBadge();
    events.events.emit('new-notification', { notifications: newNotifs });
}

function showNotificationToast(notif) {
    if (!notif) return;
    if (!ui.getAppSetting('notifications')) return;

    // Only show toast for critical or high priority notifications
    if (notif.priority !== 'critical' && notif.priority !== 'high') {
        return;
    }

    if (ui.getAppSetting('sound')) {
        try {
            const audio = new Audio('/assets/sounds/notification.mp3');
            audio.play().catch(() => {});
        } catch (e) { /* ignore */ }
    }

    const message = 'You have a new notification';
    if (ui && typeof ui.showToast === 'function') {
        ui.showToast(message, 'info', 5000);
    } else {
        console.log('[Notification]', message);
    }
}

// ==================== ADD NOTIFICATIONS ====================
export function addNotifications(newNotifs) {
    if (!newNotifs || newNotifs.length === 0) return;

    const user = auth.getUser();
    if (!user) return;

    newNotifs.forEach(async (notif) => {
        const frontendNotif = notif._id ? mapBackendToFrontend(notif) : notif;
        if (!frontendNotif.userId) frontendNotif.userId = user._id;
        await db.saveNotification(frontendNotif).catch(() => {});
    });

    db.getNotifications(user._id, { limit: 50 }).then(localNotifs => {
        if (localNotifs && localNotifs.length > 0) {
            notifications = localNotifs.map(mapBackendToFrontend);
            isLoaded = true;
            applyFilters();
            render();
            updateStats();
            updateBadge();
            hideAllStates();
        }
    }).catch(() => {});
}

export function getUnreadCount() {
    return notifications.filter(n => !n.read).length;
}

// ==================== RENDER ====================
export function render() {
    if (!isLoaded) return;
    if (!container) {
        const refs = getDomRefs();
        container = refs.container;
    }
    if (!container) return;

    container.innerHTML = '';

    if (filteredNotifications.length === 0) {
        if (searchQuery || currentFilter !== 'all') {
            const empty = document.createElement('div');
            empty.className = 'state-message empty';
            empty.innerHTML = `
                <div class="empty-icon">🔍</div>
                <h3>No matching notifications</h3>
                <p>Try adjusting your filters or search terms.</p>
                <button onclick="window.notifications?.resetFilters()" class="btn-secondary">Reset Filters</button>
            `;
            container.appendChild(empty);
        } else {
            if (emptyState) emptyState.style.display = 'block';
        }
        return;
    }

    const groups = groupNotifications(filteredNotifications);

    for (const [label, items] of Object.entries(groups)) {
        const groupDiv = document.createElement('div');
        groupDiv.className = 'notification-group';
        const header = document.createElement('div');
        header.className = 'group-header';
        header.textContent = label;
        groupDiv.appendChild(header);

        items.forEach(notif => {
            const card = createCard(notif);
            groupDiv.appendChild(card);
        });
        container.appendChild(groupDiv);
    }

    // Event delegation for backend-provided action buttons
    // ([data-action] inside the HTML body). Attached once per
    // container element — render() runs on every markRead and must
    // not stack handlers.
    if (container && _delegationEl !== container) {
        _delegationEl = container;
        container.addEventListener('click', (e) => {
            const actionEl = e.target.closest('[data-action]');
            if (!actionEl) return;

            const notifCard = e.target.closest('.notif-card');
            const notifId = notifCard?.dataset.id;
            const notif = notifications.find((n) => n.id === notifId);

            processAction(actionEl, notif);
        });
    }
}

function groupNotifications(items) {
    const groups = {};
    const today = new Date().toDateString();
    const yesterday = new Date(Date.now() - 86400000).toDateString();

    items.forEach(notif => {
        const date = new Date(notif.timestamp);
        const dateStr = date.toDateString();
        let label;
        if (dateStr === today) label = 'Today';
        else if (dateStr === yesterday) label = 'Yesterday';
        else if (date.getFullYear() === new Date().getFullYear()) {
            label = date.toLocaleDateString('en-US', { month: 'long', day: 'numeric' });
        } else {
            label = date.toLocaleDateString('en-US', { year: 'numeric', month: 'long', day: 'numeric' });
        }
        if (!groups[label]) groups[label] = [];
        groups[label].push(notif);
    });
    return groups;
}

function createCard(notif) {
    const template = document.getElementById('notificationCardTemplate');
    if (!template) {
        console.warn('[Notifications] notificationCardTemplate missing — cannot render card');
        const fallback = document.createElement('div');
        fallback.className = 'notif-card';
        fallback.textContent = notif.title || 'Notification';
        return fallback;
    }

    const card = template.content.cloneNode(true).firstElementChild;
    card.dataset.id = notif.id;
    card.classList.add(`type-${notif.category}`);

    if (!notif.read) card.classList.add('unread');
    if (notif.pinned) card.classList.add('pinned');
    if (notif.priority === 'critical') card.classList.add('critical');

    const icon = card.querySelector('.card-icon');
    if (icon) icon.textContent = notif.icon || '📩';

    const titleEl = card.querySelector('.card-title');
    if (titleEl) titleEl.textContent = notif.title || 'Notification';

    const subtitleEl = card.querySelector('.card-subtitle');
    if (subtitleEl) subtitleEl.textContent = notif.subtitle || '';

    // ── Body: always render full content; visibility handled by CSS ──
    const body = card.querySelector('.card-body');
    if (body) {
        const rawBody = notif.body || '';
        if (rawBody.trim().startsWith('<')) {
            body.innerHTML = rawBody;
        } else {
            body.textContent = rawBody;
        }
    }

    // ── Snippet: inserted dynamically right after the header ──
    // Shows the first ~120 chars of the plain-text body when the
    // card is collapsed. Hidden via CSS when expanded.
    let snippetEl = card.querySelector('.card-snippet');
    if (!snippetEl) {
        snippetEl = document.createElement('div');
        snippetEl.className = 'card-snippet';
        const header = card.querySelector('.card-header');
        if (header && header.parentNode) {
            header.parentNode.insertBefore(snippetEl, header.nextSibling);
        } else {
            card.insertBefore(snippetEl, card.firstChild);
        }
    }
    snippetEl.textContent = makeSnippet(notif.body || '');

    // ── Media / progress / buttons (hidden until expanded, via CSS) ──
    const mediaContainer = card.querySelector('.card-media');
    if (mediaContainer && notif.media) {
        if (notif.media.type === 'image') {
            const img = document.createElement('img');
            img.src = notif.media.url;
            img.alt = notif.media.alt || '';
            mediaContainer.appendChild(img);
        } else if (notif.media.type === 'video') {
            const video = document.createElement('video');
            video.src = notif.media.url;
            video.controls = true;
            mediaContainer.appendChild(video);
        }
    } else if (mediaContainer) {
        mediaContainer.style.display = 'none';
    }

    const progressContainer = card.querySelector('.card-progress');
    if (progressContainer && notif.progress) {
        const progress = document.createElement('progress');
        progress.value = notif.progress.value;
        progress.max = notif.progress.max || 100;
        progressContainer.appendChild(progress);
    } else if (progressContainer) {
        progressContainer.style.display = 'none';
    }

    const btnContainer = card.querySelector('.card-buttons');
    if (btnContainer) btnContainer.style.display = 'none';

    const timeEl = card.querySelector('.card-time');
    if (timeEl) timeEl.textContent = utils.formatDate(notif.timestamp, 'full');

    const priorityEl = card.querySelector('.card-priority');
    if (priorityEl) {
        priorityEl.textContent = notif.priority || 'normal';
        priorityEl.className = `card-priority priority-${notif.priority || 'normal'}`;
    }

    // ── Expand chevron in the footer ──
    let expandBtn = card.querySelector('.card-expand-btn');
    if (!expandBtn) {
        expandBtn = document.createElement('button');
        expandBtn.className = 'card-expand-btn';
        expandBtn.type = 'button';
        expandBtn.setAttribute('aria-label', 'Toggle notification details');
        const footer = card.querySelector('.card-footer');
        if (footer) {
            footer.appendChild(expandBtn);
        } else {
            card.appendChild(expandBtn);
        }
    }

    // ── Initial state from expandedIds ──
    const wasExpanded = expandedIds.has(notif.id);
    if (wasExpanded) {
        card.classList.add('expanded');
        expandBtn.textContent = '▴';
    } else {
        card.classList.add('collapsed');
        expandBtn.textContent = '▾';
    }

    // ── Toggle handler ──
    // Expanding marks the notification as read — only on the FIRST
    // expand, and only if not already read.
    const toggle = () => {
        const isCollapsed = card.classList.contains('collapsed');
        if (isCollapsed) {
            card.classList.remove('collapsed');
            card.classList.add('expanded');
            expandBtn.textContent = '▴';
            expandedIds.add(notif.id);
            if (!notif.read) markRead(notif.id);
        } else {
            card.classList.add('collapsed');
            card.classList.remove('expanded');
            expandBtn.textContent = '▾';
            expandedIds.delete(notif.id);
        }
    };

    // Header is clickable except on the pin/archive/delete buttons
    const header = card.querySelector('.card-header');
    if (header) {
        header.style.cursor = 'pointer';
        header.addEventListener('click', (e) => {
            if (e.target.closest('.card-actions button')) return;
            toggle();
        });
    }
    expandBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        toggle();
    });

    // ── Pin / Archive / Delete ──
    const pinBtn = card.querySelector('.pin-btn');
    if (pinBtn) {
        pinBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            togglePin(notif.id);
        });
    }
    const archiveBtn = card.querySelector('.archive-btn');
    if (archiveBtn) {
        archiveBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            toggleArchive(notif.id);
        });
    }
    const deleteBtn = card.querySelector('.delete-btn');
    if (deleteBtn) {
        deleteBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            deleteNotification(notif.id);
        });
    }

    return card;
}

// ==================== DYNAMIC ACTION PROCESSOR ====================
function processAction(element, notif) {
    const action = element.dataset.action;
    const data = { ...element.dataset };
    delete data.action;

    console.log('[Notification Action]', action, data, 'notif:', notif?.id);

    // 1. Custom registered handler
    if (actionHandlers[action]) {
        actionHandlers[action](element, notif, data);
        if (!notif?.read) markRead(notif.id);
        return;
    }

    // 2. API call if data-api or action starts with "api:"
    const apiName = data.api || (action.startsWith('api:') ? action.slice(4) : null);
    if (apiName) {
        callBackendAction(apiName, data, notif);
        return;
    }

    // 3. Navigation if data-route present
    if (data.route) {
        const params = { ...data };
        delete params.route;
        delete params.api;
        delete params.action;
        router.navigateTo(data.route, params);
        if (!notif?.read) markRead(notif.id);
        return;
    }

    // 4. Fallback
    ui.showToast(`Action: ${action}`, 'info');
}

function callBackendAction(apiName, data, notif) {
    const token = getToken();
    if (!token) {
        ui.showToast('Not authenticated', 'error');
        return;
    }

    const params = { token, ...data };
    delete params.action;
    delete params.api;

    ui.showLoading('Processing...');

    convexHttpClient.action(apiName, params)
        .then(() => {
            ui.hideLoading();
            ui.showToast('Action completed', 'success');
            if (notif && !notif.read) markRead(notif.id);
            if (notif && data.dismiss !== undefined) {
                deleteNotification(notif.id);
            }
        })
        .catch(err => {
            ui.hideLoading();
            console.error('Backend action failed:', err);
            ui.showToast('Action failed', 'error');
        });
}

// ==================== PUSH OPT-IN STATE ====================

/**
 * Whether push notifications are currently enabled for THIS device,
 * per local state. This is the value the UI bell reflects. It's kept
 * in sync with the backend by setPushEnabled(); the default (true)
 * matches what a fresh login produces.
 */
export function isPushEnabled() {
    const stored = utils.getLocalStorage(PUSH_ENABLED_KEY, null);
    if (stored === 'false' || stored === false) return false;
    return true;
}

function _setPushEnabledLocal(enabled) {
    utils.setLocalStorage(PUSH_ENABLED_KEY, enabled ? 'true' : 'false');
    events.events.emit('push-enabled-changed', { enabled: !!enabled });
}

/**
 * Enable or disable push notifications for this device.
 *
 * Enabled → push/mutations:registerDevice (re-registers the current
 *           FCM token, upserts fcmToken + pushEnabled=true).
 * Disabled → push/mutations:deactivateDevice (flips pushEnabled=false;
 *           keeps the token so re-enabling is instant).
 *
 * Requires online + authenticated. Returns true on success.
 */
export async function setPushEnabled(enabled) {
    const desired = !!enabled;

    if (!navigator.onLine) {
        ui.showToast('You need to be online to change push settings', 'warning');
        return false;
    }
    const token = getToken();
    if (!token) {
        ui.showToast('Please log in to change push settings', 'warning');
        return false;
    }

    try {
        const security = await import('./security.js');
        const deviceId = await security.getDeviceId();

        if (desired) {
            const fcmToken =
                (window.MedVixNotifications &&
                 typeof window.MedVixNotifications.getCachedFcmToken === 'function')
                    ? (window.MedVixNotifications.getCachedFcmToken() || '')
                    : '';
            if (!fcmToken) {
                ui.showToast('Push token not ready on this device. Please try again shortly.', 'warning');
                return false;
            }
            // Server function is action (verifyToken via runAction) → .action()
            const result = await convexHttpClient.action(
                'push/mutations:registerDevice',
                { token, fcmToken, deviceId, platform: 'android' },
            );
            if (result && result.success === false) {
                ui.showToast(result.message || 'Could not enable push', 'error');
                return false;
            }
        } else {
            // Server function is action → .action()
            const result = await convexHttpClient.action(
                'push/mutations:deactivateDevice',
                { token, deviceId },
            );
            if (result && result.success === false) {
                ui.showToast(result.message || 'Could not disable push', 'error');
                return false;
            }
        }

        _setPushEnabledLocal(desired);
        ui.showToast(
            desired ? 'Push notifications enabled' : 'Push notifications disabled',
            'success',
        );
        return true;
    } catch (err) {
        console.warn('[Notifications] push toggle failed', err);
        ui.showToast('Could not update push setting', 'error');
        return false;
    }
}

export async function togglePush() {
    return setPushEnabled(!isPushEnabled());
}

// ==================== OFFLINE READ QUEUE ====================

function _getPendingReads() {
    try {
        const raw = utils.getLocalStorage(PENDING_READS_KEY, null);
        if (!raw) return [];
        return Array.isArray(raw) ? raw : (JSON.parse(raw) || []);
    } catch {
        return [];
    }
}

function _savePendingReads(reads) {
    try {
        utils.setLocalStorage(PENDING_READS_KEY, JSON.stringify(reads));
    } catch { /* ignore storage failures */ }
}

function _queueRead(id) {
    const pending = _getPendingReads();
    if (!pending.some((r) => r.id === id)) {
        pending.push({ id, at: Date.now() });
        _savePendingReads(pending);
    }
}

/**
 * Drain the pending read queue. Called on online event and on
 * notifications-page init. Safe to call concurrently — the queue is
 * re-read at start and rewritten to only the entries that still
 * failed, so a mid-flight second call doesn't double-send.
 */
export async function drainReadQueue() {
    if (!navigator.onLine) return { sent: 0, failed: 0 };
    const pending = _getPendingReads();
    if (pending.length === 0) return { sent: 0, failed: 0 };

    const token = getToken();
    if (!token) return { sent: 0, failed: 0 };

    const failed = [];
    let sent = 0;

    for (const r of pending) {
        try {
            const result = await convexHttpClient.mutation(
                'notifications/mutations:markNotificationRead',
                { token, notificationId: r.id },
            );
            if (result && result.success === false) {
                failed.push(r);
            } else {
                sent++;
            }
        } catch {
            failed.push(r);
        }
    }

    _savePendingReads(failed);
    if (sent > 0) {
        console.log(`[Notifications] Drained ${sent} pending read receipt${sent === 1 ? '' : 's'}`);
    }
    return { sent, failed: failed.length };
}

// ==================== SNIPPET BUILDER ====================
//
// Collapsed cards show a short preview of the body. If the body is
// HTML (backend message fragments are), strip tags + decode entities.
// Truncate to SNIPPET_MAX. The ellipsis is intentional — the user
// knows there's more to see.

const SNIPPET_MAX = 120;

function makeSnippet(body) {
    if (!body || typeof body !== 'string') return '';
    let text = body;

    if (text.trim().startsWith('<')) {
        text = text
            .replace(/<style[\s\S]*?<\/style>/gi, '')
            .replace(/<script[\s\S]*?<\/script>/gi, '')
            .replace(/<br\s*\/?>/gi, ' ')
            .replace(/<\/(p|li|div|h[1-6])>/gi, ' ')
            .replace(/<[^>]+>/g, '')
            .replace(/&nbsp;/g, ' ')
            .replace(/&amp;/g, '&')
            .replace(/&lt;/g, '<')
            .replace(/&gt;/g, '>')
            .replace(/&quot;/g, '"')
            .replace(/&#39;/g, "'")
            .replace(/\s+/g, ' ')
            .trim();
    } else {
        text = text.replace(/\s+/g, ' ').trim();
    }

    if (text.length <= SNIPPET_MAX) return text;
    return text.slice(0, SNIPPET_MAX).trimEnd() + '…';
}

// ==================== CRUD ====================
export async function markRead(id) {
    const notif = notifications.find(n => n.id === id);
    if (!notif) return;
    if (notif.read) return;  // idempotent

    notif.read = true;

    // ---- 1. Backend (with offline fallback to queue) ----
    if (navigator.onLine) {
        const token = getToken();
        if (token) {
            try {
                // Server function is action (calls verifyToken via runAction),
                // so client must use .action() — .mutation() would fail with
                // FunctionType mismatch.
                const result = await convexHttpClient.action(
                    'notifications/mutations:markNotificationRead',
                    { token, notificationId: id },
                );
                if (result && result.success === false) {
                    _queueRead(id);
                }
            } catch (err) {
                console.warn('[Notifications] Mark read network error — queueing', err);
                _queueRead(id);
            }
        }
    } else {
        _queueRead(id);
    }

    // ---- 2. Local DB (fast, always succeeds) ----
    await db.markNotificationRead(id).catch(() => {});

    // ---- 3. UI refresh ----
    applyFilters();
    render();
    updateStats();
    updateBadge();
}

export async function markAllRead() {
    const user = auth.getUser();
    if (!user) return;
    notifications.forEach(n => n.read = true);
    try {
        const token = getToken();
        if (token) {
            // Server function is action → use .action()
            await convexHttpClient.action("notifications/mutations:markAllNotificationsRead", {
                token,
            });
        }
    } catch (err) {
        console.warn('[Notifications] Mark all read network error', err);
    }
    await db.markAllNotificationsRead(user._id).catch(() => {});
    applyFilters();
    render();
    updateStats();
    updateBadge();
    ui.showToast('All notifications marked as read', 'success');
}

export function togglePin(id) {
    const notif = notifications.find(n => n.id === id);
    if (notif) {
        notif.pinned = !notif.pinned;
        db.updateNotification(id, { pinned: notif.pinned }).catch(() => {});
        applyFilters();
        render();
        updateStats();
    }
}

export function toggleArchive(id) {
    const notif = notifications.find(n => n.id === id);
    if (notif) {
        notif.archived = !notif.archived;
        db.updateNotification(id, { archived: notif.archived }).catch(() => {});
        applyFilters();
        render();
        updateStats();
    }
}

export function deleteNotification(id) {
    notifications = notifications.filter(n => n.id !== id);
    db.deleteNotification(id).catch(() => {});
    applyFilters();
    render();
    updateStats();
    updateBadge();
}

// ==================== FILTERS & SEARCH ====================
export function applyFilters() {
    let filtered = [...notifications];

    if (currentFilter === 'unread') {
        filtered = filtered.filter(n => !n.read);
    } else if (currentFilter === 'pinned') {
        filtered = filtered.filter(n => n.pinned);
    } else if (currentFilter === 'archived') {
        filtered = filtered.filter(n => n.archived);
    } else if (currentFilter === 'important') {
        filtered = filtered.filter(n => n.important);
    }

    if (searchQuery.trim()) {
        const q = searchQuery.toLowerCase().trim();
        filtered = filtered.filter(n =>
            n.title.toLowerCase().includes(q) ||
            (n.body && n.body.toLowerCase().includes(q)) ||
            (n.subtitle && n.subtitle.toLowerCase().includes(q))
        );
    }

    if (currentSort === 'newest') {
        filtered.sort((a, b) => new Date(b.timestamp) - new Date(a.timestamp));
    } else if (currentSort === 'oldest') {
        filtered.sort((a, b) => new Date(a.timestamp) - new Date(b.timestamp));
    } else if (currentSort === 'priority') {
        const order = { critical: 0, high: 1, normal: 2, low: 3, silent: 4 };
        filtered.sort((a, b) => (order[a.priority] || 2) - (order[b.priority] || 2));
    }

    filtered.sort((a, b) => (b.pinned ? 1 : 0) - (a.pinned ? 1 : 0));

    filteredNotifications = filtered;
}

export function setFilter(filter) {
    currentFilter = filter;
    if (filterBtns) {
        filterBtns.forEach(btn => {
            btn.classList.toggle('active', btn.dataset.filter === filter);
        });
    }
    applyFilters();
    render();
}

export function setSort(sort) {
    currentSort = sort;
    applyFilters();
    render();
}

export function setSearch(query) {
    searchQuery = query;
    if (clearSearch) {
        clearSearch.classList.toggle('visible', query.length > 0);
    }
    applyFilters();
    render();
}

export function resetFilters() {
    currentFilter = 'all';
    searchQuery = '';
    if (searchInput) searchInput.value = '';
    if (clearSearch) clearSearch.classList.remove('visible');
    if (filterBtns) {
        filterBtns.forEach(btn => btn.classList.remove('active'));
        const allBtn = document.querySelector('.filter-btn[data-filter="all"]');
        if (allBtn) allBtn.classList.add('active');
    }
    applyFilters();
    render();
}

// ==================== GETTERS (for state adapters) ====================
export function getFilter() {
    return currentFilter;
}

export function getSort() {
    return currentSort;
}

export function getSearch() {
    return searchQuery;
}

// ==================== STATISTICS ====================
function updateStats() {
    if (!stats) {
        const refs = getDomRefs();
        stats = refs.stats;
    }
    if (!stats) return;

    const unread = notifications.filter(n => !n.read).length;
    const read = notifications.filter(n => n.read && !n.archived).length;
    const pinned = notifications.filter(n => n.pinned).length;
    const archived = notifications.filter(n => n.archived).length;
    const today = notifications.filter(n => {
        const d = new Date(n.timestamp);
        return d.toDateString() === new Date().toDateString();
    }).length;

    if (stats.unread) stats.unread.textContent = unread;
    if (stats.read) stats.read.textContent = read;
    if (stats.pinned) stats.pinned.textContent = pinned;
    if (stats.archived) stats.archived.textContent = archived;
    if (stats.today) stats.today.textContent = today;
}

function updateBadge() {
    if (!unreadBadge) {
        const refs = getDomRefs();
        unreadBadge = refs.unreadBadge;
    }
    if (!unreadBadge) return;

    const unread = notifications.filter(n => !n.read).length;
    unreadBadge.textContent = unread;
    unreadBadge.style.display = unread > 0 ? 'inline' : 'none';
}

// ==================== UI STATES ====================
function showLoading() {
    if (loadingState) loadingState.style.display = 'block';
    if (emptyState) emptyState.style.display = 'none';
    if (errorState) errorState.style.display = 'none';
    if (container) container.innerHTML = '';
}

function showError() {
    if (loadingState) loadingState.style.display = 'none';
    if (emptyState) emptyState.style.display = 'none';
    if (errorState) errorState.style.display = 'block';
}

function showEmpty(message = 'No notifications found.') {
    if (loadingState) loadingState.style.display = 'none';
    if (emptyState) {
        emptyState.style.display = 'block';
        const p = emptyState.querySelector('p');
        if (p) p.textContent = message;
    }
    if (errorState) errorState.style.display = 'none';
}

function hideAllStates() {
    if (loadingState) loadingState.style.display = 'none';
    if (emptyState) emptyState.style.display = 'none';
    if (errorState) errorState.style.display = 'none';
}

// ==================== HELP ====================
export function openHelp() {
    const overlay = document.getElementById('helpOverlay');
    if (!overlay) return;
    overlay.style.display = 'flex';
    import('./help.js').then(module => {
        module.renderHelp(overlay);
    }).catch(() => {
        ui.showToast('Help module not available', 'warning');
    });
}

// ==================== INITIALIZATION ====================
export function init() {
    if (isInitialized) return;
    isInitialized = true;

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', setupEventListeners);
    } else {
        setupEventListeners();
    }
}

async function setupEventListeners() {
    const refs = getDomRefs();
    container = refs.container;
    unreadBadge = refs.unreadBadge;
    loadingState = refs.loadingState;
    emptyState = refs.emptyState;
    errorState = refs.errorState;
    searchInput = refs.searchInput;
    clearSearch = refs.clearSearch;
    filterBtns = refs.filterBtns;
    sortSelect = refs.sortSelect;
    stats = refs.stats;

    if (filterBtns && filterBtns.length > 0) {
        filterBtns.forEach(btn => {
            btn.addEventListener('click', () => {
                setFilter(btn.dataset.filter);
            });
        });
    }

    if (sortSelect) {
        sortSelect.addEventListener('change', () => {
            setSort(sortSelect.value);
        });
    }

    if (searchInput) {
        searchInput.addEventListener('input', (e) => {
            setSearch(e.target.value);
        });
    }
    if (clearSearch) {
        clearSearch.addEventListener('click', () => {
            if (searchInput) searchInput.value = '';
            setSearch('');
        });
    }

    document.addEventListener('keydown', (e) => {
        if (e.key === '/' && e.ctrlKey) {
            e.preventDefault();
            if (searchInput) searchInput.focus();
        }
        if (e.key === 'Escape') {
            if (searchInput) searchInput.blur();
            const helpOverlay = document.getElementById('helpOverlay');
            if (helpOverlay && helpOverlay.style.display === 'flex') {
                helpOverlay.style.display = 'none';
            }
        }
    });

    events.events.on('new-notification', (data) => {
        if (data && data.notifications) {
            addNotifications(data.notifications);
        }
    });

    if (auth.getUser()) {
        loadNotifications(false);
        startPolling();
    } else {
        const checkUser = setInterval(async () => {
            if (auth.getUser()) {
                clearInterval(checkUser);
                await loadNotifications(false);
                startPolling();
            }
        }, 500);
        setTimeout(() => clearInterval(checkUser), 10000);
    }
}

// ==================== EXPOSE GLOBALLY ====================
// This object is intentionally richer than the module namespace — it
// adds `refresh` (a force-reload shortcut) and `_processAction` (debug).
// Callers that import this module should NOT overwrite window.notifications
// with their own import binding; doing so removes those extras.
window.notifications = {
    load: loadNotifications,
    render,
    markRead,
    markAllRead,
    togglePin,
    toggleArchive,
    deleteNotification,
    setFilter,
    setSort,
    setSearch,
    resetFilters,
    openHelp,
    refresh: () => loadNotifications(true),
    init,
    addNotifications,
    getUnreadCount,
    startPolling,
    stopPolling,
    registerAction,
    // Push opt-out
    isPushEnabled,
    setPushEnabled,
    togglePush,
    // Offline read receipts
    drainReadQueue,
    _processAction: processAction
};

// ==================== AUTO-INIT ====================
if (document.readyState === 'complete') {
    init();
} else {
    document.addEventListener('DOMContentLoaded', init);
}

export default {
    loadNotifications,
    render,
    markRead,
    markAllRead,
    togglePin,
    toggleArchive,
    deleteNotification,
    applyFilters,
    setFilter,
    setSort,
    setSearch,
    resetFilters,
    getFilter,
    getSort,
    getSearch,
    openHelp,
    init,
    addNotifications,
    getUnreadCount,
    startPolling,
    stopPolling,
    registerAction
};