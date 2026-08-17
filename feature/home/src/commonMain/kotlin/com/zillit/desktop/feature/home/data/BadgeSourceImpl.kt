package com.zillit.desktop.feature.home.data

import com.zillit.desktop.core.badges.BadgeCounts
import com.zillit.desktop.core.badges.BadgeSource
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import kotlinx.coroutines.async
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * `GET notification/project/level/unread` — the production's unread counts.
 *
 * On the notification host, not the core one: badges are their own service
 * (`NOTIFICATION_BASE_URL`).
 *
 * ## The endpoint is a drill-down, not a tree
 *
 * The query decides what one call reports: `?group=section` answers with one
 * row per area, `?section=…&group=tool` with one row per tool inside it, and
 * so on down the levels (the web's form-signature screens drill to
 * `group=level_2`). Every grouped answer has the same flat shape — rows of
 * `{"data": "<group value>", "unread": n}` — the wire truth verified live
 * 2026-08-11 and matching the web's `LocationBadgeData`. A refresh is
 * therefore three cheap calls: areas for the rail, tools for the grid and
 * tabs, home units for the board's tab strip.
 */
class BadgeSourceImpl(
    private val apiClient: ApiClient,
    private val config: AppConfig,
) : BadgeSource {

    override suspend fun fetch(): ZillitResult<BadgeCounts> = kotlinx.coroutines.coroutineScope {
        // Three independent questions to one service — asked together. Asked
        // one after another they were most of a refresh, and a refresh runs
        // on every project open, reconnect and notification event.
        val sectionsAsk = async { rows(mapOf("group" to GROUP_BY_SECTION)) }
        val toolsAsk = async {
            rows(mapOf("section" to TOOLS_SECTION, "group" to GROUP_BY_TOOL))
        }
        val unitsAsk = async {
            rows(mapOf("section" to HOME_SECTION, "group" to GROUP_BY_UNIT))
        }
        val sections = sectionsAsk.await()
        val tools = toolsAsk.await()
        val units = unitsAsk.await()

        // All three failing is a failed refresh — the store keeps its last
        // counts. One failing is not: the other two are true right now, and
        // discarding them over the third left the whole rail stale on any
        // flaky sub-query. The failed slice keeps its previous rows instead.
        if (sections is ZillitResult.Failure && tools is ZillitResult.Failure && units is ZillitResult.Failure) {
            return@coroutineScope sections
        }
        val previous = last

        // The wire names tools by their notification label; the grid names
        // them by `project/tools` identifier. Normalised here, where the two
        // vocabularies meet.
        val toolCounts = (tools as? ZillitResult.Success)?.let { got ->
            mutableMapOf<String, Int>().also { out ->
                tallyRows(got.data).forEach { (key, unread) ->
                    val id = wireToolToIdentifier(key)
                    out[id] = (out[id] ?: 0) + unread
                }
            }
        } ?: previous.toolMap()

        val counts = BadgeCounts(
            bySection = (sections as? ZillitResult.Success)?.let { tallyRows(it.data) } ?: previous.sectionMap(),
            byTool = toolCounts,
            byUnit = (units as? ZillitResult.Success)?.let { tallyRows(it.data) } ?: previous.unitMap(),
        )
        last = counts
        ZillitLog.d(TAG) { "badge keys: sections=${counts.sectionMap().keys} tools=${counts.toolMap().keys}" }
        ZillitResult.Success(counts)
    }

    /** The last full answer, so a partly failed refresh keeps the good slices. */
    private var last: BadgeCounts = BadgeCounts.Empty

    private suspend fun rows(
        queryParameters: Map<String, String>,
    ): ZillitResult<List<UnreadRowDto>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "${config.apiV2(ZillitService.Notification)}project/level/unread",
            serializer = ListSerializer(UnreadRowDto.serializer()),
            module = RequestModule.ProjectUser,
            queryParameters = queryParameters,
        )

    private companion object {
        const val TAG = "Badges"

        /** The rail's areas: one row per `*_label`. */
        const val GROUP_BY_SECTION = "section"

        /** The tools grid and window tabs: one row per tool identifier. */
        const val TOOLS_SECTION = "tools_label"
        const val GROUP_BY_TOOL = "tool"

        /** The board's unit tabs: one row per home unit id. */
        const val HOME_SECTION = "home_label"
        const val GROUP_BY_UNIT = "unit"
    }
}

