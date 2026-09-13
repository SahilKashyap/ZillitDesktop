package com.zillit.desktop.feature.esignature.ui

import com.zillit.desktop.feature.esignature.domain.EnvelopeField
import com.zillit.desktop.feature.esignature.domain.EnvelopeRecipient
import com.zillit.desktop.feature.esignature.domain.EsignPage
import com.zillit.desktop.feature.esignature.domain.FieldOption
import com.zillit.desktop.feature.esignature.domain.FieldType
import com.zillit.desktop.feature.esignature.domain.SignerOptionLike

/**
 * The editor's pure rules — the web's `constants.js` builders, its
 * `DocumentFieldPlacer` geometry and the `validateForSend` gate — kept free
 * of state plumbing so each can be tested on its own.
 */
internal object EditorRules {

    /** A problem that stops a send, worded as the web words it. */
    data class Problem(val text: String, val invalidFields: Set<Int> = emptySet())

    // ------------------------------------------------------------ recipients

    fun signerFrom(option: SignerOptionLike, routingOrder: Int): EnvelopeRecipient = EnvelopeRecipient(
        userId = option.userId,
        name = option.fullName.removeSuffix(" (you)"),
        email = option.email,
        role = EnvelopeRecipient.ROLE_SIGNER,
        routingOrder = routingOrder,
    )

    fun ccFrom(option: SignerOptionLike): EnvelopeRecipient = EnvelopeRecipient(
        userId = option.userId,
        name = option.fullName.removeSuffix(" (you)"),
        email = option.email,
        role = EnvelopeRecipient.ROLE_CC,
        routingOrder = EnvelopeRecipient.CC_ROUTING_ORDER,
    )

    fun external(draft: ExternalRecipientDraft, routingOrder: Int): EnvelopeRecipient = EnvelopeRecipient(
        name = draft.name.trim().ifBlank { draft.email.trim().substringBefore('@') },
        email = draft.email.trim(),
        role = draft.role,
        routingOrder = if (draft.role == EnvelopeRecipient.ROLE_CC) {
            EnvelopeRecipient.CC_ROUTING_ORDER
        } else {
            routingOrder
        },
        isExternal = true,
    )

    /** Signers renumbered 1..n in list order; CCs keep their fixed order. */
    fun renumber(recipients: List<EnvelopeRecipient>): List<EnvelopeRecipient> {
        var order = 0
        return recipients.map { r ->
            if (r.isSigner) {
                r.copy(routingOrder = ++order)
            } else {
                r.copy(routingOrder = EnvelopeRecipient.CC_ROUTING_ORDER)
            }
        }
    }

    /** The placeholder slots a template starts with — the web's "Signer 1". */
    fun placeholder(index: Int): EnvelopeRecipient = EnvelopeRecipient(
        name = "Signer ${index + 1}",
        role = EnvelopeRecipient.ROLE_SIGNER,
        routingOrder = index + 1,
        placeholderLabel = "Signer ${index + 1}",
    )

    /**
     * Fields re-pointed after a recipient is removed: those of the removed
     * recipient go, the rest shift down to follow their owners.
     */
    fun fieldsAfterRemoving(fields: List<EnvelopeField>, removedIndex: Int): List<EnvelopeField> =
        fields.filter { it.recipientIndex != removedIndex }
            .map { if (it.recipientIndex > removedIndex) it.copy(recipientIndex = it.recipientIndex - 1) else it }

    /** Fields re-pointed after two recipients swap places. */
    fun fieldsAfterSwap(fields: List<EnvelopeField>, a: Int, b: Int): List<EnvelopeField> = fields.map { field ->
        when (field.recipientIndex) {
            a -> field.copy(recipientIndex = b)
            b -> field.copy(recipientIndex = a)
            else -> field
        }
    }

    // ------------------------------------------------------------ placement

