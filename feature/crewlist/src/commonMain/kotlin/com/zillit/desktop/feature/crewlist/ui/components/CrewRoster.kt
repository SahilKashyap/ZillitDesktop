package com.zillit.desktop.feature.crewlist.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.crewlist.domain.CrewMember
import com.zillit.desktop.feature.crewlist.domain.CrewMemberRules
import com.zillit.desktop.feature.crewlist.domain.MemberOverride
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * Unit → Department → People, as the web draws the sheet: a dark unit band
 * over the column headings, a lighter band per department, then its members.
 * The band and headings of the unit being read stay pinned while it scrolls.
 */
@Composable
internal fun CrewRoster(
    model: CrewRosterModel,
    copy: CrewCopy,
    faces: suspend (String) -> ImageBitmap?,
    onEdit: (CrewMember, MemberOverride) -> Unit,
    onOpen: (CrewMember) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        model.loading && model.units.isEmpty() -> RosterSkeleton(modifier)
        model.units.isEmpty() -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ZillitEmptyState(title = copy.t("NoUsersFound", str(S.no_user_found)), icon = ZillitIcons.Users)
        }
        else -> {
            val headings = listOf(
                copy.t("Po_contact", str(S.name)),
                copy.t("Designation", str(S.designation)),
                copy.t("Phone", str(S.phone)),
                copy.t("poEmailLable", str(S.email)),
            )
            // Faces already fetched, kept across recomposition so scrolling back never refetches.
            val faceCache = remember { mutableMapOf<String, ImageBitmap?>() }
            ZillitLazyColumn(
                state = rememberLazyListState(),
                modifier = modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                sheet(model, headings, copy) { member ->
                    val face by produceState(faceCache[member.userId], member.userId) {
                        if (!faceCache.containsKey(member.userId)) {
                            val loaded = faces(member.userId)
                            faceCache[member.userId] = loaded
                            value = loaded
                        }
                    }
                    CrewMemberRow(
                        member = member,
                        cells = CrewMemberRules.cells(member, model.overrides[member.userId]),
                        editable = model.editable,
                        problem = model.problems[member.userId],
                        avatar = face,
                        copy = copy,
                        dialCodes = model.dialCodes,
                        onEdit = { edit -> onEdit(member, edit) },
                        onOpen = { onOpen(member) },
                    )
                }
            }
        }
    }
}

/** The sheet's items: a pinned unit band and headings, then each department's band and people. */
@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.sheet(
    model: CrewRosterModel,
    headings: List<String>,
    copy: CrewCopy,
    row: @Composable (CrewMember) -> Unit,
) {
    model.units.forEachIndexed { unitIndex, unit ->
        stickyHeader(key = "unit-$unitIndex-${unit.unitName}") {
            Column(Modifier.fillMaxWidth().background(ZillitTheme.colors.surface)) {
                if (!model.hideUnitBands) CrewBand(copy.label(unit.unitName), unit = true)
                CrewColumnHeader(headings)
            }
        }
        unit.departments.forEachIndexed { departmentIndex, department ->
            item(key = "dept-$unitIndex-$departmentIndex-${department.departmentName}") {
                CrewBand(copy.label(department.departmentName), unit = false)
            }
            department.members.forEachIndexed { memberIndex, member ->
                item(key = "m-$unitIndex-$departmentIndex-$memberIndex-${member.userId}") { row(member) }
            }
        }
    }
}

/** A sheet-shaped wait: a band, the headings' strip, and rows of faces and lines. */
@Composable
private fun RosterSkeleton(modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ZillitSkeletonBar(Modifier.fillMaxWidth(), height = 30.dp)
        repeat(SKELETON_ROWS) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(crewPalette().skeleton))
                ZillitSkeletonBar(Modifier.width(170.dp))
                ZillitSkeletonBar(Modifier.width(130.dp))
                ZillitSkeletonBar(Modifier.width(110.dp))
                ZillitSkeletonBar(Modifier.weight(1f))
            }
        }
    }
}

private const val SKELETON_ROWS = 8
