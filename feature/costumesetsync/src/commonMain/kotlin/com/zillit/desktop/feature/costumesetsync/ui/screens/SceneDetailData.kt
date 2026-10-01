package com.zillit.desktop.feature.costumesetsync.ui.screens

import com.zillit.desktop.feature.costumesetsync.domain.Rec

/**
 * One scene: the record (null when the service does not know it), its readiness read, and the characters for the add
 * dialog.
 */
internal class SceneDetailData(val scene: Rec?, val readiness: Rec?, val characters: List<Rec>)