    /**
     * One field per owner, side by side, centred on the click and clamped
     * inside the page margins — the web's `placeFieldAt`. Radio and dropdown
     * arrive with two blank options so the property panel has rows to type into.
     */
    fun place(
        type: FieldType,
        page: EsignPage,
        xPt: Double,
        yPt: Double,
        owners: List<Int>,
        nextOptionId: () -> String,
    ): List<EnvelopeField> {
        if (owners.isEmpty()) return emptyList()
        val fw = type.defaultWidth
        val fh = type.defaultHeight
        val perRow = maxOf(1, ((page.widthPt - FIELD_MARGIN * 2) / (fw + FIELD_GAP)).toInt())
        val cols = minOf(owners.size, perRow)
        val totalW = cols * fw + (cols - 1) * FIELD_GAP
        val startX = minOf(
            maxOf(FIELD_MARGIN, xPt - totalW / 2),
            page.widthPt - totalW - FIELD_MARGIN,
        ).coerceAtLeast(0.0)
        val startY = maxOf(0.0, yPt - fh / 2).coerceAtMost((page.heightPt - fh).coerceAtLeast(0.0))
        return owners.mapIndexed { position, owner ->
            val col = position % perRow
            val row = position / perRow
            EnvelopeField(
                type = type,
                page = page.page,
                x = startX + col * (fw + FIELD_GAP),
                y = startY + row * (fh + ROW_GAP),
                width = fw,
                height = fh,
                recipientIndex = owner,
                label = seedLabel(type),
                required = true,
                options = if (type.hasOptions) List(2) { FieldOption(nextOptionId(), "") } else emptyList(),
            )
        }
    }

    /**
     * Email and phone keep the type's name as their label — the type is
     * its own prompt. Everything else starts blank so the sender is nudged
     * to say what goes in the box.
     */
    fun seedLabel(type: FieldType): String = when (type) {
        FieldType.Email, FieldType.Phone -> type.label
        else -> ""
    }

    /** A field moved by a delta, kept on its page. */
    fun moved(field: EnvelopeField, page: EsignPage, dx: Double, dy: Double): EnvelopeField = field.copy(
        x = (field.x + dx).coerceIn(0.0, (page.widthPt - field.width).coerceAtLeast(0.0)),
        y = (field.y + dy).coerceIn(0.0, (page.heightPt - field.height).coerceAtLeast(0.0)),
    )

    /** A field resized by a delta, never smaller than a readable box nor past the page edge. */
    fun resized(field: EnvelopeField, page: EsignPage, dw: Double, dh: Double): EnvelopeField = field.copy(
        width = (field.width + dw).coerceIn(MIN_FIELD_SIZE, (page.widthPt - field.x).coerceAtLeast(MIN_FIELD_SIZE)),
        height = (field.height + dh).coerceIn(MIN_FIELD_SIZE, (page.heightPt - field.y).coerceAtLeast(MIN_FIELD_SIZE)),
    )

    /**
     * "Initials on every page": one initial per signer per page, in the
     * bottom-right corner, flagged `autoInitial` so the signer's pad can
     * collapse them into one. Replaces any earlier auto set.
     */
    fun withAutoInitials(
        fields: List<EnvelopeField>,
        pages: List<EsignPage>,
        signerIndexes: List<Int>,
        on: Boolean,
    ): List<EnvelopeField> {
        val manual = fields.filter { !it.autoInitial }
        if (!on || signerIndexes.isEmpty()) return manual
        val auto = pages.flatMap { page ->
            val perRow = maxOf(1, ((page.widthPt - FIELD_MARGIN * 2) / (INITIAL_W + INITIAL_GAP)).toInt())
            val cols = minOf(signerIndexes.size, perRow)
            val totalW = cols * INITIAL_W + (cols - 1) * INITIAL_GAP
            val startX = maxOf(FIELD_MARGIN, page.widthPt - totalW - FIELD_MARGIN)
            val bottomY = maxOf(0.0, page.heightPt - AUTO_INITIAL_BOTTOM)
            val rows = (signerIndexes.size + perRow - 1) / perRow
            signerIndexes.mapIndexed { position, owner ->
                val col = position % perRow
                val row = position / perRow
                EnvelopeField(
                    type = FieldType.InitialHere,
                    page = page.page,
                    x = startX + col * (INITIAL_W + INITIAL_GAP),
                    y = bottomY - (rows - 1 - row) * (INITIAL_H + AUTO_ROW_GAP),
                    width = INITIAL_W,
                    height = INITIAL_H,
                    recipientIndex = owner,
                    label = "Initial",
                    autoInitial = true,
                )
            }
        }
        return manual + auto
    }

