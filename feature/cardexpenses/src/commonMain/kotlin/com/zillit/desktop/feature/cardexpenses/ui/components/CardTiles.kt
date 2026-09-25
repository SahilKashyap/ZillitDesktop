package com.zillit.desktop.feature.cardexpenses.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.CardBadge
import com.zillit.desktop.feature.cardexpenses.domain.CardChain
import com.zillit.desktop.feature.cardexpenses.domain.CardChainStep
import com.zillit.desktop.feature.cardexpenses.domain.CardRules
import com.zillit.desktop.feature.cardexpenses.domain.CardStatus
import com.zillit.desktop.feature.cardexpenses.domain.ChainStepStatus
import com.zillit.desktop.feature.cardexpenses.domain.ExpenseCard
import com.zillit.desktop.feature.cardexpenses.ui.CardEvent
import com.zillit.desktop.feature.cardexpenses.ui.CardUiState
import com.zillit.desktop.feature.cardexpenses.ui.CardsEvent
import com.zillit.desktop.feature.cardexpenses.ui.defaultCurrency
import com.zillit.desktop.feature.cardexpenses.ui.money
import com.zillit.desktop.feature.cardexpenses.ui.unreadRow

/**
 * The register's gradient card tile — the web's `AdminCardVisual`
 * (`ui/adminUi.jsx:134-349`), on both the accountant's register and the
 * cardholder's Card tab.
 *
 * Every status draws the same card: a live one its balance and usage, one in
 * flight its proposed limit and its lifecycle — the chain, Approve / Reject /
 * Override, Activate, Assign Physical — on the card itself. The body opens the
 * card; the buttons on it do their own thing.
 *
 * [console] is the accountant's register, which offers the lifecycle actions;
 * the Card tab passes false and shows the chain for reading only.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // One card, top to bottom, as the web draws it.
@Composable
fun CardTile(state: CardUiState, card: ExpenseCard, console: Boolean, onEvent: (CardEvent) -> Unit) {
    val viewer = state.viewer
    val live = card.status == CardStatus.Active || card.status == CardStatus.Suspended
    val step = state.cardApproval(card)
    val steps = if (card.status == CardStatus.Pending) {
        CardChain.steps(card, viewer.metadata.tierConfigs)
    } else {
        emptyList()
    }
    val limit = card.tileLimit
    val balance = card.balance ?: limit
    val used = if (limit > 0) ((limit - balance).coerceAtLeast(0.0) / limit).toFloat().coerceIn(0f, 1f) else 0f
    val currency = card.currency ?: state.defaultCurrency
    val busy = state.busy && state.cardsArea.actionCardId == card.id
    val showEdit = CardRules.canEditRequest(card, viewer.userId, viewer.isAccountant)
    val showDelete = CardRules.canDeleteRequest(card, viewer.userId)
    val holder = state.people.firstOrNull { it.id == card.holderId }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(TILE_SHAPE)
            .background(cardGradient(card))
            .clickable { onEvent(CardsEvent.OpenCard(card.id)) },
    ) {
        Column(modifier = Modifier.padding(TILE_PADDING)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CardChip()
                // The card's own unread, beside the chip (`adminUi.jsx:214`): the
                // register counts under card_register (`CardRegisterPage.jsx:1017`),
                // the Card tab under my_cards (`UserCardsPage.jsx:385`).
                ZillitBadge(count = state.unreadRow(if (console) "card_register" else "my_cards", card.id))
                ZillitText(
                    text = if (live) "•••• ${card.lastFour ?: "0000"}" else str(S.desktop_ce_cards_masked),
                    style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                    color = if (live) Color.White else OnCardMuted,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                TileBadge(badgeText(CardBadge.of(card, step.totalTiers, viewer.isAccountant)))
                if (showEdit) {
                    TileIcon(
                        icon = ZillitIcons.Edit,
                        tip = if (viewer.isAccountant && card.status == CardStatus.Requested) {
                            str(S.desktop_ce_cards_review_request)
                        } else {
                            str(S.desktop_card_edit_card_details)
                        },
                        onClick = { onEvent(CardEvent.OpenCardEdit(card.id)) },
                    )
                }
                if (showDelete) {
                    TileIcon(
                        icon = ZillitIcons.Trash,
                        tip = str(S.desktop_ce_cards_delete_request_tip),
                        onClick = { onEvent(CardsEvent.AskDelete(card.id)) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            if (live) {
                ZillitText(
                    text = money(balance.coerceAtLeast(0.0), currency),
                    style = AmountStyle,
                    color = Color.White,
                    maxLines = 1,
                )
                ZillitText(
                    text = str(S.desktop_card_available_of, money(limit, currency)),
                    style = ZillitTheme.typography.bodySmall,
                    color = OnCardSoft,
                )
                Spacer(Modifier.height(12.dp))
                UsageBar(used)
            } else {
                ZillitText(text = money(limit, currency), style = AmountStyle, color = Color.White, maxLines = 1)
                ZillitText(
                    text = str(S.desktop_ce_cards_proposed_limit_lower),
                    style = ZillitTheme.typography.bodySmall,
                    color = OnCardSoft,
                )
            }

            TileRule()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ZillitText(
                            text = holder?.name?.takeIf { it.isNotBlank() } ?: str(S.desktop_card_card_holder),
                            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                            maxLines = 1,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        companyName(state, card)?.let { CompanyChip(it) }
                    }
                    holder?.designation?.takeIf { it.isNotBlank() }?.let {
                        ZillitText(
                            text = it,
                            style = ZillitTheme.typography.bodySmall,
                            color = OnCardMuted,
                            maxLines = 1,
                        )
                    }
                }
                card.bsControlCode?.takeIf { it.isNotBlank() }?.let { code ->
                    ZillitText(
                        text = str(S.desktop_ce_cards_bs_code_chip, code),
                        style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
                        color = OnCardStrong,
                        maxLines = 1,
                    )
                }
            }

            if ((card.status == CardStatus.Pending || card.status == CardStatus.Requested) && steps.isNotEmpty()) {
                TileRule()
                ChainStrip(state, steps)
            }

            if (console && card.status == CardStatus.Pending) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                ) {
                    if (viewer.canOverrideCard && !step.canApprove) {
                        TileButton(str(S.dm_nom_table_override), OverrideFill, OverrideInk, !busy) {
                            onEvent(CardsEvent.AskOverride(card.id))
                        }
                    }
                    if (step.canApprove) {
                        TileButton(str(S.reject), RejectFill, Color.White, !busy) {
                            onEvent(CardsEvent.AskReject(card.id))
                        }
                        TileButton(if (busy) "…" else str(S.approve), ApproveFill, Color.White, !busy) {
                            onEvent(CardsEvent.Approve(card.id))
                        }
                    }
                }
            }

            if (console && (card.status == CardStatus.Approved || card.status == CardStatus.Override)) {
                TileWideButton(str(S.desktop_card_activate_assign_number), ActivateInk) {
                    onEvent(CardEvent.OpenActivation(card.id))
                }
            }
            if (console && card.isDigitalActive) {
                TileWideButton(str(S.desktop_ce_cards_assign_physical), DigitalInk, ZillitIcons.CreditCard) {
                    onEvent(CardsEvent.AskAssignPhysical(card.id))
                }
            }

            if (card.status == CardStatus.Rejected && !card.rejectionReason.isNullOrBlank()) {
                TileRule()
                ZillitText(
                    text = str(S.cs_rejection_reason).uppercase(),
                    style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = OnCardMuted,
                )
                ZillitText(text = card.rejectionReason, style = ZillitTheme.typography.bodySmall, color = OnCardStrong)
            }

            if (card.status == CardStatus.Requested && viewer.isAccountant) {
                TileRule()
                ZillitText(
                    text = str(S.desktop_ce_cards_needs_review),
                    style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = NeedsReviewInk,
                )
            }
        }
    }
}

/** The approval chain as the tile draws it — a dot per level, the signer under it. */
@Composable
private fun ChainStrip(state: CardUiState, steps: List<CardChainStep>) {
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.Top) {
        steps.forEachIndexed { index, step ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(min = 66.dp)) {
                StepDot(step.status, onCard = true)
                ZillitText(
                    text = str(S.desktop_level_n, step.tier),
                    style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = if (step.status == ChainStepStatus.Waiting) OnCardFaint else OnCardStrong,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (step.status == ChainStepStatus.Approved) {
                    ZillitText(
                        text = state.personName(step.approverId),
                        style = ZillitTheme.typography.labelSmall,
                        color = OnCardMuted,
                        maxLines = 1,
                    )
                } else {
                    ZillitText(
                        text = str(S.ds_sent_filter_awaiting),
                        style = ZillitTheme.typography.labelSmall,
                        color = OnCardFaint,
                    )
                }
            }
            if (index < steps.lastIndex) {
                Box(
                    Modifier
                        .padding(top = 13.dp)
                        .width(20.dp)
                        .height(2.dp)
                        .background(if (step.status == ChainStepStatus.Approved) ChainDone else OnCardTrack),
                )
            }
        }
    }
}

