package com.zillit.desktop.feature.documentdistribution.data

import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.documentdistribution.domain.DocDistRefresh
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The realtime events Document Distribution refreshes on, by wire name —
 * the `document_distribution:*` family is bridged verbatim
 * (`listenerSocket.js:2671-2693`), and the email service's open pixel
 * arrives as `outbound:email:opened` (`listenerSocket.js:1731-1733`).
 *
 * Which destination an event refreshes follows the web page that listens:
 *
 *  - folder/document mutations → the library
 *    (`useDocumentDistribution.js:132-138`, reload page 0 silently);
 *  - `distribution:sent` / `distribution:opened` and the open pixel →
 *    history (`HistoryDrawer.jsx:726-741` and `:282-291`);
 *  - `preset:*` → the saved recipient lists (`PresetManagerModal.jsx:312-314`);
 *  - `template:*` → the email templates (`EmailTemplateManager.jsx:156-158`).
 *
 * `document_distribution:publication:added/deleted` are on the wire
 * (`listenerSocket.js:2684-2685`) and stay out — checked again 2026-09-09
 * against all three clients rather than the web alone, because that is how
 * four other exclusions in this codebase turned out to be wrong:
 *
 *  - no web page listens;
 *  - iOS declares a `publicationEvents` subject and nothing subscribes to it
 *    (`DDHistoryView.swift:1111`);
 *  - Android emits `DocDistSocketEvent.PublicationAdded` onto its bus and
 *    nothing collects it.
 *
 * The desktop's own reason is the stronger one: publications are not a
 * standing list here. `alreadyPublished` is read when the publish dialog
 * picks a target and lives only as long as that dialog, so there is nothing
 * on screen to go stale — and refetching under somebody mid-publish would be
 * a change for the worse.
 */
internal val DOC_DIST_REFRESH_BY_EVENT: Map<SocketEventName, DocDistRefresh> = buildMap {
    listOf(
        "document_distribution:folder:added",
        "document_distribution:folder:updated",
        "document_distribution:folder:deleted",
        "document_distribution:folder:moved",
        "document_distribution:document:added",
        "document_distribution:document:deleted",
        "document_distribution:document:moved",
    ).forEach { put(SocketEventName(it), DocDistRefresh.Library) }
    put(SocketEventName("document_distribution:distribution:sent"), DocDistRefresh.History)
    put(SocketEventName("document_distribution:distribution:opened"), DocDistRefresh.History)
    put(SocketEventName("outbound:email:opened"), DocDistRefresh.History)
    put(SocketEventName("document_distribution:preset:added"), DocDistRefresh.Lists)
    put(SocketEventName("document_distribution:preset:updated"), DocDistRefresh.Lists)
    put(SocketEventName("document_distribution:preset:deleted"), DocDistRefresh.Lists)
    put(SocketEventName("document_distribution:template:added"), DocDistRefresh.Templates)
    put(SocketEventName("document_distribution:template:updated"), DocDistRefresh.Templates)
    put(SocketEventName("document_distribution:template:deleted"), DocDistRefresh.Templates)
}

/**
 * The refresh pulses for a bus, or empty without one. Conflated: a send
 * emits document and distribution events back to back and one refetch per
 * destination answers all.
 */
internal fun docDistRefreshes(bus: SocketEventBus?): Flow<DocDistRefresh> =
    bus?.onAny(DOC_DIST_REFRESH_BY_EVENT.keys)
        ?.mapNotNull { DOC_DIST_REFRESH_BY_EVENT[it.event] }
        ?.conflate()
        ?: emptyFlow()

/**
 * The two deletes that retire badges, and the key each names its entity
 * under — iOS `registerDDDelete` (`ProjectObserver.swift:8740-8773`):
 * `folder._id`, `document._id`.
 *
 * Public because the sweep is the ledger's business, not this screen's: it
 * runs app-wide, on the deleting device too, whether or not the tool is
 * open (see [docDistDeletedIds]).
 */
val DOC_DIST_DELETE_EVENTS: Map<SocketEventName, String> = mapOf(
    SocketEventName("document_distribution:folder:deleted") to "folder",
    SocketEventName("document_distribution:document:deleted") to "document",
)

/**
 * The ids one delete frame names. [record] is the frame's record, already
 * out of any `data` envelope. The entity is an object (`{"_id": …}`, the
 * shape iOS reads), and is also taken as a list of them or a bare id — a
 * sweep that missed a shape would leave the deleted folder's badges for good.
 */
fun docDistDeletedIds(event: SocketEventName, record: JsonObject): Set<String> {
    val key = DOC_DIST_DELETE_EVENTS[event] ?: return emptySet()
    return when (val named = record[key]) {
        is JsonObject -> setOfNotNull(named.entityId())
        is JsonArray -> named.mapNotNull { (it as? JsonObject)?.entityId() ?: it.idText() }.toSet()
        is JsonPrimitive -> setOfNotNull(named.idText())
        else -> emptySet()
    }
}

private fun JsonObject.entityId(): String? = this["_id"].idText() ?: this["id"].idText()

private fun JsonElement?.idText(): String? =
    (this as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

/** The one doc-dist event that carries state rather than a stale-listing hint. */
internal val WATERMARK_SETTINGS_UPDATED = SocketEventName("document_distribution:watermark_settings:updated")

/**
 * The project's watermark defaults as other devices save them.
 *
 * Every device the saver's user has gets the event, the saving one included;
 * the spec says to drop it when `data.device_id` is your own, because the PUT
 * that caused it already answered the same object. Filtered here, at the
 * seam, so the view model only ever sees news. A frame without the settings
 * object is dropped too — replacing the cache with the built-in values on a
 * malformed event would undo a real save.
 */
internal fun watermarkSettingsUpdates(
    bus: SocketEventBus?,
    selfDeviceId: () -> String?,
): Flow<WatermarkSettings> =
    bus?.on(WATERMARK_SETTINGS_UPDATED, WatermarkSettingsEventDto.serializer())
        ?.filter { event -> event.deviceId.isNullOrBlank() || event.deviceId != selfDeviceId() }
        ?.mapNotNull { event -> event.settings?.toDomain() }
        ?: emptyFlow()
