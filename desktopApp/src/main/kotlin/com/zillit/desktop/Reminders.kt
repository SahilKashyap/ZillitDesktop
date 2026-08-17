package com.zillit.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.window.TrayState
import com.zillit.desktop.core.datastore.PreferenceStore
import com.zillit.desktop.feature.home.calendar.CalendarReminderScheduler
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Calendar reminders.
 *
 * Delivered through the tray declared by `AppTray`, which is where the icon and
 * its menu live — the OS posts notifications on behalf of a tray presence, so
 * the two are the same mechanism seen from different ends.
 *
 * ## Why it waits for a project
 *
 * Calendar events are per-production. Starting the poll before one is loaded
 * only produces a failed request every few minutes and a log line to match.
 */
@Composable
fun CalendarReminders(graph: AppGraph, preferences: PreferenceStore, trayState: TrayState) {
    val ready = graph as? AppGraph.Ready ?: return

    // Keyed on the project, so switching production restarts the scheduler and
    // reminders follow the user rather than staying on the calendar they left.
    val projects = remember(ready) {
        ready.projectContext?.context?.map { it.project?.projectId } ?: flowOf(null)
    }
    val projectId by projects.collectAsState(initial = null)

    val notifier = remember(trayState) { TrayNotifier(trayState) }

    LaunchedEffect(projectId) {
        if (projectId == null) return@LaunchedEffect

        CalendarReminderScheduler(
            repository = ready.calendarRepository,
            notifier = notifier,
            preferences = preferences,
            nowMillis = System::currentTimeMillis,
        ).run()
    }
}
