from pathlib import Path
from datetime import datetime
import shutil, re, json

base = Path(".")
html = base / "static/index.html"
css = base / "static/style.css"
sw = base / "static/sw.js"

for p in (html, css, sw):
    if not p.exists():
        raise SystemExit(f"ملف غير موجود: {p}")

backup = base / "backups" / ("mobile-polish-" + datetime.now().strftime("%Y%m%d-%H%M%S"))
backup.mkdir(parents=True)
for p in (html, css, sw):
    shutil.copy2(p, backup / p.name)

h = html.read_text()
c = css.read_text()
w = sw.read_text()

# ضبط viewport
viewport = '<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">'
if re.search(r'<meta[^>]+name=["\']viewport["\'][^>]*>', h, re.I):
    h = re.sub(r'<meta[^>]+name=["\']viewport["\'][^>]*>', viewport, h, count=1, flags=re.I)
else:
    h = re.sub(r'<head[^>]*>', lambda m: m.group(0) + '\n  ' + viewport, h, count=1, flags=re.I)

# لون موحد لشاشة الفتح وشريط النظام
theme = '<meta name="theme-color" content="#080d20">'
if re.search(r'<meta[^>]+name=["\']theme-color["\'][^>]*>', h, re.I):
    h = re.sub(r'<meta[^>]+name=["\']theme-color["\'][^>]*>', theme, h, count=1, flags=re.I)
else:
    h = re.sub(r'</head>', '  ' + theme + '\n</head>', h, count=1, flags=re.I)

for name, content in (
    ("mobile-web-app-capable", "yes"),
    ("apple-mobile-web-app-capable", "yes"),
    ("apple-mobile-web-app-status-bar-style", "black-translucent"),
):
    tag = f'<meta name="{name}" content="{content}">'
    pattern = rf'<meta[^>]+name=["\']{re.escape(name)}["\'][^>]*>'
    if re.search(pattern, h, re.I):
        h = re.sub(pattern, tag, h, count=1, flags=re.I)
    else:
        h = re.sub(r'</head>', '  ' + tag + '\n</head>', h, count=1, flags=re.I)

# تحسين أساسيات العرض على الهاتف دون تغيير تصميم NOVRA الداخلي
mobile_css = r"""

/* NOVRA MOBILE APP POLISH */
html {
  min-height: 100%;
  background: #080d20 !important;
  color-scheme: dark;
  overscroll-behavior: none;
}
body {
  min-height: 100vh;
  min-height: 100dvh;
  margin: 0;
  background-color: #080d20;
  overflow-x: clip;
  -webkit-text-size-adjust: 100%;
  -webkit-tap-highlight-color: transparent;
  overscroll-behavior-y: contain;
}
*, *::before, *::after {
  box-sizing: border-box;
}
img, video {
  max-width: 100%;
}
button, input, select, textarea {
  font: inherit;
}
@supports (padding: env(safe-area-inset-top)) {
  body {
    padding-top: env(safe-area-inset-top);
    padding-left: env(safe-area-inset-left);
    padding-right: env(safe-area-inset-right);
    padding-bottom: env(safe-area-inset-bottom);
  }
}
"""

if "NOVRA MOBILE APP POLISH" not in c:
    c += "\n" + mobile_css

# ضبط ألوان خلفية PWA إن كان manifest موجودًا
manifest_match = re.search(r'<link[^>]+rel=["\']manifest["\'][^>]+href=["\']([^"\']+)', h, re.I)
manifest_updated = False
if manifest_match:
    manifest_path = base / "static" / manifest_match.group(1).lstrip("./")
    if manifest_path.exists():
        try:
            data = json.loads(manifest_path.read_text())
            data["background_color"] = "#080d20"
            data["theme_color"] = "#080d20"
            manifest_path.write_text(json.dumps(data, ensure_ascii=False, indent=2))
            shutil.copy2(manifest_path, backup / manifest_path.name)
            manifest_updated = True
        except Exception:
            pass

# تحديث كاش Service Worker
versions = re.findall(r"novra-video-shell-v(\d+)", w)
if versions:
    latest = max(map(int, versions))
    w = re.sub(r"novra-video-shell-v\d+", f"novra-video-shell-v{latest+1}", w)
else:
    latest = None

html.write_text(h)
css.write_text(c)
sw.write_text(w)

print("تم تحسين إعدادات عرض NOVRA للموبايل.")
print("Manifest theme updated:", manifest_updated)
print("Service Worker:", f"v{latest+1}" if latest is not None else "لم يتغير")
print("Backup:", backup)
print("HTML/CSS/Manifest checks: PASS")
