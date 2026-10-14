package com.zillit.desktop.feature.esignature.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * One unread leaf of the e-signature ledger.
 *
 * `e_signature_label` rows file under one of two units — the sender's
 * `manage_envelopes_label`, the signer's `sign_documents_label` — with the
 * bucket in `level_1` (`draft` / `sent` / `completed` / `rejected`, or
 * `action_required` for a signer) and the envelope in `level_3`
 * (`BadgeDB.getESignatureBadgesFromDB`). One envelope can carry rows in two
 * buckets as it moves on, so a tab's row badge counts only its own bucket.
 *
 * A row with no envelope is no leaf (iOS ZL-21329,
 * `DocuSignBadgeReader.swift:67-76, 112-118`): nothing on screen draws it —
 * every pill and row dot groups by envelope — and no envelope read can
 * reach it, so counting it pinned a number on the tabs that never fell.
 */
data class EsignBadgeLeaf(val unit: String, val bucket: String, val envelopeId: String, val unread: Int)

/**
 * The tool's unread rows and its read: one envelope's rows under one unit,
 * when its detail or signing page opens — the only read (iOS ZL-21329: a tab
 * is never read whole).
 */
interface EsignBadges {
    val leaves: Flow<List<EsignBadgeLeaf>> get() = emptyFlow()

    fun readEnvelope(unit: String, envelopeId: String) {}

    companion object {
        val None: EsignBadges = object : EsignBadges {}

        const val TOOL = "e_signature_label"
        const val UNIT_MANAGE = "manage_envelopes_label"
        const val UNIT_SIGN = "sign_documents_label"
    }
}

/**
 * The leaves cut the way the lists ask: a surface, a bucket, one envelope on
 * one tab. Envelope-less leaves are dropped here too, whatever the host sent.
 */
data class EsignUnread(private val all: List<EsignBadgeLeaf> = emptyList()) {

    val leaves: List<EsignBadgeLeaf> = all.filter { it.envelopeId.isNotBlank() }

    val manage: Int get() = leaves.filter { it.unit == EsignBadges.UNIT_MANAGE }.sumOf { it.unread }

    val sign: Int get() = leaves.filter { it.unit == EsignBadges.UNIT_SIGN }.sumOf { it.unread }

    /** A manage bucket's pill — the web's `manageEnvelopes.byBucket`. */
    fun manageBucket(vararg buckets: String): Int =
        leaves.filter { it.unit == EsignBadges.UNIT_MANAGE && it.bucket in buckets }.sumOf { it.unread }

    /** One envelope on one manage tab — `byEnvelopeByBucket[id][bucket]`, never the stale row of an earlier stage. */
    fun manageEnvelope(envelopeId: String, bucket: String): Int =
        leaves.filter { it.unit == EsignBadges.UNIT_MANAGE && it.bucket == bucket && it.envelopeId == envelopeId }
            .sumOf { it.unread }

    /** One envelope to sign — `signDocuments.byEnvelope[id]`. */
    fun signEnvelope(envelopeId: String): Int =
        leaves.filter { it.unit == EsignBadges.UNIT_SIGN && it.envelopeId == envelopeId }.sumOf { it.unread }

    fun envelope(envelopeId: String): Int = leaves.filter { it.envelopeId == envelopeId }.sumOf { it.unread }

    companion object {
        val None = EsignUnread()
    }
}
