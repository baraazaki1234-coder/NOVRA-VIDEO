from pathlib import Path
from datetime import datetime
import shutil, re, sys

base = Path.home() / "NOVRA-VIDEO"
hp, jp, cp = [base / x for x in (
    "static/index.html", "static/app.js", "static/style.css"
)]
if not all(p.exists() for p in (hp, jp, cp)):
    sys.exit("ERROR: ملفات المشروع الأساسية غير موجودة.")

H, J, C = hp.read_text(), jp.read_text(), cp.read_text()

old = '''      <div class="player-tools">
        <label>السرعة <select id="speedSelect"><option>0.5</option><option>0.75</option><option selected>1</option><option>1.25</option><option>1.5</option><option>1.75</option><option>2</option></select></label>
        <button id="favoritePlayer" class="secondary-btn">♡ المفضلة</button>
      </div>'''

new = '''      <div class="player-tools">
        <div class="player-gear-wrap">
          <button id="vaultGearBtn" class="secondary-btn" type="button">⚙ إعدادات الفيديو</button>
          <div id="vaultGearMenu" class="player-gear-menu" hidden>
            <label class="gear-speed">سرعة التشغيل
              <select id="speedSelect"><option>0.5</option><option>0.75</option><option selected>1</option><option>1.25</option><option>1.5</option><option>1.75</option><option>2</option></select>
            </label>
            <button id="vaultSecretTrigger" class="gear-secret-trigger" type="button">إعدادات الفيديو</button>
            <div id="vaultActions" class="vault-player-actions" hidden>
              <button id="copyVideoToVault" type="button">عمل نسخة في الخزنة</button>
              <button id="moveVideoToVault" type="button">نقل الفيديو إلى الخزنة</button>
              <small>ستختفي الخيارات تلقائيًا بعد 30 ثانية.</small>
            </div>
          </div>
        </div>
        <button id="favoritePlayer" class="secondary-btn">♡ المفضلة</button>
      </div>'''

if old not in H:
    sys.exit("STOP: لم أجد منطقة إعدادات المشغل المطابقة. لم تتغير الملفات.")

brand_start = J.find('  const brand = document.querySelector(".brand-mark");')
if brand_start < 0:
    sys.exit("STOP: لم أجد حدث الشعار.")
brand_end = J.find('  $("#settingsBtn").onclick', brand_start)
if brand_end < 0:
    sys.exit("STOP: لم أجد نهاية حدث الشعار.")
J = J[:brand_start] + J[brand_end:]


if 'let currentCategory = "video";' not in J:
    sys.exit("STOP: لم أجد موضع متغيرات التطبيق.")
if 'async function playVideo(videoId)' not in J:
    sys.exit("STOP: لم أجد مشغل الفيديو.")

# خزنة المشغل
vault_code = r'''
let secretVaultTaps = 0, secretVaultTimer = null, vaultOptionsTimer = null;
let playerControlsTimer = null, playerTapTimer = null;

function showPlayerControls() {
  const screen = $("#playerScreen");
  screen.classList.remove("controls-hidden");
  clearTimeout(playerControlsTimer);
  if (!$("#videoPlayer").paused) {
    playerControlsTimer = setTimeout(() => {
      if (!$("#videoPlayer").paused) screen.classList.add("controls-hidden");
    }, 3500);
  }
}

function hidePlayerVaultOptions() {
  $("#vaultActions").hidden = true;
  secretVaultTaps = 0;
  clearTimeout(vaultOptionsTimer);
}

async function playerVaultAccess() {
  if (vaultOpen) return true;
  return await requestVaultAccess("player");
}

async function copyCurrentVideoToVault() {
  if (!currentVideo) return;
  if (isVaultItem(currentVideo)) return toast("الفيديو موجود بالفعل داخل الخزنة.");
  if (!await playerVaultAccess() || !currentVideo) return;

  const source = currentVideo;
  const newId = id(), filename = `${newId}.media`;
  let written = false;
  try {
    await savePosition();
    const file = await getFile(source.filename);
    await writeFile(file, filename, () => {});
    written = true;
    await dbRequest("readwrite", "put", {
      ...source, id:newId, filename, vault:true,
      created:Date.now(), position:0, lastPlayed:0
    });
    toast("تم إنشاء نسخة مستقلة في الخزنة، والأصل لم يتغير.");
    hidePlayerVaultOptions();
    $("#vaultGearMenu").hidden = true;
    await render();
  } catch (e) {
    if (written) {
      try { await (await rootDirPromise).removeEntry(filename); } catch {}
    }
    console.error(e);
    toast("تعذر إنشاء النسخة داخل الخزنة.");
  }
}

async function moveCurrentVideoToVault() {
  if (!currentVideo) return;
  if (isVaultItem(currentVideo)) return toast("الفيديو موجود بالفعل داخل الخزنة.");
  if (!await playerVaultAccess() || !currentVideo) return;

  try {
    await savePosition();
    currentVideo.vault = true;
    await dbRequest("readwrite", "put", currentVideo);
    toast("تم نقل الفيديو إلى الخزنة دون حذف ملفه.");
    hidePlayerVaultOptions();
    $("#vaultGearMenu").hidden = true;
    await render();
  } catch (e) {
    console.error(e);
    toast("تعذر نقل الفيديو.");
  }
}

'''
J = J.replace('async function playVideo(videoId)', vault_code + '\nasync function playVideo(videoId)', 1)

