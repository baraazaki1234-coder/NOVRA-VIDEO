from pathlib import Path
from datetime import datetime
import shutil
import subprocess
import sys

APP = Path("static/app.js")
HTML = Path("static/index.html")
CSS = Path("static/style.css")
SW = Path("static/sw.js")
paths = [APP, HTML, CSS, SW]

original = {p: p.read_text(encoding="utf-8") for p in paths}
updated = dict(original)
checks = []

def replace(path, old, new, label):
    count = updated[path].count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    updated[path] = updated[path].replace(old, new, 1)
    checks.append(label)

# DATABASE VERSION AND STORE
replace(APP,
    'const req = indexedDB.open(DB_NAME, 2);',
    'const req = indexedDB.open(DB_NAME, 3);',
    'App database version')

replace(APP,
'''      if (!database.objectStoreNames.contains("shared-files")) {
        database.createObjectStore("shared-files", {keyPath:"id"});
      }''',
'''      if (!database.objectStoreNames.contains("shared-files")) {
        database.createObjectStore("shared-files", {keyPath:"id"});
      }

      if (!database.objectStoreNames.contains("playlists")) {
        database.createObjectStore("playlists", {keyPath:"id"});
      }''',
    'App playlists store')

replace(APP,
'''let db, currentVideo = null, currentObjectUrl = null, toastTimer, pendingPosterId = null;''',
'''let db, currentVideo = null, currentObjectUrl = null, toastTimer, pendingPosterId = null;
let activePlaylistId = null;''',
    'Playlist state')

# PLAYLIST DATABASE HELPER
replace(APP,
'''function id() { return crypto.randomUUID ? crypto.randomUUID() : Date.now().toString(36)+Math.random().toString(36).slice(2); }''',
'''function playlistRequest(action, value) {
  return new Promise((resolve, reject) => {
    const mode = action === "all" || action === "get" ? "readonly" : "readwrite";
    const tx = db.transaction("playlists", mode);
    const store = tx.objectStore("playlists");
    let req;

    if (action === "all") req = store.getAll();
    else if (action === "put") req = store.put(value);
    else if (action === "delete") req = store.delete(value);
    else req = store.get(value);

    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
    tx.onabort = () => reject(tx.error || new Error("Playlist transaction aborted."));
  });
}

function id() { return crypto.randomUUID ? crypto.randomUUID() : Date.now().toString(36)+Math.random().toString(36).slice(2); }''',
    'Playlist database helper')