/** A chain level's marker: a tick when signed, a ring with a dot when it is next, hollow otherwise. */
@Composable
fun StepDot(status: ChainStepStatus, onCard: Boolean) {
    val colors = ZillitTheme.colors
    when (status) {
        ChainStepStatus.Approved -> Box(
            Modifier.size(DOT).clip(ZillitTheme.shapes.pill).background(if (onCard) ChainDone else colors.success),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(ZillitIcons.Check, tint = if (onCard) ChainDoneInk else Color.White, size = 14.dp)
        }

        ChainStepStatus.Current -> Box(
            Modifier.size(DOT).clip(ZillitTheme.shapes.pill).background(if (onCard) Color.White else colors.accent),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(10.dp).clip(ZillitTheme.shapes.pill)
                    .background(if (onCard) colors.accent else Color.White),
            )
        }

        ChainStepStatus.Waiting -> Box(
            Modifier
                .size(DOT)
                .clip(ZillitTheme.shapes.pill)
                .background(if (onCard) OnCardTrack else colors.surfaceSunken)
                .border(2.dp, if (onCard) OnCardFaint else colors.borderStrong, ZillitTheme.shapes.pill),
        )
    }
}

/** The tile's words for its badge. */
fun badgeText(badge: CardBadge): String = when (badge) {
    CardBadge.DigitalActive -> str(S.desktop_ce_cards_digital_active)
    CardBadge.InProgress -> str(S.desktop_ce_cards_in_progress)
    is CardBadge.PendingTiers -> str(S.ah_status_pending_progress, badge.signed, badge.total)
    is CardBadge.Plain -> badge.status.label
}

