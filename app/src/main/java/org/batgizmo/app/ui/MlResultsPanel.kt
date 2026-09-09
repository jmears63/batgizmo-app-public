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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.batgizmo.app.ml.MlDetection

private const val ML_RESULTS_MAX_LINES = 5

/**
 * Spectrogram overlay panel for the running ML summary: unique labels with the
 * maximum confidence seen so far. Reserves space for [ML_RESULTS_MAX_LINES] lines.
 */
@Composable
fun MlResultsPanel(
    modifier: Modifier = Modifier,
    summary: List<MlDetection> = emptyList(),
) {
    val panelHeight = with(LocalDensity.current) {
        (SpectrogramOverlayStyle.textSize * ML_RESULTS_MAX_LINES).toDp()
    }
    val textStyle = SpectrogramOverlayStyle.textStyle
    val lines = summary.take(ML_RESULTS_MAX_LINES)

    Box(
        modifier
            .height(panelHeight)
            .background(Color.Gray.copy(alpha = 0.4f))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.Start,
        ) {
            for (detection in lines) {
                Text(
                    text = "${detection.label}  ${"%.0f".format(detection.confidence * 100)}%",
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = textStyle,
                    textAlign = TextAlign.Start,
                )
            }
        }
    }
}
