// frontend-user/public/service-worker.js
const CACHE_VERSION = 'v3.0';
const CACHE_NAME = `medexam-${CACHE_VERSION}`;

// ====== DECRYPTION KEY (MUST match encrypt-json.js) ======
const SECRET_KEY = 'MedHubSecretKey2026!!32bytesXXKE'; // 32 bytes

// ====== HELPER: hex to Uint8Array ======
function hexToBytes(hex) {
    const bytes = new Uint8Array(hex.length / 2);
    for (let i = 0; i < bytes.length; i++) {
        bytes[i] = parseInt(hex.substr(i * 2, 2), 16);
    }
    return bytes;
}

// ====== HELPER: string to Uint8Array ======
function strToBytes(str) {
    return new TextEncoder().encode(str);
}

// ====== DECRYPT FUNCTION (using Web Crypto API) ======
async function decryptData(encryptedText) {
    try {
        const parts = encryptedText.split(':');
        if (parts.length !== 2) {
            // Not encrypted – return as‑is
            return encryptedText;
        }
        const iv = hexToBytes(parts[0]);
        const ciphertext = hexToBytes(parts[1]);

        const cryptoKey = await crypto.subtle.importKey(
            'raw',
            strToBytes(SECRET_KEY),
            { name: 'AES-CBC', length: 256 },
            false,
            ['decrypt']
        );

        const decrypted = await crypto.subtle.decrypt(
            { name: 'AES-CBC', iv: iv },
            cryptoKey,
            ciphertext
        );

        return new TextDecoder().decode(decrypted);
    } catch (error) {
        console.warn('[SW] Decryption failed, returning raw:', error);
        return encryptedText; // fallback to raw (e.g. manifest or plain JSON)
    }
}

// ====== MANUAL LIST OF FILES TO CACHE (STATIC) ======
const STATIC_ASSETS = [
    // Root & manifest
    '/manifest.json',

    // assets/images
    '/assets/images/logo 1.png',
    '/assets/images/logo-144x144.png',
    '/assets/images/logo.jpeg',
    '/assets/images/logo.png',
    '/assets/images/qr-code.jpeg',

    // CSS
    '/css/ai.css',
    '/css/common.css',
    '/css/content.css',
    '/css/exam-room.css',
    '/css/exam-settings.css',
    '/css/fa-brands-400.woff2',
    '/css/fa-regular-400.woff2',
    '/css/fa-solid-900.woff2',
    '/css/fa-v4compatibility.woff2',
    '/css/forgot-password.css',
    '/css/free-trial.css',
    '/css/index.css',
    '/css/locked.css',
    '/css/login.css',
    '/css/notes.css',
    '/css/notifications.css',
    '/css/payment.css',
    '/css/performance.css',
    '/css/privacy.css',
    '/css/profile.css',
    '/css/results.css',
    '/css/shared-note.css',
    '/css/signup.css',
    '/css/subject-specific.css',
    '/css/subjects.css',
    '/css/subscription.css',
    '/css/terms.css',
    '/css/viewer.css',
    '/css/welcome.css',

    // Pages (exclude shared-exam, shared-note)
    '/pages/index.html',
    '/pages/ai.html',
    '/pages/exam-room.html',
    '/pages/exam-settings.html',
    '/pages/forgot-password.html',
    '/pages/free-trial.html',
    '/pages/locked.html',
    '/pages/login.html',
    '/pages/notes.html',
    '/pages/notifications.html',
    '/pages/offline.html',
    '/pages/payment.html',
    '/pages/performance.html',
    '/pages/privacy.html',
    '/pages/profile.html',
    '/pages/resource-browser.html',
    '/pages/results.html',
    '/pages/signup.html',
    '/pages/subject-specific.html',
    '/pages/subjects.html',
    '/pages/subscription.html',
    '/pages/terms.html',
    '/pages/welcome.html',

    // Root-level scripts
    '/scripts/ai.js',
    '/scripts/analytics.js',
    '/scripts/exam-engine.js',
    '/scripts/help.js',
    '/scripts/notes.js',
    '/scripts/performance-rating-v2.js',
    '/scripts/questions.js',
    '/scripts/router.js',
    '/scripts/validation.js',

    // Page-specific scripts
    '/scripts/pages/ai.js',
    '/scripts/pages/exam-room.js',
    '/scripts/pages/exam-settings.js',
    '/scripts/pages/forgot-password.js',
    '/scripts/pages/free-trial.js',
    '/scripts/pages/locked.js',
    '/scripts/pages/login.js',
    '/scripts/pages/notes.js',
    '/scripts/pages/payment.js',
    '/scripts/pages/performance.js',
    '/scripts/pages/profile.js',
    '/scripts/pages/resource-browser.js',
    '/scripts/pages/results.js',
    '/scripts/pages/shared-exam.js',
    '/scripts/pages/shared-note.js',
    '/scripts/pages/signup.js',
    '/scripts/pages/subject-specific.js',
    '/scripts/pages/subjects.js',
    '/scripts/pages/subscription.js',
    '/scripts/pages/welcome.js',
];

