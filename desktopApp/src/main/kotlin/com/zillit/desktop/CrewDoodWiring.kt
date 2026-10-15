package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.ZillitHeaders
import com.zillit.desktop.feature.crewdood.data.CrewDoodRepositoryImpl
import com.zillit.desktop.feature.crewdood.domain.CrewDoodExport
import com.zillit.desktop.feature.crewdood.domain.CrewDoodExported
import com.zillit.desktop.feature.crewdood.ui.CrewDoodToolProvider
import com.zillit.desktop.feature.crewdood.ui.CrewDoodViewModel
import com.zillit.desktop.feature.email.data.DownloadsAttachmentStore
import io.ktor.client.request.post
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * Crew DOOD's view model, and the one thing the chart borrows from the app:
 * the DOOD workbook, which the deal-memo service builds and this lands in
 * Downloads.
 *
 * No permission gate, as the web ships none: the tile only exists once the
 * backend lists the tool, and who may edit the project calendar or export is
 * the server's own check (handoff §2.1, §6). The tool identifier is still the
 * placeholder `crew_dood_tool` on both clients.
 */
internal fun AppGraph.Ready.buildCrewDood(): CrewDoodViewModel = CrewDoodViewModel(
    repository = CrewDoodRepositoryImpl(
        apiClient,
        config,
        bus = socketEvents,
        currentProjectId = { projectContext?.context?.value?.project?.projectId },
    ),
    export = crewDoodExport(),
)

internal fun crewDoodProvider(viewModel: CrewDoodViewModel): CrewDoodToolProvider =
    CrewDoodToolProvider(viewModel)

/**
 * `POST /v2/deal-memo/deals/export/dood` — no body, an `.xlsx` back.
 *
 * Read raw rather than through `ApiClient`, which speaks envelopes only, and
 * with its own request rather than [postForBytes] because this route's two
 * **headers** are part of the answer: `X-Dood-Skipped` (deals the service left
 * off for missing dates) and `Content-Disposition` (the filename). The web
 * cannot see either until the server lists them in
 * `Access-Control-Expose-Headers` — a desktop client is not a browser, so both
 * are read here today.
 */
private fun AppGraph.Ready.crewDoodExport(): CrewDoodExport = CrewDoodExport {
    val url = "${config.baseUrl(ZillitService.DealMemo)}/api/v2/deal-memo/deals/export/dood"
    runCatching {
        val response = signedRawResponse(RequestModule.ProjectUser, url, bodyJson = null) { headers, bearer ->
            httpClient.post(url) {
                headers.forEach { (name, value) -> this.headers.append(name, value) }
                bearer?.let { this.headers.append(ZillitHeaders.AUTHORIZATION, "Bearer $it") }
                contentType(ContentType.Application.Json)
            }
        }
        val bytes = response.readRawBytes()
        // A JSON body on this route is the service declining — "nothing to
        // export", a permission key — so its own message is surfaced.
        val declined = !response.status.isSuccess() ||
            response.contentType()?.match(ContentType.Application.Json) == true
        if (declined) {
            val status = response.status.value.takeIf { !response.status.isSuccess() }
            throw CrewDoodExportDeclined(status, serverMessageOf(bytes))
        }
        val name = filenameOf(response.headers[CONTENT_DISPOSITION]) ?: defaultExportName()
        when (val saved = DownloadsAttachmentStore().save(name, bytes)) {
            is ZillitResult.Failure -> throw CrewDoodExportDeclined(null, saved.error.userMessage)
            is ZillitResult.Success -> {
                openSavedFile(saved.data)
                CrewDoodExported(skipped = response.headers[SKIPPED_HEADER]?.trim()?.toIntOrNull() ?: 0)
            }
        }
    }.fold(
        onSuccess = { ZillitResult.Success(it) },
        onFailure = { failure ->
            val declined = failure as? CrewDoodExportDeclined
            ZillitResult.Failure(
                rawExportError(declined?.httpStatus, declined?.serverMessage, failure.message),
            )
        },
    )
}

/** `filename=…` / `filename*=UTF-8''…` out of a Content-Disposition header. */
private fun filenameOf(disposition: String?): String? {
    val header = disposition?.takeIf { it.isNotBlank() } ?: return null
    val match = FILENAME.find(header) ?: return null
    return match.groupValues[1].trim().trim('"').takeIf { it.isNotBlank() }
}

/** The web's fallback: `crew-dood_<date>_<time>.xlsx`. */
private fun defaultExportName(): String {
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    val date = "${now.year}${pad(now.monthNumber)}${pad(now.day)}"
    val time = "${pad(now.hour)}${pad(now.minute)}${pad(now.second)}"
    return "crew-dood_${date}_$time.xlsx"
}

private fun pad(value: Int): String = value.toString().padStart(2, '0')

private fun serverMessageOf(bytes: ByteArray): String? = runCatching {
    com.zillit.desktop.core.network.HttpClientFactory.json
        .parseToJsonElement(bytes.decodeToString())
        .let { element -> (element as? kotlinx.serialization.json.JsonObject)?.get("message") }
        ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
}.getOrNull()?.takeIf { it.isNotBlank() }

/** [httpStatus] null means a refusal on an otherwise-successful response. */
private class CrewDoodExportDeclined(
    val httpStatus: Int?,
    val serverMessage: String?,
) : RuntimeException(serverMessage ?: "declined")

private val FILENAME = Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE)
private const val SKIPPED_HEADER = "X-Dood-Skipped"
private const val CONTENT_DISPOSITION = "Content-Disposition"
