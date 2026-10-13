package com.zillit.desktop.feature.chat.data

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.CallOptions
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.chat.domain.ChatMessage
import com.zillit.desktop.feature.chat.domain.GroupRoom
import kotlinx.serialization.json.JsonElement

/**
 * What the File Cabinet reads of group chat, kept out of [ChatRepositoryImpl]
 * so the repository does not grow a second job: the archived lines of a room
 * and the list of rooms to pick from. Nothing here writes, caches or touches
 * the thread maps the Chats tab opens from.
 */
internal class CabinetChatSource(
    private val apiClient: ApiClient,
    private val config: AppConfig,
    private val myUserId: () -> String?,
    private val decrypt: (String) -> String?,
    private val options: () -> CallOptions,
    private val listingParameters: () -> Map<String, Any?>,
) {
    /**
     * `GET group-chat/archive/{room}/{before}/previous?isFileCabinet=true` —
     * the web's `getArchivedMessage({filecabinet: true})`
     * (`api/cnc/chat/cncAPI.js:97-109`), ascending by time. The flag is the
     * only parameter the web sends; the listing's `cnc_section` pair is not
     * part of this route.
     */
    suspend fun history(roomId: String, beforeMillis: Long): ZillitResult<List<ChatMessage>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "${config.apiV2(ZillitService.Chat)}group-chat/archive/$roomId/$beforeMillis/previous",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            queryParameters = mapOf("isFileCabinet" to "true"),
            options = options(),
        ).map { body ->
            chatRows(body)
                .mapNotNull { readChatMessage(it, myUserId(), decrypt, isGroup = true) }
                .sortedBy(ChatMessage::timestampMillis)
        }

    /** The same `GET chat-room` the Chats tab lists, with the rooms it hides left in. */
    suspend fun rooms(): ZillitResult<List<GroupRoom>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "${config.apiV2(ZillitService.Chat)}chat-room",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            options = options(),
            queryParameters = listingParameters(),
        ).map { roomsFrom(it, includeDisabled = true) }
}
