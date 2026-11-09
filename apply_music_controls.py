from pathlib import Path
from datetime import datetime
import shutil
import subprocess
import sys

ROOT = Path(".")
JS = ROOT / "static/app.js"
HTML = ROOT / "static/index.html"
CSS = ROOT / "static/style.css"
SW = ROOT / "static/sw.js"

files = [JS, HTML, CSS, SW]
for f in files:
    if not f.is_file():
        sys.exit(f"ERROR: الملف غير موجود: {f}")

original = {f: f.read_text(encoding="utf-8") for f in files}
updated = dict(original)

def replace_once(file, old, new, label):
    content = updated[file]
    count = content.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: متوقع تطابق واحد، الموجود {count}")
    updated[file] = content.replace(old, new, 1)

# 1) Music state
replace_once(
    JS,
    "let currentAudioUrl = null;\n",
    """let currentAudioUrl = null;

// NOVRA MUSIC ENGINE
let musicBaseQueue = [];
let musicQueue = [];
let musicQueueIndex = -1;
let musicQueueSignature = "";
let musicShuffleEnabled = false;
let musicRepeatMode = "off"; // off | all | one
let currentAudioId = null;
let musicLoadToken = 0;
""",
    "إضافة حالة مشغل الموسيقى"
)

# 2) Music engine functions
music_engine = r'''
function shuffleMusicIds(ids) {
  const result = [...ids];
  for (let i = result.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [result[i], result[j]] = [result[j], result[i]];
  }
  return result;
}

function updateMusicControls() {
  const controls = $("#musicControls");
  if (!controls) return;

  const hasQueue = musicQueue.length > 0;
  const hasAudio = !!currentAudio && !!currentAudioId;

  $("#musicPrevBtn").disabled = !hasQueue;
  $("#musicNextBtn").disabled = !hasQueue;
  $("#musicPlayPauseBtn").disabled = !hasQueue && !hasAudio;
  $("#musicShuffleBtn").classList.toggle("active", musicShuffleEnabled);
  $("#musicShuffleBtn").setAttribute("aria-pressed", String(musicShuffleEnabled));

  const repeatButton = $("#musicRepeatBtn");
  repeatButton.classList.toggle("active", musicRepeatMode !== "off");
  repeatButton.setAttribute("aria-pressed", String(musicRepeatMode !== "off"));
  repeatButton.textContent = musicRepeatMode === "one"
    ? "🔂 تكرار الأغنية"
    : musicRepeatMode === "all"
      ? "🔁 تكرار القائمة"
      : "↻ التكرار مغلق";

  $("#musicPlayPauseBtn").textContent =
    hasAudio && !currentAudio.paused ? "Ⅱ إيقاف مؤقت" : "▶ تشغيل";

  const title = $("#musicNowPlaying");
  if (title) {
    title.textContent = hasAudio
      ? `يعمل الآن: ${currentAudioId}`
      : "اختر أغنية لبدء التشغيل";
  }
}

function syncMusicQueue(items) {
  const ids = items
    .filter(item => item.kind === "audio" && !isVaultItem(item))
    .map(item => item.id);

  const signature = JSON.stringify(ids);

  if (signature !== musicQueueSignature) {
    musicBaseQueue = ids;

    if (musicShuffleEnabled) {
      const rest = ids.filter(id => id !== currentAudioId);
      const shuffled = shuffleMusicIds(rest);
      musicQueue = ids.includes(currentAudioId)
        ? [currentAudioId, ...shuffled]
        : shuffled;
    } else {
      musicQueue = [...ids];
    }

    musicQueueSignature = signature;
  }

  musicQueueIndex = currentAudioId
    ? musicQueue.indexOf(currentAudioId)
    : -1;

  const controls = $("#musicControls");
  const playlistHasAudio = currentCategory === "playlists" && ids.length > 0;
  if (controls) {
    controls.hidden = !(currentCategory === "audio" || playlistHasAudio);
  }

  updateMusicControls();
}

async function playMusicTrack(id) {
  const token = ++musicLoadToken;

  try {
    const all = await dbRequest("readonly", "all");
    const track = all.find(item => item.id === id);

    if (!track || track.kind !== "audio" || isVaultItem(track)) {
      toast("ملف الصوت غير متاح.");
      return;
    }

    if (!musicBaseQueue.includes(id)) {
      musicBaseQueue.push(id);
      musicQueue = musicShuffleEnabled
        ? [id, ...shuffleMusicIds(musicBaseQueue.filter(x => x !== id))]
        : [...musicBaseQueue];
      musicQueueSignature = JSON.stringify(musicBaseQueue);
    }

    musicQueueIndex = musicQueue.indexOf(id);
    currentAudioId = id;

    const file = await getFile(track.filename);
    if (token !== musicLoadToken) return;

    if (currentAudio) currentAudio.pause();

    if (currentAudioUrl) {
      URL.revokeObjectURL(currentAudioUrl);
      currentAudioUrl = null;
    }

    currentAudioUrl = URL.createObjectURL(file);

    if (!currentAudio) currentAudio = new Audio();

    currentAudio.pause();
    currentAudio.src = currentAudioUrl;
    currentAudio.onended = handleMusicEnded;
    currentAudio.onerror = () => toast("تعذر تشغيل الملف الصوتي.");

    updateMusicControls();

    await currentAudio.play();
    updateMusicControls();
  } catch (error) {
    if (token === musicLoadToken) {
      console.error(error);
      toast("تعذر فتح الملف الصوتي من التخزين المحلي.");
    }
  }
}

async function advanceMusic(direction) {
  if (!musicQueue.length) {
    toast("مفيش أغاني في قائمة التشغيل الحالية.");
    return;
  }

  if (direction < 0 && currentAudio && currentAudio.currentTime > 3) {
    currentAudio.currentTime = 0;
    currentAudio.play().catch(() => {});
    return;
  }

  let nextIndex = musicQueueIndex + direction;

  if (nextIndex < 0 || nextIndex >= musicQueue.length) {
    if (musicRepeatMode !== "all") {
      if (direction > 0 && currentAudio) {
        currentAudio.pause();
        updateMusicControls();
      }
      return;
    }

    nextIndex = direction > 0 ? 0 : musicQueue.length - 1;
  }

  const nextId = musicQueue[nextIndex];
  if (nextId) await playMusicTrack(nextId);
}

function handleMusicEnded() {
  if (musicRepeatMode === "one" && currentAudio) {
    currentAudio.currentTime = 0;
    currentAudio.play().catch(() => toast("تعذر إعادة تشغيل الأغنية."));
    return;
  }

  advanceMusic(1);
}

function toggleMusicShuffle() {
  musicShuffleEnabled = !musicShuffleEnabled;

  const currentId = currentAudioId;
  if (musicShuffleEnabled) {
    const rest = musicBaseQueue.filter(id => id !== currentId);
    musicQueue = currentId && musicBaseQueue.includes(currentId)
      ? [currentId, ...shuffleMusicIds(rest)]
      : shuffleMusicIds(musicBaseQueue);
  } else {
    musicQueue = [...musicBaseQueue];
  }

  musicQueueIndex = currentId ? musicQueue.indexOf(currentId) : -1;
  updateMusicControls();
}

function cycleMusicRepeat() {
  musicRepeatMode = musicRepeatMode === "off"
    ? "all"
    : musicRepeatMode === "all"
      ? "one"
      : "off";

  updateMusicControls();
}

function toggleMusicPlayPause() {
  if (!currentAudio || !currentAudioId) {
    if (musicQueue.length) {
      const first = musicQueue[0];
      playMusicTrack(first);
    }
    return;
  }

  if (currentAudio.paused) {
    if (currentAudio.ended) currentAudio.currentTime = 0;
    currentAudio.play().catch(() => toast("تعذر استكمال التشغيل."));
  } else {
    currentAudio.pause();
  }

  updateMusicControls();
}

'''

