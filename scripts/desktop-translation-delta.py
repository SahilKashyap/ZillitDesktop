#!/usr/bin/env python3
"""
Writes the desktop-only keys a language still lacks, as translation input.

    scripts/desktop-translation-delta.py <code> <out-dir> [chunk-size]

Reads desktop-en.xml and desktop-<code>.xml, and writes the English lines for
every key missing from the translation into <out-dir>/<code>-partN.xml, one
chunk per file. A translator (human or agent) translates each chunk and appends
the result to desktop-<code>.xml before its closing </resources>.
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
I18N = ROOT / "core/strings/src/jvmMain/resources/i18n"


def main(argv):
    code, out_dir = argv[0], Path(argv[1])
    size = int(argv[2]) if len(argv) > 2 else 330
    english_lines = {}
    for line in (I18N / "desktop-en.xml").read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if stripped.startswith("<string "):
            key = stripped.split('name="', 1)[1].split('"', 1)[0]
            english_lines[key] = stripped
    have = set()
    target = I18N / f"desktop-{code}.xml"
    if target.is_file():
        have = {e.get("name") for e in ET.parse(target).getroot() if e.tag == "string"}
    missing = [k for k in english_lines if k not in have]
    out_dir.mkdir(parents=True, exist_ok=True)
    for old in out_dir.glob(f"{code}-part*.xml"):
        old.unlink()
    for i in range(0, len(missing), size):
        chunk = missing[i:i + size]
        (out_dir / f"{code}-part{i // size + 1}.xml").write_text(
            "\n".join(english_lines[k] for k in chunk) + "\n", encoding="utf-8",
        )
    print(f"{code}: {len(missing)} missing keys in {(len(missing) + size - 1) // size} part(s)")


if __name__ == "__main__":
    main(sys.argv[1:])