# أحداث الترس والحركات
anchor = '  $("#favoritePlayer").onclick = async () => {'
events = r'''
  $("#vaultGearBtn").onclick = () => {
    $("#vaultGearMenu").hidden = !$("#vaultGearMenu").hidden;
    hidePlayerVaultOptions();
  };

  $("#vaultSecretTrigger").onclick = () => {
    secretVaultTaps++;
    clearTimeout(secretVaultTimer);
    secretVaultTimer = setTimeout(() => secretVaultTaps = 0, 1800);
    if (secretVaultTaps >= 5) {
      secretVaultTaps = 0;
      $("#vaultActions").hidden = false;
      clearTimeout(vaultOptionsTimer);
      vaultOptionsTimer = setTimeout(hidePlayerVaultOptions, 30000);
      toast("ظهرت خيارات الخزنة لمدة 30 ثانية.");
    }
  };

  $("#copyVideoToVault").onclick = copyCurrentVideoToVault;
  $("#moveVideoToVault").onclick = moveCurrentVideoToVault;

  $("#playerScreen").addEventListener("click", e => {
    if (e.target !== $("#videoPlayer")) return;
    const v = $("#videoPlayer"), r = v.getBoundingClientRect();
    const x = e.clientX - r.left, w = r.width;

    if (playerTapTimer) {
      clearTimeout(playerTapTimer);
      playerTapTimer = null;
      if (x > w * .62) {
        v.currentTime = Math.min(v.duration || Infinity, v.currentTime + 10);
        toast("تقديم 10 ثوانٍ");
      } else if (x < w * .38) {
        v.currentTime = Math.max(0, v.currentTime - 10);
        toast("رجوع 10 ثوانٍ");
      } else {
        v.paused ? v.play().catch(() => {}) : v.pause();
      }
      showPlayerControls();
      return;
    }

    playerTapTimer = setTimeout(() => {
      playerTapTimer = null;
      const screen = $("#playerScreen");
      if (screen.classList.contains("controls-hidden")) showPlayerControls();
      else {
        clearTimeout(playerControlsTimer);
        screen.classList.add("controls-hidden");
      }
    }, 260);
  });

  $("#videoPlayer").addEventListener("play", showPlayerControls);
  $("#videoPlayer").addEventListener("pause", () => {
    clearTimeout(playerControlsTimer);
    $("#playerScreen").classList.remove("controls-hidden");
  });

'''
if anchor not in J:
    sys.exit("STOP: لم أجد موضع أحداث المفضلة.")
J = J.replace(anchor, events + anchor, 1)

# إزالة واجهة الخزنة من الإضافة العادية، مع إبقاء عنصرها البرمجي للتوافق
if 'const useVault = $("#chooseVault")?.checked === true;' not in J:
    sys.exit("STOP: مسار الاستيراد مختلف؛ لم أغيّر واجهة الإضافة.")
