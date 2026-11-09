from pathlib import Path
from datetime import datetime
import shutil
import subprocess
import sys

app = Path("static/app.js")
sw = Path("static/sw.js")

if not app.exists() or not sw.exists():
    sys.exit("ERROR: ملفات المشروع المطلوبة غير موجودة.")

source = app.read_text(encoding="utf-8")
worker = sw.read_text(encoding="utf-8")

changes = []

def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    changes.append(label)
    return text.replace(old, new, 1)

# 1. Music playback state
source = replace_once(
    source,
    'let currentAudioId = null;\nlet musicLoadToken = 0;',
    'let currentAudioId = null;\n'
    'let currentMusicVideo = false;\n'
    'let currentMusicTitle = "";\n'
    'let musicLoadToken = 0;',
    "music state"
)

# 2. Manual import destination overrides detected media type
source = replace_once(
    source,
    '''async function importLocal(file) {
  const kind = mediaKind(file.type, file.name);
  if (!kind) throw new Error("نوع الملف غير مدعوم. اختر فيديو أو ملفًا صوتيًا معروفًا.");

  const useVault = $("#chooseVault")?.checked === true;''',
    '''async function importLocal(file) {
  const actualKind = mediaKind(file.type, file.name);
  if (!actualKind) throw new Error("نوع الملف غير مدعوم. اختر فيديو أو ملفًا صوتيًا معروفًا.");

  const useVault = $("#chooseVault")?.checked === true;''',
    "local import detection"
)

source = replace_once(
    source,
    '''  const destination = useVault ? "vault" : ($("#importDestination")?.value || "auto");
  if (!useVault && destination !== "auto" && destination !== kind) {
    throw new Error(kind === "audio"
      ? "الملف صوتي؛ اختر وجهة الموسيقى والصوتيات."
      : "الملف فيديو؛ اختر وجهة الفيديوهات.");
  }

  const mediaId = id();''',
    '''  const destination = useVault ? "vault" : ($("#importDestination")?.value || "auto");
  const kind = useVault || destination === "auto"
    ? actualKind
    : destination;

  const mediaId = id();''',
    "local import destination"
)

source = replace_once(
    source,
    'const duration = kind === "audio" ? await getAudioDuration(file) : 0;',
    'const duration = actualKind === "audio" ? await getAudioDuration(file) : 0;',
    "local import duration"
)

source = replace_once(
    source,
    'if (kind === "video") generateAndSaveThumb(mediaId,file);',
    'if (actualKind === "video") generateAndSaveThumb(mediaId,file);',
    "local import thumbnail"
)

# 3. URL imports also respect the selected destination
source = replace_once(
    source,
    '''    const destination = useVault
      ? "vault"
      : ($("#importDestination")?.value || "auto");

    if (!useVault && destination !== "auto" && destination !== finalKind) {
      throw new Error(finalKind === "audio"
        ? "الملف صوتي؛ اختر وجهة الموسيقى والصوتيات."
        : "الملف فيديو؛ اختر وجهة الفيديوهات.");
    }

    const mediaId = id();''',
    '''    const actualKind = finalKind;
    const destination = useVault
      ? "vault"
      : ($("#importDestination")?.value || "auto");

    if (!useVault && destination !== "auto") {
      finalKind = destination;
    }

    const mediaId = id();''',
    "URL import destination"
)

source = replace_once(
    source,
    '''    const duration = finalKind === "audio"
      ? await getAudioDuration(file)
      : 0;''',
    '''    const duration = actualKind === "audio"
      ? await getAudioDuration(file)
      : 0;''',
    "URL import duration"
)

source = replace_once(
    source,
    'if (finalKind === "video") generateAndSaveThumb(mediaId, file);',
    'if (actualKind === "video") generateAndSaveThumb(mediaId, file);',
    "URL import thumbnail"
)

# 4. Replace music control status display
source = replace_once(
    source,
    '''  const hasQueue = musicQueue.length > 0;
  const hasAudio = !!currentAudio && !!currentAudioId;

  $("#musicPrevBtn").disabled = !hasQueue;
  $("#musicNextBtn").disabled = !hasQueue;
  $("#musicPlayPauseBtn").disabled = !hasQueue && !hasAudio;''',
    '''  const hasQueue = musicQueue.length > 0;
  const hasPlayback = !!currentAudioId &&
    (!!currentAudio || currentMusicVideo);
  const isPlaying = currentMusicVideo
    ? !$("#videoPlayer").paused
    : !!currentAudio && !currentAudio.paused;

  $("#musicPrevBtn").disabled = !hasQueue;
  $("#musicNextBtn").disabled = !hasQueue;
  $("#musicPlayPauseBtn").disabled = !hasQueue && !hasPlayback;''',
    "music control state"
)

