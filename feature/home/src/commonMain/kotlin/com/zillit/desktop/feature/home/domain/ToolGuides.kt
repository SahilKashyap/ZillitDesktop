@file:Suppress("MaxLineLength") // Published URLs, copied from the web byte for byte.

package com.zillit.desktop.feature.home.domain

/**
 * The two links behind a tile's ⓘ besides its text — the web's `TooltipInfo`:
 * **More**, the tool's page on documentation.zillit.com, and **Watch video**,
 * its tutorial clip (`pages/FilmTools/useAvailableFilmTools.js`, each entry's
 * `tutorialLink` and `infoVideoURL`; `utils/tutorialURLLink.js` and
 * `utils/videoURL.js` for the addresses).
 *
 * Matched exactly as the ⓘ text is: the FIRST entry whose name the tool's
 * identifier contains. Every tool has a video — the web plays its generic
 * clip when the entry names none — but only some have a documentation page.
 */
data class ToolGuide(val tutorialUrl: String?, val videoUrl: String)

/** What the web's tutorial and video picks branch on, beyond [ToolInfoViewer]. */
data class GuideViewer(
    val info: ToolInfoViewer,
    /** The production's `project_type`, sent to the docs site; "default" when unknown. */
    val projectType: String? = null,
)

fun toolGuide(identifier: String, viewer: GuideViewer): ToolGuide {
    val entry = GUIDES.firstOrNull { identifier.contains(it.name) }
    val tutorialKey = entry?.tutorial?.invoke(viewer.info)
    val videoKey = entry?.video?.invoke(viewer.info)
    return ToolGuide(
        tutorialUrl = tutorialKey?.let { key ->
            TUTORIALS[key]?.let { base -> tutorialUrl(base, key, entry.name, viewer) }
        },
        videoUrl = videoKey?.let(VIDEOS::get) ?: VIDEOS.getValue(STATIC_VIDEO),
    )
}

/**
 * The web's `TooltipInfo` query: `for=accounts` for the accounts department on
 * the Accounts page, otherwise `for=admin` for an admin — or for whoever posts
 * on a tool whose entry is named by its full identifier, the only case its
 * `findTool(section.name)` can match — and always `project_type`. Set into the
 * query, ahead of the `#anchor`, as `URL.searchParams.set` does.
 */
private fun tutorialUrl(base: String, key: String, entryName: String, viewer: GuideViewer): String {
    val info = viewer.info
    val forWhom = when {
        info.departmentIdentifier?.contains("accounts") == true && key == "accounts" -> "accounts"
        info.isAdmin || info.canPost(entryName) -> "admin"
        else -> null
    }
    val anchor = base.substringAfter('#', "")
    val front = base.substringBefore('#')
    val path = front.substringBefore('?')
    val existing = front.substringAfter('?', "")
        .split('&')
        .filter { it.isNotBlank() }
        .map { it.substringBefore('=') to it.substringAfter('=', "") }
        .filterNot { (name, _) -> name == "for" && forWhom != null || name == "project_type" }
    val params = existing +
        listOfNotNull(forWhom?.let { "for" to it }) +
        ("project_type" to (viewer.projectType?.takeIf { it.isNotBlank() } ?: "default"))
    val query = params.joinToString("&") { (name, value) -> "$name=$value" }
    return "$path?$query" + if (anchor.isNotEmpty()) "#$anchor" else ""
}

private class GuideEntry(
    val name: String,
    val tutorial: ((ToolInfoViewer) -> String?)? = null,
    val video: ((ToolInfoViewer) -> String?)? = null,
)

private fun fixed(key: String): (ToolInfoViewer) -> String = { key }

private fun accountant(viewer: ToolInfoViewer) = viewer.departmentIdentifier?.contains("accounts") == true

private fun signer(viewer: ToolInfoViewer) = viewer.canPost("forms_and_signature_tool")

private fun caterer(viewer: ToolInfoViewer) = viewer.departmentIdentifier?.contains("department_catering") == true

