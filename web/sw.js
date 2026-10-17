// Vibe Visualizer service worker: makes the app installable and quick to open.
// It only keeps the app's own files. It never stores lyrics or anything from /api/
// (Spicy Lyrics terms: stored responses must be refetched or dropped within 30 days; we simply don't store them here).
const CACHE = 'vv-shell-v1';
const SHELL = ['./', 'index.html', 'logo.svg', 'manifest.webmanifest', 'icon-192.png', 'icon-512.png'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)));
  self.skipWaiting();
});

self.addEventListener('activate', e => {
  e.waitUntil(caches.keys()
    .then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
    .then(() => self.clients.claim()));
});

self.addEventListener('fetch', e => {
  const url = new URL(e.request.url);
  // leave everything else to the network: API calls, other sites, non-GET requests
  if (e.request.method !== 'GET' || url.origin !== location.origin || /^\/(api|canvas-api)\//.test(url.pathname)) return;
  // network first, so updates show up right away; the stored copy is only a fallback
  e.respondWith(fetch(e.request).then(r => {
    if (r.ok) { const copy = r.clone(); caches.open(CACHE).then(c => c.put(e.request, copy)); }
    return r;
  }).catch(() => caches.match(e.request).then(r => r || caches.match('index.html'))));
});