source = replace_once(
    source,
    '''  $("#musicPlayPauseBtn").textContent =
    hasAudio && !currentAudio.paused ? "Ⅱ إيقاف مؤقت" : "▶ تشغيل";

  const title = $("#musicNowPlaying");
  if (title) {
    title.textContent = hasAudio
      ? `يعمل الآن: ${currentAudioId}`
      : "اختر أغنية لبدء التشغيل";
  }''',
    '''  $("#musicPlayPauseBtn").textContent =
    isPlaying ? "Ⅱ إيقاف مؤقت" : "▶ تشغيل";

  const title = $("#musicNowPlaying");
  if (title) {
    title.textContent = hasPlayback
      ? `يعمل الآن: ${currentMusicTitle || currentAudioId}`
      : "اختر أغنية لبدء التشغيل";
  }''',
    "music now playing display"
)

# 5. Replace the music playback function
start = source.find("async function playMusicTrack(id) {")
end = source.find("\nasync function advanceMusic(direction) {", start)

if start < 0 or end < 0:
    raise RuntimeError("Could not locate playMusicTrack function.")

new_function = '''async function playMusicTrack(id) {
  const token = ++musicLoadToken;

  try {
    const all = await dbRequest("readonly", "all");
    const track = all.find(item => item.id === id);

    if (!track || track.kind !== "audio" || isVaultItem(track)) {
      toast("الملف غير متاح في قائمة الموسيقى.");
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
    currentMusicTitle = track.name || "بدون اسم";

    const actualKind = mediaKind(track.type, track.filename);

    if (actualKind === "video") {
      currentMusicVideo = true;

      if (currentAudio) {
        currentAudio.pause();
        currentAudio.removeAttribute("src");
        currentAudio.load();
      }

      if (currentAudioUrl) {
        URL.revokeObjectURL(currentAudioUrl);
        currentAudioUrl = null;
      }

      updateMusicControls();
      await playVideo(id, true);
      return;
    }

    currentMusicVideo = false;

    const player = $("#videoPlayer");
    if (player && (player.currentSrc || player.src)) {
      player.pause();
      player.removeAttribute("src");
      player.load();

      if (currentObjectUrl) URL.revokeObjectURL(currentObjectUrl);
      currentObjectUrl = null;
      currentVideo = null;
      closeModal("playerModal");
    }

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
      toast("تعذر فتح الملف من التخزين المحلي.");
    }
  }
}
'''

source = source[:start] + new_function + source[end:]
changes.append("music playback function")

# 6. Previous/next works with either audio or video playback
source = replace_once(
    source,
    '''  if (direction < 0 && currentAudio && currentAudio.currentTime > 3) {
    currentAudio.currentTime = 0;
    currentAudio.play().catch(() => {});
    return;
  }''',
    '''  const activePlayer = currentMusicVideo
    ? $("#videoPlayer")
    : currentAudio;

  if (direction < 0 && activePlayer && activePlayer.currentTime > 3) {
    activePlayer.currentTime = 0;
    activePlayer.play().catch(() => {});
    return;
  }''',
    "previous track handling"
)

# 7. Repeat-one supports video tracks
source = replace_once(
    source,
    '''function handleMusicEnded() {
  if (musicRepeatMode === "one" && currentAudio) {
    currentAudio.currentTime = 0;
    currentAudio.play().catch(() => toast("تعذر إعادة تشغيل الأغنية."));
    return;
  }

  advanceMusic(1);
}''',
    '''function handleMusicEnded() {
  const player = currentMusicVideo ? $("#videoPlayer") : currentAudio;

  if (musicRepeatMode === "one" && player) {
    player.currentTime = 0;
    player.play().catch(() => toast("تعذر إعادة تشغيل الملف."));
    return;
  }

  advanceMusic(1);
}''',
    "repeat-one handling"
)

# 8. Play/pause supports both player types
source = replace_once(
    source,
    '''function toggleMusicPlayPause() {
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
}''',
    '''function toggleMusicPlayPause() {
  if (!currentAudioId || (!currentAudio && !currentMusicVideo)) {
    if (musicQueue.length) playMusicTrack(musicQueue[0]);
    return;
  }

  const player = currentMusicVideo ? $("#videoPlayer") : currentAudio;

  if (player.paused) {
    if (player.ended) player.currentTime = 0;
    player.play().catch(() => toast("تعذر استكمال التشغيل."));
  } else {
    player.pause();
  }

  updateMusicControls();
}''',
    "music play pause"
)