/**
 * The figure a tile shows as the card's limit: `card_limit`, then
 * `monthly_limit`, then the proposal (`adminUi.jsx:172`).
 */
val ExpenseCard.tileLimit: Double
    get() = limit.takeIf { it > 0 } ?: monthlyLimit?.takeIf { it > 0 } ?: proposedLimit ?: 0.0

/**
 * The company the card is pinned to, or the one owning its bank — so a card
 * from before the pin still shows its chip (`CardRegisterPage.jsx:996-1002`).
 */
fun companyName(state: CardUiState, card: ExpenseCard): String? {
    val companies = state.cardsArea.reference.companies
    return (
        companies.firstOrNull { it.id == card.companyId }
            ?: card.issuer?.let { bank -> companies.firstOrNull { bank in it.bankIds } }
        )?.name?.takeIf { it.isNotBlank() }
}

/** The per-status gradient; teal for a card that is live on its virtual number only. */
private fun cardGradient(card: ExpenseCard): Brush = Brush.linearGradient(
    if (card.isDigitalActive) {
        GradDigital
    } else {
        when (card.status) {
            CardStatus.Active -> GradActive
            CardStatus.Suspended -> GradSuspended
            CardStatus.Pending -> GradPending
            CardStatus.Approved -> GradApproved
            CardStatus.Override -> GradOverride
            CardStatus.Rejected -> GradRejected
            else -> GradRequested
        }
    },
)

/** The EMV chip in the tile's corner. */
@Composable
private fun CardChip() {
    Box(
        Modifier
            .width(34.dp)
            .height(24.dp)
            .clip(ZillitTheme.shapes.small)
            .background(OnCardTrack)
            .border(1.dp, OnCardSoft, ZillitTheme.shapes.small),
    )
}

