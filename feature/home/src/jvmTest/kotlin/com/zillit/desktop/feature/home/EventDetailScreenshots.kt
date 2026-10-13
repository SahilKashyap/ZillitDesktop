package com.zillit.desktop.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.home.calendar.CalendarEvent
import com.zillit.desktop.feature.home.calendar.CallType
import com.zillit.desktop.feature.home.calendar.EventAudience
import com.zillit.desktop.feature.home.calendar.EventDetailPopover
import com.zillit.desktop.feature.home.calendar.EventDetailState
import com.zillit.desktop.feature.home.calendar.EventInvitee
import com.zillit.desktop.feature.home.calendar.EventInviteeRow
import com.zillit.desktop.feature.home.calendar.EventPermissions
import com.zillit.desktop.feature.home.calendar.InvitationStatus
import com.zillit.desktop.feature.home.calendar.InviteeListDialog
import com.zillit.desktop.feature.home.calendar.InviteeListState
import java.io.File
import kotlin.test.Test

/**
 * The event detail popover as PNGs, for looking at — not asserting on.
 *
 * Opt-in: runs only when `CALENDAR_SHOTS` names a directory, so the normal
 * test pass stays fast and writes nothing. The assertions about this surface
 * live in `EventDetailRenderTest`; this exists because "does it look like the
 * web's" is a question only a picture answers, and rendering the real
 * composable is cheaper and safer than launching a signed-in app.
 */
class EventDetailScreenshots {

    private fun event(
        audience: EventAudience = EventAudience.Members,
        callType: CallType? = CallType.Audio,
        status: String? = "pending",
    ) = CalendarEvent(
        id = "e1",
        title = "Daily Meet",
        startMillis = START,
        endMillis = START + DURATION,
        status = status,
        location = "Mehboob Studios, Bandra West",
        locationLat = 19.0607,
        locationLng = 72.8302,
        description = "Stand-up for the whole unit. Camera and grip to bring " +
            "the revised shot list.",
        creatorName = "Randeep Iphone Test",
        creatorId = "u-creator",
        reminderMinutes = 5,
        hasCall = callType != null,
        isRecurring = true,
        audience = audience,
        callType = callType,
        cncGroupId = "room-1",
        invitedCount = 21,
        createdAtMillis = START - DAY,
    )

    private fun detail(
        event: CalendarEvent = event(),
        permissions: EventPermissions = EventPermissions(canRespond = true),
        hasFinished: Boolean = false,
    ) = EventDetailState(
        event = event,
        permissions = permissions,
        hasFinished = hasFinished,
        canJoinCall = !hasFinished,
        rangeLabel = "Wed, Oct 14 11:30 AM - 8:00 PM",
        fullDateLabel = "Wed, Oct 14, 2026",
        weekLabel = "Week 42 of the Year",
        durationText = "8 hr 30 min",
        createdLabel = "Created Tue, Oct 13, 2026 9:05 AM",
        inviteeCount = event.invitedCount,
        inviteeFaces = CREW.take(event.invitedCount),
    )

    private fun inviteeList() = InviteeListState(
        eventTitle = "Daily Meet",
        rows = listOf(
            EventInviteeRow("1", userId = "u0", status = InvitationStatus.Accepted),
            EventInviteeRow("2", userId = "u1", status = InvitationStatus.Accepted),
            EventInviteeRow("3", userId = "u2", status = InvitationStatus.Pending),
            EventInviteeRow(
                "4",
                userId = "u3",
                status = InvitationStatus.Rejected,
                rejectionReason = "On the second unit all day.",
            ),
            EventInviteeRow("5", email = "dop@example.com", isExternal = true),
        ),
        directory = CREW,
        isLoading = false,
    )

    @Test
    fun `render every surface`() {
        val dir = System.getenv("CALENDAR_SHOTS")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        dir.mkdirs()

        val surfaces = listOf<Pair<String, @Composable () -> Unit>>(
            "popover-invitee" to {
                EventDetailPopover(detail(), onEvent = {}, onJoinCall = {}, onOpenLink = {})
            },
            "popover-organiser" to {
                EventDetailPopover(
                    detail(
                        event = event(status = null),
                        permissions = EventPermissions(canManage = true),
                    ),
                    onEvent = {},
                    onOpenLink = {},
                )
            },
            "popover-cancelled" to {
                EventDetailPopover(
                    detail(event = event(status = "cancelled"), permissions = EventPermissions()),
                    onEvent = {},
                )
            },
            "invitee-list" to { InviteeListDialog(inviteeList(), onEvent = {}) },
        )

        listOf(false, true).forEach { dark ->
            surfaces.forEach { (name, content) ->
                shoot(File(dir, "$name-${if (dark) "dark" else "light"}.png"), dark, content)
            }
        }
    }

    private fun shoot(file: File, dark: Boolean, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(
            width = WIDTH_DP * DENSITY,
            height = HEIGHT_DP * DENSITY,
            density = Density(DENSITY.toFloat()),
        ) {
            ZillitTheme(darkTheme = dark, animateThemeChange = false) { content() }
        }
        // Early frames lie about a scrolling body — it stretches to the foot
        // and draws its rail until the layout settles — and the dialog shell
        // fades in, so frame 0 catches it half-there.
        repeat(SETTLING_FRAMES) { scene.render(it * FRAME_NANOS) }
        val image = scene.render(SETTLED_NANOS)
        file.writeBytes(requireNotNull(image.encodeToData()).bytes)
        scene.close()
    }

    private companion object {
        const val DENSITY = 2

        /** This Mac's own window, so the card sits where it really sits. */
        const val WIDTH_DP = 1100
        const val HEIGHT_DP = 820
        const val SETTLING_FRAMES = 3
        const val FRAME_NANOS = 500_000_000L
        const val SETTLED_NANOS = 2_000_000_000L

        /** 2026-10-14T11:30Z — the event the mock-up was drawn from. */
        const val START = 1_792_013_400_000L
        const val DURATION = 30_600_000L
        const val DAY = 86_400_000L

        val CREW = listOf(
            EventInvitee("u0", "Randeep Singh", "Director", "Direction"),
            EventInvitee("u1", "Peach Android", "Gaffer", "Electrical"),
            EventInvitee("u2", "Sam Grip", "Key Grip", "Grip"),
            EventInvitee("u3", "Asha Rao", "1st AD", "Direction"),
            EventInvitee("u4", "Tom Boom", "Boom Operator", "Sound"),
        ) + (5 until 21).map { EventInvitee("u$it", "Crew Member $it", "Trainee", "Production") }
    }
}
