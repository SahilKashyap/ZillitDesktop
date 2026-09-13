@file:Suppress("TooManyFunctions") // One override per route.

package com.zillit.desktop.feature.esignature

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.esignature.domain.AuditEntry
import com.zillit.desktop.feature.esignature.domain.BulkJob
import com.zillit.desktop.feature.esignature.domain.Envelope
import com.zillit.desktop.feature.esignature.domain.EnvelopeDraft
import com.zillit.desktop.feature.esignature.domain.EnvelopeField
import com.zillit.desktop.feature.esignature.domain.EnvelopeRecipient
import com.zillit.desktop.feature.esignature.domain.EnvelopeScope
import com.zillit.desktop.feature.esignature.domain.EnvelopeStatus
import com.zillit.desktop.feature.esignature.domain.EnvelopeTemplate
import com.zillit.desktop.feature.esignature.domain.EsignFileTransfer
import com.zillit.desktop.feature.esignature.domain.EsignPage
import com.zillit.desktop.feature.esignature.domain.EsignPdf
import com.zillit.desktop.feature.esignature.domain.EsignRepository
import com.zillit.desktop.feature.esignature.domain.EsignViewer
import com.zillit.desktop.feature.esignature.domain.FieldType
import com.zillit.desktop.feature.esignature.domain.SavedSignature
import com.zillit.desktop.feature.esignature.domain.SignedField
import com.zillit.desktop.feature.esignature.domain.SignerOptionLike
import com.zillit.desktop.feature.esignature.domain.StoredFile
import com.zillit.desktop.feature.esignature.domain.TemplateDraft
import com.zillit.desktop.feature.esignature.ui.EsignViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A repository that answers from memory and remembers what it was asked —
 * enough for every flow test to drive the view model without a network.
 */
internal class FakeEsignRepo(override val refreshes: Flow<Unit> = emptyFlow()) : EsignRepository {
    var listLoads = 0
    val envelopesById = mutableMapOf<String, Envelope>()
    var sentBuckets: Map<String, List<Envelope>> = emptyMap()
    var receivedBuckets: Map<String, List<Envelope>> = emptyMap()
    val created = mutableListOf<EnvelopeDraft>()
    val updated = mutableListOf<Pair<String, EnvelopeDraft>>()
    val sent = mutableListOf<String>()
    val signed = mutableListOf<Pair<String, List<SignedField>>>()
    val accepted = mutableListOf<String>()
    val viewed = mutableListOf<String>()
    val declined = mutableListOf<Pair<String, String>>()
    val voided = mutableListOf<Pair<String, String>>()
    val reminded = mutableListOf<Pair<String, String?>>()
    val deleted = mutableListOf<String>()
    val templates = mutableListOf<EnvelopeTemplate>()
    val templateDrafts = mutableListOf<TemplateDraft>()
    val bulkStarts = mutableListOf<Triple<String, String, String?>>()
    var marks: List<SavedSignature> = emptyList()
    var acceptFails = false
    var nextId = 100

    override suspend fun envelopes(
        scope: EnvelopeScope,
        bucket: String,
        userId: String?,
    ): ZillitResult<List<Envelope>> {
        listLoads++
        val rows = if (scope == EnvelopeScope.Sent) sentBuckets[bucket] else receivedBuckets[bucket]
        return ZillitResult.Success(rows.orEmpty())
    }

    override suspend fun envelope(id: String): ZillitResult<Envelope> =
        envelopesById[id]?.let { ZillitResult.Success(it) }
            ?: ZillitResult.Failure(ZillitError.Validation("no envelope $id"))

    override suspend fun create(draft: EnvelopeDraft): ZillitResult<Envelope> {
        created += draft
        val id = "e${nextId++}"
        val envelope = envelopeOf(id, draft)
        envelopesById[id] = envelope
        return ZillitResult.Success(envelope)
    }

