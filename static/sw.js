const CACHE = "novra-video-shell-v24";
const CACHE_PREFIX = "novra-video-shell-";

const ASSETS = [
  "/",
  "/index.html",
  "/style.css",
  "/app.js",
  "/manifest.json",
  "/sw.js",
  "/icon.svg"
];

const SHELL_PATHS = new Set(ASSETS);

self.addEventListener("install", event => {
  event.waitUntil(
    caches.open(CACHE)
      .then(cache => cache.addAll(
        ASSETS.map(path => new Request(path, {cache: "reload"}))
      ))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener("activate", event => {
  event.waitUntil(
    caches.keys()
      .then(keys => Promise.all(
        keys
          .filter(key => key.startsWith(CACHE_PREFIX) && key !== CACHE)
          .map(key => caches.delete(key))
      ))
      .then(() => self.clients.claim())
  );
});

async function openShareDatabase() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open("novra-video-db", 3);

    request.onupgradeneeded = () => {
      const database = request.result;

      if (!database.objectStoreNames.contains("videos")) {
        database.createObjectStore("videos", {keyPath: "id"});
      }

      if (!database.objectStoreNames.contains("shared-files")) {
        database.createObjectStore("shared-files", {keyPath: "id"});
      }

      if (!database.objectStoreNames.contains("playlists")) {
        database.createObjectStore("playlists", {keyPath: "id"});
      }
    };

    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
    request.onblocked = () => reject(
      new Error("Database upgrade is blocked by another application tab.")
    );
  });
}

async function saveSharedFiles(files) {
  const database = await openShareDatabase();

  try {
    await new Promise((resolve, reject) => {
      const tx = database.transaction("shared-files", "readwrite");
      const store = tx.objectStore("shared-files");

      files.forEach((file, index) => {
        const fileId = crypto.randomUUID
          ? crypto.randomUUID()
          : Date.now().toString(36) + "-" + index + "-" + Math.random().toString(36).slice(2);

        store.put({
          id: fileId,
          blob: file,
          name: file.name || ("shared-file-" + index),
          type: file.type || "",
          size: file.size || 0,
          lastModified: file.lastModified || Date.now(),
          created: Date.now()
        });
      });

      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
      tx.onabort = () => reject(tx.error || new Error("Storage transaction aborted."));
    });
  } finally {
    database.close();
  }
}

async function handleShare(request) {
  try {
    const formData = await request.formData();

    const files = formData
      .getAll("media")
      .filter(value => value instanceof File);

    const sharedText = formData.get("text");
    if (
      typeof sharedText === "string" &&
      /\[?\d{1,2}:\d{2}(?:[.:]\d{1,3})?\]/.test(sharedText)
    ) {
      files.push(new File(
        [sharedText],
        "shared-lyrics.lrc",
        {type: "text/plain", lastModified: Date.now()}
      ));
    }

    if (!files.length) {
      return Response.redirect(
        new URL("/?shared=1", self.location.origin),
        303
      );
    }

    await saveSharedFiles(files);

    return Response.redirect(
      new URL("/?shared=1", self.location.origin),
      303
    );

  } catch (error) {
    console.error("NOVRA VIDEO share error:", error);

    return new Response(
      "تعذر استقبال الملفات. افتح NOVRA VIDEO وحاول المشاركة مرة أخرى.",
      {
        status: 500,
        headers: {"Content-Type": "text/plain; charset=utf-8"}
      }
    );
  }
}



// Shell assets: network first, cached fallback for offline use.
// Dynamic APIs and media are deliberately never stored in Cache Storage.
self.addEventListener("fetch", event => {
  const request = event.request;
  if (request.method !== "GET") return;

  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;

  // Preserve browser-managed media range requests.
  if (request.headers.has("range")) return;

  const path = url.pathname;

  // API responses and media must always go to the server.
  if (path === "/api" || path.startsWith("/api/")) return;

  // Cache only the explicit application shell allowlist.
  if (!SHELL_PATHS.has(path)) return;

  event.respondWith((async () => {
    const cache = await caches.open(CACHE);

    try {
      const response = await fetch(request, {cache: "no-store"});

      if (response && response.ok) {
        await cache.put(path, response.clone());
      }

      return response;
    } catch (error) {
      const cached = await cache.match(path);

      if (cached) return cached;

      if (path === "/") {
        const fallback = await cache.match("/index.html");
        if (fallback) return fallback;
      }

      return new Response("NOVRA VIDEO is offline and this file is not cached.", {
        status: 503,
        headers: {"Content-Type": "text/plain; charset=utf-8"}
      });
    }
  })());
});

// Android share target receiver.
self.addEventListener("fetch", event => {
  const request = event.request;
  if (request.method !== "POST") return;

  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;
  if (url.pathname !== "/share-target/") return;

  event.respondWith(handleShare(request));
});
