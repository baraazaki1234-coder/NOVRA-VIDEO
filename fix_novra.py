from pathlib import Path
from datetime import datetime
import shutil, re, sys

root = Path.cwd()
js = root / "static/app.js"
html = root / "static/index.html"
css = root / "static/style.css"
sw = root / "static/sw.js"
files = (js, html, css, sw)

for p in files:
    if not p.is_file():
        sys.exit(f"STOP: الملف غير موجود: {p}")

J, H, C, S = [p.read_text(encoding="utf-8") for p in files]

# جهّز التعديلات في الذاكرة أولًا، قبل الكتابة على الملفات.
# منع الضغط على الفيديو من إيقافه مباشرة.
J = J.replace('  p.addEventListener("click", togglePlayerPlayback);\n', '')
J = J.replace('    }, 3500);', '    }, 10000);', 1)
J = J.replace(
    'secretVaultTimer = setTimeout(() => secretVaultTaps = 0, 1800);',
    'secretVaultTimer = setTimeout(() => secretVaultTaps = 0, 3000);'
)

# الضغط المزدوج في منتصف الفيديو لا يوقف التشغيل.
J = J.replace(
'''      } else {
        v.paused ? v.play().catch(() => {}) : v.pause();
      }
      showPlayerControls();''',
'''      }
      showPlayerControls();'''
)

# إبقاء أدوات التحكم ظاهرة أثناء لمسها أو تحريك أشرطة التحكم.
needle = '  $("#videoPlayer").addEventListener("play", showPlayerControls);'
if needle not in J:
    sys.exit("STOP: لم أجد نقطة ربط أحداث مشغل الفيديو؛ لم يتم تعديل الملفات.")

if 'custom-controls").addEventListener("pointerdown"' not in J:
    J = J.replace(
        needle,
'''  $("#playerScreen .custom-controls").addEventListener("pointerdown", () => {
    showPlayerControls();
  });
  $("#playerScreen .custom-controls").addEventListener("input", () => {
    showPlayerControls();
  });

''' + needle, 1
    )

# إعادة اختصار فتح الخزنة عبر خمس ضغطات على علامة N.
needle = '  $("#vaultGearBtn").onclick = () => {'
if needle not in J:
    sys.exit("STOP: لم أجد إعدادات المشغل؛ لم يتم تعديل الملفات.")

if "let logoVaultTaps = 0" not in J:
    J = J.replace(
        needle,
'''  let logoVaultTaps = 0, logoVaultTimer = null;
  const logoMark = document.querySelector(".brand-mark");
  if (logoMark) {
    logoMark.addEventListener("click", () => {
      logoVaultTaps++;
      clearTimeout(logoVaultTimer);
      logoVaultTimer = setTimeout(() => logoVaultTaps = 0, 2500);
      if (logoVaultTaps >= 5) {
        logoVaultTaps = 0;
        requestVaultAccess("browse");
      }
    });
  }

''' + needle, 1
    )

# إزالة ربط زر قفل الخزنة من JavaScript.
J, removed_handler = re.subn(
    r'\s*\$\("#lockVaultBtn"\)\.onclick\s*=\s*\(\)\s*=>\s*\{.*?\n\s*\};',
    '',
    J,
    count=1,
    flags=re.S
)

if removed_handler == 0 and '$("#lockVaultBtn").onclick' in J:
    sys.exit("STOP: لم أستطع تحديد حدث زر قفل الخزنة بأمان.")

# إزالة زر قفل الخزنة من نافذة الإعدادات.
H, removed_button = re.subn(
    r'<button class="secondary-btn full" id="lockVaultBtn"[^>]*>.*?</button>',
    '',
    H,
    count=1,
    flags=re.S
)

if removed_button == 0 and 'id="lockVaultBtn"' in H:
    sys.exit("STOP: لم أستطع تحديد زر قفل الخزنة في HTML بأمان.")

# تحديث روابط ملفات الواجهة عند وجود الصيغة المعتادة.
H = re.sub(r'href="/style\.css(?:\?v=[^"]*)?"',
           'href="/style.css?v=8"', H, count=1)
H = re.sub(r'src="/app\.js(?:\?v=[^"]*)?"',
           'src="/app.js?v=8"', H, count=1)

# طلب تحديث Service Worker دون الاعتماد على كاش المتصفح.
J = re.sub(
    r'navigator\.serviceWorker\.register\(\s*(["\'])/sw\.js\1\s*\)',
    'navigator.serviceWorker.register("/sw.js", {updateViaCache:"none"}).then(reg => reg.update().catch(() => {}))',
    J,
    count=1
)

# تحسين ظهور أدوات التحكم.
C += '''

/* NOVRA VIDEO - player controls fix */
#playerScreen .custom-controls {
  position: relative;
  z-index: 4;
  opacity: 1;
  visibility: visible;
  pointer-events: auto;
  transition: opacity .18s ease, visibility .18s ease;
}
#playerScreen.controls-hidden .custom-controls {
  opacity: 0;
  visibility: hidden;
  pointer-events: none;
}
#playerScreen .custom-controls input[type="range"] {
  min-height: 28px;
  touch-action: pan-x;
  cursor: pointer;
}
#playerScreen #seekBar {
  flex: 1;
  min-width: 45px;
  padding: 0;
  background: transparent;
}
#playerScreen video {
  touch-action: manipulation;
}
'''

# تحديث الكاش وإصلاح طريقة جلب ملفات الواجهة.
S, cache_count = re.subn(
    r'const CACHE = "[^"]+";',
    'const CACHE = "novra-video-shell-v8";',
    S,
    count=1
)
if cache_count != 1:
    sys.exit("STOP: لم أجد اسم كاش Service Worker؛ لم يتم تعديل الملفات.")

fetch_start = S.find('self.addEventListener("fetch", event => {')
if fetch_start < 0:
    sys.exit("STOP: لم أجد fetch handler في Service Worker؛ لم يتم تعديل الملفات.")

S = S[:fetch_start] + r'''
self.addEventListener("fetch", event => {
  const request = event.request;
  if (request.method !== "GET") return;

  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;
  if (request.headers.has("range")) return;

  const path = url.pathname;
  const shellPaths = new Set([
    "/", "/index.html", "/style.css", "/app.js",
    "/manifest.json", "/sw.js"
  ]);

  event.respondWith((async () => {
    const cache = await caches.open(CACHE);

    if (shellPaths.has(path)) {
      try {
        const response = await fetch(request);
        if (response && response.ok) {
          await cache.put(path, response.clone());
        }
        return response;
      } catch (error) {
        return (await cache.match(path)) ||
               (await cache.match("/index.html"));
      }
    }

    const cached = await cache.match(path);
    if (cached) return cached;

    try {
      const response = await fetch(request);
      if (response && response.ok) {
        await cache.put(path, response.clone());
      }
      return response;
    } catch (error) {
      return (await cache.match(path)) ||
             (await cache.match("/index.html"));
    }
  })());
});
'''

# إنشاء نسخة احتياطية ثم حفظ التعديلات.
backup = root / "backups" / (
    "player-vault-fix-" + datetime.now().strftime("%Y%m%d-%H%M%S")
)
backup.mkdir(parents=True, exist_ok=True)

for p in files:
    shutil.copy2(p, backup / p.name)

for p, content in zip(files, (J, H, C, S)):
    p.write_text(content, encoding="utf-8")

print("NOVRA_FIX_APPLIED")
print("Backup:", backup)
