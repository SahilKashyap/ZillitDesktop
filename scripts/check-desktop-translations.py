#!/usr/bin/env python3
"""
Validates the desktop-only translation files against desktop-en.xml.

    scripts/check-desktop-translations.py            # every desktop-<code>.xml
    scripts/check-desktop-translations.py fr de      # just these

For each file: it must parse; every key must exist in desktop-en.xml; every
value must carry the same Android placeholders (`%1$s`, `%d`, `{tool_name}`)
as the English; nothing may be left identical to the English unless the
English is a proper noun or a symbol (reported as a warning, not a failure).
Prints coverage per language and exits non-zero on any failure.
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
I18N = ROOT / "core/strings/src/jvmMain/resources/i18n"
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[sdf]|\{[a-z_]+\}")


def entries(path):
    return {e.get("name"): (e.text or "") for e in ET.parse(path).getroot() if e.tag == "string"}


def main(codes):
    english = entries(I18N / "desktop-en.xml")
    files = [I18N / f"desktop-{c}.xml" for c in codes] if codes else sorted(I18N.glob("desktop-*.xml"))
    failed = False
    for path in files:
        code = path.stem.split("-", 1)[1]
        if code == "en":
            continue
        try:
            translated = entries(path)
        except ET.ParseError as error:
            print(f"{code}: XML parse error: {error}")
            failed = True
            continue
        unknown = sorted(k for k in translated if k not in english)
        bad_placeholders = sorted(
            k for k, v in translated.items()
            if k in english and sorted(PLACEHOLDER.findall(v)) != sorted(PLACEHOLDER.findall(english[k]))
        )
        untranslated = sorted(
            k for k, v in translated.items()
            if k in english and v.strip() == english[k].strip() and re.search(r"[A-Za-z]{4,}", v)
        )
        empty = sorted(k for k, v in translated.items() if not v.strip())
        coverage = sum(1 for k in english if k in translated)
        print(f"{code}: {coverage}/{len(english)} keys, unknown={len(unknown)}, "
              f"placeholders={len(bad_placeholders)}, identical={len(untranslated)}, empty={len(empty)}")
        for label, keys in (("unknown", unknown), ("placeholders", bad_placeholders), ("empty", empty)):
            if keys:
                failed = True
                print(f"  {label}: {' '.join(keys[:15])}{' …' if len(keys) > 15 else ''}")
        if untranslated:
            print(f"  identical to English (check): {' '.join(untranslated[:10])}{' …' if len(untranslated) > 10 else ''}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main(sys.argv[1:])
