package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.CashAccount
import com.zillit.desktop.feature.cashexpenses.domain.CashRules
import com.zillit.desktop.feature.cashexpenses.domain.Claim
import com.zillit.desktop.feature.cashexpenses.domain.ClaimField
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseCategory
import com.zillit.desktop.feature.cashexpenses.domain.LineItemEditor
import com.zillit.desktop.feature.cashexpenses.ui.BatchEvent
import com.zillit.desktop.feature.cashexpenses.ui.CashEvent
import com.zillit.desktop.feature.cashexpenses.ui.date
import kotlin.math.abs

/**
 * The receipt image for a stored key, or null — a host seam as the crew faces
 * are ([com.zillit.desktop.feature.cashexpenses.ui.LocalCashFaces]). Absent,
 * the card shows a tile that opens the receipt instead of a thumbnail.
 */
val LocalCashReceiptImage: ProvidableCompositionLocal<suspend (String) -> ImageBitmap?> =
    staticCompositionLocalOf { { _: String -> null } }

/** Whether this production is television — the web shows the receipt's Episode only there. */
val LocalCashTelevision: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

/** How one receipt card behaves on the page it is on. */
internal data class ReceiptCardMode(
    /** Whether anything on the card can be typed into. */
    val editable: Boolean,
    /** Coding: the claimant's name, amount and category are shown, not edited (`lockClaimantFields`). */
    val lockClaimant: Boolean,
    /** Audit: the per-receipt Verify. */
    val showVerify: Boolean,
    val verifying: Boolean,
)

/**
 * One receipt of the open batch — the web's `BatchReceiptCard`
 * (`PCPostLedgerPage.jsx:338-430`): its facts, its coding, its Verify, and
 * the receipt beside them.
 */
