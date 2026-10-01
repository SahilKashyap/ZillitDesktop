package com.zillit.desktop.feature.costumesetsync.domain

/**
 * A printable document as one self-contained HTML file.
 *
 * The web prints with `window.print()` over a hidden `.csync-print-root`. A
 * desktop window has no such print portal, so Print / PDF hands the same
 * content over as an `.html` file through the host's save dialog: it opens in
 * any browser, which prints it or saves it as PDF.
 */
object PrintHtml {
    /** Escapes text for an HTML body or attribute. */
    fun esc(text: String?): String = buildString {
        text.orEmpty().forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(c)
            }
        }
    }

    private const val STYLE = "body{font-family:-apple-system,Segoe UI,Helvetica,Arial,sans-serif;color:#111;margin:32px;font-size:13px}" +
        "h1{font-size:26px;margin:0 0 4px}h2{font-size:16px;margin:24px 0 4px;border-bottom:1px solid #ccc;padding-bottom:4px}" +
        ".sub{letter-spacing:.12em;font-size:11px;color:#555}.muted{color:#555}.char{margin:10px 0 10px 12px}" +
        ".take{margin:6px 0 6px 12px;padding-left:8px;border-left:3px solid #ddd}" +
        "table{border-collapse:collapse;width:100%;margin-top:8px}td,th{padding:3px 8px;text-align:left;vertical-align:top}" +
        "th{border-bottom:1px solid #999;font-size:11px;text-transform:uppercase}.r{text-align:right;white-space:nowrap}" +
        ".total td{border-top:1px solid #999;font-weight:600}.grand td{border-top:2px solid #111;font-weight:700}" +
        ".group td{background:#eee;font-weight:700}.account td{font-weight:600}@media print{body{margin:12mm}}"

    /** Wraps [body] (already escaped HTML) into a full page titled [title]. */
    fun page(title: String, body: String): String =
        "<!doctype html><html><head><meta charset=\"utf-8\"><title>${esc(title)}</title><style>$STYLE</style></head><body>$body</body></html>"

    /** A file-name-safe slug: `Continuity of prep 2026-09-29` → `continuity-of-prep-2026-09-29`. */
    fun slug(text: String): String = text.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("").replace(Regex("-+"), "-").trim('-')
}