# PLAYLIST UI FUNCTIONS
helpers = r'''
async function renderPlaylistPanel(playlists) {
  const panel = $("#playlistPanel");
  if (!panel) return;

  panel.hidden = currentCategory !== "playlists";
  if (panel.hidden) return;

  const grid = $("#playlistGrid");
  const backRow = $("#playlistBackRow");
  const createRow = $("#playlistCreateRow");
  const activeName = $("#activePlaylistName");

  grid.innerHTML = "";
  grid.hidden = !!activePlaylistId;
  backRow.hidden = !activePlaylistId;
  createRow.hidden = !!activePlaylistId;

  let selected = playlists.find(p => p.id === activePlaylistId);

  if (activePlaylistId && !selected) {
    activePlaylistId = null;
    grid.hidden = false;
    backRow.hidden = true;
    createRow.hidden = false;
  }

  if (selected) activeName.textContent = selected.name;

  if (!activePlaylistId && !playlists.length) {
    const empty = document.createElement("p");
    empty.className = "playlist-empty";
    empty.textContent = "لسه مفيش قوائم تشغيل. أنشئ أول قائمة من هنا.";
    grid.append(empty);
  }

  for (const playlist of playlists) {
    const card = document.createElement("article");
    card.className = "playlist-card";

    const open = document.createElement("button");
    open.type = "button";
    open.className = "playlist-open";

    const symbol = document.createElement("span");
    symbol.className = "playlist-symbol";
    symbol.textContent = "♫";

    const info = document.createElement("span");
    info.className = "playlist-info";
    info.textContent = playlist.name + " · " + (playlist.mediaIds || []).length + " ملف";

    const arrow = document.createElement("span");
    arrow.className = "playlist-arrow";
    arrow.textContent = "←";

    open.append(symbol, info, arrow);
    open.onclick = () => {
      activePlaylistId = playlist.id;
      render();
    };

    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "playlist-delete";
    remove.textContent = "حذف القائمة";
    remove.onclick = async () => {
      if (!confirm("حذف قائمة التشغيل؟ لن يتم حذف ملفات الفيديو أو الصوت.")) return;
      await playlistRequest("delete", playlist.id);
      if (activePlaylistId === playlist.id) activePlaylistId = null;
      toast("تم حذف قائمة التشغيل.");
      await render();
    };

    card.append(open, remove);
    grid.append(card);
  }
}

async function openPlaylistPicker(media) {
  const playlists = await playlistRequest("all");
  const wrap = $("#playlistPickerOptions");
  wrap.innerHTML = "";

  $("#playlistPickerMediaName").textContent = media.name || "ملف وسائط";

  if (!playlists.length) {
    const note = document.createElement("p");
    note.className = "muted";
    note.textContent = "أنشئ قائمة تشغيل أولًا من تبويب قوائم التشغيل.";
    wrap.append(note);
  }

  for (const playlist of playlists) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "playlist-pick-option";

    const included = (playlist.mediaIds || []).includes(media.id);
    button.textContent = (included ? "✓ إزالة من: " : "＋ إضافة إلى: ") + playlist.name;

    button.onclick = async () => {
      const current = await playlistRequest("get", playlist.id);
      if (!current) return;

      current.mediaIds = current.mediaIds || [];
      const index = current.mediaIds.indexOf(media.id);

      if (index >= 0) current.mediaIds.splice(index, 1);
      else current.mediaIds.push(media.id);

      await playlistRequest("put", current);
      toast(index >= 0 ? "تمت إزالة الملف من القائمة." : "تمت إضافة الملف إلى القائمة.");
      await render();
      await openPlaylistPicker(media);
    };

    wrap.append(button);
  }

  openModal("playlistPickerModal");
}

'''

replace(APP,
    'async function render() {',
    helpers + 'async function render() {',
    'Playlist UI functions')

# RENDER DATA
replace(APP,
'''  const all = await dbRequest("readonly","all");
  const ordinary = all.filter(v => !isVaultItem(v));''',
'''  const all = await dbRequest("readonly","all");
  const allPlaylists = await playlistRequest("all");
  const ordinary = all.filter(v => !isVaultItem(v));
  const selectedPlaylist = allPlaylists.find(p => p.id === activePlaylistId);''',
    'Load playlists in render')

replace(APP,
'''  $("#allCount").textContent = currentCategory === "audio" ? audioOnly.length :
    currentCategory === "vault" ? all.filter(isVaultItem).length : videosOnly.length;''',
'''  $("#allCount").textContent = currentCategory === "audio" ? audioOnly.length :
    currentCategory === "vault" ? all.filter(isVaultItem).length :
    currentCategory === "playlists" ? (selectedPlaylist ? (selectedPlaylist.mediaIds || []).length : allPlaylists.length) :
    videosOnly.length;''',
    'Playlist counter')

replace(APP,
'''  let items = currentCategory === "vault"
    ? (vaultOpen ? all.filter(isVaultItem) : [])
    : currentCategory === "audio" ? audioOnly : videosOnly;''',
'''  let items = currentCategory === "vault"
    ? (vaultOpen ? all.filter(isVaultItem) : [])
    : currentCategory === "audio" ? audioOnly
    : currentCategory === "playlists"
      ? (selectedPlaylist
          ? ordinary.filter(v => (selectedPlaylist.mediaIds || []).includes(v.id))
          : [])
      : videosOnly;''',
    'Select playlist media')