    // ------------------------------------------------------------ validation

    /** Option fields with a blank option label — the backend refuses them. */
    fun optionFieldsMissingLabels(fields: List<EnvelopeField>): Set<Int> =
        fields.withIndex()
            .filter { (_, f) -> f.type.hasOptions && (f.options.isEmpty() || f.options.any { it.label.isBlank() }) }
            .map { it.index }
            .toSet()

    /** Typed and choice fields the sender never labelled — the signer would not know what to fill. */
    fun fieldsMissingLabel(fields: List<EnvelopeField>): Set<Int> =
        fields.withIndex()
            .filter { (_, f) ->
                f.type.hasLabel && f.type != FieldType.FullName && f.type != FieldType.Image && f.label.isBlank()
            }
            .map { it.index }
            .toSet()

    /** The web's `validateForSend`, one problem at a time in its order. */
    @Suppress("ReturnCount") // One early return per gate, in the web's order.
    fun problemBeforeSend(editor: EditorState): Problem? {
        if (!editor.hasDocument) return Problem("Please add a document to the envelope")
        val optionBad = optionFieldsMissingLabels(editor.fields)
        val labelBad = fieldsMissingLabel(editor.fields)
        val signers = editor.signers
        if (signers.isEmpty()) return Problem("Please add at least one signer")
        val placeholders = signers.filter { it.isPlaceholder || it.email.isBlank() }
        if (placeholders.isNotEmpty()) {
            val names = placeholders.joinToString(", ") { it.name.ifBlank { "a signer" } }
            return Problem("Pick a real user for $names before sending — placeholders can't receive the envelope.")
        }
        if (editor.fields.none { it.type.isMark }) {
            return Problem("Please place at least one signature or initial field on the document before sending")
        }
        val uncovered = editor.signerIndexes.filter { index -> editor.fields.none { it.recipientIndex == index } }
        if (uncovered.isNotEmpty()) {
            val names = uncovered.joinToString(", ") { index ->
                editor.recipients[index].name.ifBlank { editor.recipients[index].email }
            }
            return Problem(
                "Please place at least one field for $names before sending. Every signer needs something to sign.",
            )
        }
        if (optionBad.isNotEmpty()) return Problem(optionLabelsMessage(optionBad.size), optionBad)
        if (labelBad.isNotEmpty()) {
            val type = editor.fields[labelBad.first()].type.label
            return Problem(
                "One of your $type fields has no label — see the red highlight on the document. " +
                    "Label every field so the signer knows what to fill in.",
                labelBad,
            )
        }
        return null
    }

    /** Drafts only need a subject and complete option fields. */
    fun problemBeforeDraft(editor: EditorState): Problem? {
        if (editor.title.isBlank()) return Problem("Please add an email subject")
        val optionBad = optionFieldsMissingLabels(editor.fields)
        if (optionBad.isNotEmpty()) return Problem(optionLabelsMessage(optionBad.size), optionBad)
        return null
    }

    private fun optionLabelsMessage(n: Int): String = if (n == 1) {
        "1 dropdown / radio field is missing option labels — see the red highlight on the document. " +
            "Click the field to fill in every option."
    } else {
        "$n dropdown / radio fields are missing option labels — see the red highlights on the document. " +
            "Click each to fill in every option."
    }

    private const val FIELD_MARGIN = 20.0
    private const val FIELD_GAP = 24.0
    private const val ROW_GAP = 24.0
    private const val MIN_FIELD_SIZE = 20.0
    private const val INITIAL_W = 140.0
    private const val INITIAL_H = 35.0
    private const val INITIAL_GAP = 6.0
    private const val AUTO_ROW_GAP = 6.0
    private const val AUTO_INITIAL_BOTTOM = 50.0
}