# 9. Allow videos categorized as music to use the video player
source = replace_once(
    source,
    'async function playVideo(videoId) {',
    'async function playVideo(videoId, musicMode = false) {',
    "video player mode argument"
)

source = replace_once(
    source,
    '''  if (!v) return;

  try {
    const file = await getFile(v.filename);''',
    '''  if (!v) return;

  currentMusicVideo = musicMode;
  if (!musicMode) {
    currentAudioId = null;
    currentMusicTitle = "";
  }

  try {
    const file = await getFile(v.filename);''',
    "video player music state"
)

source = replace_once(
    source,
    '''    player.play().catch(() => {
      refreshPlayerControls();
    });

  } catch (e) {''',
    '''    player.play().then(() => {
      if (musicMode) updateMusicControls();
      refreshPlayerControls();
    }).catch(() => {
      refreshPlayerControls();
      if (musicMode) updateMusicControls();
    });

  } catch (e) {''',
    "video player play state"
)

# 10. Video ended event advances music queue only in music mode
source = replace_once(
    source,
    '''  $("#videoPlayer").addEventListener("ended", async () => {
    if (currentVideo) {
      currentVideo.position = 0;
      await dbRequest("readwrite", "put", currentVideo);
    }
  });''',
    '''  $("#videoPlayer").addEventListener("ended", async () => {
    if (currentVideo) {
      currentVideo.position = 0;
      await dbRequest("readwrite", "put", currentVideo);
    }

    if (currentMusicVideo && currentAudioId) {
      handleMusicEnded();
    }
  });''',
    "video ended queue integration"
)

# 11. Add a category transfer button to each media card
source = replace_once(
    source,
    '''    const fav = document.createElement("button");
    fav.textContent = v.favorite ? "♥ محفوظ" : "♡ المفضلة";''',
    '''    if (currentCategory !== "vault" && !isVaultItem(v)) {
      const move = document.createElement("button");
      move.textContent = kind === "audio"
        ? "⇄ نقل إلى الفيديوهات"
        : "⇄ نقل إلى الموسيقى";
      move.title = "تغيير مكان الملف في المكتبة دون نسخه أو حذفه";
      move.onclick = async () => {
        const oldKind = v.kind || "video";
        v.kind = oldKind === "audio" ? "video" : "audio";

        try {
          await dbRequest("readwrite", "put", v);
          toast(v.kind === "audio"
            ? "تم نقل الملف إلى الموسيقى. إذا كان فيديو فسيعمل بالصورة والصوت."
            : "تم نقل الملف إلى الفيديوهات.");
          await render();

          if (v.kind === "video" && mediaKind(v.type, v.filename) === "video") {
            getFile(v.filename).then(file => generateAndSaveThumb(v.id, file)).catch(() => {});
          }
        } catch (error) {
          console.error(error);
          v.kind = oldKind;
          toast("تعذر تغيير تصنيف الملف.");
        }
      };
      actions.append(move);
    }

    const fav = document.createElement("button");
    fav.textContent = v.favorite ? "♥ محفوظ" : "♡ المفضلة";''',
    "manual category transfer button"
)

# 12. Closing the video player should stop music-video mode
source = replace_once(
    source,
    '''    currentObjectUrl = null;
    currentVideo = null;

    await render();
  });''',
    '''    currentObjectUrl = null;
    currentVideo = null;

    if (currentMusicVideo) {
      currentMusicVideo = false;
      currentAudioId = null;
      currentMusicTitle = "";
      musicLoadToken++;
      updateMusicControls();
    }

    await render();
  });''',
    "player close music cleanup"
)

# 13. Cache refresh
worker = replace_once(
    worker,
    'const CACHE = "novra-video-shell-v14";',
    'const CACHE = "novra-video-shell-v15";',
    "service worker cache version"
)

# All replacements passed: now create backup and write
stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
backup = Path("backups") / f"music-video-categories-{stamp}"
backup.mkdir(parents=True, exist_ok=True)
shutil.copy2(app, backup / "app.js")
shutil.copy2(sw, backup / "sw.js")

app.write_text(source, encoding="utf-8")
sw.write_text(worker, encoding="utf-8")

print("Applied changes:")
for item in changes:
    print(" -", item)
print("Backup:", backup)

# JavaScript syntax validation; rollback on failure
check = subprocess.run(
    ["node", "--check", str(app)],
    text=True,
    capture_output=True
)

if check.returncode != 0:
    shutil.copy2(backup / "app.js", app)
    shutil.copy2(backup / "sw.js", sw)
    print(check.stderr)
    sys.exit("JavaScript syntax check failed. Original files restored.")

print("JavaScript syntax: PASS")
print("Service Worker cache: v15")
print("Original media files were not modified.")
