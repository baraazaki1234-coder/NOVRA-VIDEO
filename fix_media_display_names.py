from pathlib import Path
from datetime import datetime
import shutil

p = Path("static/app.js")
s = p.read_text()

if "currentMusicTitle" not in s:
    raise SystemExit("متغير اسم التشغيل غير موجود؛ لم يتم تعديل أي ملف.")

changes = 0

# اسم الأغنية بدل ID
start = s.find("async function playMusicTrack(id) {")
end = s.find("async function advanceMusic(direction)", start)
if start < 0 or end < 0:
    raise SystemExit("لم أتعرف على دالة تشغيل الموسيقى.")

part = s[start:end]
anchor = "currentAudioId = id;"
if anchor in part and "currentMusicTitle = track.name" not in part:
    part = part.replace(
        anchor,
        anchor + '\n    currentMusicTitle = track.name || track.filename || "بدون اسم";',
        1
    )
    s = s[:start] + part + s[end:]
    changes += 1

# اسم الفيديو عند تشغيله من قسم الموسيقى
start = s.find("async function playVideo(")
end = s.find("\nasync function ", start + 10)
if start < 0 or end < 0:
    raise SystemExit("لم أتعرف على دالة تشغيل الفيديو.")

part = s[start:end]
anchor = "currentVideo = v;"
if anchor in part and "currentMusicTitle = v.name" not in part:
    part = part.replace(
        anchor,
        anchor + '\n    if (musicMode) currentMusicTitle = v.name || v.filename || "بدون اسم";',
        1
    )
    s = s[:start] + part + s[end:]
    changes += 1

# منع ظهور ID كاسم بديل
s = s.replace(
    '${currentMusicTitle || currentAudioId}',
    '${currentMusicTitle || "بدون اسم"}'
)

if changes == 0:
    print("الأسماء موجودة بالفعل أو أن مواضع التعديل مختلفة.")
else:
    backup = Path("backups") / ("media-names-" + datetime.now().strftime("%Y%m%d-%H%M%S"))
    backup.mkdir(parents=True, exist_ok=True)
    shutil.copy2(p, backup / "app.js")
    p.write_text(s)
    print("تم تعديل عرض أسماء الوسائط.")
    print("Backup:", backup)

print("افحص JavaScript بالأمر التالي: node --check static/app.js")
