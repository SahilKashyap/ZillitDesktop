package com.zillit.desktop.core.common

/**
 * Renders a backend key as words, without translating it.
 *
 * The API speaks in identifiers and translation keys — `callsheet_tool`,
 * `entertainment_industry_label` — and both reach the UI. Resolving them is
 * `core:localization`'s job, against `GET preset/labels|messages|identifiers`.
 *
 * This is the **miss path** of that lookup, and only that: it drops the known
 * suffix and title-cases the rest, so a key the dictionary has not caught up
 * with reads as `Entertainment Industry` rather than
 * `entertainment_industry_label`. The web does the same in `useLabelTranslate`;
 * Android shows the raw key, and it shows.
 *
 * It lives in `core:common` rather than `core:localization` because the
 * fallback must work with no dictionary loaded at all — the sign-in screen
 * before the first fetch returns, a unit test that wired no store. Call
 * `String.localised()` instead of this; reach for it directly only when there
 * is genuinely no dictionary to consult.
 *
 * Shared rather than duplicated per feature — the same two suffixes come back
 * from presets, tools and badges.
 */
fun String.toDisplayLabel(): String = removeSuffix("_label")
    .removeSuffix("_tool")
    // Tool groups arrive as `group_accounts_payroll_label`; the prefix is
    // scaffolding for the translation key, not something to read on a heading.
    .removePrefix("group_")
    .split('_', '-')
    .filter { it.isNotBlank() }
    .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