replace(APP,
'''  $("#videoGrid").innerHTML = "";
  $("#emptyState").hidden = items.length > 0;

  const heading = $("#libraryHeading");''',
'''  await renderPlaylistPanel(allPlaylists);

  const playlistIndexView = currentCategory === "playlists" && !activePlaylistId;
  $("#videoGrid").innerHTML = "";
  $("#videoGrid").hidden = playlistIndexView;
  $("#emptyState").hidden = playlistIndexView || items.length > 0;

  const heading = $("#libraryHeading");''',
    'Render playlist panel')

replace(APP,
'''  if (heading) heading.textContent = currentCategory === "vault" ? "الخزنة الخاصة" :
    currentCategory === "audio" ? "الموسيقى والصوتيات" : "مكتبة الفيديوهات";''',
'''  if (heading) heading.textContent = currentCategory === "vault" ? "الخزنة الخاصة" :
    currentCategory === "audio" ? "الموسيقى والصوتيات" :
    currentCategory === "playlists" ? (selectedPlaylist ? selectedPlaylist.name : "قوائم التشغيل") :
    "مكتبة الفيديوهات";''',
    'Playlist heading')

replace(APP,
'''    if (currentCategory === "vault" && !vaultOpen) {''',
'''    if (currentCategory === "playlists" && activePlaylistId) {
      h.textContent = "القائمة فارغة";
      p.textContent = "أضف فيديوهات أو صوتيات إليها من المكتبة.";
      btn.hidden = true;
    } else if (currentCategory === "vault" && !vaultOpen) {''',
    'Playlist empty state')

# CARD ACTIONS
replace(APP,
'''    actions.append(fav,del);
    body.append(title,meta,actions);''',
'''    if (currentCategory !== "vault" && !isVaultItem(v)) {
      const addToPlaylist = document.createElement("button");
      addToPlaylist.textContent = "＋ قائمة";
      addToPlaylist.title = "إضافة أو إزالة من قائمة تشغيل";
      addToPlaylist.onclick = () => openPlaylistPicker(v);
      actions.append(addToPlaylist);
    }

    actions.append(fav,del);
    body.append(title,meta,actions);''',
    'Add to playlist button')

replace(APP,
'''    del.onclick = () => deleteVideo(v.id);''',
'''    del.onclick = async () => {
      if (currentCategory === "playlists" && activePlaylistId) {
        const playlist = await playlistRequest("get", activePlaylistId);
        if (!playlist) return;

        playlist.mediaIds = (playlist.mediaIds || []).filter(mediaId => mediaId !== v.id);
        await playlistRequest("put", playlist);
        toast("تمت إزالة الملف من قائمة التشغيل فقط.");
        await render();
      } else {
        deleteVideo(v.id);
      }
    };''',
    'Remove from playlist without deleting media')

# EVENTS
replace(APP,
'''  document.querySelectorAll(".library-tab").forEach(tab => {
    tab.onclick = () => {
      currentCategory = tab.dataset.category;''',
'''  $("#createPlaylistBtn").onclick = async () => {
    const input = $("#playlistNameInput");
    const name = input.value.trim();

    if (!name) {
      toast("اكتب اسم قائمة التشغيل أولًا.");
      input.focus();
      return;
    }

    const playlists = await playlistRequest("all");
    if (playlists.some(p => p.name.toLowerCase() === name.toLowerCase())) {
      toast("يوجد بالفعل قائمة بنفس الاسم.");
      return;
    }

    await playlistRequest("put", {
      id: id(),
      name,
      mediaIds: [],
      created: Date.now()
    });

    input.value = "";
    toast("تم إنشاء قائمة التشغيل.");
    await render();
  };

  $("#playlistNameInput").addEventListener("keydown", e => {
    if (e.key === "Enter") $("#createPlaylistBtn").click();
  });

  $("#playlistBack").onclick = () => {
    activePlaylistId = null;
    render();
  };

  document.querySelectorAll(".library-tab").forEach(tab => {
    tab.onclick = () => {
      currentCategory = tab.dataset.category;
      activePlaylistId = null;''',
    'Playlist event handlers')

