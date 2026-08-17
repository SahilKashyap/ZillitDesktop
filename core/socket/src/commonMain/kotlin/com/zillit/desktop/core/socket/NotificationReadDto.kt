package com.zillit.desktop.core.socket

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The payload of [ZillitSocketEvents.Badges.NotificationRead] and
 * [ZillitSocketEvents.Badges.NotificationLevelRead].
 *
 * Field-for-field Android's `ReadOrDeleteNotificationRequestModel` (whose
 * Gson encoding omits nulls — `explicitNulls = false` reproduces that), plus
 * `module`, which Android does not send but the web does and the server
 * accepts: it rode the desktop's first working board-read and is kept so a
 * proven emit does not change shape underneath a working feature.
 *
 * [segment] scopes the read: a home unit id, `"email_label"`, a tool label.
 * The `level_*` trio narrows a level read the way the tools' tab badges are
 * keyed; [referenceId] narrows to one entity (one email, one PO).
 */
@Serializable
data class NotificationReadDto(
    @SerialName("project_id") val projectId: String,
    @SerialName("segment") val segment: String? = null,
    @SerialName("module") val module: String? = null,
    @SerialName("timestamp") val timestamp: Long? = null,
    @SerialName("reference_id") val referenceId: String? = null,
    @SerialName("section") val section: String? = null,
    @SerialName("tool") val tool: String? = null,
    @SerialName("unit") val unit: String? = null,
    @SerialName("level_1") val level1: String? = null,
    @SerialName("level_2") val level2: String? = null,
    @SerialName("level_3") val level3: String? = null,
    @SerialName("action") val action: String? = null,
    /** Which client read it — iOS sends `"I"`, Android `"A"`; the desktop is `"D"`. */
    @SerialName("platform") val platform: String = "D",
)
