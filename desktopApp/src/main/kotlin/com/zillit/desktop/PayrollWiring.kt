package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.asFailure
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.S3Presigner
import com.zillit.desktop.core.network.ZillitHeaders
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.email.data.DownloadsAttachmentStore
import com.zillit.desktop.feature.payroll.data.PayrollBinaryTransport
import com.zillit.desktop.feature.payroll.data.PayrollDocumentsImpl
import com.zillit.desktop.feature.payroll.data.payrollProducerSeams
import com.zillit.desktop.feature.payroll.domain.PayrollExportFile
import com.zillit.desktop.feature.payroll.domain.PayrollFiles
import com.zillit.desktop.feature.payroll.domain.PayrollPerson
import com.zillit.desktop.feature.payroll.domain.PayrollViewer
import com.zillit.desktop.feature.payroll.ui.PayrollViewModel
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Payroll's host seams: who the viewer is (department and designation from
 * the crew list, rights from the permission grid), the crew's names, the
 * production's name for the payslip, and the rendered files — the payslip
 * PDF, the run summary and the processing workbooks, which answer bytes the
 * envelope client cannot read.
 */
internal fun AppGraph.Ready.buildPayroll(permissions: () -> ProjectPermissions): PayrollViewModel {
    val transport = payrollTransport()
    // The pay engine is a JavaScript bundle the payroll service publishes;
    // Rhino runs it here so the desktop's overtime money is the same
    // arithmetic every other client's is. See RhinoScriptHost.
    val seams = payrollProducerSeams(apiClient, config, transport, RhinoScriptHost())
    return PayrollViewModel(
        repository = payrollRepository,
        viewer = { payrollViewer(permissions()) },
        now = System::currentTimeMillis,
        people = { payrollPeople() },
        projectName = { projectContext?.context?.value?.project?.name.orEmpty() },
        documents = PayrollDocumentsImpl(config, transport),
        files = payrollFiles(),
        seams = seams,
    )
}

private fun AppGraph.Ready.payrollViewer(permissions: ProjectPermissions): PayrollViewer {
    val context = projectContext?.context?.value
    val me = context?.user(context.profile?.userId)
    return PayrollViewer(
        userId = context?.profile?.userId.orEmpty(),
        departmentIdentifier = me?.department,
        designationIdentifier = me?.designation,
        canView = permissions.canView(PAYROLL_TOOL),
        rightsLoaded = permissions !== ProjectPermissions.Empty,
    )
}

private fun AppGraph.Ready.payrollPeople(): Map<String, PayrollPerson> =
    projectContext?.context?.value?.users.orEmpty().associate { user ->
        user.userId to PayrollPerson(
            userId = user.userId,
            fullName = user.fullName,
            department = user.department,
            designation = user.designation,
            hasDeal = user.signingRequired,
        )
    }

