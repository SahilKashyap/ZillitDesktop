package com.zillit.desktop.feature.chat.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The production a live frame belongs to — `detail.project_id`, or the same
 * key at the top level — and null when the frame does not say.
 *
 * Every send names its production (see the envelopes in `ChatWire`) and the
 * server echoes it, which is what Android's `private_chat` handler gates on
 * (`chatData.detail.project_id == projectDetails.projectId`). One socket
 * serves every production this device is on, so without the gate a message
 * for a production that is not open lands in the open one's thread cache and
 * lifts its unread.
 */
fun frameProjectId(payload: JsonElement): String? {
    val outer = payload as? JsonObject ?: return null
    val detail = outer["detail"] as? JsonObject
    return detail?.projectId() ?: outer.projectId()
}

private fun JsonObject.projectId(): String? =
    (this["project_id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
