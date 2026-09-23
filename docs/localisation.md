# Localisation

The desktop app is shown in any of the 22 languages the Android client ships,
using the Android client's own translations. Two systems carry the words:

| What | Module | Source | Read with |
|---|---|---|---|
| The app's own text — buttons, headings, dialogs, empty states | `core:strings` | Android `res/values-*/strings.xml`, copied by `scripts/sync-android-strings.py` | `str(S.key)` |
| Server-named things — tool names, units, designations, server messages | `core:localization` | `GET preset/labels|messages|identifiers?lang=` | `"key".localised()` |

Both follow one preference, `ZillitPreferences.Language` (`ui.language`): a
language code, or blank for "follow the system". The globe in the app bar,
the production picker, and Settings › Appearance all write it. Changing it
swaps the bundled catalogue in place and refetches the server dictionaries.

## Writing text

```kotlin
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

ZillitText(text = str(S.start_project))
ZillitButton(text = str(S.cancel), …)
str(S.use_my_name, name)            // "Use my name (%1$s)"
plural(S.bs_day_count, days)        // <plurals> — one / other
```

`str()` is one ordinary function. Called during composition it subscribes
the caller to the catalogue (it is snapshot state), so a language switch
recomposes the text with no `collectAsState` and no restart. Called from a
ViewModel it is a plain read.

**Prefer keys over copied text in state.** A ViewModel that stores
`str(S.saving)` in its state shows the old language until the next emission.
Store the key, or the thing the text is about, and call `str()` at the
point of display.

**Uninstalled is English.** Before the graph installs a language — and in
every test — `Strings` holds the bundled English catalogue. A screen test
that asserts on `"Sign out?"` keeps passing after the screen moves to
`str(S.desktop_sign_out_title)`.

## Finding a key

Every Android key is a constant on `S`, so the IDE completes them and a typo
is a compile error. To find the key for a piece of English text:

```bash
scripts/find-string-key.py "Cancel" "Something went wrong"
```

Where several keys carry the same text the bare one is listed first
(`cancel` before `ah_cancel`); the prefixed ones belong to a phone tool and
are fine to use when the text is generic.

Match **exactly**. `Try Again` and `Try again` are different keys with
different translations, and a near-miss is a different sentence in Japanese.

## Adding text Android does not have

```bash
scripts/add-desktop-string.py desktop_switch_project "Switch project"
```

This appends to `core/strings/src/jvmMain/resources/i18n/desktop-en.xml`,
regenerates `S.kt`, and refuses duplicates: if the text already exists under
any key it prints that key instead. Never edit `desktop-en.xml` or `S.kt` by
hand — the script locks the file, so several people (or agents) can add at
once.

Desktop-only keys are English in every language until the phones gain the
same key; the next sync then picks up Android's translation with no code
change, because the Android file is laid over the desktop one per key.

Placeholders are Android's: `%1$s`, `%2$d`. A Kotlin template
`"Version $v is available"` becomes the text `Version %1$s is available.`
and the call `str(S.desktop_update_available, v)`.

## What is not text

Leave alone: log messages, `testTag`s, routes and paths, JSON and wire
values, `@SerialName`s, enum ids, URLs, regexes, `require()` messages,
exception messages, KDoc. A string that reaches the server or the log is not
a string a person reads on screen.

## Keeping up with Android

```bash
scripts/sync-android-strings.py            # ../ZillitAndroidV20 by default
scripts/sync-android-strings.py /path/to/ZillitAndroidV20
```

Deterministic: a re-run with no upstream change is a no-op. It strips
`translatable="false"` entries and anything named like an API key (the
Android file carries a Maps key in a `<string>`).

## Right-to-left

Arabic and Hebrew flip `LocalLayoutDirection` from `ZillitTheme`, so every
window mirrors. Layouts that hard-code `Alignment.Start`/`End` are correct;
ones that use absolute `Left`/`Right` or `padding(start = …)` mixed with
`paddingFromBaseline` will not mirror and need a look when a screen is
checked in Arabic.

## Coverage

Server-named things were already localised. The app frame (rail, bar,
status, sign-out), the production picker's chrome, and Settings › Appearance
moved to `str()` first; feature modules move as they are touched. To see
what a module still has:

```bash
grep -n '"[A-Z][a-z][^"$]*"' feature/<module>/src/commonMain -r | grep -v 'testTag\|ZillitLog\|SerialName'
```
