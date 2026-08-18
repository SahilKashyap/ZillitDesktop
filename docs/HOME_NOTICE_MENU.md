# Home notice menu — parity with Android

Right-click / kebab / long-press on a post in a Home unit. Reference:
Android `bottomNav/home/Home.kt` (`showDropDownOptions` for what is shown,
`optionHandler` for what a click checks) and `utils/OptionsHandler.kt`
(labels, alphabetical order, Delete last). Reviewed 2026-08-18.

Where Android shows an item to everyone and refuses on the click, the
desktop leaves the item out — the same rule one step earlier. Edit and
Delete keep the item and explain the *clock* on the click, as Android's toast
does. Wording is Android's.

| Android option | Shown when (Android) | Click check (Android) | Desktop |
|---|---|---|---|
| Translate | translate on, not translated, not mine | — | **not ported** (no translation layer) |
| Publish to Doc Distribution | file post, call sheet unit | Distribution posting rights, online, non-text | shown on the call sheet's files when the user has DD posting rights (`canPublishToDistribution`) |
| Read By User | always | delivered | **Read by…** on every sent post |
| Copy | never on Android (iOS has it) | — | on any post with words |
| Edit | message non-empty | delivered; owner **or** posting rights; 30 min; online | **author only** (`Notice.isEditableBy` / `editVerdict`, 30 min) — see *The server's rule* below; replies owner-only too (`canEditMessage`) |
| Gallery | always | — | **Gallery** on every sent post → the unit's Media / Docs / Links library over the board's posts (`NoticeLibraryPanel`, `toLibrary`) |
| Reply | not a group item, delivered, posting rights | — | shown when the unit can be posted to |
| Forward | unit ≠ call sheet | delivered; posting rights on this unit; online; confirm | **Forward…** when not the call sheet *and* the user can post here; the target unit's rights are checked again on pick |
| Share | unit ≠ call sheet | delivered; posting rights | **not ported** (system share sheet) |
| Download | file post (not text/location) | download rights, online | **Download** on any file post; without download rights the click says "You do not have downloading rights on <unit>" |
| Image Reply (`edit_and_post`) | picture, posting rights, not group | — | **Image Reply** on pictures when the user can post |
| Delete (last) | always | non-admin: 30 min; owner or admin; confirm; online | shown to the author or an admin; owner past 30 min is told; admin any age; confirm row |
| — | — | — | **Pin/Unpin** — from the web, not Android; **author only** (same route as Edit) |

## The server's rule for Edit and Pin

Both ride `PUT home/chat/{id}`. Found live on 2026-08-18 (SG Document
Distribution, signed in as an admin with posting rights): Pin on someone
else's post → "You do not have access to this."; Pin on the user's own
seven-day-old post → accepted (so age is not the server's concern —
the thirty minutes are the clients'). Android's client lets anyone with
posting rights into the editor on another's post (`Options.EditComment`:
`!hasPostingRights() && !isOwner`) and would meet the same refusal on save;
the desktop does not offer what the server will not take. If the product
wants admin edits, that is a backend change first.

## Live pass, 2026-08-18

Own old text post → Edit says "You can't edit a message after 30 minutes.";
own fresh post → Edit saves ("… edited"), Pin/Unpin round-trips (banner),
Delete asks then removes; Forward → picker → "Forwarded to Bulletin."; Read
by → Read 1 / Unread 11; Download → `~/Downloads/<file>` and opened; Image
Reply → pen editor; Publish to Doc Distribution (call sheet PDF) → confirm
prompt; Gallery → Media 20 / Docs 5 / Links (thumbnails, play badges; a
picture opens the lightbox *over* the panel — moved above the dialogs; a
document opens; a `https://…` link opens the browser, trailing "." trimmed).

Order on the desktop is fixed (Reply, Image Reply, Copy, Download, Publish,
Forward, Read by, Gallery, Pin, Edit, Delete) rather than alphabetical.

Rules pinned by tests: `NoticeMenuRulesTest` (edit rule, library buckets,
URL finding), `HomeBoardRulesTest` (posting rights edit another's post, a
reply stays owner-only, no rights → refused), `HomeBoardRenderTest` (which
items appear for own post / someone else's picture / a viewer without
posting rights / the call sheet; Gallery opens the three tabs).
