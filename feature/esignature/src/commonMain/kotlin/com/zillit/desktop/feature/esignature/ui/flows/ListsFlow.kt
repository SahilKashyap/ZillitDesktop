package com.zillit.desktop.feature.esignature.ui.flows

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.esignature.domain.Envelope
import com.zillit.desktop.feature.esignature.domain.EnvelopeScope
import com.zillit.desktop.feature.esignature.domain.EnvelopeStatus
import com.zillit.desktop.feature.esignature.ui.EsignPageKind
import com.zillit.desktop.feature.esignature.ui.EsignStore
import com.zillit.desktop.feature.esignature.ui.EsignSurface
import com.zillit.desktop.feature.esignature.ui.ManageBuckets
import com.zillit.desktop.feature.esignature.ui.SignBucket
import com.zillit.desktop.feature.esignature.ui.orFail
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * The two lists — the manager's four buckets and the receiver's three —
 * fetched whole, as the web eager-fetches both scopes on mount so the
 * segment badges and the tab pills agree.
 */
internal class ListsFlow(private val store: EsignStore) {

    fun refreshVisible() {
        when (store.current.surface) {
            EsignSurface.Manage -> loadManage()
            EsignSurface.Sign -> loadSignList()
            EsignSurface.Templates, EsignSurface.Bulk -> Unit
        }
    }

    fun loadBoth() {
        if (!store.current.viewer.receiverOnly) loadManage()
        loadSignList()
    }

    fun loadManage() {
        store.update { copy(manage = manage.copy(loading = true)) }
        store.runTask {
            val answers = coroutineScope {
                ManageBuckets.all.map { bucket ->
                    async { bucket to store.repository.envelopes(EnvelopeScope.Sent, bucket, userId = null) }
                }.awaitAll()
            }
            val loaded = mutableMapOf<String, List<Envelope>>()
            var firstError: String? = null
            answers.forEach { (bucket, result) ->
                when (result) {
                    is ZillitResult.Success -> loaded[bucket] = result.data
                    is ZillitResult.Failure -> firstError = firstError ?: result.error.userMessage
                }
            }
            store.update {
                copy(manage = manage.copy(buckets = manage.buckets + loaded, loading = false))
            }
            firstError?.let { store.failed(it) }
        }
    }

    fun loadSignList() {
        store.update { copy(signList = signList.copy(loading = true)) }
        store.runTask {
            val userId = store.current.currentUserId
            val answers = coroutineScope {
                SignBucket.entries.map { bucket ->
                    async { bucket.wire to store.repository.envelopes(EnvelopeScope.Received, bucket.wire, userId) }
                }.awaitAll()
            }
            val loaded = mutableMapOf<String, List<Envelope>>()
            var firstError: String? = null
            answers.forEach { (bucket, result) ->
                when (result) {
                    is ZillitResult.Success -> loaded[bucket] = filterReceived(bucket, result.data)
                    is ZillitResult.Failure -> firstError = firstError ?: result.error.userMessage
                }
            }
            store.update {
                val pending = loaded[SignBucket.Action.wire]?.size ?: signList.rows(SignBucket.Action).size
                copy(
                    signList = signList.copy(buckets = signList.buckets + loaded, loading = false),
                    pendingForMe = pending,
                )
            }
            firstError?.let { store.failed(it) }
        }
    }

    /**
     * The received bucket minus what I have already signed — the web's
     * `receivedEnvelopes` filter. The backend is trusted when it cannot
     * find my row; an envelope it put here belongs here.
     */
    private fun filterReceived(bucket: String, rows: List<Envelope>): List<Envelope> {
        if (bucket != SignBucket.Action.wire) return rows
        val me = store.current.currentUserId
        val email = store.current.currentUserEmail
        return rows.filter { envelope ->
            val mine = envelope.recipientFor(me, email) ?: return@filter true
            !mine.signed
        }
    }

    /** A draft opens in the editor; anything else in the tracker. */
    fun open(envelope: Envelope, editor: EditorFlow, detail: DetailFlow) {
        if (envelope.status == EnvelopeStatus.Draft) {
            if (store.refusesPost()) return
            editor.openDraft(envelope)
        } else {
            detail.open(envelope.id, envelope)
        }
    }

    fun deleteDraft() {
        val id = store.current.manage.confirmDeleteId ?: return
        store.update { copy(manage = manage.copy(confirmDeleteId = null)) }
        if (store.refusesPost()) return
        store.runTask {
            store.orFail { store.repository.deleteDraft(id) } ?: return@runTask
            store.notice("Draft deleted.")
            if (store.current.page == EsignPageKind.Editor && store.current.editor?.envelopeId == id) {
                store.update { copy(editor = null, page = EsignPageKind.Lists) }
            }
            loadManage()
        }
    }
}