// One card, top to bottom, as the web lays it out.
@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod")
@Composable
internal fun ReceiptCard(
    claim: Claim,
    number: Int,
    currency: String?,
    formatMoney: (Double, String?) -> String,
    accounts: List<CashAccount>?,
    mode: ReceiptCardMode,
    onEvent: (CashEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val coded = CashRules.isCoded(claim)
    val done = claim.isVerified || coded
    fun edit(field: ClaimField, value: String) = onEvent(BatchEvent.EditClaim(claim.id, field, value))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(
                width = 1.dp,
                color = if (done) toneContent(StatusTone.Done).copy(alpha = DONE_BORDER_ALPHA) else colors.border,
                shape = ZillitTheme.shapes.large,
            ),
    ) {
        Column(
            modifier = Modifier.weight(1f).padding(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                Box(
                    modifier = Modifier.size(30.dp).clip(ZillitTheme.shapes.medium)
                        .background(toneBackground(StatusTone.Pending)),
                    contentAlignment = Alignment.Center,
                ) {
                    ZillitIcon(ZillitIcons.File, tint = toneContent(StatusTone.Pending), size = 16.dp)
                }
                ZillitText(
                    text = str(S.desktop_pc_receipt_number, number.toString().padStart(2, '0')),
                    style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = colors.textMuted,
                )
                Box(modifier = Modifier.weight(1f)) {
                    if (mode.lockClaimant || !mode.editable) {
                        LockedField(claim.codedDescription?.ifBlank { null } ?: claim.description.ifBlank { "—" })
                    } else {
                        ZillitTextField(
                            value = claim.codedDescription.orEmpty(),
                            onValueChange = { edit(ClaimField.Name, it) },
                            placeholder = str(S.desktop_pc_receipt_name_hint),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (mode.showVerify) {
                    ZillitButton(
                        text = if (claim.isVerified) str(S.ah_verified) else str(S.txt_verify),
                        onClick = { onEvent(CashEvent.ToggleVerify(claim.id)) },
                        variant = if (claim.isVerified) ButtonVariant.Secondary else ButtonVariant.Primary,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Check,
                        loading = mode.verifying,
                        enabled = mode.editable,
                    )
                } else if (coded) {
                    ZillitStatusPill(label = str(S.desktop_pc_coded_badge), tone = StatusTone.Done)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                LabelledCell(str(S.amount), Modifier.weight(1f)) {
                    if (mode.lockClaimant || !mode.editable) {
                        LockedField(formatMoney(claim.grossAmount, currency))
                    } else {
                        AmountField(claim) { edit(ClaimField.Amount, it) }
                    }
                }
                LabelledCell(str(S.desktop_pc_purchase_date), Modifier.weight(1f)) {
                    LockedField(date(claim.receiptDate))
                }
                LabelledCell(str(S.av_category), Modifier.weight(1f)) {
                    if (mode.lockClaimant || !mode.editable) {
                        LockedField(ExpenseCategory.label(claim.category))
                    } else {
                        ZillitSelect(
                            value = claim.category?.takeIf { it.isNotBlank() },
                            options = listOf<String?>(null) + ExpenseCategory.entries.map { it.wire },
                            onSelect = { edit(ClaimField.Category, it.orEmpty()) },
                            label = { wire ->
                                wire?.let { ExpenseCategory.label(it) } ?: str(S.desktop_pc_category_hint)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            val television = LocalCashTelevision.current || !claim.episode.isNullOrBlank()
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                LabelledCell(str(S.desktop_pc_cost_code), Modifier.weight(1f), required = true) {
                    if (mode.editable) {
                        CashCoaField(
                            value = claim.costCode.orEmpty(),
                            onValueChange = { edit(ClaimField.CostCode, it) },
                            accounts = accounts,
                        )
                    } else {
                        LockedField(claim.costCode?.ifBlank { null } ?: "—")
                    }
                }
                LabelledCell(str(S.ah_lbl_vendor), Modifier.weight(1f)) {
                    if (mode.editable) {
                        ZillitTextField(
                            value = claim.description,
                            onValueChange = { edit(ClaimField.Vendor, it) },
                            placeholder = str(S.cash_receipt_vendor_hint),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LockedField(claim.description.ifBlank { "—" })
                    }
                }
                if (television) {
                    LabelledCell(str(S.episode), Modifier.weight(1f)) {
                        if (mode.editable) {
                            ZillitTextField(
                                value = claim.episode.orEmpty(),
                                onValueChange = { edit(ClaimField.Episode, it) },
                                placeholder = str(S.ah_episode_hint),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LockedField(claim.episode?.ifBlank { null } ?: "—")
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }

            val hasLines = claim.lineItems.any { !it.autoDeduction }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ZillitText(
                    text = if (hasLines) str(S.desktop_pc_split_note) else "",
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                    modifier = Modifier.weight(1f),
                )
                if (mode.editable) {
                    ZillitButton(
                        text = if (hasLines) str(S.desktop_pc_edit_split) else str(S.desktop_pc_split_into_lines),
                        onClick = { onEvent(BatchEvent.OpenSplit(claim.id)) },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        leadingIcon = if (hasLines) ZillitIcons.Edit else ZillitIcons.Add,
                    )
                }
            }
            if (claim.lineItems.isNotEmpty()) LinesSummary(claim, currency, formatMoney)
        }

        Column(
            modifier = Modifier
                .width(PREVIEW_COLUMN)
                .background(colors.surfaceSunken)
                .padding(ZillitTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            FieldLabel(str(S.desktop_pc_receipt_image))
            ReceiptPreview(claim, onEvent)
        }
    }
}

/**
 * The receipt's split, read-only under the card — what the split editor
 * holds — with the web's allocation line under it.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // Each line with its kind, code and amount.
@Composable
private fun LinesSummary(claim: Claim, currency: String?, formatMoney: (Double, String?) -> String) {
    val colors = ZillitTheme.colors
    val lines = LineItemEditor.fromWire(claim.lineItems)
    val sum = LineItemEditor.total(lines)
    val gross = claim.grossAmount
    val diff = gross - sum
    val ok = abs(diff) < SPLIT_PENNY
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZillitText(
                text = str(S.ah_line_items),
                style = ZillitTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            ZillitText(
                text = "${formatMoney(sum, currency)} / ${formatMoney(gross, currency)}",
                style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
                color = when {
                    ok -> colors.success
                    diff < 0 -> colors.danger
                    else -> colors.warning
                },
            )
        }
        val splitParents = lines.mapNotNull { it.splitParentId }.toSet()
        lines.forEach { line ->
            val label = when {
                line.extras.isTax -> str(S.desktop_po_reclaimable_tax)
                line.autoDeduction -> line.description.ifBlank { str(S.desktop_payroll_deduction) }
                else -> line.description.ifBlank { "—" }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ZillitTheme.shapes.medium)
                    .background(colors.surfaceSunken)
                    .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs)
                    .padding(start = if (line.isSplitChild) ZillitTheme.spacing.lg else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitText(
                    text = label,
                    style = ZillitTheme.typography.bodySmall,
                    color = if (line.id in splitParents) colors.textMuted else colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (line.autoDeduction) {
                    ZillitText(
                        text = str(S.desktop_pc_auto).uppercase(),
                        style = ZillitTheme.typography.labelSmall,
                        color = colors.textMuted,
                    )
                }
                ZillitText(
                    text = line.account.ifBlank { "—" },
                    style = ZillitTheme.typography.bodySmall,
                    color = if (line.account.isBlank() && !line.autoDeduction) colors.danger else colors.textSecondary,
                )
                ZillitText(
                    text = formatMoney(line.gross, currency),
                    style = ZillitTheme.typography.numeric,
                    color = if (line.autoDeduction) colors.danger else colors.textPrimary,
                )
            }
        }
        if (!ok) {
            ZillitText(
                text = if (diff > 0) {
                    str(S.desktop_inv_to_allocate, formatMoney(diff, currency))
                } else {
                    str(S.desktop_pc_over_receipt, formatMoney(abs(diff), currency))
                },
                style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.Bold),
                color = colors.warning,
            )
        }
    }
}

/** The receipt beside its facts: a thumbnail when the host can draw one, a tile that opens it otherwise. */
@Composable
private fun ReceiptPreview(claim: Claim, onEvent: (CashEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val key = claim.receiptKey
    val load = LocalCashReceiptImage.current
    val image by produceState<ImageBitmap?>(initialValue = null, key, claim.receiptIsPdf) {
        value = key?.takeIf { !claim.receiptIsPdf }?.let { runCatching { load(it) }.getOrNull() }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(PREVIEW_HEIGHT)
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .then(if (key != null) Modifier.clickable { onEvent(CashEvent.ViewReceipt(key)) } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val shown = image
        when {
            key == null -> PreviewPlaceholder(ZillitIcons.Eye, str(S.desktop_pc_no_receipt))
            shown != null -> Image(
                bitmap = shown,
                contentDescription = str(S.desktop_pc_receipt_image),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            claim.receiptIsPdf -> PreviewPlaceholder(ZillitIcons.File, str(S.desktop_pc_pdf_receipt))
            else -> PreviewPlaceholder(ZillitIcons.Receipt, str(S.desktop_receipt))
        }
        if (key != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(ZillitTheme.spacing.sm)
                    .clip(ZillitTheme.shapes.medium)
                    .background(colors.surfaceRaised)
                    .border(1.dp, colors.border, ZillitTheme.shapes.medium)
                    .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitIcon(ZillitIcons.Eye, size = 12.dp, tint = colors.textSecondary)
                ZillitText(text = str(S.view), style = ZillitTheme.typography.labelSmall, color = colors.textSecondary)
            }
        }
    }
}

@Composable
private fun PreviewPlaceholder(icon: ImageVector, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ZillitIcon(icon, size = 22.dp, tint = ZillitTheme.colors.textMuted)
        ZillitText(
            text = label,
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = ZillitTheme.colors.textMuted,
        )
    }
}

/** The amount as typed: kept as text until it reads as a number, so "12." survives the keystroke. */
@Composable
private fun AmountField(claim: Claim, onCommit: (String) -> Unit) {
    var text by remember(claim.id) { mutableStateOf(plain(claim.grossAmount)) }
    ZillitTextField(
        value = text,
        onValueChange = { typed ->
            text = typed
            if (typed.trim().toDoubleOrNull() != null) onCommit(typed)
        },
        placeholder = "0.00",
        keyboardType = KeyboardType.Decimal,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun plain(amount: Double): String =
    if (amount == amount.toLong().toDouble()) amount.toLong().toString() else amount.toString()

/** A field label as the web's `VFieldLabel`: small caps, with the required star. */
@Composable
internal fun FieldLabel(text: String, required: Boolean = false) {
    Row {
        ZillitText(
            text = text.uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = ZillitTheme.colors.textMuted,
        )
        if (required) {
            ZillitText(
                text = " *",
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = ZillitTheme.colors.accent,
            )
        }
    }
}

@Composable
private fun LabelledCell(
    label: String,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        FieldLabel(label, required)
        content()
    }
}

/** What the claimant entered, shown locked — the web's `LockedField`. */
@Composable
internal fun LockedField(text: String, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(ZillitIcons.Lock, size = 12.dp, tint = colors.textMuted)
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A nominal code field that suggests from the chart of accounts — the web's
 * `CoaCodeInput`: code and name, best matches first, and a code the chart
 * does not have is still kept (it goes out `[[wrapped]]` on save).
 */
@Composable
internal fun CashCoaField(
    value: String,
    onValueChange: (String) -> Unit,
    accounts: List<CashAccount>?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    var fieldHeight by remember { mutableIntStateOf(0) }
    val matches = remember(accounts, value) { CashCoa.rank(accounts.orEmpty(), value).take(MAX_MATCHES) }
    val exact = matches.size == 1 && matches.first().code.equals(value.trim(), ignoreCase = true)
    Box(modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.hasFocus }) {
        ZillitTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = str(S.desktop_pc_enter_code),
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().onSizeChanged { fieldHeight = it.height },
        )
        val suggest = matches.isNotEmpty() && !exact
        if (focused && enabled && suggest) {
            Popup(
                offset = IntOffset(0, fieldHeight),
                properties = PopupProperties(focusable = false),
            ) {
                CoaSuggestions(matches) { onValueChange(it.code) }
            }
        }
    }
}

@Composable
private fun CoaSuggestions(matches: List<CashAccount>, onPick: (CashAccount) -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .width(SUGGESTION_WIDTH)
            .clip(ZillitTheme.shapes.large)
            .background(colors.surfaceRaised)
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .padding(vertical = ZillitTheme.spacing.xs),
    ) {
        matches.forEach { account ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(account) }
                    .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitText(
                    text = account.code,
                    style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.SemiBold),
                )
                ZillitText(
                    text = account.name,
                    style = ZillitTheme.typography.bodySmall.copy(fontStyle = FontStyle.Normal),
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The chart as the code picker reads it — the web's `rankRows`. */
internal object CashCoa {

    /** Postable rows; exact code, code prefix, name prefix, code substring, name substring. */
    fun rank(accounts: List<CashAccount>, query: String): List<CashAccount> {
        val q = query.trim().lowercase()
        val postable = accounts.filter { it.postable }
        if (q.isEmpty()) return postable
        return postable.mapNotNull { account ->
            val code = account.code.lowercase()
            val name = account.name.lowercase()
            val score = when {
                code == q -> EXACT
                code.startsWith(q) -> CODE_PREFIX
                name.startsWith(q) -> NAME_PREFIX
                q in code -> CODE_CONTAINS
                q in name -> NAME_CONTAINS
                else -> 0
            }
            account.takeIf { score > 0 }?.let { it to score }
        }.sortedByDescending { it.second }.map { it.first }
    }

    private const val EXACT = 5
    private const val CODE_PREFIX = 4
    private const val NAME_PREFIX = 3
    private const val CODE_CONTAINS = 2
    private const val NAME_CONTAINS = 1
}

private const val SPLIT_PENNY = 0.005
private const val DONE_BORDER_ALPHA = 0.35f
private const val MAX_MATCHES = 8
private val PREVIEW_COLUMN = 220.dp
private val PREVIEW_HEIGHT = 170.dp
private val SUGGESTION_WIDTH = 320.dp
