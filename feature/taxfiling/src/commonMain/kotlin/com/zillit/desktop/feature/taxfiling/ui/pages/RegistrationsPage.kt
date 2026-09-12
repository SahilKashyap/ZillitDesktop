package com.zillit.desktop.feature.taxfiling.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.taxfiling.domain.SupportedFiling
import com.zillit.desktop.feature.taxfiling.domain.TaxFormat
import com.zillit.desktop.feature.taxfiling.domain.TaxRegistration
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingEvent
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingUiState
import com.zillit.desktop.feature.taxfiling.ui.components.MtdAvatar
import com.zillit.desktop.feature.taxfiling.ui.components.MtdButton
import com.zillit.desktop.feature.taxfiling.ui.components.MtdButtonSize
import com.zillit.desktop.feature.taxfiling.ui.components.MtdButtonVariant
import com.zillit.desktop.feature.taxfiling.ui.components.MtdCard
import com.zillit.desktop.feature.taxfiling.ui.components.MtdDot
import com.zillit.desktop.feature.taxfiling.ui.components.MtdIconTile
import com.zillit.desktop.feature.taxfiling.ui.components.MtdPill
import com.zillit.desktop.feature.taxfiling.ui.components.MtdRule
import com.zillit.desktop.feature.taxfiling.ui.components.MtdSkeletonRows
import com.zillit.desktop.feature.taxfiling.ui.components.PillTone
import com.zillit.desktop.feature.taxfiling.ui.components.mtdPalette
import com.zillit.desktop.feature.taxfiling.ui.components.mtdText

/**
 * One filing's companies — the web's `TaxFilingModule` list and
 * `RegistrationsView`: a card per registered VAT number, each saying whether
 * HMRC is connected and what can be done next.
 */
@Composable
internal fun RegistrationsPage(state: TaxFilingUiState, onEvent: (TaxFilingEvent) -> Unit) {
    PageTitle(
        icon = ZillitIcons.Hierarchy,
        title = SupportedFiling.MtdVat.title,
        description = "Register VAT numbers, connect to HMRC, and file Making Tax Digital VAT returns per company.",
    )
    val registrations = state.named
    when {
        state.loading && registrations.isEmpty() ->
            MtdCard(modifier = Modifier.fillMaxWidth(), padding = 0.dp) { MtdSkeletonRows() }
        registrations.isEmpty() -> RegistrationsEmpty(onRegister = { onEvent(TaxFilingEvent.ComposeRegistration) })
        else -> {
            ListHeader(count = registrations.size, onRegister = { onEvent(TaxFilingEvent.ComposeRegistration) })
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                registrations.forEach { registration ->
                    RegistrationCard(
                        registration = registration,
                        connecting = state.connectingId == registration.id,
                        canReachAuthority = state.canReachAuthority,
                        onEvent = onEvent,
                    )
                }
            }
        }
    }
}

@Composable
private fun ListHeader(count: Int, onRegister: () -> Unit) {
    val palette = mtdPalette()
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                ZillitText(
                    text = "Registered companies",
                    style = mtdText(16.5.sp, FontWeight.Bold, tracking = (-0.02).em),
                    color = palette.ink,
                )
                MtdPill(text = count.toString(), tone = PillTone.Neutral, mono = true)
            }
            ZillitText(
                text = "Connect a company to HMRC, open it to file a VAT return, or remove it.",
                style = mtdText(13.5.sp),
                color = palette.ink3,
            )
        }
        MtdButton(
            text = "Register a company",
            onClick = onRegister,
            variant = MtdButtonVariant.Primary,
            icon = ZillitIcons.Add,
        )
    }
}

/**
 * A registration: who, which number, how often — and a footer that says what
 * is next. Connect first; "Open VAT return" waits, locked, until HMRC is.
 */
@Composable
private fun RegistrationCard(
    registration: TaxRegistration,
    connecting: Boolean,
    canReachAuthority: Boolean,
    onEvent: (TaxFilingEvent) -> Unit,
) {
    val palette = mtdPalette()
    MtdCard(modifier = Modifier.fillMaxWidth(), padding = 0.dp, hoverLift = true) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            MtdAvatar(name = registration.companyName, size = 46.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                ZillitText(
                    text = registration.companyName,
                    style = mtdText(16.5.sp, FontWeight.Bold, tracking = (-0.02).em),
                    color = palette.ink,
                    maxLines = 1,
                )
                RegistrationFacts(registration)
            }
            MtdPill(
                text = registration.status,
                tone = if (registration.isActive) PillTone.Active else PillTone.Neutral,
            )
        }
        MtdRule()
        RegistrationActions(registration, connecting, canReachAuthority, onEvent)
    }
}

