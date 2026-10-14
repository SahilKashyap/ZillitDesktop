package com.zillit.desktop

import com.zillit.desktop.core.badges.BadgeSections
import com.zillit.desktop.core.badges.LedgerRead
import com.zillit.desktop.core.badges.NotificationRecord
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.feature.drive.domain.DriveBadgeWire
import com.zillit.desktop.feature.drive.domain.DriveBadges
import com.zillit.desktop.feature.drive.domain.DriveUnreadRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val DRIVE_BADGE_TAG = "DriveBadges"

/**
 * The Drive's unread dots, off the ledger — the web's `getDriveBadgeCounts`
 * input (`tool=drive_label`, `section=tools_label`).
 *
 * Reads go the web's way (`DriveManagement.jsx:796-835, 1120-1140`): opening
 * a folder or a file sends `notification:read` naming it as the
 * `reference_id` — not a level read, which the server widens to the whole
 * tool (ZL-18873) — and flips the same rows locally so the dot does not wait
 * for the echo. An item deleted takes its rows with it, locally only.
 */
internal fun AppGraph.Ready.driveBadges(): DriveBadges = object : DriveBadges {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // `changes`, not `counts`: the tool's tile count is one per item, so a second
    // unread row for the same file leaves it unchanged while the dot must move.
    override val rows: Flow<List<DriveUnreadRow>> = badgeStore.changes
        .onStart { emit(Unit) }
        .map {
            badgeStore.unreadRows(BadgeSections.TOOLS)
                .filter { it.tool == DriveBadgeWire.TOOL }
                .map(NotificationRecord::toDriveRow)
        }
        .distinctUntilChanged()

    override fun readItem(id: String) {
        scope.launch {
            runCatching {
                emitSegmentRead(
                    this@driveBadges,
                    segment = DriveBadgeWire.TOOL,
                    module = DriveBadgeWire.TOOL,
                    referenceId = id,
                )
            }.onFailure { ZillitLog.w(DRIVE_BADGE_TAG) { "read of $id not sent: ${it::class.simpleName}" } }
        }
    }

    /**
     * iOS's folder open (`markDriveFolderAndChildrenRead`): the folder's rows
     * and its direct children's, here at once. The server is told item by
     * item — the folder, then each child the read turned — with the same
     * `reference_id` read as a file open; iOS's bare `notification:level:read`
     * for the folder is the one the server widens to the whole tool (ZL-18873).
     */
    override fun readFolder(folderId: String) {
        scope.launch {
            val opened = LedgerRead.Matching("drive folder $folderId") { row ->
                row.tool == DriveBadgeWire.TOOL && row.toDriveRow().isReadWithFolder(folderId)
            }
            val children = badgeStore.unreadRows(BadgeSections.TOOLS)
                .filter(opened::matches)
                .map { it.referenceId }
                .filter { it.isNotBlank() && it != folderId }
                .distinct()
            runCatching {
                emitSegmentRead(
                    this@driveBadges,
                    segment = DriveBadgeWire.TOOL,
                    module = DriveBadgeWire.TOOL,
                    referenceId = folderId,
                    read = opened,
                )
                children.forEach { child ->
                    emitSegmentRead(
                        this@driveBadges,
                        segment = DriveBadgeWire.TOOL,
                        module = DriveBadgeWire.TOOL,
                        referenceId = child,
                    )
                }
            }.onFailure { ZillitLog.w(DRIVE_BADGE_TAG) { "folder read $folderId not sent: ${it::class.simpleName}" } }
        }
    }

    override fun forget(ids: Collection<String>) {
        if (ids.isEmpty()) return
        scope.launch { ids.forEach { badgeStore.markRead(LedgerRead.Reference(it)) } }
    }
}

/**
 * A ledger row as the dots read it. `view` and `folder_id` hide in
 * `reference_data` (an object, or a JSON string of one) and the
 * depth-four-and-below ancestors in the top-level `levels[]`, none of which
 * the record lifts out.
 */
internal fun NotificationRecord.toDriveRow(): DriveUnreadRow {
    val wire = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
    val reference = wire?.get("reference_data").asObject()
    return DriveUnreadRow(
        unit = unit,
        referenceId = referenceId,
        action = action,
        level1 = level1,
        level2 = level2,
        level3 = level3,
        deeper = wire?.get("levels").levelValues(),
        view = reference?.get("view").text().orEmpty(),
        folderId = reference?.get("folder_id").text().orEmpty(),
    )
}

private fun JsonElement?.asObject(): JsonObject? = when (this) {
    is JsonObject -> this
    is JsonPrimitive ->
        if (isString) runCatching { Json.parseToJsonElement(content) as? JsonObject }.getOrNull() else null
    else -> null
}

private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it != "null" }

/** `[{"level_4": "id"}, …]` → the ids; a `"level_4:id"` string or a bare id is taken too. */
private fun JsonElement?.levelValues(): List<String> =
    (this as? JsonArray).orEmpty().flatMap { entry ->
        when (entry) {
            is JsonObject -> entry.values.mapNotNull { it.text() }
            is JsonPrimitive -> listOfNotNull(entry.text()?.substringAfterLast(':'))
            else -> emptyList()
        }
    }