replace_once(
    JS,
    "async function render() {\n",
    music_engine + "\nasync function render() {\n",
    "إضافة محرك الموسيقى"
)

# 3) Preserve playlist order
replace_once(
    JS,
    """      ? (selectedPlaylist
          ? ordinary.filter(v => (selectedPlaylist.mediaIds || []).includes(v.id))
          : [])
""",
    """      ? (selectedPlaylist
          ? (selectedPlaylist.mediaIds || [])
              .map(id => ordinary.find(v => v.id === id))
              .filter(Boolean)
          : [])
""",
    "ترتيب عناصر قائمة التشغيل"
)

# 4) Do not sort playlist items; playlist order is intentional
replace_once(
    JS,
    """  items.sort((a,b) => sort === "oldest" ? a.created-b.created :
    sort === "name" ? a.name.localeCompare(b.name) :
    sort === "largest" ? b.size-a.size : b.created-a.created);

  await renderPlaylistPanel(allPlaylists);
""",
    """  if (!(currentCategory === "playlists" && selectedPlaylist)) {
    items.sort((a,b) => sort === "oldest" ? a.created-b.created :
      sort === "name" ? a.name.localeCompare(b.name) :
      sort === "largest" ? b.size-a.size : b.created-a.created);
  }

  syncMusicQueue(items);
  await renderPlaylistPanel(allPlaylists);
""",
    "مزامنة قائمة انتظار الموسيقى"
)