@Composable
private fun TileBadge(text: String) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
        color = OnCardStrong,
        maxLines = 1,
        modifier = Modifier
            .clip(ZillitTheme.shapes.medium)
            .background(OnCardTrack)
            .border(1.dp, OnCardFaint, ZillitTheme.shapes.medium)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun CompanyChip(name: String) {
    ZillitText(
        text = name,
        style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = Color.White,
        maxLines = 1,
        modifier = Modifier
            .clip(ZillitTheme.shapes.small)
            .background(OnCardTrack)
            .border(1.dp, OnCardFaint, ZillitTheme.shapes.small)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun TileIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, tip: String, onClick: () -> Unit) {
    ZillitTooltip(tip) {
        Box(
            Modifier.size(24.dp).clip(ZillitTheme.shapes.pill).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(icon, contentDescription = tip, tint = OnCardStrong, size = 14.dp)
        }
    }
}

@Composable
private fun UsageBar(fraction: Float) {
    Box(Modifier.fillMaxWidth().height(6.dp).clip(ZillitTheme.shapes.pill).background(OnCardTrack)) {
        Box(Modifier.fillMaxWidth(fraction).height(6.dp).clip(ZillitTheme.shapes.pill).background(Color.White))
    }
}

@Composable
private fun TileRule() {
    Spacer(Modifier.height(12.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(OnCardRule))
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun TileButton(text: String, fill: Color, ink: Color, enabled: Boolean, onClick: () -> Unit) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = ink,
        modifier = Modifier
            .clip(ZillitTheme.shapes.medium)
            .background(if (enabled) fill else fill.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
private fun TileWideButton(
    text: String,
    ink: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(Color.White)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { ZillitIcon(it, tint = ink, size = 13.dp, modifier = Modifier.padding(end = 6.dp)) }
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
            color = ink,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The tile grid: three across on a wide register, two on a narrower one, one
 * inside a tight hub column (`grid-cols-1 sm:grid-cols-2 lg:grid-cols-3`).
 * Tiles top-align, as the web's `items-start`.
 */
@Composable
fun <T> TileGrid(items: List<T>, modifier: Modifier = Modifier, tile: @Composable (T) -> Unit) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val columns = columnsFor(maxWidth)
        Column(verticalArrangement = Arrangement.spacedBy(GRID_GAP)) {
            items.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(GRID_GAP), verticalAlignment = Alignment.Top) {
                    row.forEach { item -> Box(Modifier.weight(1f)) { tile(item) } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** The skeleton grid while the register loads — card silhouettes, not "Loading…". */
@Composable
fun TileSkeletons(modifier: Modifier = Modifier) {
    TileGrid(List(SKELETONS) { it }, modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(TILE_SHAPE)
                .background(ZillitTheme.colors.surface)
                .border(1.dp, ZillitTheme.colors.border, TILE_SHAPE)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ZillitSkeletonBar(Modifier.width(96.dp))
            ZillitSkeletonBar(Modifier.width(160.dp))
            ZillitSkeletonBar(Modifier.width(112.dp))
            ZillitSkeletonBar(Modifier.fillMaxWidth(), height = 6.dp)
        }
    }
}

private fun columnsFor(width: Dp): Int = when {
    width >= THREE_UP -> THREE_COLUMNS
    width >= TWO_UP -> 2
    else -> 1
}

private const val THREE_COLUMNS = 3

private val TILE_SHAPE = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
private val TILE_PADDING = 20.dp
private val DOT = 28.dp
private val GRID_GAP = 16.dp
private val THREE_UP = 900.dp
private val TWO_UP = 540.dp
private const val SKELETONS = 6

private val AmountStyle = androidx.compose.ui.text.TextStyle(
    fontSize = 24.sp,
    lineHeight = 30.sp,
    fontWeight = FontWeight.ExtraBold,
)

// The tile is a card on a fixed gradient in both themes, so its inks are white
// at the web's opacities rather than theme tokens.
private val OnCardStrong = Color.White.copy(alpha = 0.9f)
private val OnCardSoft = Color.White.copy(alpha = 0.78f)
private val OnCardMuted = Color.White.copy(alpha = 0.7f)
private val OnCardFaint = Color.White.copy(alpha = 0.5f)
private val OnCardTrack = Color.White.copy(alpha = 0.2f)
private val OnCardRule = Color.White.copy(alpha = 0.2f)
private val ChainDone = Color(0xFF4ADE80)
private val ChainDoneInk = Color(0xFF0B3D23)
private val OverrideFill = Color(0xFFFBBF24)
private val OverrideInk = Color(0xFF4A2C05)
private val RejectFill = Color(0xFFEF4444)
private val ApproveFill = Color(0xFF22C55E)
private val ActivateInk = Color(0xFF7A3D05)
private val DigitalInk = Color(0xFF0F766E)
private val NeedsReviewInk = Color(0xFFFDE68A)

// `CARD_STATUS_META` (`adminUi.jsx:117-125`), and the digital-active teal (`:165-167`).
private val GradActive = listOf(Color(0xFF2F7D52), Color(0xFF134E31))
private val GradSuspended = listOf(Color(0xFF545B69), Color(0xFF2C313B))
private val GradPending = listOf(Color(0xFF4A5160), Color(0xFF262B34))
private val GradRequested = listOf(Color(0xFF5A6270), Color(0xFF2F343D))
private val GradApproved = listOf(Color(0xFFF0982F), Color(0xFFC9670F), Color(0xFF7A3D05))
private val GradOverride = listOf(Color(0xFF6D4AAF), Color(0xFF3A2566))
private val GradRejected = listOf(Color(0xFF7E4A52), Color(0xFF43242A))
private val GradDigital = listOf(Color(0xFF14B8A6), Color(0xFF0D9488), Color(0xFF115E59))