/**
 * [com.zillit.desktop.core.badges.BadgeDrilldown] over the same endpoint —
 * per-screen questions (a tool's tab counts) with the same flat-row decode
 * the store's three standing queries use.
 */
class BadgeDrilldownImpl(
    private val apiClient: ApiClient,
    private val config: AppConfig,
) : com.zillit.desktop.core.badges.BadgeDrilldown {

    override suspend fun unread(
        query: com.zillit.desktop.core.badges.BadgeDrilldownQuery,
    ): ZillitResult<Map<String, Int>> {
        val parameters = buildMap {
            put("group", query.groupBy)
            query.section?.let { put("section", it) }
            query.tool?.let { put("tool", it) }
            query.unit?.let { put("unit", it) }
            query.level1?.let { put("level_1", it) }
            query.level2?.let { put("level_2", it) }
        }
        return apiClient.request(
            verb = HttpVerb.Get,
            url = "${config.apiV2(ZillitService.Notification)}project/level/unread",
            serializer = ListSerializer(UnreadRowDto.serializer()),
            module = RequestModule.ProjectUser,
            queryParameters = parameters,
        ).let { got ->
            when (got) {
                is ZillitResult.Failure -> got
                is ZillitResult.Success -> ZillitResult.Success(tallyRows(got.data))
            }
        }
    }
}

/**
 * Folds one grouped answer into counts per key.
 *
 * Rows the server could not attribute (`data` null or blank) are dropped
 * after a log line — a count that cannot be placed cannot be drawn.
 * Negative counts are treated as zero rather than subtracted, and keys are
 * summed rather than replaced, matching the tolerance the old aggregation
 * kept: a wrong badge that heals on the next refresh beats a hidden one.
 */
internal fun tallyRows(rows: List<UnreadRowDto>): Map<String, Int> {
    val totals = mutableMapOf<String, Int>()
    var unplaced = 0

    rows.forEach { row ->
        val unread = row.unread.coerceAtLeast(0)
        if (unread == 0) return@forEach

        val key = (row.data as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        if (key == null) {
            unplaced += unread
            return@forEach
        }
        totals[key] = (totals[key] ?: 0) + unread
    }

    if (unplaced > 0) {
        // Worth one line: it is the difference between "nothing unread" and
        // "unread exists but the server did not say where".
        ZillitLog.w("Badges") { "$unplaced unread with no grouping — badges cannot be placed" }
    }

    return totals
}

/**
 * The notification service's tool key, as the grid names it.
 *
 * The wire says `location_tool_label` or `forms_and_signature_label` (the
 * web passes exactly these as its `tool=` params); `project/tools` says
 * `location_tool` and `forms_and_signature_tool`. Strip the label suffix and
 * guarantee the `_tool` one — already-bare identifiers pass through.
 */
internal fun wireToolToIdentifier(key: String): String {
    val bare = key.removeSuffix("_label")
    return if (bare.endsWith("_tool")) bare else bare + "_tool"
}

/**
 * One row of a grouped answer: the group's value and its unread total.
 *
 * [data] stays a [JsonElement] rather than a `String` because the ungrouped
 * form of the endpoint answers `{"data": null, "unread": n}` — and a decode
 * that rejects the whole call over one null row would read as a broken
 * client rather than "no unread".
 */
@Serializable
internal data class UnreadRowDto(
    @SerialName("data") val data: JsonElement? = null,
    @SerialName("unread") val unread: Int = 0,
)