/** The card's foot: the connection on the left, then export, remove, and the way into the return. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RegistrationActions(
    registration: TaxRegistration,
    connecting: Boolean,
    canReachAuthority: Boolean,
    onEvent: (TaxFilingEvent) -> Unit,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(mtdPalette().surface2)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        ConnectionState(registration, connecting, canReachAuthority, onEvent, Modifier.weight(1f))
        MtdButton(
            text = "Export data",
            onClick = { onEvent(TaxFilingEvent.ExportData(registration)) },
            variant = MtdButtonVariant.Ghost,
            size = MtdButtonSize.Small,
        )
        MtdButton(
            text = "Remove",
            onClick = { onEvent(TaxFilingEvent.AskRemove(registration)) },
            variant = MtdButtonVariant.Danger,
            size = MtdButtonSize.Small,
            icon = ZillitIcons.Trash,
        )
        MtdButton(
            text = "Open VAT return",
            onClick = { onEvent(TaxFilingEvent.Open(registration)) },
            variant = MtdButtonVariant.Secondary,
            size = MtdButtonSize.Small,
            icon = if (registration.connected) null else ZillitIcons.Lock,
            iconRight = if (registration.connected) ZillitIcons.ArrowRight else null,
            enabled = registration.connected,
            tinted = registration.connected,
        )
    }
}

/** `VRN 123 456 789 · Quarterly filing`. */
@Composable
internal fun RegistrationFacts(registration: TaxRegistration, trailing: (@Composable () -> Unit)? = null) {
    val palette = mtdPalette()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        ZillitText(
            text = "VRN ${TaxFormat.vrn(registration.registrationNumber)}",
            style = mtdText(13.sp, FontWeight.SemiBold, mono = true, tracking = 0.02.em),
            color = palette.ink2,
            maxLines = 1,
        )
        MtdDot()
        ZillitText(
            text = "${TaxFormat.frequency(registration.filingFrequency)} filing",
            style = mtdText(13.sp),
            color = palette.ink3,
            maxLines = 1,
        )
        if (trailing != null) {
            MtdDot()
            trailing()
        }
    }
}

/**
 * The footer's left side: connected, connecting, or the button that starts it.
 *
 * While HMRC's consent is open in the browser the card says so, and offers a
 * way to stop waiting — there is no page for HMRC to send a desktop back to.
 */
@Composable
private fun ConnectionState(
    registration: TaxRegistration,
    connecting: Boolean,
    canReachAuthority: Boolean,
    onEvent: (TaxFilingEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = mtdPalette()
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            registration.connected -> {
                ZillitIcon(icon = ZillitIcons.Shield, tint = palette.green, size = 15.dp)
                ZillitText(
                    text = "Connected to HMRC",
                    style = mtdText(12.5.sp, FontWeight.SemiBold),
                    color = palette.green,
                )
            }
            connecting -> {
                MtdButton(
                    text = "Connecting…",
                    onClick = {},
                    variant = MtdButtonVariant.Primary,
                    size = MtdButtonSize.Small,
                    loading = true,
                )
                ZillitText(
                    text = "Finish signing in to HMRC in your browser.",
                    style = mtdText(12.sp),
                    color = palette.muted,
                )
                MtdButton(
                    text = "Cancel",
                    onClick = { onEvent(TaxFilingEvent.CancelConnect) },
                    variant = MtdButtonVariant.Ghost,
                    size = MtdButtonSize.Small,
                )
            }
            else -> {
                MtdButton(
                    text = "Connect to HMRC",
                    onClick = { onEvent(TaxFilingEvent.Connect(registration)) },
                    variant = MtdButtonVariant.Primary,
                    size = MtdButtonSize.Small,
                    icon = ZillitIcons.Link,
                    enabled = canReachAuthority,
                )
                ZillitText(text = "required to file a return", style = mtdText(12.sp), color = palette.muted)
            }
        }
    }
}

/** No registrations yet: what this is for, the one button, and the three steps. */
@Composable
private fun RegistrationsEmpty(onRegister: () -> Unit) {
    val palette = mtdPalette()
    MtdCard(modifier = Modifier.fillMaxWidth(), padding = 0.dp) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 62.dp, bottom = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MtdIconTile(icon = ZillitIcons.Building, size = 84.dp, iconSize = 40.dp, radius = 24.dp)
            Spacer(Modifier.height(22.dp))
            ZillitText(
                text = "No companies registered yet",
                style = mtdText(20.sp, FontWeight.Bold, tracking = (-0.02).em),
                color = palette.ink,
            )
            Spacer(Modifier.height(8.dp))
            ZillitText(
                text = "Register a company’s VAT number to connect it to HMRC and file Making Tax Digital " +
                    "returns. You can register as many companies as you file for.",
                style = mtdText(14.5.sp),
                color = palette.ink3,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 440.dp),
            )
            Spacer(Modifier.height(26.dp))
            MtdButton(
                text = "Register a company",
                onClick = onRegister,
                variant = MtdButtonVariant.Primary,
                size = MtdButtonSize.Large,
                icon = ZillitIcons.Add,
            )
        }
        MtdRule()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.surface2)
                .padding(horizontal = 20.dp, vertical = 15.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepHint(1, "Register VRN")
            ZillitIcon(icon = ZillitIcons.ArrowRight, tint = palette.faint, size = 14.dp)
            StepHint(2, "Connect to HMRC")
            ZillitIcon(icon = ZillitIcons.ArrowRight, tint = palette.faint, size = 14.dp)
            StepHint(3, "File VAT return")
        }
    }
}

@Composable
private fun StepHint(number: Int, label: String) {
    val palette = mtdPalette()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(palette.surface)
                .border(1.dp, palette.border2, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            ZillitText(
                text = number.toString(),
                style = mtdText(11.sp, FontWeight.Bold, mono = true, lineHeight = 12.sp),
                color = palette.ink3,
            )
        }
        ZillitText(text = label, style = mtdText(12.5.sp, FontWeight.SemiBold), color = palette.ink3)
    }
}