/** `sectionList`, in its order — the same order [toolDescription] matches in. */
private val GUIDES: List<GuideEntry> = listOf(
    GuideEntry("account_hub"),
    GuideEntry(
        "account",
        tutorial = fixed("accounts"),
        video = { if (accountant(it)) "accountsForAccountant" else "accountsForUser" },
    ),
    GuideEntry("asset_report", fixed("assetReport"), fixed("assetReport")),
    GuideEntry(
        "catering",
        tutorial = { if (caterer(it)) "cateringForCaterer" else "catering" },
        video = { if (caterer(it)) "cateringForCaterer" else "cateringForUser" },
    ),
    GuideEntry("confidential_info", fixed("confidentialInfo"), fixed("confidentialInfo")),
    GuideEntry("continuity", fixed("continuity"), fixed("continuity")),
    GuideEntry(
        "forms_and_signature",
        tutorial = fixed("contractsAndSignature"),
        video = { if (signer(it)) "contractAndSignatureAdmin" else "contractAndSignatureUser" },
    ),
    GuideEntry("generate_crew_list", fixed("generateCrewList"), fixed("generateCrewList")),
    GuideEntry("info", fixed("info"), fixed("info")),
    GuideEntry("permission_grid", fixed("viewingAndPostingRightsGrid"), fixed("viewingAndPostingRights")),
    GuideEntry("pre_production_tool", fixed("preProductionCalendar")),
    GuideEntry("production_tool", fixed("productionCalendar")),
    GuideEntry("purchase_order", fixed("purchaseOrder"), fixed("purchaseOrder")),
    GuideEntry("card_expenses", fixed("productionExpenseCards")),
    GuideEntry("cash_expenses", fixed("pettyCashExpenses")),
    GuideEntry("deal_memo", fixed("dealMemo")),
    GuideEntry("timecard", fixed("timeCard")),
    GuideEntry("payroll", fixed("payroll")),
    GuideEntry("cost_report", fixed("costReport")),
    GuideEntry("location", fixed("location"), fixed("location")),
    GuideEntry("recce", fixed("recce"), fixed("recce")),
    GuideEntry("weather", fixed("weather"), fixed("weather")),
    GuideEntry("wardrobe_tool", fixed("wardrobeBackground")),
    GuideEntry("script_distribution", fixed("scriptAndPagesDistribution"), fixed("scriptAndPagesDistribution")),
    GuideEntry("schedule_distribution", fixed("scheduleFullAndOneLine"), fixed("scheduleFullAndOneLine")),
    GuideEntry("casting_main_tool", fixed("castingMain"), fixed("castingMain")),
    GuideEntry("casting_background_tool", fixed("castingBackground"), fixed("castingBackground")),
    GuideEntry("script_notes_tool", fixed("continuityScriptNotes"), fixed("continuityScriptNotes")),
    GuideEntry("wardrobe_main_tool", fixed("wardrobeMain"), fixed("wardrobeMain")),
    GuideEntry("wardrobe_background_tool", fixed("wardrobeBackground"), fixed("wardrobeBackground")),
    GuideEntry("main_budget_tool", fixed("budgetFull"), fixed("budgetFull")),
    GuideEntry("department_budget_tool", fixed("budgetDepartment"), fixed("budgetDepartment")),
    GuideEntry("budget_builder_tool", fixed("budgetBuilder")),
    // The web also tells a driver apart by designation; without the hub's
    // driver list the desktop splits coordinator (posting right) from everyone else.
    GuideEntry(
        "transportation_tool",
        tutorial = fixed("transportation"),
        video = { if (it.canPost("transportation_tool")) "transportation" else "transportationUser" },
    ),
    GuideEntry(
        "production_report_tool",
        tutorial = { secondAd(it) },
        video = { secondAd(it) },
    ),
    GuideEntry("dod_tool", fixed("scheduleDod"), fixed("scheduleDod")),
    GuideEntry("external_users_tool", fixed("externalUsers"), fixed("externalUser")),
    GuideEntry("box_schedule_tool", fixed("boxSchedule"), fixed("boxSchedule")),
    GuideEntry("document_distribution_tool", fixed("documentDistribution"), fixed("documentDistribution")),
    GuideEntry("sides_tool", fixed("sides"), fixed("sides")),
)

