package com.zillit.desktop.feature.sos

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.sos.domain.ExternalContactDraft
import com.zillit.desktop.feature.sos.domain.IsdCode
import com.zillit.desktop.feature.sos.domain.SosAlert
import com.zillit.desktop.feature.sos.domain.SosContact
import com.zillit.desktop.feature.sos.domain.SosContactKind
import com.zillit.desktop.feature.sos.domain.SosFix
import com.zillit.desktop.feature.sos.domain.SosRelation
import com.zillit.desktop.feature.sos.domain.SosRepository

/**
 * A whole SOS backend in memory.
 *
 * Pages the alert feed the way the service does — rows strictly older than the
 * cursor, newest first, capped at [pageSize] — so a test can drive the real
 * paging logic rather than a stub that always answers everything.
 */
internal class FakeSosRepository : SosRepository {

    val alerts = mutableListOf<SosAlert>()
    val contactRows = mutableListOf<SosContact>()
    var pageSize = 50

    val alertCursors = mutableListOf<Pair<Long, Boolean>>()
    val reads = mutableListOf<Long>()
    val sent = mutableListOf<SosFix?>()
    val deletedAlerts = mutableListOf<String>()
    var deletedAllAt: Long? = null
    val entryTypesAsked = mutableListOf<String>()
    val created = mutableListOf<String>()
    val updated = mutableListOf<Pair<String, String>>()
    val deletedContacts = mutableListOf<String>()

    var failNext: ZillitError? = null

    override suspend fun alerts(
        cursorMillis: Long,
        older: Boolean,
        limit: Int,
        newest: Boolean,
    ): ZillitResult<List<SosAlert>> {
        alertCursors += cursorMillis to newest
        val page = alerts
            .filter { if (older) it.updatedMillis < cursorMillis else it.updatedMillis > cursorMillis }
            .sortedByDescending { it.updatedMillis }
            .take(minOf(limit, pageSize))
        return ZillitResult.Success(page)
    }

    override suspend fun markRead(timestampMillis: Long): ZillitResult<Unit> {
        reads += timestampMillis
        return ZillitResult.Success(Unit)
    }

    override suspend fun sendAlert(fix: SosFix?): ZillitResult<Unit> = refuseOr {
        sent += fix
    }

    override suspend fun deleteAlert(alertId: String, timestampMillis: Long): ZillitResult<Unit> = refuseOr {
        deletedAlerts += alertId
        alerts.removeAll { it.id == alertId }
    }

    override suspend fun deleteAllAlerts(timestampMillis: Long): ZillitResult<Unit> = refuseOr {
        deletedAllAt = timestampMillis
        alerts.clear()
    }

    override suspend fun contacts(entryType: String): ZillitResult<List<SosContact>> {
        entryTypesAsked += entryType
        return ZillitResult.Success(contactRows.toList())
    }

    override suspend fun createInternal(userId: String): ZillitResult<Unit> = refuseOr {
        created += userId
        contactRows += contact(id = "c-$userId", kind = SosContactKind.Internal, userId = userId)
    }

    override suspend fun createExternal(draft: ExternalContactDraft): ZillitResult<Unit> = refuseOr {
        created += draft.contactName
        contactRows += contact(
            id = "c-${draft.contactName}",
            kind = SosContactKind.External,
            draft = draft,
        )
    }

    override suspend fun updateInternal(contactId: String, userId: String): ZillitResult<Unit> = refuseOr {
        updated += contactId to userId
        val index = contactRows.indexOfFirst { it.id == contactId }
        if (index >= 0) contactRows[index] = contactRows[index].copy(userId = userId, userFullName = userId)
    }

    override suspend fun updateExternal(
        contactId: String,
        draft: ExternalContactDraft,
    ): ZillitResult<Unit> = refuseOr {
        updated += contactId to draft.contactName
        val index = contactRows.indexOfFirst { it.id == contactId }
        if (index >= 0) {
            contactRows[index] = contactRows[index].copy(
                contactName = draft.contactName,
                relation = draft.relation,
                countryCode = draft.countryCode,
                phoneNumber = draft.phoneNumber,
            )
        }
    }

    override suspend fun deleteContact(contactId: String): ZillitResult<Unit> = refuseOr {
        deletedContacts += contactId
        contactRows.removeAll { it.id == contactId }
    }

    override suspend fun relations(): ZillitResult<List<SosRelation>> =
        ZillitResult.Success(listOf(SosRelation(id = "1", name = "Family", identifier = "family")))

    override suspend fun isdCodes(): ZillitResult<List<IsdCode>> = ZillitResult.Success(
        listOf(
            IsdCode(name = "United Kingdom", dialCode = "+44", code = "GB"),
            IsdCode(name = "India", dialCode = "+91", code = "IN"),
        ),
    )

    /** Answers [failNext] once if it is set, otherwise runs the write. */
    private fun refuseOr(write: () -> Unit): ZillitResult<Unit> {
        val refusal = failNext
        if (refusal != null) {
            failNext = null
            return ZillitResult.Failure(refusal)
        }
        write()
        return ZillitResult.Success(Unit)
    }
}

internal fun contact(
    id: String,
    kind: SosContactKind,
    userId: String = "",
    draft: ExternalContactDraft? = null,
) = SosContact(
    id = id,
    kind = kind,
    entryType = "user",
    userId = userId,
    userFullName = if (kind == SosContactKind.Internal) userId else "",
    userDesignation = "",
    contactName = draft?.contactName.orEmpty(),
    relation = draft?.relation.orEmpty(),
    countryCode = draft?.countryCode.orEmpty(),
    phoneNumber = draft?.phoneNumber.orEmpty(),
    createdMillis = 0L,
)

internal fun alert(id: String, at: Long, deleted: Boolean = false, sender: String = "u-other") = SosAlert(
    id = id,
    uuid = "uuid-$id",
    senderId = sender,
    senderNameHint = "Sam Carter",
    text = "Sam Carter needs help",
    mapsUrl = "https://maps.google.com/?q=1,2",
    action = "sos_alert",
    contactInfo = "",
    createdMillis = at,
    updatedMillis = at,
    deleted = deleted,
)
