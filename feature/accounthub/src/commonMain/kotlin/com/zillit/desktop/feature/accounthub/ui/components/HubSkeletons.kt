package com.zillit.desktop.feature.accounthub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar

/**
 * The placeholders the web draws while a hub page's data is on its way
 * (commit 8256c5cd7, "AH icons + skeletons"), each the shape of what will
 * arrive. A spinner and a line of text said "loading" too, but the page then
 * jumped from one line to a full layout; a skeleton holds the layout still.
 */

/** Approvers' department list: six rows of icon, name over count, a stack of faces, a button. */
@Composable
fun ApproverRowsSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        repeat(APPROVER_ROWS) {
            SkeletonCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    Sk(width = 18.dp, height = 18.dp)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Sk(width = 160.dp, height = 14.dp)
                        Sk(width = 96.dp, height = 10.dp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                        repeat(FACES) { Sk(width = FACE, height = FACE, round = true) }
                    }
                    Sk(width = 56.dp, height = 32.dp)
                }
            }
        }
    }
}

/** Forms Configuration's preview: three section cards, a header strip over a two-column field grid. */
@Composable
fun FormSectionsSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
        repeat(FORM_SECTIONS) {
            SkeletonCard(padded = false) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ZillitTheme.colors.surfaceSunken)
                        .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    Sk(width = 16.dp, height = 16.dp)
                    Sk(width = 160.dp, height = 12.dp)
                }
                Column(
                    modifier = Modifier.padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    repeat(FORM_FIELD_ROWS) {
                        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xl)) {
                            repeat(2) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                                ) {
                                    Sk(width = 16.dp, height = 16.dp)
                                    ZillitSkeletonBar(modifier = Modifier.weight(1f), height = 12.dp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Budget: the version cards down the left, the detail card beside them. */
@Composable
fun BudgetSkeleton(modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
        Column(
            modifier = Modifier.width(VERSION_COLUMN),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            repeat(BUDGET_CARDS) {
                SkeletonCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                        Sk(width = 48.dp, height = 18.dp)
                        Sk(width = 56.dp, height = 18.dp)
                    }
                    Sk(width = 160.dp, height = 14.dp)
                    Sk(width = 96.dp, height = 12.dp)
                }
            }
        }
        SkeletonCard(modifier = Modifier.weight(1f)) {
            Sk(width = 192.dp, height = 16.dp)
            Sk(width = 128.dp, height = 12.dp)
            repeat(BUDGET_LINES) { ZillitSkeletonBar(height = 12.dp) }
        }
    }
}

@Composable
private fun SkeletonCard(
    modifier: Modifier = Modifier,
    padded: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = ZillitTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .then(
                if (padded) {
                    Modifier.padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md)
                } else {
                    Modifier
                },
            ),
        verticalArrangement = Arrangement.spacedBy(if (padded) ZillitTheme.spacing.sm else 0.dp),
    ) { content() }
}

/** One pulsing block of a fixed size — the web's `<Sk>`. */
@Composable
private fun Sk(width: Dp, height: Dp, round: Boolean = false) {
    ZillitSkeletonBar(
        modifier = Modifier
            .size(width = width, height = height)
            .then(if (round) Modifier.clip(CircleShape) else Modifier),
        height = height,
    )
}

private const val APPROVER_ROWS = 6
private const val FACES = 3
private val FACE = 22.dp
private const val FORM_SECTIONS = 3
private const val FORM_FIELD_ROWS = 2
private const val BUDGET_CARDS = 4
private const val BUDGET_LINES = 7
private val VERSION_COLUMN = 320.dp