private fun secondAd(viewer: ToolInfoViewer): String =
    if (!viewer.isAdmin && viewer.canPost("production_report_tool")) "productionReportSecondAd" else "productionReport"

private const val STATIC_VIDEO = "staticVideo"

private val VIDEOS: Map<String, String> = mapOf(
    "accountsForAccountant" to
        "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/web/accounts%20acc.mp4",
    "accountsForUser" to
        "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/web/accounts%20user.mp4",
    "assetReport" to
        "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/web/How%20to%20GenerateView%20Asset%20Report%20%20%281%29.mp4",
    "cateringForCaterer" to
        "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/web/How%20to%20upload%20images%20and%20documents%20in%20catering.mp4",
    "cateringForUser" to
        "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/web/catering%20user.mp4",
    "confidentialInfo" to
        "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/web/How%20to%20view%20or%20post%20in%20Confidential%20Info%20Web%20TARAN.mp4",
    "continuity" to
        "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/web/How%20to%20upload%2C%20forward%2C%20view%20or%20delete%20scenes%20in%20Continuity%20%20Web.mp4",
    "contractAndSignatureAdmin" to
        "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/web/Contract%20tool%20admin.mp4",
    "contractAndSignatureUser" to
        "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/web/contract%20tools%20users.mp4",
    "generateCrewList" to
        "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/web/How%20to%20view%20%26%20generate%20Crew%20list_%20Web.mp4",
    "info" to
        "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/web/How%20to%20post%20or%20view%20messages%20in%20Info%20%20Web%20TARAN.mp4",
    "viewingAndPostingRights" to
        "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/web/viewing%20postimg.mp4",
    "purchaseOrder" to
        "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/web/purchase%20order%20tool.mp4",
    "location" to
        "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/web/location%20tools%20web.mp4",
    "recce" to
        "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/web/How%20to%20use%20the%20Recce%20Tool%20Web.mp4",
    "weather" to
        "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/web/How%20to%20view%20Weather%20Web%20TARAN.mp4",
    "scriptAndPagesDistribution" to
        "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/web/How%20to%20Upload%20Full%20Script%20and%20Pages%20%20Web.mp4",
    "scheduleFullAndOneLine" to
        "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/web/_How%20to%20Upload%20Schedule%20Full%20%26%20One%20Line%20%20Web.mp4",
    "castingMain" to
        "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/web/How%20to%20Upload%2C%20Edit%2C%20Move%2C%20Delete%20%26%20Publish%20Casting%20Main%20Web%20.mp4",
    "castingBackground" to
        "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/web/How%20to%20Upload%2C%20Edit%2C%20Move%2C%20Delete%20%26%20Publish%20Casting%20%28Background%20Cast%29%20Web%20.mp4",
    "continuityScriptNotes" to
        "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/web/Continuity%20Script%20Notes%20Merged%20WEB.mp4",
    "wardrobeMain" to
        "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/web/How%20to%20Upload%2C%20Edit%2C%20Move%20%26%20Delete%20Costume%20Folder%20%28Main%20Cast%29%20Web%20.mp4",
    "wardrobeBackground" to
        "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/web/How%20to%20Upload%2C%20Edit%2C%20Move%20%26%20Delete%20Costume%20Folder%20%28Background%20Cast%29%20Web%20.mp4",
    "budgetFull" to
        "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/web/_How%20to%20upload%20and%20chat%20in%20budget%20%28full%29%20%20Web.mp4",
    "budgetDepartment" to
        "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/web/How%20to%20upload%20and%20chat%20in%20Budget%20%28Department%29%20%20Web.mp4",
    "transportation" to
        "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/web/Transport-coordinator-merged%20WEB.mp4",
    "transportationDriver" to
        "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/web/transportation-driver-merged%20WEB.mp4",
    "transportationUser" to
        "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/web/transportation-user-merged%20WEB.mp4",
    "productionReportSecondAd" to
        "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/web/How%20to%20upload%20document%20%26%20chat%20on%20Production%20Report%20for%202nd%20AD%20web.mp4",
    "productionReport" to
        "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/web/production%20report.mp4",
    "scheduleDod" to
        "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/web/How%20to%20upload%20in%20D.O.D.%20%20Web.mp4",
    "externalUser" to
        "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/web/How%20to%20add%2C%20edit%20or%20delete%20External%20Users%20Web%20TARAN.mp4",
    "boxSchedule" to
        "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/web/box%20schedule%20web.mp4",
    "documentDistribution" to
        "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/web/How%20to%20use%20the%20Document%20Distribution%20Tool%20Web.mp4",
    "sides" to
        "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/web/How%20to%20use%20the%20Sides%20Tool%20Web.mp4",
    "staticVideo" to
        "https://zillit-tutorial-videos.s3.dualstack.us-east-1.amazonaws.com/static%20video.mp4",
)

