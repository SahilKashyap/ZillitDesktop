package com.zillit.desktop.feature.costumesetsync.ui.screens

import com.zillit.desktop.core.designsystem.component.encodeQrCode
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.ui.t
import com.zillit.desktop.feature.costumesetsync.ui.tEnum

private const val QUIET_MODULES = 2

/**
 * What a label's QR carries: the asset number, which the scan lookup (`GET /costumes/lookup/{asset}`) takes as typed.
 */
internal fun qrPayload(costume: Rec): String = costume.str("asset_number")

/** HTML text escape: names and notes are user typed. */
internal fun escapeHtml(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/**
 * One label's detail lines, as the web's `Label` draws them: "name ×qty", "type · colour · Size x", "character · source
 * (vendor)".
 */
internal fun labelLines(c: Rec): List<String> {
    val quantity = c.long("quantity").takeIf { it > 1 }?.let { " ×$it" }.orEmpty()
    val traits = listOfNotNull(
        c.str("type").ifBlank { null },
        c.str("color").ifBlank { null },
        c.str("size").ifBlank { null }?.let { "${t("csync_size")} $it" },
    ).joinToString(" · ")
    val character = c.rec("character")?.str("name")?.ifBlank { null }?.let { "$it · " }.orEmpty()
    val vendor = c.rec("vendor")?.str("name")?.ifBlank { null }?.let { " ($it)" }.orEmpty()
    return listOf(c.str("name") + quantity, traits, character + tEnum(c.str("source")) + vendor)
}

/** The QR as an inline SVG (one square per dark module), or empty when the payload cannot be encoded. */
internal fun qrSvg(payload: String): String {
    val matrix = encodeQrCode(payload) ?: return ""
    val full = matrix.size + QUIET_MODULES * 2
    val cells = StringBuilder()
    for (y in 0 until matrix.size) for (x in 0 until matrix.size) {
        if (matrix[x, y]) cells.append(
            "<rect x=\"${x + QUIET_MODULES}\" y=\"${y + QUIET_MODULES}\" width=\"1\" height=\"1\"/>",
        )
    }
    return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $full $full\" width=\"84\" height=\"84\" " +
        "shape-rendering=\"crispEdges\">" +
        "<rect width=\"$full\" height=\"$full\" fill=\"#fff\"/><g fill=\"#000\">$cells</g></svg>"
}

/**
 * The printable label sheet: a self-contained HTML page that opens its own print dialog, since the
 * desktop has no `window.print()` over the app. Each label is the QR beside the asset number and details.
 */
internal fun labelSheetHtml(title: String, costumes: List<Rec>): String {
    val labels = costumes.joinToString("") { c ->
        val lines = labelLines(c).filter { it.isNotBlank() }.mapIndexed { i, line ->
            "<div class=\"l$i\">${escapeHtml(line)}</div>"
        }.joinToString("")
        "<div class=\"label\">${qrSvg(qrPayload(c))}" +
            "<div><div class=\"asset\">${escapeHtml(c.str("asset_number"))}</div>$lines</div></div>"
    }
    return "<!doctype html><html><head><meta charset=\"utf-8\"><title>${escapeHtml(title)}</title><style>" +
        "body{font-family:-apple-system,Segoe UI,Arial,sans-serif;margin:12mm}" +
        ".sheet{display:grid;grid-template-columns:repeat(3,1fr);gap:12px}" +
        ".label{display:flex;gap:10px;align-items:center;padding:10px;border:1px dashed #999;border-radius:6px;" +
            "background:#fff;color:#000;break-inside:avoid;page-break-inside:avoid}" +
        ".asset{font-family:ui-monospace,Menlo,monospace;font-weight:800;font-size:15px}" +
        ".l0{font-size:12px;font-weight:600}.l1,.l2{font-size:11px;color:#4b5563}" +
        "</style></head><body><div class=\"sheet\">$labels</div>" +
        "<script>window.addEventListener('load',function(){setTimeout(function(){window.print()},300)})</script>" +
            "</body></html>"
}
