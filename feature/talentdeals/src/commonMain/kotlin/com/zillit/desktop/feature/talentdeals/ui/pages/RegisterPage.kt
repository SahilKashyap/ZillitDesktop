package com.zillit.desktop.feature.talentdeals.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitErrorState
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.talentdeals.domain.TalentContract
import com.zillit.desktop.feature.talentdeals.domain.TalentNumbers
import com.zillit.desktop.feature.talentdeals.ui.PAGE_GUTTER
import com.zillit.desktop.feature.talentdeals.ui.RegisterState
import com.zillit.desktop.feature.talentdeals.ui.TalentDealsEvent
import com.zillit.desktop.feature.talentdeals.ui.TalentDealsUiState

/**
 * The register — the contracts uploaded through Add Contract, and nothing else
 * (`TalentContractsPage.jsx`): a search, Add Contract for those who post, and a
 * card of rows — Talent / Type / Contract / Payment terms. A row opens the
 * read-only contract; the remove chip (posting only) asks first.
 */
@Composable
internal fun RegisterPage(state: TalentDealsUiState, onEvent: (TalentDealsEvent) -> Unit,
    modifier: Modifier = Modifier) {
    val register = state.register
    val canPost = state.viewer.canPost
    Column(modifier.fillMaxSize().padding(horizontal = PAGE_GUTTER, vertical = PAGE_GUTTER)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = ZillitTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitSearchField(
                value = register.query,
                onValueChange = { onEvent(TalentDealsEvent.Search(it)) },
                placeholder = str(S.desktop_td_search_placeholder),
                modifier = Modifier.weight(1f),
            )
            if (canPost) {
                ZillitButton(
                    text = str(S.desktop_td_add_contract),
                    leadingIcon = ZillitIcons.Add,
                    onClick = { onEvent(TalentDealsEvent.AddContract) },
                )
            }
        }
        RegisterBody(state, register, canPost, onEvent)
    }
}

@Composable
private fun RegisterBody(
    state: TalentDealsUiState,
    register: RegisterState,
    canPost: Boolean,
    onEvent: (TalentDealsEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.large),
    ) {
        HeaderRow()
        when {
            register.loading -> SkeletonRows()
            register.error != null -> ZillitErrorState(
                message = register.error,
                onRetry = { onEvent(TalentDealsEvent.Retry) },
                modifier = Modifier.fillMaxSize(),
            )
            register.visible.isEmpty() -> ZillitEmptyState(
                title = when {
                    register.contracts.isNotEmpty() -> str(S.desktop_td_no_match)
                    canPost -> str(S.desktop_td_empty_can_post)
                    else -> str(S.desktop_td_empty)
                },
                icon = ZillitIcons.File,
                modifier = Modifier.fillMaxSize(),
            )
            else -> ZillitLazyColumn(Modifier.fillMaxSize()) {
                items(register.visible, key = TalentContract::id) { contract ->
                    ContractRow(contract, symbol = state.reference.currencies.get(contract.currency).symbol, canPost,
                        onEvent)
                }
            }
        }
    }
}

@Composable
private fun HeaderRow() {
    val colors = ZillitTheme.colors
    Row(
        Modifier.fillMaxWidth().background(colors.surfaceSunken).padding(horizontal = ZillitTheme.spacing.lg,
            vertical = ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        listOf(
            str(S.talent) to TALENT_WEIGHT,
            str(S.type) to TYPE_WEIGHT,
            str(S.contract_text) to CONTRACT_WEIGHT,
            str(S.desktop_td_col_terms) to TERMS_WEIGHT,
        ).forEach { (title, weight) ->
            ZillitText(
                text = title.uppercase(),
                style = ZillitTheme.typography.columnHeader,
                color = colors.textMuted,
                maxLines = 1,
                modifier = Modifier.weight(weight),
            )
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
}

@Composable
private fun SkeletonRows() {
    Column(Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
        repeat(SKELETON_ROWS) { ZillitSkeletonBar(Modifier.fillMaxWidth(), height = 28.dp) }
    }
}

/** One contract: name over reference, a role tag, the agreement's file name, and fee over terms. */
@Composable
private fun ContractRow(contract: TalentContract, symbol: String, canPost: Boolean,
    onEvent: (TalentDealsEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(Modifier.fillMaxWidth().clickable { onEvent(TalentDealsEvent.OpenContract(contract.id)) }) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Column(Modifier.weight(TALENT_WEIGHT)) {
                ZillitText(contract.person, style = ZillitTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1)
                ZillitText(contract.reference, style = ZillitTheme.typography.numeric, color = colors.textMuted,
                    maxLines = 1)
            }
            Box(Modifier.weight(TYPE_WEIGHT)) {
                ZillitStatusPill(label = contract.role, tone = roleTone(contract.role))
            }
            Box(Modifier.weight(CONTRACT_WEIGHT)) {
                val file = contract.longFormContract?.name?.takeIf { it.isNotBlank() }
                ZillitText(
                    text = file ?: str(S.desktop_td_not_uploaded),
                    color = if (file != null) colors.textSecondary else colors.textMuted,
                    maxLines = 1,
                )
            }
            Row(Modifier.weight(TERMS_WEIGHT), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    ZillitText(
                        TalentNumbers.formatMoney(contract.fee, symbol.ifBlank { contract.currency }),
                        style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                    )
                    termsLine(contract).takeIf { it.isNotEmpty() }?.let {
                        ZillitText(it, style = ZillitTheme.typography.labelSmall, color = colors.textMuted,
                            maxLines = 1)
                    }
                }
                if (canPost) {
                    ZillitIconButton(
                        icon = ZillitIcons.Trash,
                        contentDescription = str(S.bs_chip_remove, contract.reference),
                        onClick = { onEvent(TalentDealsEvent.RequestRemove(contract)) },
                        modifier = Modifier.width(REMOVE_CHIP),
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
    }
}

/** `Weekly · 3 tranches` — the cadence word, then the tranche count when there are any. */
internal fun termsLine(contract: TalentContract): String {
    val cadence = contract.schedule.takeIf { it.isWeekly }?.let { str(S.ce_weekly) }
    val count = contract.trancheCount
    val tranches = when {
        count == 1 -> str(S.desktop_td_tranche_one, count)
        count > 1 -> str(S.desktop_td_tranche_other, count)
        else -> null
    }
    return listOfNotNull(cadence, tranches).joinToString(" · ")
}

@Composable
private fun roleTone(role: String): StatusTone = when (role.lowercase()) {
    "director" -> StatusTone.Escalated
    "cast" -> StatusTone.Pending
    "producer" -> StatusTone.Ready
    else -> StatusTone.Neutral
}

private const val TALENT_WEIGHT = 0.38f
private const val TYPE_WEIGHT = 0.16f
private const val CONTRACT_WEIGHT = 0.26f
private const val TERMS_WEIGHT = 0.30f
private const val SKELETON_ROWS = 3
private val REMOVE_CHIP = 32.dp
