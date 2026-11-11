const CACHE = "natskyll-v15";
// Egen cache för tryck på aviseringens knappar. Den ska överleva versionsbyten.
const STATE = "natskyll-state";
const ASSETS = [
  ".",
  "index.html",
  "manifest.webmanifest",
  "icon-192.png",
  "icon-512.png",
  "img/skagerak.jpg", "img/skagerak-t.jpg",
  "img/kull.jpg", "img/kull-t.jpg",
  "img/pizzan.jpg", "img/pizzan-t.jpg",
  "img/brdisk.jpg", "img/brdisk-t.jpg",
  "img/skagdisk.jpg", "img/skagdisk-t.jpg"
];

self.addEventListener("install", e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(ASSETS)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", e => {
  e.waitUntil(
    caches.keys().then(keys => Promise.all(keys.filter(k => k !== CACHE && k !== STATE).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", e => {
  if (e.request.method !== "GET") return;
  // Bara appens egna filer cachas. Anrop till GitHub (backupen) går alltid direkt mot nätet.
  if (new URL(e.request.url).origin !== self.location.origin) return;
  e.respondWith(
    caches.match(e.request).then(cached => cached || fetch(e.request).then(res => {
      const copy = res.clone();
      caches.open(CACHE).then(c => c.put(e.request, copy)).catch(()=>{});
      return res;
    }).catch(() => caches.match("index.html")))
  );
});

async function pushAction(a) {
  const c = await caches.open(STATE), url = new URL("__actions", self.registration.scope).href;
  let list = [];
  const r = await c.match(url);
  if (r) { try { list = await r.json(); } catch (e) {} }
  list.push(a);
  await c.put(url, new Response(JSON.stringify(list), { headers: { "Content-Type": "application/json" } }));
}

function showRun(d, paused, t) {
  return self.registration.showNotification((paused ? "Pausad: " : "Pågår: ") + d.t, {
    body: paused ? "Tryck Fortsätt eller Klar" : "Tryck Klar när du är färdig",
    tag: "run", silent: true, requireInteraction: true, icon: "icon-192.png", timestamp: d.start || t, data: d,
    actions: paused ? [{ action: "fortsatt", title: "Fortsätt" }, { action: "klar", title: "Klar" }]
                    : [{ action: "klar", title: "Klar" }, { action: "paus", title: "Paus" }]
  });
}

// Knapparna Klar, Paus och Fortsätt på tidtagningens avisering sparas med klockslag, utan att appen
// behöver öppnas. Tryck på själva aviseringen (eller på larmens aviseringar) öppnar appen.
self.addEventListener("notificationclick", e => {
  const n = e.notification, d = n.data || {}, a = e.action;
  n.close();
  if (n.tag === "run" && (a === "klar" || a === "paus" || a === "fortsatt")) {
    const t = Date.now();
    e.waitUntil((async () => {
      await pushAction({ a, k: d.k, t });
      const list = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
      list.forEach(c => c.postMessage({ type: "run-action" }));
      if (a === "paus") await showRun(d, true, t);
      else if (a === "fortsatt") await showRun(d, false, t);
    })());
    return;
  }
  e.waitUntil(self.clients.matchAll({ type: "window", includeUncontrolled: true }).then(list => {
    for (const c of list) if ("focus" in c) return c.focus();
    return self.clients.openWindow(".");
  }));
});