private fun AppGraph.Ready.payrollTransport(): PayrollBinaryTransport = object : PayrollBinaryTransport {
    override suspend fun post(url: String, body: JsonObject): ZillitResult<ByteArray> = postForBytes(url, body)

    /**
     * The processing workbooks are GETs. A JSON body in reply is a refusal:
     * its message goes up as the server's, so the screen translates it.
     */
    override suspend fun get(url: String): ZillitResult<ByteArray> = try {
        val response = signedRawResponse(RequestModule.ProjectUser, url, bodyJson = null) { headers, bearer ->
            httpClient.get(url) {
                headers.forEach { (name, value) -> this.headers.append(name, value) }
                bearer?.let { this.headers.append(ZillitHeaders.AUTHORIZATION, "Bearer $it") }
            }
        }
        val bytes = response.readRawBytes()
        val json = response.contentType()?.match(ContentType.Application.Json) == true
        when {
            response.status.isSuccess() && !json -> ZillitResult.Success(bytes)
            !response.status.isSuccess() ->
                PayrollExportDeclined(response.status.value, refusal(bytes)).toPayrollExportError().asFailure()
            // A refusal wrapped in an otherwise-successful response — no real status to blame.
            else -> PayrollExportDeclined(httpStatus = null, refusal(bytes)).toPayrollExportError().asFailure()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
        ZillitResult.Failure(ZillitError.Unknown(failure.message))
    }

    override suspend fun postForFile(
        url: String,
        body: JsonObject,
        requestedFormat: String,
    ): ZillitResult<PayrollExportFile> = postForExportFile(url, body, requestedFormat)
}

/**
 * The run summary's own route (2026-09-25): the service answers either the
 * file itself, a success envelope pointing at an S3 object (a CSV, or a ZIP
 * once the run exports as several files), or a real refusal. Kept off
 * [postForBytes] deliberately — that helper is shared by every other module's
 * exports, none of which answer this way, and folding the S3 branch into it
 * would be a behaviour change for callers that never asked for one. The auth
 * decision itself (token vs `moduledata`, the one 401 retry) is shared, via
 * [signedRawResponse] — this is not a second copy of that.
 */
private suspend fun AppGraph.Ready.postForExportFile(
    url: String,
    body: JsonObject,
    requestedFormat: String,
): ZillitResult<PayrollExportFile> {
    val bodyJson = HttpClientFactory.json.encodeToString(JsonElement.serializer(), body)
    return runCatching {
        val response = signedRawResponse(RequestModule.ProjectUser, url, bodyJson) { headers, bearer ->
            httpClient.post(url) {
                headers.forEach { (name, value) -> this.headers.append(name, value) }
                bearer?.let { this.headers.append(ZillitHeaders.AUTHORIZATION, "Bearer $it") }
                contentType(ContentType.Application.Json)
                setBody(bodyJson)
            }
        }
        val bytes = response.readRawBytes()
        val isJson = response.contentType()?.match(ContentType.Application.Json) == true
        when {
            !response.status.isSuccess() -> throw PayrollExportDeclined(response.status.value, refusal(bytes))
            !isJson -> PayrollExportFile(bytes, extensionOfMime(response.contentType()) ?: requestedFormat)
            else -> exportFileFromEnvelope(bytes, requestedFormat)
        }
    }.fold(
        onSuccess = { ZillitResult.Success(it) },
        onFailure = { failure ->
            ZillitLog.w("PayrollExport") {
                "postForExportFile failed: ${failure::class.simpleName}: ${failure.message}"
            }
            ZillitResult.Failure(failure.toPayrollExportError())
        },
    )
}

/** [rawExportError] over [PayrollExportDeclined] — see that function for why. */
private fun Throwable.toPayrollExportError(): ZillitError {
    val declined = this as? PayrollExportDeclined
    return rawExportError(declined?.httpStatus, declined?.serverMessage, message ?: "Export failed")
}

/**
 * A JSON body on the export route: either the service declining ("no
 * timecards in this run") or a success envelope naming an S3 object — the
 * shape a large run summary comes back as instead of streaming. Only the
 * second is recoverable here.
 */
private suspend fun AppGraph.Ready.exportFileFromEnvelope(
    bytes: ByteArray,
    requestedFormat: String,
): PayrollExportFile {
    val envelope = runCatching {
        HttpClientFactory.json.parseToJsonElement(bytes.decodeToString()).jsonObject
    }.getOrNull() ?: throw PayrollExportDeclined(technical = "The service returned no file")
    val attachment = envelope.exportAttachment() ?: throw PayrollExportDeclined(
        serverMessage = envelope["message"]?.jsonPrimitive?.contentOrNull,
        technical = "The service returned no file",
    )
    val ext = attachment.contentSubtype?.takeIf { it.isNotBlank() } ?: requestedFormat
    return PayrollExportFile(fetchS3Object(attachment), ext)
}

private data class ExportAttachment(
    val media: String,
    val bucket: String,
    val region: String,
    val contentSubtype: String?,
)

/** A success envelope naming an S3 object — `status: 1` with a non-blank media/bucket/region triple. */
@Suppress("ReturnCount") // Guard clauses over an optional chain; each is one missing field.
private fun JsonObject.exportAttachment(): ExportAttachment? {
    if (this["status"]?.jsonPrimitive?.intOrNull != 1) return null
    val data = this["data"]?.jsonObject ?: return null
    val media = data["media"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    val bucket = data["bucket"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    val region = data["region"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    return ExportAttachment(media, bucket, region, data["content_subtype"]?.jsonPrimitive?.contentOrNull)
}

private suspend fun AppGraph.Ready.fetchS3Object(attachment: ExportAttachment): ByteArray {
    val presigner = S3Presigner(credentials = { awsKeyPair(remoteConfigRepository) })
    val fileUrl = presigner.presignedGet(bucket = attachment.bucket, region = attachment.region, key = attachment.media)
        ?: throw PayrollExportDeclined(technical = "The export file could not be reached")
    val response = httpClient.get(fileUrl)
    if (!response.status.isSuccess()) throw PayrollExportDeclined(technical = "The export file could not be reached")
    return response.readRawBytes()
}

/** `text/csv` → `csv`; the category an S3 attachment carries (`content_type: "document"`) is not a MIME. */
private fun extensionOfMime(type: ContentType?): String? = when {
    type == null -> null
    type.match(ContentType("application", "zip")) -> "zip"
    type.match(ContentType("application", "x-zip-compressed")) -> "zip"
    type.match(ContentType.Text.CSV) -> "csv"
    type.match(ContentType.Application.Pdf) -> "pdf"
    type.match(ContentType("application", "vnd.openxmlformats-officedocument.spreadsheetml.sheet")) -> "xlsx"
    else -> null
}

/** [httpStatus] null means a soft decline on an otherwise-successful response. */
private class PayrollExportDeclined(
    val httpStatus: Int? = null,
    val serverMessage: String? = null,
    technical: String? = null,
) : RuntimeException(serverMessage ?: technical ?: "declined${httpStatus?.let { " ($it)" } ?: ""}")

private fun refusal(bytes: ByteArray): String? = runCatching {
    HttpClientFactory.json.parseToJsonElement(bytes.decodeToString()).jsonObject["message"]?.jsonPrimitive?.content
}.getOrNull()?.takeIf { it.isNotBlank() }

/** Saved to Downloads, then handed to the OS — the cost report's and the hub's route for exports. */
private fun payrollFiles(): PayrollFiles {
    val store = DownloadsAttachmentStore()
    return PayrollFiles { fileName, bytes ->
        when (val saved = store.save(fileName, bytes)) {
            is ZillitResult.Success -> {
                openSavedFile(saved.data)
                ZillitResult.Success(Unit)
            }
            is ZillitResult.Failure -> saved
        }
    }
}

/** The payroll tool's identifier on the permission grid (`usePayrollRights.js`). */
private const val PAYROLL_TOOL = "payroll_tool"