private val TUTORIALS: Map<String, String> = mapOf(
    "accounts" to
        "https://documentation.zillit.com/#accounts",
    "assetReport" to
        "https://documentation.zillit.com/#asset-register",
    "catering" to
        "https://documentation.zillit.com/#catering",
    "cateringForCaterer" to
        "https://documentation.zillit.com/?for=catering#catering",
    "confidentialInfo" to
        "https://documentation.zillit.com/#confidential-info",
    "continuity" to
        "https://documentation.zillit.com/#continuity",
    "contractsAndSignature" to
        "https://documentation.zillit.com/#contracts-and-signature",
    "generateCrewList" to
        "https://documentation.zillit.com/#generate-crew-list",
    "info" to
        "https://documentation.zillit.com/#info",
    "viewingAndPostingRightsGrid" to
        "https://documentation.zillit.com/#viewing-and-posting-rights-grid",
    "preProductionCalendar" to
        "https://documentation.zillit.com/#pre-production-calendar",
    "productionCalendar" to
        "https://documentation.zillit.com/#production-calendar",
    "purchaseOrder" to
        "https://documentation.zillit.com/#purchase-order",
    "productionExpenseCards" to
        "https://documentation.zillit.com/#production-expense-cards",
    "pettyCashExpenses" to
        "https://documentation.zillit.com/#petty-cash-expenses",
    "dealMemo" to
        "https://documentation.zillit.com/#deal-memo",
    "timeCard" to
        "https://documentation.zillit.com/#time-card",
    "payroll" to
        "https://documentation.zillit.com/#payroll",
    "costReport" to
        "https://documentation.zillit.com/#cost-report",
    "location" to
        "https://documentation.zillit.com/#location",
    "recce" to
        "https://documentation.zillit.com/#recce",
    "weather" to
        "https://documentation.zillit.com/#weather",
    "wardrobeBackground" to
        "https://documentation.zillit.com/#wardrobe_background",
    "scriptAndPagesDistribution" to
        "https://documentation.zillit.com/#script-and-pages-distribution",
    "scheduleFullAndOneLine" to
        "https://documentation.zillit.com/#schedule-full-and-one-line",
    "castingMain" to
        "https://documentation.zillit.com/#casting-main",
    "castingBackground" to
        "https://documentation.zillit.com/#casting-background",
    "continuityScriptNotes" to
        "https://documentation.zillit.com/#continuity-script-notes",
    "wardrobeMain" to
        "https://documentation.zillit.com/#wardrobe_main",
    "budgetFull" to
        "https://documentation.zillit.com/#budget-full",
    "budgetDepartment" to
        "https://documentation.zillit.com/#budget-department",
    "budgetBuilder" to
        "https://documentation.zillit.com/#budget-builder",
    "transportation" to
        "https://documentation.zillit.com/#transportation",
    "productionReportSecondAd" to
        "https://documentation.zillit.com/?designation=2nd_assistant_director_label#production-report",
    "productionReport" to
        "https://documentation.zillit.com/#production-report",
    "scheduleDod" to
        "https://documentation.zillit.com/#schedule-dod",
    "externalUsers" to
        "https://documentation.zillit.com/#external-users",
    "boxSchedule" to
        "https://documentation.zillit.com/#box-schedule",
    "documentDistribution" to
        "https://documentation.zillit.com/#document-distribution",
    "sides" to
        "https://documentation.zillit.com/#sides",
)
