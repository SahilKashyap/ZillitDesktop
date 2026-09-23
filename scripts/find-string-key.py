#!/usr/bin/env python3
"""
Finds the Android string key(s) for a piece of English text, so a desktop
literal can be replaced by `str(S.<key>)`.

    scripts/find-string-key.py "Cancel" "Something went wrong" ...
    scripts/find-string-key.py --file literals.txt      # one text per line

Matching is exact after case-folding, trimming and collapsing whitespace, with
a trailing ellipsis or colon ignored. Where several keys carry the same text,
the least prefixed one is listed first (`cancel` before `ah_cancel`): the bare
key is usually the older, app-wide one, and the prefixed ones belong to a tool.
Nothing is fuzzy on purpose — a near miss is a different sentence.
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
I18N = ROOT / "core" / "strings" / "src" / "jvmMain" / "resources" / "i18n"


def normalise(text: str) -> str:
    text = re.sub(r"\\'", "'", text)
    text = re.sub(r"\s+", " ", text).strip().lower()
    return text.rstrip(".:…").strip()


def catalogue() -> dict:
    by_text = {}
    for path in (I18N / "strings-en.xml", I18N / "desktop-en.xml"):
        if not path.is_file():
            continue
        for elem in ET.parse(path).getroot():
            if elem.tag == "string" and elem.get("name"):
                by_text.setdefault(normalise(elem.text or ""), []).append(elem.get("name"))
    for keys in by_text.values():
        keys.sort(key=lambda k: (k.count("_"), len(k), k))
    return by_text


def main(argv):
    texts = argv
    if argv and argv[0] == "--file":
        texts = [line.rstrip("\n") for line in open(argv[1], encoding="utf-8") if line.strip()]
    table = catalogue()
    for text in texts:
        keys = table.get(normalise(text), [])
        print(f"{text!r}: {' '.join(keys[:5]) if keys else '(no key — add to desktop-en.xml)'}")


if __name__ == "__main__":
    main(sys.argv[1:])
