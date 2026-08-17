package com.zillit.desktop.feature.email.data

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.jsonBody
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.zillit.desktop.feature.email.domain.FolderRepository

/**
 * Creating, renaming and deleting mail folders.
 *
 * Reading them lives with the rest of mail; writing them is its own concern and
 * its own port.
 */
class FolderRepositoryImpl(
    private val apiClient: ApiClient,
    private val config: AppConfig,
) : FolderRepository {

    private val api get() = config.apiV2(ZillitService.Email)

    override suspend fun createFolder(name: String): ZillitResult<Unit> =
        apiClient.request(
            verb = HttpVerb.Post,
            url = "${api}imap-folders",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            body = jsonBody(buildJsonObject { put("folder_name", name) }),
        ).map { }

    override suspend fun renameFolder(name: String, newName: String): ZillitResult<Unit> =
        apiClient.request(
            verb = HttpVerb.Put,
            url = "${api}imap-folders",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            body = jsonBody(
                buildJsonObject {
                    // Both names, not an id: IMAP folders are identified by name,
                    // so a rename is "this one becomes that one".
                    put("folder_name", name)
                    put("new_folder_name", newName)
                },
            ),
        ).map { }

    override suspend fun deleteFolder(name: String): ZillitResult<Unit> =
        apiClient.request(
            verb = HttpVerb.Delete,
            url = "${api}imap-folders",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            body = jsonBody(buildJsonObject { put("folder_name", name) }),
        ).map { }
}
