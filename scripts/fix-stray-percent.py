#!/usr/bin/env python3
"""
Doubles bare `%` signs in catalogue values that are formatted with arguments.

    scripts/fix-stray-percent.py <code> [<code> …]     # e.g. en fr de

`str(S.key, args)` runs the value through Java's String.format, where a
lone `%` is a malformed conversion: the formatter throws and the app shows
the raw template ("Tax rate must be between %1$s% and %2$s%."). A value
with a positional placeholder is always formatted, so in such a value every
`%` that is not itself a conversion must be written `%%`. Values without
placeholders are returned verbatim and are left alone — doubling them would
put "%%" on screen.
"""
import re
import sys
from pathlib import Path

I18N = Path(__file__).resolve().parent.parent / "core/strings/src/jvmMain/resources/i18n"
TOKEN = re.compile(r"%%|%(?:\d+\$)?[sdf]")
POSITIONAL = re.compile(r"%(?:\d+\$)?[sdf]")


def fix_value(value: str) -> str:
    if not POSITIONAL.search(value):
        return value
    out, i = [], 0
    for m in TOKEN.finditer(value):
        out.append(value[i:m.start()].replace("%", "%%"))
        out.append(m.group(0))
        i = m.end()
    out.append(value[i:].replace("%", "%%"))
    return "".join(out)


def main(codes):
    for code in codes:
        path = I18N / f"desktop-{code}.xml"
        text = path.read_text(encoding="utf-8")
        changed = []

        def repl(m):
            fixed = fix_value(m.group(2))
            if fixed != m.group(2):
                changed.append(m.group(1))
            return f'<string name="{m.group(1)}">{fixed}</string>'

        text = re.sub(r'<string name="([^"]+)">([^<]*)</string>', repl, text)
        if changed:
            path.write_text(text, encoding="utf-8")
        print(f"{code}: fixed {len(changed)} {changed}")


if __name__ == "__main__":
    main(sys.argv[1:])
