from pathlib import Path
import re

ROOT = Path(".")
FILES = [
    Path("static/app.js"),
    Path("static/index.html"),
    Path("static/style.css"),
    Path("static/sw.js"),
]

PATTERNS = {
    "app.js": [
        r"\bkind\b", r"videoPlayer", r"currentVideo",
        r"currentAudio", r"playMusicTrack", r"handleMusicEnded",
        r"getFile", r"createObjectURL", r"accept\s*=",
        r"fileInput", r"input\[type", r"addEventListener",
        r"function\s+(?:render|playVideo|openVideo|playMusicTrack)",
        r"async function", r"onended", r"\.ended",
        r"videoOnly", r"audioOnly", r"currentCategory",
        r"mediaIds", r"vault"
    ],
    "index.html": [
        r"videoPlayer", r"audio", r"music", r"file",
        r"upload", r"category", r"playlist", r"player"
    ],
    "style.css": [
        r"video-player", r"music", r"audio", r"player",
        r"modal", r"media-card"
    ],
    "sw.js": [
        r"CACHE", r"static/app.js", r"static/index.html"
    ]
}

def show_matches(path, patterns, before=8, after=14):
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    hits = set()

    for i, line in enumerate(lines):
        if any(re.search(p, line, re.I) for p in patterns):
            start = max(0, i - before)
            end = min(len(lines), i + after + 1)
            hits.update(range(start, end))

    if not hits:
        print("لا توجد نتائج مطابقة.")
        return

    selected = sorted(hits)
    groups = []
    start = prev = selected[0]

    for n in selected[1:]:
        if n > prev + 1:
            groups.append((start, prev))
            start = n
        prev = n
    groups.append((start, prev))

    printed = 0
    for start, end in groups:
        print(f"\n--- الأسطر {start+1}-{end+1} ---")
        for i in range(start, end + 1):
            print(f"{i+1:5}: {lines[i]}")
            printed += 1
            if printed >= 1800:
                print("\n[توقف العرض عند الحد الآمن]")
                return

for path in FILES:
    print("\n" + "=" * 75)
    print(f"FILE: {path}")

    if not path.exists():
        print("الملف غير موجود.")
        continue

    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    print(f"عدد الأسطر: {len(lines)}")

    key = path.name
    show_matches(path, PATTERNS.get(key, []))

print("\n" + "=" * 75)
print("INSPECTION COMPLETE — لم يتم تعديل أي ملف.")
