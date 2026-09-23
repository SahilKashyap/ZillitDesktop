package com.zillit.desktop.feature.crewlist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.crewlist.domain.CrewUnit
import com.zillit.desktop.feature.crewlist.ui.components.CrewCopy
import com.zillit.desktop.feature.crewlist.ui.components.CrewNoticeBanner
import com.zillit.desktop.feature.crewlist.ui.components.CrewRoster
import com.zillit.desktop.feature.crewlist.ui.components.CrewRosterModel
import com.zillit.desktop.feature.crewlist.ui.components.CrewTitleBar
import com.zillit.desktop.feature.crewlist.ui.components.CrewToolbar
import com.zillit.desktop.feature.crewlist.ui.components.CrewToolbarModel
import com.zillit.desktop.feature.crewlist.ui.components.EditsHint
import com.zillit.desktop.feature.crewlist.ui.components.rememberCrewCopy
import com.zillit.desktop.feature.crewlist.ui.dialogs.CompanyDetailsDrawer
import com.zillit.desktop.feature.crewlist.ui.dialogs.CrewPdfViewer
import com.zillit.desktop.feature.crewlist.ui.dialogs.CustomiseDialog
import com.zillit.desktop.feature.crewlist.ui.dialogs.CustomiseModel
import com.zillit.desktop.feature.crewlist.ui.dialogs.DepartmentOrderDialog
import com.zillit.desktop.feature.crewlist.ui.dialogs.DistributionDialogs
import com.zillit.desktop.feature.crewlist.ui.dialogs.GeneratePdfDialog
import com.zillit.desktop.feature.crewlist.ui.dialogs.MemberProfileDrawer
import com.zillit.desktop.feature.crewlist.ui.dialogs.PublishConfirmDialog
import com.zillit.desktop.feature.crewlist.ui.dialogs.WorkingOverlay
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The Crew List — `CrewListCustom.jsx`: title and banner, the toolbar, the
 * grouped sheet, and every surface laid over it.
 */