// ====== FILES THAT MUST NEVER BE CACHED ======
const EXCLUDED_PATHS = [
    '/',                      // root → index.html (network‑first)
    '/pages/shared-exam.html',
    '/pages/shared-note.html',
    // ⚠️ /data/questions/ is NO LONGER excluded – we cache them now!
];

function isExcluded(url) {
    if (EXCLUDED_PATHS.includes(url.pathname)) return true;
    // Skip manifest.json and assetlinks.json from decryption/caching?
    // We'll handle them in the fetch logic below.
    return false;
}

// ====== INSTALL ======
self.addEventListener('install', event => {
    console.log(`[SW] Install ${CACHE_VERSION}`);
    event.waitUntil(
        caches.open(CACHE_NAME).then(cache => {
            return Promise.allSettled(
                STATIC_ASSETS.map(url =>
                    fetch(url)
                        .then(response => {
                            if (!response.ok) throw new Error(`HTTP ${response.status}`);
                            return cache.put(url, response);
                        })
                        .catch(err => console.warn(`[SW] Failed to cache ${url}:`, err.message))
                )
            );
        }).then(() => self.skipWaiting())
    );
});

// ====== ACTIVATE ======
self.addEventListener('activate', event => {
    console.log(`[SW] Activate ${CACHE_VERSION}`);
    event.waitUntil(
        caches.keys().then(keys =>
            Promise.all(keys.filter(k => k !== CACHE_NAME).map(k => caches.delete(k)))
        ).then(() => self.clients.claim())
    );
});

// ====== FETCH ======
self.addEventListener('fetch', event => {
    const url = new URL(event.request.url);

    // Ignore cross-origin (except Google Fonts / Quill)
    if (url.origin !== self.location.origin &&
        !url.href.includes('fonts.googleapis.com') &&
        !url.href.includes('cdn.quilljs.com')) {
        return;
    }

    // ============================================================
    // 1. SPECIAL HANDLER FOR JSON FILES (encrypted question data)
    // ============================================================
    if (url.pathname.endsWith('.json') &&
        url.origin === self.location.origin &&
        !url.pathname.includes('manifest.json') &&
        !url.pathname.includes('assetlinks.json')) {

        event.respondWith(handleJsonRequest(event.request));
        return;
    }

    // ============================================================
    // 2. EXCLUDED FILES → network only
    // ============================================================
    if (isExcluded(url)) {
        event.respondWith(fetch(event.request));
        return;
    }

    // ============================================================
    // 3. ALL OTHER STATIC ASSETS → cache‑first
    // ============================================================
    event.respondWith(
        caches.match(event.request)
            .then(cached => {
                if (cached) return cached;
                // fallback: go network
                return fetch(event.request);
            })
            .catch(() => {
                if (event.request.mode === 'navigation') {
                    return caches.match('/pages/offline.html');
                }
                return new Response('Offline content not available.', { status: 404 });
            })
    );
});

// ====== JSON REQUEST HANDLER (cache‑first + decryption) ======
async function handleJsonRequest(request) {
    const cache = await caches.open(CACHE_NAME);

    try {
        // 1) Try cache first
        const cachedResponse = await cache.match(request);
        if (cachedResponse) {
            const encryptedText = await cachedResponse.text();
            const decryptedText = await decryptData(encryptedText);
            return new Response(decryptedText, {
                headers: { 'Content-Type': 'application/json' }
            });
        }

        // 2) Fallback to network
        const networkResponse = await fetch(request);
        if (networkResponse.ok) {
            // Clone the response so we can read the body and cache it
            const clonedResponse = networkResponse.clone();
            const encryptedText = await clonedResponse.text();

            // Cache the encrypted version (as-is)
            const bodyToCache = encryptedText;
            await cache.put(request, new Response(bodyToCache, {
                headers: { 'Content-Type': 'application/octet-stream' }
            }));

            // Decrypt and return
            const decryptedText = await decryptData(encryptedText);
            return new Response(decryptedText, {
                headers: { 'Content-Type': 'application/json' }
            });
        }

        // 3) If all fails
        return new Response(JSON.stringify({ error: 'Offline: unable to load data' }), {
            status: 503,
            headers: { 'Content-Type': 'application/json' }
        });
    } catch (error) {
        console.error('[SW] JSON handler error:', error);
        return new Response(JSON.stringify({ error: 'Service error' }), {
            status: 500,
            headers: { 'Content-Type': 'application/json' }
        });
    }
}