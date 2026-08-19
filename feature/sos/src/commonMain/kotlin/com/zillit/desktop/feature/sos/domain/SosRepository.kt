package com.zillit.desktop.feature.sos.domain

import com.zillit.desktop.core.common.ZillitResult

/** The SOS routes on the project host, plus the alert feed on the notification host. */
interface SosRepository {

    /**
     * A page of the `sos_label` feed from [cursorMillis] — older rows when
     * [older], newer ones otherwise. [newest] marks the first page from "now"
     * so it can be kept under a stable name for offline viewing.
     */
    suspend fun alerts(cursorMillis: Long, older: Boolean, limit: Int, newest: Boolean): ZillitResult<List<SosAlert>>

    /** Marks the `sos_label` segment read up to [timestampMillis]. */
    suspend fun markRead(timestampMillis: Long): ZillitResult<Unit>

    /** Raises the alarm. [fix] is where the sender is; null sends the alert without coordinates. */
    suspend fun sendAlert(fix: SosFix?): ZillitResult<Unit>

    suspend fun deleteAlert(alertId: String, timestampMillis: Long): ZillitResult<Unit>

    suspend fun deleteAllAlerts(timestampMillis: Long): ZillitResult<Unit>

    /** The receivers on the [entryType] list — `admin` or `user`. */
    suspend fun contacts(entryType: String): ZillitResult<List<SosContact>>

    suspend fun createInternal(userId: String): ZillitResult<Unit>

    suspend fun createExternal(draft: ExternalContactDraft): ZillitResult<Unit>

    suspend fun updateInternal(contactId: String, userId: String): ZillitResult<Unit>

    suspend fun updateExternal(contactId: String, draft: ExternalContactDraft): ZillitResult<Unit>

    suspend fun deleteContact(contactId: String): ZillitResult<Unit>

    /** The relationships the outsider form offers. */
    suspend fun relations(): ZillitResult<List<SosRelation>>

    /** Every dialling code, for the outsider form. */
    suspend fun isdCodes(): ZillitResult<List<IsdCode>>
}
