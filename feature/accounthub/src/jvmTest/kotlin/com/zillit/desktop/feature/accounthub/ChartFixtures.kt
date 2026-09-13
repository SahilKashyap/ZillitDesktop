package com.zillit.desktop.feature.accounthub

import com.zillit.desktop.feature.accounthub.domain.AccountHubViewer
import com.zillit.desktop.feature.accounthub.domain.CoaAccount
import com.zillit.desktop.feature.accounthub.domain.CoaBulkRow
import com.zillit.desktop.feature.accounthub.domain.CoaBulkStatus
import com.zillit.desktop.feature.accounthub.domain.CoaCostType
import com.zillit.desktop.feature.accounthub.domain.CoaLineType
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.domain.TrackingNode
import com.zillit.desktop.feature.accounthub.domain.TrackingSet
import com.zillit.desktop.feature.accounthub.ui.AccountForm
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.BulkAddState
import com.zillit.desktop.feature.accounthub.ui.ChartState

/** A production's chart as the render tests and the screenshots draw it. */
internal object ChartFixtures {

    val accountant = AccountHubViewer(
        userId = "u1",
        isAccountant = true,
        canView = true,
        canPost = true,
        canDownload = true,
        ready = true,
    )

    /** A top-level group: its breadcrumb is itself. */
    private fun group(id: String, code: String, name: String, costType: CoaCostType = CoaCostType.Expense) =
        CoaAccount(id = id, code = code, name = name, lineType = CoaLineType.Header, costType = costType, headId = id)

    /** A row one level below this one, with the breadcrumb the server would write. */
    private fun CoaAccount.child(id: String, code: String, name: String): CoaAccount {
        val level = requireNotNull(lineType.childType)
        return CoaAccount(
            id = id,
            code = code,
            name = name,
            lineType = level,
            costType = costType,
            headId = headId,
            sectionId = if (level == CoaLineType.Section) id else sectionId,
            categoryId = if (level == CoaLineType.Category) id else categoryId,
        )
    }

    val atl = group("h1", "1000", "Above the Line")
    val story = atl.child("s1", "1100", "Story & Rights")
    val writing = story.child("c1", "1110", "Script Writing Fees")
    val firstDraft = writing.child("x1", "1110-01", "First Draft")
    val revisions = writing.child("x2", "1110-02", "Revisions")
    val rights = story.child("c2", "1120", "Rights Purchase").copy(isPosting = false)
    val producers = atl.child("s2", "1200", "Producers")
    val executive = producers.child("c3", "1210", "Executive Producer").copy(isActive = false)
    val btl = group("h2", "2000", "Below the Line — Production")
    val camera = btl.child("s3", "2100", "Camera")
    val cameraHire = camera.child("c4", "2110", "Camera Rental").copy(source = CoaAccount.BUDGET_SOURCE)
    val unnamed = camera.child("c5", "2120", "")
    val assets = group("a1", "0100", "Current Assets", CoaCostType.Asset)
    val bank = assets.child("a2", "0110", "Bank Accounts")
    val barclays = bank.child("a3", "0111", "Barclays Operating")
    val creditors = group("l1", "0500", "Creditors", CoaCostType.Liability)

    val accounts = listOf(
        atl, story, writing, firstDraft, revisions, rights, producers, executive,
        btl, camera, cameraHire, unnamed, assets, bank, barclays, creditors,
    )

    val layers = listOf(
        TrackingSet(
            id = "t1",
            name = "Locations",
            prefix = "LOC",
            color = "#FB923C",
            nodes = listOf(
                TrackingNode(
                    id = "n1",
                    setId = "t1",
                    code = "LOC-LON",
                    name = "London",
                    description = "Soundstage hire + studio support",
                ),
                TrackingNode(id = "n2", setId = "t1", code = "LOC-MAD", name = "Madrid"),
                TrackingNode(id = "n3", setId = "t1", code = "LOC-LA", name = "Los Angeles", isActive = false),
            ),
        ),
        TrackingSet(id = "t2", name = "Episodes", prefix = "EP", color = "#3B82F6", isActive = false),
    )

    fun state(chart: ChartState = ChartState(accounts = accounts, loaded = true)) = AccountHubUiState(
        viewer = accountant,
        area = HubArea.ChartOfAccounts,
        chart = chart,
    )

    fun bulk(): ChartState {
        val rows = listOf(
            CoaBulkRow(localId = "r1", code = "1130", name = "Story Consultant", serverId = "srv-1"),
            CoaBulkRow(localId = "r2", code = "1110", name = "Duplicate of an existing code"),
            CoaBulkRow(localId = "r3", code = "1140", lineType = CoaLineType.SubCategory, costType = CoaCostType.Asset),
            CoaBulkRow(localId = "r4", code = "", name = "", isActive = false),
        )
        return ChartState(
            accounts = accounts,
            loaded = true,
            bulk = BulkAddState(
                parent = story,
                rows = rows,
                status = mapOf("r1" to CoaBulkStatus.Saved, "r3" to CoaBulkStatus.Error),
                errors = mapOf("r3" to "Parent must be a Headers"),
            ),
        )
    }

    fun editing(account: CoaAccount) =
        ChartState(accounts = accounts, loaded = true, form = AccountForm.editing(account, accounts))
}