    override suspend fun update(envelopeId: String, draft: EnvelopeDraft): ZillitResult<Envelope> {
        updated += envelopeId to draft
        val envelope = envelopeOf(envelopeId, draft)
        envelopesById[envelopeId] = envelope
        return ZillitResult.Success(envelope)
    }

    private fun envelopeOf(id: String, draft: EnvelopeDraft) = Envelope(
        id = id,
        title = draft.title,
        status = EnvelopeStatus.Draft,
        recipients = draft.recipients,
        fields = draft.fields,
        document = draft.document,
    )

    override suspend fun send(envelopeId: String): ZillitResult<Unit> {
        sent += envelopeId
        return ZillitResult.Success(Unit)
    }

    override suspend fun deleteDraft(envelopeId: String): ZillitResult<Unit> {
        deleted += envelopeId
        return ZillitResult.Success(Unit)
    }

    override suspend fun remind(envelopeId: String, recipientId: String?): ZillitResult<Unit> {
        reminded += envelopeId to recipientId
        return ZillitResult.Success(Unit)
    }

    override suspend fun markViewed(envelopeId: String): ZillitResult<Unit> {
        viewed += envelopeId
        return ZillitResult.Success(Unit)
    }

    override suspend fun acceptTerms(envelopeId: String): ZillitResult<Unit> {
        if (acceptFails) return ZillitResult.Failure(ZillitError.Validation("down"))
        accepted += envelopeId
        return ZillitResult.Success(Unit)
    }

    override suspend fun voidEnvelope(envelopeId: String, reason: String): ZillitResult<Unit> {
        voided += envelopeId to reason
        return ZillitResult.Success(Unit)
    }

    override suspend fun sign(envelopeId: String, answers: List<SignedField>): ZillitResult<Unit> {
        signed += envelopeId to answers
        return ZillitResult.Success(Unit)
    }

    override suspend fun decline(envelopeId: String, reason: String): ZillitResult<Unit> {
        declined += envelopeId to reason
        return ZillitResult.Success(Unit)
    }

    override suspend fun auditTrail(envelopeId: String): ZillitResult<List<AuditEntry>> =
        ZillitResult.Success(emptyList())

    override suspend fun auditTrailPdf(envelopeId: String): ZillitResult<ByteArray> =
        ZillitResult.Success(byteArrayOf(1))

    override suspend fun savedSignatures(): ZillitResult<List<SavedSignature>> = ZillitResult.Success(marks)

    override suspend fun saveSignature(isSignature: Boolean, image: StoredFile): ZillitResult<Unit> {
        marks = marks + SavedSignature(id = "m${nextId++}", isSignature = isSignature, image = image)
        return ZillitResult.Success(Unit)
    }

    override suspend fun deleteSavedSignature(id: String): ZillitResult<Unit> {
        marks = marks.filter { it.id != id }
        return ZillitResult.Success(Unit)
    }

    override suspend fun templates(): ZillitResult<List<EnvelopeTemplate>> = ZillitResult.Success(templates.toList())

    override suspend fun templateCategories(): ZillitResult<List<String>> =
        ZillitResult.Success(templates.map { it.category }.distinct())

    override suspend fun createTemplate(draft: TemplateDraft): ZillitResult<EnvelopeTemplate> {
        templateDrafts += draft
        val template = templateOf("t${nextId++}", draft)
        templates += template
        return ZillitResult.Success(template)
    }

    override suspend fun updateTemplate(templateId: String, draft: TemplateDraft): ZillitResult<EnvelopeTemplate> {
        templateDrafts += draft
        val template = templateOf(templateId, draft)
        templates.replaceAll { if (it.id == templateId) template else it }
        return ZillitResult.Success(template)
    }

    private fun templateOf(id: String, draft: TemplateDraft) = EnvelopeTemplate(
        id = id,
        name = draft.name,
        category = draft.category,
        documents = draft.documents,
        recipients = draft.slots,
        fields = draft.fields,
        settings = draft.settings,
    )

