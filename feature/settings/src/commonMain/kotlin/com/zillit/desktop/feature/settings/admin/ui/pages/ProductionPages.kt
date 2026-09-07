package com.zillit.desktop.feature.settings.admin.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.settings.admin.domain.AdminUnit
import com.zillit.desktop.feature.settings.admin.domain.DeletionSchedule
import com.zillit.desktop.feature.settings.admin.domain.UnitKind
import com.zillit.desktop.feature.settings.admin.ui.AdminConfirmation
import com.zillit.desktop.feature.settings.admin.ui.AdminDestination
import com.zillit.desktop.feature.settings.admin.ui.AdminEvent
import com.zillit.desktop.feature.settings.admin.ui.AdminUiState
import com.zillit.desktop.feature.settings.admin.ui.NameKind

/**
 * The pages about the production itself — its name, its company block, its
 * watermark, its units, and ending it.
 */

/** The production's name, everywhere in Zillit. */
@Composable
fun ProductionNamePage(state: AdminUiState, onEvent: (AdminEvent) -> Unit, onBack: () -> Unit) {
    AdminPage(
        title = AdminDestination.ProductionName.title,
        description = "What this production is called everywhere in Zillit.",
        state = state,
        onEvent = onEvent,
        onBack = onBack,
    ) {
        ZillitSectionCard(title = "Name") {
            ZillitText(
                text = state.productionName.ifBlank { "Not set" },
                style = ZillitTheme.typography.titleMedium,
            )
            ZillitText(
                text = "Everyone on the production sees this, and it heads every document " +
                    "the production sends out.",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
            ZillitButton(
                text = "Change name",
                onClick = { onEvent(AdminEvent.OpenProductionName) },
                size = ButtonSize.Small,
                variant = ButtonVariant.Secondary,
            )
        }
    }
}

/**
 * The company block printed at the head of the crew list.
 *
 * Shown as what it is — a preview of the header — rather than as a form,
 * because that is the only place these fields are ever read. Editing opens the
 * form over it.
 */
@Suppress("LongMethod") // A header preview; every line is one printed field.
@Composable
fun CompanyDetailsPage(state: AdminUiState, onEvent: (AdminEvent) -> Unit, onBack: () -> Unit) {
    val company = state.company

    AdminPage(
        title = AdminDestination.CompanyDetails.title,
        description = "Printed at the head of the crew list and the documents this production sends.",
        state = state,
        onEvent = onEvent,
        onBack = onBack,
        action = {
            ZillitButton(
                text = "Edit details",
                onClick = { onEvent(AdminEvent.OpenCompany) },
                size = ButtonSize.Small,
            )
        },
    ) {
        ZillitSectionCard(title = "Crew list header") {
            if (company.name.isBlank() && company.address.isBlank()) {
                ZillitText(
                    text = "Nothing set. The crew list is printed without a company header.",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            } else {
                ZillitText(
                    text = company.name.ifBlank { "Unnamed company" },
                    style = ZillitTheme.typography.titleMedium,
                )
                HeaderLine("Address", company.address)
                HeaderLine(
                    "Phone",
                    listOf(company.countryCode, company.phone)
                        .filter { it.isNotBlank() }
                        .joinToString(" "),
                )
                HeaderLine("Email", company.email)
                HeaderLine("Company number", company.companyNumber)
                HeaderLine("Registered address", company.registeredAddress)
                company.customFields.forEach { field -> HeaderLine(field.label, field.value) }
            }
        }

        ZillitSectionCard(title = "Logo") {
            if (company.hasLogo) {
                ZillitText(
                    text = "A logo is set and printed above the company name.",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
                RemoveButton("Remove logo") {
                    onEvent(AdminEvent.Ask(AdminConfirmation.ClearCompanyLogo()))
                }
            } else {
                // Honest about the gap rather than offering a button that
                // cannot work: uploading needs the picker and the media
                // pipeline, which the phone and web clients have and this one
                // does not yet.
                ZillitNotice(
                    text = "No logo set. Uploading one is done on the phone or web app for now; " +
                        "this page can show and remove it.",
                    tone = StatusTone.Pending,
                    icon = ZillitIcons.Info,
                )
            }
        }
    }
}

@Composable
private fun HeaderLine(label: String, value: String) {
    if (value.isBlank()) return
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitText(
            text = "$label:",
            style = ZillitTheme.typography.label,
            color = ZillitTheme.colors.textMuted,
        )
        ZillitText(text = value, style = ZillitTheme.typography.bodySmall)
    }
}

/** The logo stamped across documents this production sends out. */
@Composable
fun WatermarkPage(state: AdminUiState, onEvent: (AdminEvent) -> Unit, onBack: () -> Unit) {
    AdminPage(
        title = AdminDestination.Watermark.title,
        description = "Stamped across documents this production sends out.",
        state = state,
        onEvent = onEvent,
        onBack = onBack,
    ) {
        ZillitSectionCard(title = "Current watermark") {
            if (state.watermarkUrl.isNullOrBlank()) {
                ZillitText(
                    text = "No watermark. Documents go out unstamped.",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            } else {
                ZillitText(
                    text = "A watermark is set and stamped on every document this production sends.",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
                RemoveButton("Remove watermark") {
                    onEvent(AdminEvent.Ask(AdminConfirmation.ClearWatermark()))
                }
            }
        }

        ZillitNotice(
            text = "Uploading a new watermark is done on the phone or web app for now; " +
                "this page can show and remove the one that is set.",
            tone = StatusTone.Pending,
            icon = ZillitIcons.Info,
        )
    }
}

/**
 * Units — the three kinds, one page each.
 *
 * One composable, because the row is identical and only the verbs differ: a
 * shooting unit can be switched off, a joined unit renamed, a remote unit
 * neither. Those differences come from [UnitKind] rather than from three copies
 * of this list.
 */
@Composable
fun UnitsPage(
    destination: AdminDestination,
    state: AdminUiState,
    onEvent: (AdminEvent) -> Unit,
    onBack: () -> Unit,
) {
    val kind = state.unitKind ?: UnitKind.Home

    AdminPage(
        title = destination.title,
        description = when (kind) {
            // Not shooting units. `home/unit` is the dashboard — bulletin,
            // calendar, call sheet — and calling them units is the server's
            // word, not the reader's.
            UnitKind.Home ->
                "The sections of this production's dashboard, and who can see each one."
            UnitKind.Remote ->
                "A unit shooting away from the main production, with its own board and call sheets."
            UnitKind.Shooting ->
                "Main, second and splinter units. Crew attach themselves to one when they join."
        },
        state = state,
        onEvent = onEvent,
        onBack = onBack,
        search = "Search units",
        action = {
            ZillitButton(
                text = "New unit",
                onClick = { onEvent(AdminEvent.OpenName(NameKind.Unit)) },
                size = ButtonSize.Small,
            )
        },
    ) {
        RowCard {
            val rows = state.unitsMatching
            when {
                rows.isEmpty() && state.hasLoaded && state.query.isNotBlank() ->
                    EmptyRow("No unit matches “${state.query}”.")

                rows.isEmpty() && state.hasLoaded ->
                    EmptyRow("This production has no ${destination.title.lowercase()} yet.")

                else -> rows.forEachIndexed { index, unit ->
                    if (index > 0) RowRule()
                    UnitRow(unit, onEvent)
                }
            }
        }

        if (kind == UnitKind.Remote) {
            // Not an omission on this client. The server offers no update or
            // delete route for remote units, and neither does any other client.
            ZillitNotice(
                text = "Remote units can be added but not renamed or removed. " +
                    "That is true on every Zillit app.",
                tone = StatusTone.Pending,
                icon = ZillitIcons.Info,
            )
        }
    }
}

@Composable
private fun UnitRow(unit: AdminUnit, onEvent: (AdminEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = unit.name.localised(),
            style = ZillitTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )

        if (unit.locked) ZillitTag("Built in", tone = TagTone.Neutral)

        // Only the dashboard sections have a switch — `home/unit/visibility`.
        // The other two kinds have no such route.
        if (unit.kind == UnitKind.Home) {
            ZillitCheckbox(
                checked = unit.enabled,
                onCheckedChange = { onEvent(AdminEvent.UnitEnabledChanged(unit.id, it)) },
                label = "In use",
            )
        }

        if (unit.kind != UnitKind.Remote && unit.isRemovable) {
            ZillitButton(
                text = "Rename",
                onClick = {
                    onEvent(
                        AdminEvent.OpenName(
                            kind = NameKind.Unit,
                            targetId = unit.id,
                            initial = unit.name.localised(),
                        ),
                    )
                },
                size = ButtonSize.Small,
                variant = ButtonVariant.Tertiary,
            )
            RemoveButton("Delete") {
                onEvent(
                    AdminEvent.Ask(
                        AdminConfirmation.RemoveUnit(unit.kind, unit.id, unit.name.localised()),
                    ),
                )
            }
        }
    }
}

/**
 * Ending the production.
 *
 * Never immediate: the delay is the safety, and the production stays visible
 * with a way to call it off until it elapses. The three delays are the choice
 * — there is no separate confirm step on top, because picking "48 hours" *is*
 * the deliberate act, and the confirmation names what goes.
 */
@Suppress("LongMethod") // One page, and the wording is most of it.
@Composable
fun DeleteProductionPage(state: AdminUiState, onEvent: (AdminEvent) -> Unit, onBack: () -> Unit) {
    AdminPage(
        title = AdminDestination.DeleteProduction.title,
        description = "Removes the production and everything in it, for everyone on it.",
        state = state,
        onEvent = onEvent,
        onBack = onBack,
    ) {
        if (state.deletion.isScheduled) {
            ZillitNotice(
                text = state.deletion.hours
                    ?.let { "This production is scheduled for deletion in $it hours." }
                    ?: "This production is scheduled for deletion.",
                tone = StatusTone.Rejected,
                icon = ZillitIcons.Warning,
                action = {
                    ZillitButton(
                        text = "Call it off",
                        onClick = { onEvent(AdminEvent.CancelDeletion) },
                        size = ButtonSize.Small,
                        enabled = !state.isSaving,
                    )
                },
            )
            return@AdminPage
        }

        ZillitSectionCard(title = "What goes") {
            ZillitText(
                text = "Every notice, document, timecard, expense and message on " +
                    "“${state.productionName.ifBlank { "this production" }}”, for everyone on it. " +
                    "Crew lose access the moment it happens.",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
            ZillitText(
                text = "Deletion is scheduled, not immediate. It can be called off from this " +
                    "page until the delay is up — after that it cannot.",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }

        ZillitSectionCard(title = "Schedule it") {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                DeletionSchedule.OFFERED_HOURS.forEach { hours ->
                    ZillitButton(
                        text = "In $hours hours",
                        onClick = {
                            onEvent(
                                AdminEvent.Ask(
                                    AdminConfirmation.DeleteProduction(
                                        hours = hours,
                                        name = state.productionName.ifBlank { "this production" },
                                    ),
                                ),
                            )
                        },
                        variant = ButtonVariant.Danger,
                        size = ButtonSize.Small,
                        enabled = !state.isSaving,
                    )
                }
            }
        }
    }
}
