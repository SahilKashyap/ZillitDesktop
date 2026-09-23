#!/usr/bin/env python3
"""
Lists every `S.<key>` referenced in Kotlin source that the generated `S`
does not define — the keys a compile would reject — grouped by file.

    scripts/check-string-keys.py [paths...]     # default: the whole tree
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
S_KT = ROOT / "core/strings/src/commonMain/kotlin/com/zillit/desktop/core/strings/S.kt"
defined = set(re.findall(r"const val `?([A-Za-z0-9_]+)`?: String", S_KT.read_text()))
roots = [Path(p).resolve() for p in sys.argv[1:]] or [ROOT / "core", ROOT / "feature", ROOT / "desktopApp"]
missing = 0
for root in roots:
    for path in root.rglob("*.kt"):
        if "/build/" in str(path):
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        keys = sorted({k for k in re.findall(r"\bS\.([a-z_][A-Za-z0-9_]*)\b", text) if k not in defined})
        if keys:
            missing += len(keys)
            print(f"{path.relative_to(ROOT)}: {' '.join(keys)}")
print(f"{missing} unresolved key reference(s)")