    override suspend fun deleteTemplate(templateId: String): ZillitResult<Unit> {
        templates.removeAll { it.id == templateId }
        return ZillitResult.Success(Unit)
    }

    override suspend fun startBulkSend(templateId: String, csvText: String, name: String?): ZillitResult<BulkJob> {
        bulkStarts += Triple(templateId, csvText, name)
        val rows = csvText.lines().count { it.isNotBlank() } - 1
        return ZillitResult.Success(BulkJob(id = "job1", status = "running", totalRows = rows))
    }

    override suspend fun bulkJobs(): ZillitResult<List<BulkJob>> = ZillitResult.Success(emptyList())

    override suspend fun bulkJob(jobId: String): ZillitResult<BulkJob> =
        ZillitResult.Success(BulkJob(id = jobId, status = "completed"))

    override suspend fun retryFailedRows(jobId: String): ZillitResult<Unit> = ZillitResult.Success(Unit)

    override suspend fun remindOutstanding(jobId: String): ZillitResult<Int> = ZillitResult.Success(0)
}

internal object FakeTransfer : EsignFileTransfer {
    override suspend fun store(fileName: String, contentType: String, bytes: ByteArray) =
        ZillitResult.Success(StoredFile(media = "s3/$fileName", name = fileName))
    override suspend fun fetch(file: StoredFile) = ZillitResult.Success(byteArrayOf(1, 2, 3))
    override suspend fun land(fileName: String, bytes: ByteArray) = ZillitResult.Success(Unit)
}

/** Answers one 612×792pt page per call, without touching PDFBox. */
internal object FakePdf : EsignPdf {
    override fun renderPages(pdf: ByteArray, targetWidthPx: Int) = ZillitResult.Success(
        listOf(
            EsignPage(
                page = 1,
                imageBytes = byteArrayOf(0),
                widthPx = 612,
                heightPx = 792,
                widthPt = 612.0,
                heightPt = 792.0,
            ),
        ),
    )
    override fun rasterizeStrokes(strokes: List<List<Pair<Float, Float>>>, width: Int, height: Int) =
        ZillitResult.Success(byteArrayOf(9))
    override fun rasterizeText(text: String, fontKey: String, width: Int, height: Int) =
        ZillitResult.Success(byteArrayOf(8))
}

internal fun testModel(
    repo: FakeEsignRepo,
    viewer: EsignViewer = EsignViewer(canView = true, canPost = true, ready = true),
    userId: String = "u1",
    options: List<SignerOptionLike> = listOf(
        SignerOptionLike("u1", "Ada Lovelace (you)", "ada@x.io"),
        SignerOptionLike("u2", "Bob Stone", "bob@x.io"),
        SignerOptionLike("u3", "Cy Nomail", ""),
    ),
): EsignViewModel = EsignViewModel(
    repository = repo,
    transfer = FakeTransfer,
    pdf = FakePdf,
    resolveViewer = { viewer },
    currentUserId = { userId },
    currentUserName = { "Ada Lovelace" },
    signerOptions = { options },
    newId = { "id-${repo.nextId++}" },
    currentUserEmail = { "ada@x.io" },
    now = { 1_700_000_000_000L },
)

internal fun signer(id: String, userId: String, status: String = "sent", index: Int = 0) = EnvelopeRecipient(
    id = id,
    userId = userId,
    name = "User $userId",
    email = "$userId@x.io",
    role = "signer",
    routingOrder = index + 1,
    status = status,
)

internal fun field(
    id: String,
    type: FieldType,
    recipientId: String,
    page: Int = 1,
    autoInitial: Boolean = false,
    required: Boolean = true,
) = EnvelopeField(
    id = id,
    type = type,
    page = page,
    x = 50.0,
    y = 50.0 + id.hashCode() % 300,
    recipientId = recipientId,
    autoInitial = autoInitial,
    required = required,
)
