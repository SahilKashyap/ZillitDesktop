package com.zillit.desktop.core.config

/**
 * Thirteen per-module backends merged into four services.
 *
 * | New host | Absorbs |
 * |---|---|
 * | `cseapi` | continuity, sides, e-signature |
 * | `mrmiapi` | map, recce, media, integrations |
 * | `lcwapi` | location, casting, wardrobe |
 * | `scriptopsapi` | script notes, script distribution, schedule distribution |
 *
 * **Only the host changes.** Every path after it is appended by callers
 * exactly as before, which is why this rewrites the endpoint map rather than
 * touching a single repository: `/api/v2/continuity/archive` on `cseapi` is
 * the same route it was on `continuityapi`. Verified against develop
 * 2026-08-27.
 *
 * ## Why this is applied once, at load
 *
 * Android carries the same mapping as thirteen hand-written properties in
 * `network/ServiceHosts.kt`, each re-reading a Firebase flag on every access.
 * It has to: its `BuildConfig` fields are compile-time constants, so the swap
 * can only happen at the call site. Desktop resolves every host through
 * [AppConfig.baseUrl], so rewriting the map once means all thirteen modules
 * follow with no repository changes and nothing to keep in sync.
 *
 * It also removes the trailing-slash trap that shape forces on Android —
 * `withSlash()` for most modules but `noSlash()` for sides and e-signature,
 * whose callers append `/api/…` themselves. [AppConfig.baseUrl] already
 * trims, so both spellings land the same way here.
 */
object ConsolidatedHosts {

    /** Which consolidated service now serves each old one. */
    val mapping: Map<ZillitService, ZillitService> = mapOf(
        ZillitService.Continuity to ZillitService.Cse,
        ZillitService.Sides to ZillitService.Cse,
        ZillitService.ESignature to ZillitService.Cse,

        ZillitService.Map to ZillitService.Mrmi,
        ZillitService.Recce to ZillitService.Mrmi,
        ZillitService.Media to ZillitService.Mrmi,
        ZillitService.Integrations to ZillitService.Mrmi,

        ZillitService.Location to ZillitService.Lcw,
        ZillitService.Casting to ZillitService.Lcw,
        ZillitService.Wardrobe to ZillitService.Lcw,

        ZillitService.ScriptNotes to ZillitService.ScriptOps,
        ZillitService.ScriptDistribution to ZillitService.ScriptOps,
        ZillitService.ScheduleDistribution to ZillitService.ScriptOps,
    )

    /** The four services doing the absorbing. */
    val consolidated: Set<ZillitService> = mapping.values.toSet()

    /**
     * [services] with the thirteen repointed at their consolidated host.
     *
     * A module whose consolidated host is not configured keeps its own — a
     * half-filled properties file degrades to the old routing for that module
     * rather than throwing at the first call. The old entries are left in the
     * map untouched, so flipping back is a flag change and not a redeploy.
     */
    fun applied(services: Map<ZillitService, String>): Map<ZillitService, String> {
        val resolved = services.toMutableMap()
        mapping.forEach { (old, new) ->
            val host = services[new] ?: return@forEach
            resolved[old] = host
        }
        return resolved
    }
}