# 5) Replace old one-file audio player with queue-aware playback
replace_once(
    JS,
    """    } else {
      const play = document.createElement("button");
      play.textContent = "♫ تشغيل الصوت";
      play.onclick = async () => {
        try {
          const file = await getFile(v.filename);
          if (currentAudioUrl) URL.revokeObjectURL(currentAudioUrl);
          currentAudioUrl = URL.createObjectURL(file);
          if (!currentAudio) currentAudio = new Audio();
          currentAudio.pause();
          currentAudio.src = currentAudioUrl;
          currentAudio.play().catch(()=>toast("تعذر تشغيل الملف الصوتي."));
          currentAudio.onended = () => {
            if (currentAudioUrl) URL.revokeObjectURL(currentAudioUrl);
            currentAudioUrl = null;
          };
        } catch {
          toast("تعذر فتح الملف الصوتي من التخزين المحلي.");
        }
      };
      actions.append(play);
    }
""",
    """    } else {
      const play = document.createElement("button");
      play.textContent = currentAudioId === v.id && currentAudio && !currentAudio.paused
        ? "♫ يعمل الآن"
        : "♫ تشغيل الصوت";
      play.onclick = () => playMusicTrack(v.id);
      actions.append(play);
    }
""",
    "ربط كروت الصوت بمحرك الموسيقى"
)

# 6) Add controls in the library UI
replace_once(
    HTML,
    '      <section id="playlistPanel" class="playlist-panel" hidden>',
    '''      <section id="musicControls" class="music-controls" hidden aria-label="التحكم في الموسيقى">
        <div class="music-now">
          <span class="music-orb">♫</span>
          <div>
            <strong>NOVRA MUSIC</strong>
            <small id="musicNowPlaying">اختر أغنية لبدء التشغيل</small>
          </div>
        </div>
        <div class="music-buttons">
          <button id="musicPrevBtn" type="button" aria-label="السابق" title="السابق">⏮</button>
          <button id="musicPlayPauseBtn" type="button">▶ تشغيل</button>
          <button id="musicNextBtn" type="button" aria-label="التالي" title="التالي">⏭</button>
          <button id="musicShuffleBtn" type="button" aria-pressed="false">⤨ عشوائي</button>
          <button id="musicRepeatBtn" type="button" aria-pressed="false">↻ التكرار مغلق</button>
        </div>
      </section>

      <section id="playlistPanel" class="playlist-panel" hidden>''',
    "إضافة لوحة تحكم الموسيقى"
)

