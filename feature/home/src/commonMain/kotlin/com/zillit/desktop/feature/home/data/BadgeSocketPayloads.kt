package com.zillit.desktop.feature.home.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * What a `notification:silent` frame tells the badges to forget.
 *
 * iOS applies two of its cases to its local ledger and never shows them again
 * (`ProjectObserver`): `tools_no_view_access`, whose entries are the tool's
 * wire label and are re-sent as a read so the server forgets too, and
 * `unit_no_view_access`. The desktop, until now, only re-polled the ledger the
 * server never prunes.
 */
data class BadgeSuppression(
    /** Tool identifiers, already mapped from the wire's `_label` names. */
    val toolIdentifiers: Set<String>,
    /** The wire labels as sent, for the read the server needs to hear. */
    val toolWireLabels: Set<String>,
    val units: Set<String>,
) {
    val isEmpty: Boolean get() = toolIdentifiers.isEmpty() && units.isEmpty()
}

fun badgeSuppressionFrom(payload: JsonElement?): BadgeSuppression? {
    val record = payload.record() ?: return null
    val reference = (record["reference_data"] as? JsonObject) ?: record
    val labels = reference.strings("tools_no_view_access")
    val units = reference.strings("unit_no_view_access")
    if (labels.isEmpty() && units.isEmpty()) return null
    return BadgeSuppression(
        toolIdentifiers = labels.mapTo(mutableSetOf()) { wireToolToIdentifier(it) },
        toolWireLabels = labels,
        units = units,
    )
}

/**
 * Where one `notification:save` frame lands, for the optimistic lift.
 *
 * Only the three keys the ledger groups by; anything else the refresh sorts
 * out 600ms later. A frame with none of them is not an unread — a silent
 * clear or a calendar edit rides the same event.
 */
data class BadgeArrival(
    val section: String?,
    val toolIdentifier: String?,
    val unit: String?,
    /** Which production the unread belongs to; null on frames that do not say. */
    val projectId: String? = null,
)

fun badgeArrivalFrom(payload: JsonElement?, activeProjectId: String? = null): BadgeArrival? {
    val record = payload.record() ?: return null
    // Beside `section` in the record on the wire; the outer level is read too
    // for a frame that wraps the record in `data` and names the project outside.
    val project = record.text("project_id") ?: (payload as? JsonObject)?.text("project_id")
    if (record.isQuiet() || project.isElsewhere(activeProjectId)) return null
    // The phones do not count these, and neither does the banner pipeline
    // (`quietReason` in feature:notifications, from ProjectObserver.swift):
    // a silent push, a frame flagged `ignore`, or this person's own action.
    // Lifting the badge for one would show a count the refresh then takes
    // away 600ms later — a flicker for nothing.
    val section = record.text("section")
    val tool = record.text("tool")?.let(::wireToolToIdentifier)
    val unit = record.text("unit")
    if (section == null && tool == null && unit == null) return null
    return BadgeArrival(section, tool, unit, project)
}

/**
 * The frame's record, with the tolerance `unwrapRecord` in feature:notifications
 * already needs for the same frames: the record itself, `{"data": {...}}`,
 * `data` as a JSON **string** (the server does send that), an array's first
 * record, or `data` nested once more. A frame this could not open would make
 * the bump and the silent clear simply never fire — the failure that looks
 * like nothing at all.
 */
private fun JsonElement?.record(): JsonObject? = when (this) {
    is JsonObject -> when (val inner = this["data"]) {
        null -> this
        is JsonObject -> inner["data"]?.record() ?: inner
        is JsonArray -> inner.firstOrNull()?.record()
        is JsonPrimitive ->
            if (!inner.isString) this
            else runCatching { Json.parseToJsonElement(inner.content) }.getOrNull()?.record() ?: this
        else -> this
    }
    is JsonArray -> firstOrNull()?.record()
    is JsonPrimitive ->
        if (!isString) null
        else runCatching { Json.parseToJsonElement(content) }.getOrNull()?.record()
    else -> null
}

/**
 * One socket serves every production this device is on, so a frame can belong
 * to a production that is not open. The web refreshes its visible badges only
 * when `project_id` is the current project's; lifting the count here for
 * another production's unread put that production's badge on the open one,
 * where nothing could ever clear it. A frame that names no project keeps the
 * old behaviour — the refresh 600ms later is scoped by the request header and
 * sorts it out.
 */
private fun String?.isElsewhere(activeProjectId: String?): Boolean =
    this != null && activeProjectId != null && this != activeProjectId

private fun JsonObject.isQuiet(): Boolean {
    val reference = this["reference_data"] as? JsonObject
    return flag("silent") || reference?.flag("ignore") == true || reference?.flag("self") == true
}

private fun JsonObject.flag(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.contentOrNull?.let { it == "true" || it == "1" } == true

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.strings(key: String): Set<String> =
    (this[key] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.jsonPrimitive?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
        ?.toSet()
        .orEmpty()
