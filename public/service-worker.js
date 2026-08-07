// frontend-user/service-worker.js
const CACHE_VERSION = 'v2.6';
const CACHE_NAME = `medexam-${CACHE_VERSION}`;

// ====== MANUAL LIST OF ALL FILES TO CACHE ======
// Everything below is cached – EXCEPT index.html, shared-exam.html,
// shared-note.html, and all /data/questions/*.json files.
const STATIC_ASSETS = [
  // Root and manifest
  '/manifest.json',

  // assets/images
  '/assets/images/logo 1.png',
  '/assets/images/logo-144x144.png',
  '/assets/images/logo.jpeg',
  '/assets/images/logo.png',
  '/assets/images/qr-code.jpeg',

  // css
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

  // pages (excluded: shared-exam.html, shared-note.html)
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

  // scripts (root level)
  '/scripts/ai.js',
  '/scripts/analytics.js',
  '/scripts/exam-engine.js',
  '/scripts/help.js',
  '/scripts/notes.js',
  '/scripts/performance-rating-v2.js',
  '/scripts/questions.js',
  '/scripts/router.js',
  '/scripts/validation.js',

  // scripts/pages
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
  '/',                   // root → index.html
  '/pages/shared-exam.html',
  '/pages/shared-note.html',
  // plus any /data/questions/* checked below
];

function isExcluded(url) {
  if (EXCLUDED_PATHS.includes(url.pathname)) return true;
  if (url.pathname.startsWith('/data/questions/')) return true;
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

  // ignore cross-origin (except Google Fonts / Quill)
  if (url.origin !== self.location.origin &&
      !url.href.includes('fonts.googleapis.com') &&
      !url.href.includes('cdn.quilljs.com')) {
    return;
  }

  // Excluded files → network only (never from cache, never cached)
  if (isExcluded(url)) {
    event.respondWith(fetch(event.request));
    return;
  }

  // All manually listed files → cache first
  event.respondWith(
    caches.match(event.request)
      .then(cached => {
        if (cached) return cached;
        // fallback: go network (shouldn't happen for listed files)
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