@Composable
fun CrewListScreen(
    state: CrewListUiState,
    visibleUnits: () -> List<CrewUnit>,
    onEvent: (CrewListEvent) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The Crew List widget's shape: the roster, the search and the PDF — no
     * designer, no edits, no publishing (it may be showing another production).
     */
    compact: Boolean = false,
    /** Opens the Crew List widget; null inside the widget itself, and in tests. */
    onOpenWidget: (() -> Unit)? = null,
    slots: CrewListSlots = CrewListSlots(),
    /**
     * Something outside the screen is drawn over it — the frame's rights
     * prompt, or the tool's toast — so the Customise canvas steps aside.
     */
    canvasCovered: Boolean = false,
) {
    val viewer = state.viewer
    val copy = rememberCrewCopy(viewer.toolName)
    // White like the web's sheet, so the bands and rows read as one document.
    Box(modifier.fillMaxSize().background(ZillitTheme.colors.surface)) {
        if (viewer.isBlocked) {
            ZillitText(
                text = str(S.desktop_cl_no_access_to_tool, viewer.toolName),
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textMuted,
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }
        Sheet(state, visibleUnits, onEvent, compact, onOpenWidget, slots, copy)
        Overlays(state, onEvent, compact, slots, copy, canvasCovered)
    }
}

@Composable
private fun Sheet(
    state: CrewListUiState,
    visibleUnits: () -> List<CrewUnit>,
    onEvent: (CrewListEvent) -> Unit,
    compact: Boolean,
    onOpenWidget: (() -> Unit)?,
    slots: CrewListSlots,
    copy: CrewCopy,
) {
    val viewer = state.viewer
    Column(Modifier.fillMaxSize()) {
        if (!compact) {
            CrewTitleBar(title = viewer.toolName, summary = summary(state.units), onOpenWidget = onOpenWidget)
            CrewNoticeBanner(
                copy = copy,
                showOrderLine = viewer.isAdmin,
                showEmailLine = viewer.canPost,
                onOpenOrder = { onEvent(CrewListEvent.Admin.OpenDepartments) },
            )
        }
        CrewToolbar(
            model = CrewToolbarModel(
                busy = state.working != null || (state.isLoading && !state.hasLoaded),
                editing = state.editing,
                // Offered to everyone; a press without the posting right asks an
                // admin (the rights-request flip).
                showAddExternal = slots.externalUserForm != null,
                query = state.query,
                compact = compact,
            ),
            copy = copy,
            onGenerate = { onEvent(CrewListEvent.Document.OpenChooser) },
            onPreview = { onEvent(CrewListEvent.Design.Open) },
            onRefresh = { onEvent(CrewListEvent.Sheet.Refresh) },
            onAddExternal = { onEvent(CrewListEvent.Sheet.AddExternalUser) },
            onEdit = { onEvent(CrewListEvent.Sheet.StartEditing) },
            onDone = { onEvent(CrewListEvent.Sheet.DoneEditing) },
            onSearch = { onEvent(CrewListEvent.Sheet.Search(it)) },
        )
        EditsHint(visible = !compact && viewer.canPost && state.hasOverrides, copy = copy)
        Box(Modifier.fillMaxWidth().height(1.dp).background(ZillitTheme.colors.border))
        val units = remember(state.units, state.query) { visibleUnits() }
        CrewRoster(
            model = CrewRosterModel(
                units = units,
                loading = state.isLoading && !state.hasLoaded,
                hideUnitBands = viewer.isOtherProject,
                editable = !compact && state.editing && viewer.canPost,
                overrides = state.overrides,
                problems = state.problems,
                dialCodes = state.dialCodes,
            ),
            copy = copy,
            faces = slots.faces,
            onEdit = { member, edit -> onEvent(CrewListEvent.Sheet.EditMember(member, edit)) },
            onOpen = { member -> if (!compact) onEvent(CrewListEvent.Sheet.OpenProfile(member)) },
            modifier = Modifier.weight(1f),
        )
    }
}

/** Everything laid over the sheet, in the order it stacks. */
@Composable
private fun Overlays(
    state: CrewListUiState,
    onEvent: (CrewListEvent) -> Unit,
    compact: Boolean,
    slots: CrewListSlots,
    copy: CrewCopy,
    canvasCovered: Boolean,
) {
    CustomiseDialog(
        model = state.customise?.let { customiseModel(state, it, canvasCovered) },
        copy = copy,
        canvas = slots.canvas,
        onEvent = onEvent,
        onGenerate = { onEvent(CrewListEvent.Document.OpenChooser) },
    )
    CompanyDetailsDrawer(editor = state.company, copy = copy, dialCodes = state.dialCodes, onEvent = onEvent)
    MemberProfileDrawer(
        member = state.profile,
        copy = copy,
        selfUserId = state.selfUserId,
        hideUnit = state.viewer.isOtherProject,
        faces = slots.faces,
        actions = slots.contact,
        onClose = { onEvent(CrewListEvent.Sheet.CloseProfile) },
    )
    DepartmentOrderDialog(editor = state.departments, copy = copy, faces = slots.faces, onEvent = onEvent)
    GeneratePdfDialog(
        visible = state.chooserOpen,
        copy = copy,
        hideExternalLabel = state.hideExternalLabel,
        compact = compact,
        onHideExternalLabel = { onEvent(CrewListEvent.Document.HideExternalLabel(it)) },
        onRun = { onEvent(CrewListEvent.Document.Run(it)) },
        onDismiss = { onEvent(CrewListEvent.Document.CloseChooser) },
    )
    CrewPdfViewer(
        viewer = state.pdf,
        copy = copy,
        compact = compact,
        publishing = state.working == CrewWork.Publishing,
        onPublish = { onEvent(CrewListEvent.Document.AskPublishViewed) },
        onDistribute = { onEvent(CrewListEvent.Document.DistributeViewed) },
        onDownload = { onEvent(CrewListEvent.Document.DownloadViewed) },
        onClose = { onEvent(CrewListEvent.Document.CloseViewer) },
    )
    PublishConfirmDialog(
        visible = state.confirmPublish,
        copy = copy,
        onConfirm = { onEvent(CrewListEvent.Document.ConfirmPublish) },
        onCancel = { onEvent(CrewListEvent.Document.CancelPublish) },
    )
    DistributionDialogs(
        prompt = state.distribution,
        onConfirm = { onEvent(CrewListEvent.Document.ConfirmDistribution) },
        onCancel = { onEvent(CrewListEvent.Document.CancelDistribution) },
        onDismiss = { onEvent(CrewListEvent.Document.DismissDistribution) },
    )
    if (state.addingExternalUser) {
        slots.externalUserForm?.invoke { notice -> onEvent(CrewListEvent.Sheet.ExternalUserFinished(notice)) }
    }
    WorkingOverlay(work = state.working, copy = copy)
}

/**
 * The Customise dialog's inputs. Anything drawn over its canvas takes the
 * browser pane down while it shows — a heavyweight surface paints over dialogs.
 */
private fun customiseModel(state: CrewListUiState, customise: CustomiseState, covered: Boolean) = CustomiseModel(
    customise = customise,
    layout = state.layout,
    hideInternalLines = state.hideInternalLines,
    canDesign = state.viewer.canPost,
    isAdmin = state.viewer.isAdmin,
    obscured = covered || state.chooserOpen || state.working != null || state.pdf != null ||
        state.distribution != null || state.company != null || state.confirmPublish ||
        state.addingExternalUser || state.profile != null || state.departments != null,
    generating = state.working == CrewWork.Generating,
)

/** "42 people · 9 departments" — how much sheet there is, before scrolling. */
private fun summary(units: List<CrewUnit>): String {
    if (units.isEmpty()) return ""
    val people = units.sumOf { unit -> unit.departments.sumOf { it.members.size } }
    val departments = units.sumOf { it.departments.size }
    val peopleLabel = if (people == 1) "1 person" else "$people people"
    val departmentLabel = if (departments == 1) "1 department" else "$departments departments"
    val unitLabel = if (units.size > 1) " · ${units.size} units" else ""
    return "$peopleLabel · $departmentLabel$unitLabel"
}