# HTML
replace(HTML,
'''<nav class="library-tabs" aria-label="نوع المكتبة"><button class="library-tab active" type="button" data-category="video">الفيديوهات</button><button class="library-tab" type="button" data-category="audio">الموسيقى والصوتيات</button></nav>''',
'''<nav class="library-tabs" aria-label="نوع المكتبة"><button class="library-tab active" type="button" data-category="video">الفيديوهات</button><button class="library-tab" type="button" data-category="audio">الموسيقى والصوتيات</button><button class="library-tab" type="button" data-category="playlists">قوائم التشغيل</button></nav>''',
    'Playlist tab HTML')

replace(HTML,
'''      <section id="videoGrid" class="video-grid"></section>''',
'''      <section id="playlistPanel" class="playlist-panel" hidden>
        <div id="playlistCreateRow" class="playlist-create-row">
          <input id="playlistNameInput" type="text" maxlength="50" placeholder="اسم قائمة التشغيل">
          <button id="createPlaylistBtn" class="primary-btn" type="button">＋ إنشاء قائمة</button>
        </div>
        <div id="playlistBackRow" class="playlist-back-row" hidden>
          <button id="playlistBack" class="secondary-btn" type="button">→ كل القوائم</button>
          <strong id="activePlaylistName"></strong>
        </div>
        <div id="playlistGrid" class="playlist-grid"></div>
      </section>

      <section id="videoGrid" class="video-grid"></section>''',
    'Playlist panel HTML')

replace(HTML,
'''<div class="toast" id="toast" role="status"></div>''',
'''<div class="modal-backdrop" id="playlistPickerModal" hidden>
    <section class="modal">
      <button class="close-btn" data-close="playlistPickerModal" aria-label="إغلاق">×</button>
      <p class="eyebrow">YOUR PLAYLISTS</p>
      <h2>إدارة القوائم</h2>
      <p class="muted" id="playlistPickerMediaName"></p>
      <div id="playlistPickerOptions" class="playlist-picker-options"></div>
    </section>
  </div>
  <div class="toast" id="toast" role="status"></div>''',
    'Playlist picker modal')

replace(HTML,
    'href="/style.css?v=8"',
    'href="/style.css?v=9"',
    'Stylesheet cache version')

replace(HTML,
    'src="/app.js?v=9"',
    'src="/app.js?v=10"',
    'App script cache version')

# CSS
updated[CSS] += r'''

/* NOVRA VIDEO — PLAYLISTS */
#videoGrid[hidden],
#playlistPanel[hidden],
#playlistGrid[hidden],
#playlistBackRow[hidden],
#playlistCreateRow[hidden] {
  display: none !important;
}
.playlist-panel {
  margin: 8px 0 20px;
  padding: 16px;
  border: 1px solid rgba(110, 157, 230, .22);
  border-radius: 18px;
  background: linear-gradient(145deg, rgba(20, 36, 66, .78), rgba(12, 20, 39, .82));
}
.playlist-create-row {
  display: flex;
  gap: 9px;
  margin-bottom: 16px;
}
.playlist-create-row input {
  flex: 1;
  min-width: 0;
  color: #edf5ff;
  background: rgba(4, 12, 27, .72);
  border: 1px solid rgba(126, 164, 220, .28);
  border-radius: 11px;
  padding: 11px 13px;
  outline: none;
}
.playlist-create-row input:focus {
  border-color: #65dfff;
  box-shadow: 0 0 0 3px rgba(82, 204, 255, .1);
}
.playlist-create-row .primary-btn { white-space: nowrap; }
.playlist-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 11px;
}
.playlist-card {
  display: flex;
  align-items: stretch;
  gap: 8px;
  min-width: 0;
  padding: 9px;
  border: 1px solid rgba(128, 164, 220, .2);
  border-radius: 13px;
  background: rgba(8, 17, 35, .66);
}
.playlist-open {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  text-align: right;
  color: #eaf4ff;
  background: transparent;
  border: 0;
}
.playlist-symbol {
  width: 39px;
  height: 39px;
  flex: 0 0 39px;
  display: grid;
  place-items: center;
  border-radius: 12px;
  color: #9af1ff;
  background: linear-gradient(145deg, #174c78, #4d438d);
  font-size: 19px;
}
.playlist-info {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  font-weight: 700;
}
.playlist-arrow { margin-right: auto; color: #91dfff; }
.playlist-delete {
  align-self: center;
  padding: 7px 9px;
  border: 1px solid rgba(255, 125, 151, .2);
  border-radius: 8px;
  color: #ff9eae;
  background: rgba(90, 24, 48, .22);
  font-size: 10px;
  white-space: nowrap;
}
.playlist-back-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 2px 0 14px;
}
.playlist-back-row strong { color: #eaf4ff; overflow-wrap: anywhere; }
.playlist-picker-options { display: grid; gap: 9px; margin-top: 16px; }
.playlist-pick-option {
  width: 100%;
  padding: 12px 14px;
  border: 1px solid rgba(117, 166, 226, .25);
  border-radius: 11px;
  color: #eaf4ff;
  background: rgba(22, 39, 68, .72);
  text-align: right;
  font-size: 12px;
}
.playlist-empty {
  grid-column: 1 / -1;
  color: #a9bbd5;
  font-size: 13px;
  text-align: center;
  padding: 22px 8px;
}
@media (max-width: 480px) {
  .playlist-create-row { flex-direction: column; }
  .playlist-create-row .primary-btn { width: 100%; }
  .playlist-grid { grid-template-columns: 1fr; }
}
'''