# 7) Bind controls in init
replace_once(
    JS,
    '  $("#sortSelect").onchange = render;\n',
    '''  $("#sortSelect").onchange = render;

  $("#musicPrevBtn").onclick = () => advanceMusic(-1);
  $("#musicNextBtn").onclick = () => advanceMusic(1);
  $("#musicPlayPauseBtn").onclick = toggleMusicPlayPause;
  $("#musicShuffleBtn").onclick = toggleMusicShuffle;
  $("#musicRepeatBtn").onclick = cycleMusicRepeat;
''',
    "ربط أزرار الموسيقى"
)

# 8) Futuristic scoped styling
updated[CSS] += r'''

/* NOVRA MUSIC DECK - scoped controls */
.music-controls {
  margin: 4px 0 20px;
  padding: 15px;
  border: 1px solid #526cbd66;
  border-radius: 17px;
  background:
    radial-gradient(circle at 12% 50%, #3c6ee52b, transparent 35%),
    linear-gradient(115deg, #111d38, #171633 58%, #10182c);
  box-shadow: 0 10px 35px #101b3b55, inset 0 1px #ffffff0a;
}
.music-controls[hidden] { display: none; }
.music-now {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
  margin-bottom: 13px;
}
.music-orb {
  width: 43px;
  height: 43px;
  flex: 0 0 43px;
  display: grid;
  place-items: center;
  border: 1px solid #73cfff77;
  border-radius: 14px;
  color: #a6e8ff;
  background: radial-gradient(circle, #3978c866, #22204d);
  box-shadow: 0 0 22px #48b9ff25;
  font-size: 21px;
}
.music-now strong,
.music-now small { display: block; }
.music-now strong {
  color: #a9dfff;
  font-size: 10px;
  letter-spacing: 2px;
}
.music-now small {
  margin-top: 4px;
  color: #b4c3e5;
  font-size: 11px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 70vw;
}
.music-buttons {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 7px;
}
.music-buttons button {
  min-height: 37px;
  padding: 7px 11px;
  border: 1px solid #526aa766;
  border-radius: 10px;
  color: #dcecff;
  background: #1b2948;
  font-size: 11px;
}
.music-buttons button:disabled {
  opacity: .38;
  cursor: not-allowed;
}
.music-buttons button.active {
  color: #fff;
  border-color: #65d9ff;
  background: linear-gradient(135deg, #285e91, #403b83);
  box-shadow: 0 0 17px #42baff2b;
}
@media(max-width:600px) {
  .music-controls { padding: 12px; }
  .music-buttons { gap: 6px; }
  .music-buttons button { flex: 1 1 auto; padding: 8px 7px; }
}
'''

# 9) Bump service worker cache
replace_once(
    SW,
    'const CACHE = "novra-video-shell-v13";',
    'const CACHE = "novra-video-shell-v14";',
    "تحديث كاش التطبيق"
)

# Backup, write, and syntax-check
backup = ROOT / "backups" / ("music-controls-" + datetime.now().strftime("%Y%m%d-%H%M%S"))
backup.mkdir(parents=True, exist_ok=False)

for f in files:
    dest = backup / f.relative_to(ROOT)
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(f, dest)

try:
    for f in files:
        temp = f.with_suffix(f.suffix + ".music-tmp")
        temp.write_text(updated[f], encoding="utf-8")
        temp.replace(f)

    check = subprocess.run(
        ["node", "--check", str(JS)],
        text=True,
        capture_output=True
    )

    if check.returncode != 0:
        raise RuntimeError("فشل فحص JavaScript:\n" + check.stderr)

except Exception as error:
    for f in files:
        f.write_text(original[f], encoding="utf-8")
    print("تم إرجاع الملفات الأصلية بسبب فشل التعديل.")
    print(error)
    sys.exit(1)

print("تم تطبيق نظام الموسيقى بنجاح.")
print("Backup:", backup)
print("JavaScript syntax: PASS")
print("Service Worker cache: v14")
