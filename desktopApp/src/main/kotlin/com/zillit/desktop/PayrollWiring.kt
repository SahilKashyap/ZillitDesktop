package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.network.headersFor
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.email.data.DownloadsAttachmentStore
import com.zillit.desktop.feature.payroll.data.PayrollBinaryTransport
import com.zillit.desktop.feature.payroll.data.PayrollDocumentsImpl
import com.zillit.desktop.feature.payroll.domain.PayrollFiles
import com.zillit.desktop.feature.payroll.domain.PayrollPerson
import com.zillit.desktop.feature.payroll.domain.PayrollViewer
import com.zillit.desktop.feature.payroll.ui.PayrollViewModel
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Payroll's host seams: who the viewer is (department and designation from
 * the crew list, rights from the permission grid), the crew's names, the
 * production's name for the payslip, and the rendered files — the payslip
 * PDF, the run summary and the processing workbooks, which answer bytes the
 * envelope client cannot read.
 */
internal fun AppGraph.Ready.buildPayroll(permissions: () -> ProjectPermissions) = PayrollViewModel(
    repository = payrollRepository,
    viewer = { payrollViewer(permissions()) },
    now = System::currentTimeMillis,
    people = { payrollPeople() },
    projectName = { projectContext?.context?.value?.project?.name.orEmpty() },
    documents = PayrollDocumentsImpl(config, payrollTransport()),
    files = payrollFiles(),
)

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
        )
    }

private fun AppGraph.Ready.payrollTransport(): PayrollBinaryTransport = object : PayrollBinaryTransport {
    override suspend fun post(url: String, body: JsonObject): ZillitResult<ByteArray> = postForBytes(url, body)

    /**
     * The processing workbooks are GETs. A JSON body in reply is a refusal:
     * its message goes up as the server's, so the screen translates it.
     */
    override suspend fun get(url: String): ZillitResult<ByteArray> = try {
        val headers = headerProvider.headersFor(RequestModule.ProjectUser, null, null)
        val response = httpClient.get(url) { headers.forEach { (name, value) -> this.headers.append(name, value) } }
        val bytes = response.readRawBytes()
        val json = response.contentType()?.match(ContentType.Application.Json) == true
        if (response.status.isSuccess() && !json) {
            ZillitResult.Success(bytes)
        } else {
            ZillitResult.Failure(ZillitError.Http(response.status.value, refusal(bytes)))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
        ZillitResult.Failure(ZillitError.Unknown(failure.message))
    }
}

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