# SERVICE WORKER
replace(SW,
    'const CACHE = "novra-video-shell-v11";',
    'const CACHE = "novra-video-shell-v12";',
    'Service worker cache version')

replace(SW,
    'const request = indexedDB.open("novra-video-db", 2);',
    'const request = indexedDB.open("novra-video-db", 3);',
    'Service worker database version')

replace(SW,
'''      if (!database.objectStoreNames.contains("shared-files")) {
        database.createObjectStore("shared-files", {keyPath: "id"});
      }''',
'''      if (!database.objectStoreNames.contains("shared-files")) {
        database.createObjectStore("shared-files", {keyPath: "id"});
      }

      if (!database.objectStoreNames.contains("playlists")) {
        database.createObjectStore("playlists", {keyPath: "id"});
      }''',
    'Service worker playlists store')

# PREFLIGHT CHECKS
for p, content in updated.items():
    if not content:
        raise RuntimeError(f"Empty output: {p}")

for required in (
    'id="playlistPanel"',
    'id="playlistPickerModal"',
    'data-category="playlists"',
):
    if required not in updated[HTML]:
        raise RuntimeError("HTML verification failed: " + required)

for required in (
    'indexedDB.open(DB_NAME, 3)',
    'db.transaction("playlists", mode)',
    'openPlaylistPicker(v)',
):
    if required not in updated[APP]:
        raise RuntimeError("App verification failed: " + required)

if 'indexedDB.open("novra-video-db", 3)' not in updated[SW]:
    raise RuntimeError("Service worker database version verification failed")

print("PREFLIGHT PASS:", len(checks), "exact replacements")
print("Files ready:", len(updated))

# BACKUP THEN WRITE
backup = Path("backups") / ("playlists-" + datetime.now().strftime("%Y%m%d-%H%M%S"))
backup.mkdir(parents=True, exist_ok=False)

for p in paths:
    dest = backup / p
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(p, dest)

try:
    for p, content in updated.items():
        temp = p.with_suffix(p.suffix + ".playlist-tmp")
        temp.write_text(content, encoding="utf-8")
        temp.replace(p)

    node = shutil.which("node")
    if node:
        for p in (APP, SW):
            result = subprocess.run(
                [node, "--check", str(p)],
                capture_output=True, text=True
            )
            if result.returncode:
                raise RuntimeError(result.stderr or f"Syntax error in {p}")

except Exception as error:
    for p in paths:
        shutil.copy2(backup / p, p)
    print("INSTALL FAILED — original files restored.")
    print("Reason:", error)
    print("Backup:", backup)
    sys.exit(1)

print("SUCCESS: playlists installed.")
print("Backup:", backup)
print("Syntax check:", "PASS" if shutil.which("node") else "Node unavailable; skipped")
print("Updated:", ", ".join(str(p) for p in paths))
