package com.zillit.desktop.feature.addashboard.data

import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.addashboard.domain.AdRefresh
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.mapNotNull

/**
 * Which of the dashboard's three pages an `ad_dashboard:*` frame reloads.
 *
 * The tool had no realtime at all until 2026-09-09 — not one subscription in
 * the module — so an artiste marked present on set, a meal added, or a day
 * submitted stayed invisible here until the page was reopened. That is the
 * whole point of a dashboard somebody watches while a unit shoots.
 *
 * The names and the fan-out are Android's `AdDashboardSocketKeys`, itself a
 * 1:1 port of the web's `AD_HANDLERS` map — each event refreshes exactly what
 * the same action performed locally would.
 *
 * ## Two things this map does NOT carry
 *
 * `ad_dashboard:ad_report:list`, `:report_template:list` and
 * `:ad_rate_config:updated` have no desktop page: this port is Today,
 * Register and Shoot days, with no reports or rate config to reload. The
 * supporting-artiste query events belong to the portal's Queries page and
 * live in that module (`SaPortalSync`).
 *
 * ## An iOS spelling that is not copied
 *
 * iOS registers three of the shoot-schedule names differently —
 * `shoot_schedule:absent`, `:wrapped`, and `:_updated` / `:_deleted` with a
 * bare leading underscore (`ProjectObserver.swift:7785-7789`). The last two
 * cannot be real server names, and Android's set is internally consistent and
 * cites the web's handler map, so Android's spelling is followed here.
 */
internal val AD_REFRESH_BY_EVENT: Map<SocketEventName, Set<AdRefresh>> = buildMap {
    listOf(
        "ad_dashboard:artiste:created",
        "ad_dashboard:artiste:updated",
        "ad_dashboard:artiste:deleted",
    ).forEach { put(SocketEventName(it), setOf(AdRefresh.Register)) }

    // Adding to the day can create the artiste as well, so the web fans these
    // out to both refetch keys and so does this.
    listOf(
        "ad_dashboard:today_list:artiste_added",
        "ad_dashboard:add_extras:artiste_added",
    ).forEach { put(SocketEventName(it), setOf(AdRefresh.Today, AdRefresh.Register)) }

    listOf(
        "ad_dashboard:shoot_schedule:artiste_present",
        "ad_dashboard:shoot_schedule:artiste_absent",
        "ad_dashboard:shoot_schedule:artiste_wrapped",
        "ad_dashboard:shoot_schedule:artiste_meal_added",
        "ad_dashboard:shoot_schedule:artiste_meal_updated",
        "ad_dashboard:shoot_schedule:artiste_meal_deleted",
        "ad_dashboard:ad_shoot_day:meal_added",
        "ad_dashboard:ad_shoot_day:meal_deleted",
    ).forEach { put(SocketEventName(it), setOf(AdRefresh.Today)) }

    // A day's header changing, or being submitted, moves both the open day and
    // the row for it in the days list — submitting locks that day's rows.
    listOf(
        "ad_dashboard:ad_shoot_day:updated",
        "ad_dashboard:ad_shoot_day:submitted",
    ).forEach { put(SocketEventName(it), setOf(AdRefresh.Today, AdRefresh.Days)) }
}

/**
 * The dashboard's refresh pulses for a bus, or empty without one.
 *
 * Conflated: adding artistes to a day emits one frame per artiste, and a
 * single reload answers the burst.
 */
internal fun adRefreshes(bus: SocketEventBus?): Flow<Set<AdRefresh>> =
    bus?.onAny(AD_REFRESH_BY_EVENT.keys)
        ?.mapNotNull { AD_REFRESH_BY_EVENT[it.event] }
        ?.conflate()
        ?: emptyFlow()