C += r'''

/* NOVRA VIDEO — Space interface refresh */
:root {
  --nv-blue:#46b8fc;
  --nv-violet:#7774ff;
  --nv-lilac:#a8a9fa;
  --nv-line:rgba(105,157,255,.25);
}
body {
  background:
    radial-gradient(ellipse at 15% 0%,rgba(53,70,180,.22),transparent 42%),
    radial-gradient(ellipse at 90% 20%,rgba(70,184,252,.10),transparent 36%),
    #05091f !important;
}
.library-tabs {
  display:flex; gap:9px; padding:5px;
  border:1px solid var(--nv-line); border-radius:16px;
  background:rgba(10,18,48,.85);
}
.library-tab {
  flex:1; min-height:44px; border:1px solid transparent !important;
  border-radius:12px !important; color:#b9c9f4 !important;
  background:transparent !important; box-shadow:none !important;
}
.library-tab.active {
  color:white !important;
  border-color:rgba(70,184,252,.4) !important;
  background:linear-gradient(115deg,rgba(49,92,194,.48),rgba(91,75,210,.4)) !important;
  box-shadow:0 0 20px rgba(70,184,252,.13) !important;
}
select,input:not([type=checkbox]):not([type=range]),textarea {
  color:#eaf2ff !important; background:#101a3a !important;
  border:1px solid rgba(111,151,238,.35) !important;
  border-radius:12px !important; color-scheme:dark;
}
select option { color:#eaf2ff; background:#101a3a; }
.privacy-options { display:none !important; }

.video-grid {
  grid-template-columns:minmax(0,1fr) !important;
  gap:13px !important;
}
.video-grid .video-card {
  display:grid !important;
  grid-template-columns:minmax(112px,36%) minmax(0,1fr);
  align-items:center; gap:9px 13px; min-width:0; padding:10px;
  border:1px solid var(--nv-line) !important;
  border-radius:17px !important;
  background:linear-gradient(125deg,#111d43,#070d23) !important;
}
.video-grid .video-card > .thumb {
  grid-column:1; grid-row:1 / span 5;
  width:100% !important; height:auto !important;
  aspect-ratio:16/9; min-width:0; border-radius:11px; overflow:hidden;
}
.video-grid .video-card > :not(.thumb) { grid-column:2; min-width:0; }
.video-grid .video-card .thumb img {
  width:100%; height:100%; object-fit:cover; background:#05091f;
}
.video-grid .video-card .card-actions {
  display:flex; flex-wrap:wrap; gap:7px;
}
.player-screen {
  position:relative; overflow:hidden;
  border:1px solid rgba(70,184,252,.3) !important;
  background:#02040d !important;
  box-shadow:0 0 28px rgba(70,184,252,.12);
}
.player-screen .custom-controls {
  opacity:1; transition:opacity .22s ease;
  background:linear-gradient(180deg,rgba(13,22,53,.82),rgba(3,7,22,.96)) !important;
}
.player-screen.controls-hidden .custom-controls {
  opacity:0; pointer-events:none;
}
.custom-controls input[type=range] { accent-color:#46b8fc !important; }
.control-btn {
  color:#eaf2ff !important; background:#14234b !important;
  border:1px solid rgba(70,184,252,.3) !important;
}
.main-control { background:linear-gradient(135deg,#347ee8,#6758dc) !important; }

.player-tools {
  display:flex; align-items:center; justify-content:space-between;
  flex-wrap:wrap; gap:10px;
}
.player-gear-wrap { position:relative; }
.player-gear-menu {
  position:absolute; z-index:30; bottom:calc(100% + 9px); right:0;
  width:min(280px,82vw); padding:12px; display:grid; gap:10px;
  border:1px solid rgba(70,184,252,.35); border-radius:15px;
  background:linear-gradient(145deg,#14234b,#090f29);
  box-shadow:0 12px 35px rgba(0,0,0,.48);
}
.player-gear-menu[hidden],.vault-player-actions[hidden] { display:none !important; }
.player-gear-menu button,.player-gear-menu select { width:100%; min-height:40px; }
.gear-speed { display:grid; gap:7px; color:#dce8ff; }
.gear-secret-trigger {
  color:#9eb5e8 !important; background:rgba(7,13,35,.55) !important;
  border:1px solid rgba(111,151,238,.18) !important;
  border-radius:10px; font-size:12px;
}
.vault-player-actions {
  display:grid; gap:8px; padding-top:9px;
  border-top:1px solid var(--nv-line);
}
.vault-player-actions button {
  color:#eff7ff !important; border:1px solid rgba(70,184,252,.32) !important;
  border-radius:10px; background:linear-gradient(110deg,#173a72,#34317e) !important;
}
.vault-player-actions small { color:#9eafd5; line-height:1.6; }
@media(max-width:380px) {
  .video-grid .video-card { grid-template-columns:112px minmax(0,1fr); gap:8px; padding:8px; }
}
'''

# تعديل HTML في الذاكرة فقط بعد التأكد من جميع المواضع
H = H.replace(old, new, 1)

backup = base / "backups" / ("interface-upgrade-" + datetime.now().strftime("%Y%m%d-%H%M%S"))
backup.mkdir(parents=True, exist_ok=True)
for p in (hp,jp,cp):
    shutil.copy2(p, backup / p.name)

hp.write_text(H)
jp.write_text(J)
cp.write_text(C)

print("UPGRADE_APPLIED")
print("Backup:", backup)
