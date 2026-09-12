package com.zillit.desktop.feature.cardexpenses.domain

import com.zillit.desktop.core.common.ZillitResult

/**
 * Picks a file on this machine and puts it where the server can read it.
 *
 * A host seam rather than a repository call, for the reason the bank
 * reconciliation module gives: the picker belongs to the desktop and the
 * object store to the production, and neither is something the card service
 * offers. Nothing in this module streams bytes to the API — every route here
 * takes a **pointer** to a file already in storage.
 *
 * Success carrying null is a cancelled picker, which is not a failure: the
 * person changed their mind, and apologising for that is worse than silence.
 */
fun interface CardAttachmentUploader {
    suspend fun pick(kind: PickKind): ZillitResult<CardAttachment?>
}

/**
 * What is being picked, which decides the file types offered and the size cap.
 *
 * The two are genuinely different: a receipt is a photograph or a PDF and is
 * small, a statement is a spreadsheet or a CSV and is larger. Offering a CSV
 * in the receipt picker would let someone attach one and only learn it was
 * wrong after the batch was refused.
 */
enum class PickKind { Receipt, Statement }
