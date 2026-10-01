package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zillit.desktop.feature.costumesetsync.data.Answer
import com.zillit.desktop.feature.costumesetsync.ui.MediaEntry
import com.zillit.desktop.feature.costumesetsync.ui.SyncCtx
import com.zillit.desktop.feature.costumesetsync.ui.attachMedia
import com.zillit.desktop.feature.costumesetsync.ui.t
import kotlinx.serialization.json.JsonObject

/** Files one report and then its photos; a retry re-sends only what failed. */
@Stable
internal class ReportFiling(private val ctx: SyncCtx, private val entityType: String, private val kind: String) {
    var media by mutableStateOf(emptyList<MediaEntry>())
    var busy by mutableStateOf(false)

    /** The answer that filed the report, once it has. */
    var created: Answer? by mutableStateOf(null)
        private set

    /** The filed report's id (empty until filed). */
    val filedId: String get() = created?.rec?.let { it.rec("request") ?: it }?.id.orEmpty()

    /** True when the report and every photo are in; false leaves the form open to retry. */
    suspend fun file(path: String, request: JsonObject): Boolean {
        busy = true
        if (created == null) {
            created = ctx.write { ctx.api.post(path, request) }
            if (created == null) {
                busy = false
                return false
            }
        }
        val failed = ctx.attachMedia(media, entityType, filedId, kind) { media = it }
        busy = false
        if (failed > 0) {
            ctx.toast(t("csync_saved_media_failed", "n" to failed), false)
            return false
        }
        return true
    }
}
