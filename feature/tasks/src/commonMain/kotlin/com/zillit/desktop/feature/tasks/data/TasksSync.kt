package com.zillit.desktop.feature.tasks.data

import com.zillit.desktop.core.socket.SocketEventName

/**
 * Live updates for Tasks, emitted by `zillit_tasks` through the chat service.
 *
 * Every frame is `{ project_id, device_id, task_id }` and carries no task
 * body — a receiver refetches. Project tasks go to everyone who can view the
 * tool; self tasks only to their creator and assignee, so a refetch is always
 * safe to run for any frame that arrives.
 */
val tasksSyncEvents: List<SocketEventName> = listOf(
    SocketEventName("tasks:created"),
    SocketEventName("tasks:updated"),
    SocketEventName("tasks:deleted"),
    SocketEventName("tasks:commented"),
)
