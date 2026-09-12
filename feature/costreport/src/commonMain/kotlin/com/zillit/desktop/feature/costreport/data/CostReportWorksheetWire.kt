package com.zillit.desktop.feature.costreport.data

import com.zillit.desktop.feature.costreport.domain.CrLockState
import com.zillit.desktop.feature.costreport.domain.EtcVersion
import com.zillit.desktop.feature.costreport.domain.EtcVersionLine
import com.zillit.desktop.feature.costreport.domain.PostCadence
import com.zillit.desktop.feature.costreport.domain.SnapshotPost
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// -- weekly ETC versions -----------------------------------------------------------

/**
 * `GET /weekly-etc/versions`. `saved_at` is a bigint the server's driver
 * hands back as a *string* ("1716123456789"), which is why it goes through
 * the lenient epoch reader rather than a number read.
 */
internal fun parseEtcVersions(data: JsonElement?): List<EtcVersion> = rowsOf(data).mapNotNull { row ->
    val id = row.str("version_id", "id", "_id") ?: return@mapNotNull null
    EtcVersion(
        id = id,
        label = row.str("version_label", "label").orEmpty(),
        rowCount = row.num("row_count", "line_count")?.toInt(),
        savedAtMs = row.epoch("saved_at", "created_at"),
    )
}

/** `GET /weekly-etc/versions/{id}` — a bare row array, or the rows under a wrapper. */
internal fun parseEtcVersionLines(data: JsonElement?): List<EtcVersionLine> = rowsOf(data).mapNotNull { row ->
    val account = row.str("account", "code") ?: return@mapNotNull null
    EtcVersionLine(
        account = account,
        etcAmount = row.num("etc_amount") ?: 0.0,
        vtpAmount = row.num("vtp_amount") ?: 0.0,
        currency = row.str("currency"),
    )
}

/** The id a create answers with — `data.version_id`, or the bare id. */
internal fun parseCreatedVersionId(data: JsonElement?): String? = when (data) {
    is JsonObject -> data.str("version_id", "id", "_id")
    is JsonPrimitive -> data.content.trim().takeIf { it.isNotEmpty() && data.isString }
    else -> null
}

internal fun etcVersionBody(weekEnding: String, label: String, lines: List<EtcVersionLine>, currency: String?) =
    buildJsonObject {
        put("week_ending", weekEnding)
        put("label", label.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull)
        put("lines", linesJson(lines))
        put("currency", currency?.let(::JsonPrimitive) ?: JsonNull)
    }

internal fun etcVersionPatchBody(lines: List<EtcVersionLine>, currency: String?) = buildJsonObject {
    put("lines", linesJson(lines))
    currency?.let { put("currency", it) }
}

private fun linesJson(lines: List<EtcVersionLine>) = JsonArray(
    lines.map { line ->
        buildJsonObject {
            put("account", line.account)
            put("etc_amount", line.etcAmount)
            put("vtp_amount", line.vtpAmount)
        }
    },
)

// -- lock ----------------------------------------------------------------------------

/**
 * `GET /lock-period` — `lockedDate` on the read route, `last_cr_locked_date`
 * where the write route and the settings document spell it; either shape of
 * `data` (bare or under `value`).
 */
internal fun parseLockState(data: JsonElement?): CrLockState {
    val obj = ((data as? JsonObject)?.get("value") as? JsonObject) ?: data as? JsonObject
    return CrLockState(
        lockedDate = CrLockState.normaliseDate(obj?.str("lockedDate", "last_cr_locked_date", "locked_date")),
        timeZone = obj?.str("tz", "timezone"),
        loaded = true,
    )
}

// -- posts ----------------------------------------------------------------------------

/**
 * `POST /snapshots`. A daily or weekly post sends its cadence and lets the
 * server resolve the window in the project's zone; a custom one sends its own
 * window. Empty filters stay off the body so the server's defaults apply —
 * and a note is always sent on the resolved cadences, as `null` when blank,
 * exactly as the web's `postDaily` / `postWeekly` do.
 */
internal fun snapshotPostBody(post: SnapshotPost) = buildJsonObject {
    put("cadence", post.cadence.wire)
    if (post.cadence == PostCadence.Custom) {
        post.periodStartMs?.let { put("period_start", it) }
        post.periodEndMs?.let { put("period_end", it) }
        post.name?.takeIf { it.isNotBlank() }?.let { put("name", it) }
        post.note?.takeIf { it.isNotBlank() }?.let { put("post_note", it) }
    } else {
        put("post_note", post.note?.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull)
    }
    post.companyId?.takeIf { it.isNotBlank() }?.let { put("company_id", it) }
    post.budgetVersionId?.takeIf { it.isNotBlank() }?.let { put("budget_version_id", it) }
    post.currency?.takeIf { it.isNotBlank() }?.let { put("currency", it) }
    post.etcVersionId?.takeIf { it.isNotBlank() }?.let { put("etc_version_id", it) }
}
