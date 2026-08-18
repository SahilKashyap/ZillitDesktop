package com.zillit.desktop.feature.documentdistribution.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.jsonBody
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * A file that already lives in the production's storage, described for the
 * library — what another tool hands over when it publishes into Document
 * Distribution. Every field but the first three is optional and omitted when
 * blank or zero, exactly as the phones build the body.
 */
data class FromToolFile(
    /** Where it files: `["Call Sheet"]`, `["Info"]` — the tool's name, no date segment. */
    val folderPath: List<String>,
    /** The stored name, with its extension. */
    val name: String,
    /** The raw storage key. */
    val media: String,
    val bucket: String? = null,
    val region: String? = null,
    val contentType: String? = null,
    val contentSubtype: String? = null,
    val thumbnail: String? = null,
    val caption: String? = null,
    val fileSizeBytes: Long = 0,
    val durationMillis: Long = 0,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    /** `yyyy-MM-dd` — the day the file was posted; null lets the server say today. */
    val folderDate: String? = null,
) {
    /** Never prints the name or key — a production's file names are content. */
    override fun toString(): String = "FromToolFile(path=$folderPath, bytes=$fileSizeBytes)"
}

/**
 * `POST document-distribution/documents/from-tool` — publishes a file already
 * in storage into the library **without re-uploading it**: the body carries
 * the storage keys and the server registers the document (Android
 * `FromToolDistributionRepository.kt:88-154`, iOS `DDToolPublisher.publish`,
 * web `publishFromTool`). Folder resolution is by name, case-insensitive, so
 * publishing twice reuses the folder.
 *
 * Its own small class rather than a method on the library repository: the
 * callers are other tools' boards, which should not have to know the whole
 * library API exists.
 */
class FromToolPublisher(
    private val apiClient: ApiClient,
    config: AppConfig,
) {
    private val base = "${config.baseUrl(ZillitService.DocDistribution)}/api/v2/document-distribution"

    /** Returns the server's document id. */
    suspend fun publish(file: FromToolFile): ZillitResult<String> {
        // The two things the server hard-fails on, said before the round trip.
        if (file.name.isBlank() || !file.name.contains('.')) {
            return ZillitResult.Failure(
                ZillitError.Storage(
                    technical = "from-tool publish needs a file name with an extension",
                    userMessage = "This file has no name to publish under.",
                ),
            )
        }
        if (file.media.isBlank()) {
            return ZillitResult.Failure(
                ZillitError.Storage(
                    technical = "from-tool publish needs the storage key",
                    userMessage = "This file is not in storage yet.",
                ),
            )
        }
        return apiClient.request(
            verb = HttpVerb.Post,
            url = "$base/documents/from-tool",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            body = jsonBody(file.toBody()),
        ).map { data ->
            ((data as? JsonObject)?.get("_id") as? JsonPrimitive)?.contentOrNull.orEmpty()
        }
    }
}

/**
 * The wire body, field for field as the phones send it. Blank strings and
 * zero numbers are left out rather than sent as null — the platform's body
 * hash rejects a JSON null (Android's comment at `:109-112`).
 */
internal fun FromToolFile.toBody(): JsonObject = buildJsonObject {
    putJsonArray("folder_path") { folderPath.filter { it.isNotBlank() }.forEach { add(JsonPrimitive(it)) } }
    put("original_name", name)
    // Mirrors `original_name` — the server keeps both (ZL-19801).
    put("name", name)
    put("media", media)
    bucket?.takeIf { it.isNotBlank() }?.let { put("bucket", it) }
    region?.takeIf { it.isNotBlank() }?.let { put("region", it) }
    contentType?.takeIf { it.isNotBlank() }?.let { put("content_type", it) }
    contentSubtype?.takeIf { it.isNotBlank() }?.let { put("content_subtype", it) }
    thumbnail?.takeIf { it.isNotBlank() }?.let { put("thumbnail", it) }
    caption?.takeIf { it.isNotBlank() }?.let { put("caption", it) }
    // A string on the wire, as everywhere the file size travels.
    if (fileSizeBytes > 0) put("file_size", fileSizeBytes.toString())
    if (durationMillis > 0) put("duration", durationMillis)
    if (heightPx > 0) put("height", heightPx)
    if (widthPx > 0) put("width", widthPx)
    put("media_type", MEDIA_TYPE_DOCUMENT)
    folderDate?.takeIf { it.isNotBlank() }?.let { put("folder_date", it) }
}

private const val MEDIA_TYPE_DOCUMENT = "document"
