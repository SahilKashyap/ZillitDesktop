package com.zillit.desktop

import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.crewlist.ui.CrewListEvent
import com.zillit.desktop.feature.crewlist.ui.CrewListToolProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * The rights grid's crew-list banner "Click Here" (ZL-17040). The web mounts
 * `ChangePriorityList` in place; here the department listing order belongs to
 * the Crew List tool, so it opens there — the same seam Distribution List uses.
 */
internal fun permissionGridListingOrder(viewModels: AppViewModels): ((WindowNavigator) -> Unit)? =
    viewModels.crewList?.let { crew ->
        { navigator ->
            // The crew list's admin controller refuses the event until
            // `start()` has read the role.
            crew.start()
            crew.onEvent(CrewListEvent.Admin.OpenDepartments)
            navigator.openInNewWindow(WorkspaceRoute.Tool(CrewListToolProvider.CREW_LIST_PATH))
        }
    }

/** The Default Grid's "Download Excel": a native save dialog seeded with the web's file name. */
internal suspend fun savePermissionGridFile(fileName: String, bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
    val dialog = FileDialog(null as Frame?, fileName, FileDialog.SAVE)
    dialog.file = fileName
    dialog.isVisible = true
    val directory = dialog.directory ?: return@withContext
    val chosen = dialog.file ?: return@withContext
    runCatching { File(directory, chosen).writeBytes(bytes) }
}
