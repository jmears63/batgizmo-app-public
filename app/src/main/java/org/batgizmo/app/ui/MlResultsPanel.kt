/*
 * Copyright (c) 2025-2026 John Mears
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.batgizmo.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.batgizmo.app.ml.MlSummaryAccumulator
import org.batgizmo.app.ml.MlSummaryEntry
import kotlin.time.Duration.Companion.seconds

/**
 * Layout and age-styling knobs for [MlResultsPanel]. Padding is defined once and
 * reused for both the modifier and the reserved panel height.
 */
private object MlResultsPanelStyle {
    val paddingHorizontal: Dp = 4.dp
    val paddingVertical: Dp = 2.dp

    /** Live: newer than this (seconds) → [freshColor]. */
    const val FRESH_AGE_SEC = 10.0
    /** Live: newer than this (seconds) → overlay grey; else [staleColor]. */
    const val RECENT_AGE_SEC = 20.0

    val freshColor = Color.White
    val recentColor: Color get() = SpectrogramOverlayStyle.textColor
    val staleColor = Color(0xFF555555)

    fun panelHeight(rowHeight: Dp): Dp =
        rowHeight * MlSummaryAccumulator.MAX_SUMMARY_ENTRIES + paddingVertical * 2

    fun colorFor(ageColors: Boolean, ageSec: Double): Color {
        if (!ageColors) return freshColor
        return when {
            ageSec < FRESH_AGE_SEC -> freshColor
            ageSec < RECENT_AGE_SEC -> recentColor
            else -> staleColor
        }
    }

    fun formatEntry(entry: MlSummaryEntry): String =
        "${entry.label}  ${"%.0f".format(entry.confidence * 100)}%"
}

/**
 * Spectrogram overlay panel for the running ML summary.
 *
 * [summary] is already ordered and capped by [MlSummaryAccumulator].
 * When [ageColors] is true (live): under [MlResultsPanelStyle.FRESH_AGE_SEC]
 * white, under [MlResultsPanelStyle.RECENT_AGE_SEC] overlay grey, otherwise
 * darker grey. When false (viewer): all rows use [freshColor] (white) with no
 * age variation.
 */
@Composable
fun MlResultsPanel(
    modifier: Modifier = Modifier,
    summary: List<MlSummaryEntry> = emptyList(),
    ageColors: Boolean = true,
) {
    val rowHeight = with(LocalDensity.current) { SpectrogramOverlayStyle.textSize.toDp() }
    val panelHeight = MlResultsPanelStyle.panelHeight(rowHeight)

    var nowEpochSec by remember {
        mutableDoubleStateOf(System.currentTimeMillis() / 1000.0)
    }
    if (ageColors) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(1.seconds)
                nowEpochSec = System.currentTimeMillis() / 1000.0
            }
        }
    }

    Column(
        modifier
            .height(panelHeight)
            .padding(
                horizontal = MlResultsPanelStyle.paddingHorizontal,
                vertical = MlResultsPanelStyle.paddingVertical,
            ),
        horizontalAlignment = Alignment.Start,
    ) {
        for (entry in summary) {
            val color = MlResultsPanelStyle.colorFor(
                ageColors,
                nowEpochSec - entry.lastSeenAtEpochSec,
            )
            Text(
                text = MlResultsPanelStyle.formatEntry(entry),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                style = SpectrogramOverlayStyle.textStyle.copy(color = color),
                color = color,
                textAlign = TextAlign.Start,
            )
        }
    }
}
