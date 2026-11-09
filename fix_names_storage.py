from pathlib import Path
from datetime import datetime
import shutil
import subprocess
import sys

root = Path.cwd()
targets = {
    "app.js": root / "static/app.js",
    "index.html": root / "static/index.html",
    "sw.js": root / "static/sw.js",
}
original = {k: p.read_text(encoding="utf-8") for k, p in targets.items()}

changes = [
    ("app.js", '    actions.append(fav,del);', '''    const rename = document.createElement("button");
    rename.className = "rename-media-btn";
    rename.textContent = "✎ تعديل الاسم";
    rename.title = "تعديل اسم الملف في المكتبة فقط";
    rename.onclick = async () => {
      const enteredName = prompt("اكتب الاسم الجديد:", String(v.name || ""));
      if (enteredName === null) return;
      const trimmedName = enteredName.trim();
      if (!trimmedName) {
        toast("الاسم لا يمكن أن يكون فارغًا.");
        return;
      }
      const newName = safeName(trimmedName);
      if (!newName.trim()) {
        toast("الاسم غير صالح.");
        return;
      }
      const oldName = v.name;
      try {
        v.name = newName;
        await dbRequest("readwrite", "put", v);
        toast("تم تعديل اسم الملف.");
        await render();
      } catch (error) {
        v.name = oldName;
        toast("تعذر حفظ الاسم الجديد.");
      }
    };

    actions.append(rename,fav,del);'''),
    ("app.js", 'ordinary.reduce((n,v)=>n+(v.size||0),0)', 'all.reduce((n,v)=>n+(Number(v.size)||0),0)'),
    ("index.html", '<small>مساحة مستخدمة تقريبًا</small>', '<small>حجم ملفات المكتبة (بما فيها الخزنة)</small>'),
    ("sw.js", 'novra-video-shell-v12', 'novra-video-shell-v13'),
]

for key, old, new in changes:
    count = original[key].count(old)
    if count != 1:
        print(f"PRECHECK FAILED: {key}, anchor count = {count}")
        sys.exit(1)

updated = dict(original)
for key, old, new in changes:
    updated[key] = updated[key].replace(old, new, 1)

backup = root / "backups" / ("names-storage-" + datetime.now().strftime("%Y%m%d-%H%M%S"))
backup.mkdir(parents=True, exist_ok=False)

for key, path in targets.items():
    shutil.copy2(path, backup / path.name)

try:
    for key, path in targets.items():
        temp = path.with_name(path.name + ".novra-tmp")
        temp.write_text(updated[key], encoding="utf-8")
        temp.replace(path)

    result = subprocess.run(
        ["node", "--check", str(targets["app.js"])],
        capture_output=True, text=True
    )
    if result.returncode:
        raise RuntimeError(result.stderr or result.stdout)

except Exception as exc:
    for key, path in targets.items():
        shutil.copy2(backup / path.name, path)
    print("FAILED; original files restored:", exc)
    sys.exit(1)

print("SUCCESS: updates installed.")
print("Backup:", backup)
print("JavaScript syntax: PASS")
print("IndexedDB and media files: unchanged")
