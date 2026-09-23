package com.zillit.desktop.feature.esignature.ui.flows

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.esignature.domain.Envelope
import com.zillit.desktop.feature.esignature.ui.DetailState
import com.zillit.desktop.feature.esignature.ui.EsignPageKind
import com.zillit.desktop.feature.esignature.ui.EsignStore
import com.zillit.desktop.feature.esignature.ui.orFail

/**
 * The tracker — the web's `EnvelopeStatusTracker`: the full envelope, its
 * audit trail, reminders with the web's per-recipient cooldown, the
 * certificate download, and the sender's cancel.
 */
internal class DetailFlow(private val store: EsignStore) {

    /** Opens the tracker on a list row, then swaps in the full envelope. */
    fun open(envelopeId: String, seed: Envelope? = null) {
        store.update {
            copy(
                page = EsignPageKind.Detail,
                signing = null,
                detail = DetailState(envelope = seed ?: Envelope(id = envelopeId), loading = true),
            )
        }
        load(envelopeId)
    }

    fun refresh() {
        val id = store.current.detail?.envelope?.id ?: return
        store.update { copy(detail = detail?.copy(refreshing = true)) }
        load(id)
    }

    private fun load(envelopeId: String) {
        store.runTask {
            when (val full = store.repository.envelope(envelopeId)) {
                is ZillitResult.Failure -> {
                    store.update { copy(detail = detail?.copy(loading = false, refreshing = false)) }
                    store.failed(full.error.userMessage)
                }
                is ZillitResult.Success -> {
                    val envelope = full.data
                    val me = store.current.currentUserId
                    val email = store.current.currentUserEmail
                    store.update {
                        copy(
                            detail = detail?.copy(
                                envelope = envelope,
                                loading = false,
                                refreshing = false,
                                // Who sent it decides whether cancelling is offered.
                                sentByMe = envelope.createdBy.isNotBlank() && envelope.createdBy == me,
                                me = envelope.recipientFor(me, email),
                            ),
                        )
                    }
                    loadAudit(envelopeId)
                }
            }
        }
    }

    private fun loadAudit(envelopeId: String) {
        store.update { copy(detail = detail?.copy(auditLoading = true)) }
        store.runTask {
            when (val trail = store.repository.auditTrail(envelopeId)) {
                is ZillitResult.Success -> store.update {
                    copy(
                        detail = detail?.copy(
                            audit = trail.data.sortedByDescending { it.happenedOn ?: 0 },
                            auditLoading = false,
                        ),
                    )
                }
                // The trail is garnish; the tracker stays useful without it.
                is ZillitResult.Failure -> store.update { copy(detail = detail?.copy(auditLoading = false)) }
            }
        }
    }

    fun remind(recipientId: String?) {
        val detail = store.current.detail ?: return
        if (store.refusesPost()) return
        val key = recipientId ?: ALL_KEY
        val now = store.now()
        val since = if (recipientId == null) detail.remindAllAt else detail.remindedAt[recipientId] ?: 0L
        if (now - since < REMIND_COOLDOWN_MS) {
            store.notice(str(S.desktop_ds_reminder_already_sent_try_again_in_a_moment))
            return
        }
        store.update { copy(detail = this.detail?.copy(reminding = this.detail.reminding + key)) }
        store.runTask {
            val ok = store.orFail { store.repository.remind(detail.envelope.id, recipientId) }
            store.update {
                copy(
                    detail = this.detail?.let { d ->
                        d.copy(
                            reminding = d.reminding - key,
                            remindedAt = if (ok != null && recipientId != null) {
                                d.remindedAt + (recipientId to now)
                            } else {
                                d.remindedAt
                            },
                            remindAllAt = if (ok != null && recipientId == null) now else d.remindAllAt,
                        )
                    },
                )
            }
            if (ok != null) {
                store.notice(
                    if (recipientId == null) str(S.desktop_ds_reminders_sent) else str(S.docusign_resend_success),
                )
                loadAudit(detail.envelope.id)
            }
        }
    }

    fun downloadAuditPdf() {
        val detail = store.current.detail ?: return
        store.update { copy(detail = this.detail?.copy(downloadingAudit = true)) }
        store.runTask {
            val bytes = store.orFail { store.repository.auditTrailPdf(detail.envelope.id) }
            if (bytes != null) {
                val safe = detail.envelope.title.ifBlank { "envelope" }.replace(Regex("[^A-Za-z0-9._-]+"), "_")
                store.orFail { store.transfer.land("audit-trail-$safe.pdf", bytes) }
                    ?.let { store.notice(str(S.desktop_ds_audit_trail_saved_to_downloads)) }
            }
            store.update { copy(detail = this.detail?.copy(downloadingAudit = false)) }
        }
    }

    fun downloadSigned() {
        val detail = store.current.detail ?: return
        val signed = detail.envelope.signedDocument
        if (signed == null) {
            store.notice(str(S.desktop_ds_signed_document_is_not_ready_yet))
            return
        }
        store.update { copy(detail = this.detail?.copy(downloadingSigned = true)) }
        store.runTask {
            val bytes = store.orFail { store.transfer.fetch(signed) }
            if (bytes != null) {
                val name = signed.name.ifBlank { "${detail.envelope.title.ifBlank { "signed" }}.pdf" }
                store.orFail { store.transfer.land(name, bytes) }
                    ?.let { store.notice(str(S.docusign_signing_attachment_saved)) }
            }
            store.update { copy(detail = this.detail?.copy(downloadingSigned = false)) }
        }
    }

    /**
     * Cancels an envelope that has already gone out. Not a delete: the
     * envelope and its trail stay, marked void with the reason the
     * recipients are told.
     */
    fun confirmVoid(lists: ListsFlow) {
        val detail = store.current.detail ?: return
        val reason = detail.voidReason.trim()
        if (reason.isBlank()) {
            store.failed(str(S.desktop_ds_say_why_this_envelope_is_being_cancelled))
            return
        }
        store.runTask {
            store.orFail { store.repository.voidEnvelope(detail.envelope.id, reason) } ?: run {
                store.update { copy(detail = this.detail?.copy(voiding = false)) }
                return@runTask
            }
            store.update { copy(detail = null, page = EsignPageKind.Lists) }
            store.notice(str(S.desktop_ds_envelope_cancelled))
            lists.loadManage()
        }
    }

    private companion object {
        const val ALL_KEY = "__all__"
        const val REMIND_COOLDOWN_MS = 60_000L
    }
}
