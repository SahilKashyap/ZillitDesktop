#!/usr/bin/env python3
"""
Adds desktop-only text to the string catalogue and regenerates `S`.

    scripts/add-desktop-string.py desktop_sign_out "Sign out"
    scripts/add-desktop-string.py desktop_update_available "Version %1$s is available."

Rules it enforces, so the catalogue stays one catalogue:

  - If the exact text already exists under any key (Android's or ours), the
    existing key is printed and nothing is added: the Android key is
    already translated into 22 languages, and a second key for the same
    words would read in English under every one of them.
  - A key that exists with different text is refused; pick another name.
  - Keys are snake_case identifiers starting with `desktop_`, so a reader
    can tell at the call site that the text has no phone translation yet.

Safe to run from several terminals at once: the file is locked while it is
rewritten, and `S.kt` is regenerated under the same lock. Never edit
`desktop-en.xml` or `S.kt` by hand.
"""
import fcntl
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parent.parent
I18N = ROOT / "core" / "strings" / "src" / "jvmMain" / "resources" / "i18n"
DESKTOP = I18N / "desktop-en.xml"
LOCK = I18N / ".lock"
KEY = re.compile(r"^desktop_[a-z0-9_]+$")


def normalise(text: str) -> str:
    # Case-sensitive on purpose: "Try again" and "Try Again" are different
    # text, and forcing the caller onto the other one changes what is on
    # screen. find-string-key.py stays case-insensitive so it still *finds*
    # the near-miss for a human to judge.
    return re.sub(r"\s+", " ", text.replace("\\'", "'")).strip()


def android_escape(text: str) -> str:
    # Android's resource escaping: apostrophes and double quotes need a
    # backslash, and the XML characters go through the usual entities. Input
    # that already carries Android escapes is unescaped first, so a shell
    # that passed `\'` through does not end up doubly escaped.
    plain = text.replace("\\'", "'").replace('\\"', '"')
    escaped = escape(plain).replace("'", "\\'").replace('"', '\\"').replace("\n", "\\n")
    # Android (and our parser) trim a value's ends unless it is wrapped in
    # double quotes, so a separator like " · %1$s" would arrive without its
    # leading space. Quoting is Android's own way of saying "keep this".
    if plain != plain.strip():
        return f'"{escaped}"'
    return escaped


def existing_key_for(text: str) -> str | None:
    wanted = normalise(text)
    matches = []
    for path in (I18N / "strings-en.xml", DESKTOP):
        if not path.is_file():
            continue
        for elem in ET.parse(path).getroot():
            if elem.tag == "string" and normalise(elem.text or "") == wanted:
                matches.append(elem.get("name"))
    # The least prefixed key first — `cancel` over `ah_cancel` — as
    # find-string-key.py ranks them: the bare key is the app-wide one.
    matches.sort(key=lambda k: (k.count("_"), len(k), k))
    return matches[0] if matches else None


def main(argv):
    if len(argv) != 2:
        sys.exit(__doc__)
    key, text = argv
    if not KEY.match(key):
        sys.exit(f"refused: '{key}' must be snake_case and start with desktop_")
    if not text.strip():
        sys.exit("refused: empty text")

    with open(LOCK, "w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        found = existing_key_for(text)
        if found:
            print(f"exists: use S.{found}")
            return
        root = ET.parse(DESKTOP).getroot()
        for elem in root:
            if elem.get("name") == key:
                sys.exit(f"refused: '{key}' already holds {elem.text!r}")
        source = DESKTOP.read_text(encoding="utf-8")
        entry = f'    <string name="{key}">{android_escape(text)}</string>\n'
        source = source.replace("</resources>", entry + "</resources>")
        DESKTOP.write_text(source, encoding="utf-8")
        subprocess.run(
            [sys.executable, str(ROOT / "scripts" / "sync-android-strings.py"), "--keys-only"],
            check=True, capture_output=True,
        )
        print(f"added: S.{key}")


if __name__ == "__main__":
    main(sys.argv[1:])
