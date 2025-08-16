/*
 * Copyright (c) 2025 John Mears
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.RangeSlider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import org.batgizmo.app.FloatRange
import org.batgizmo.app.HORange
import org.batgizmo.app.UIModel
import uk.org.gimell.batgimzoapp.R

class Sliders {
    private val maxRange = FloatRange(0f, 1f)

    @Composable
    fun Compose(
        modifier: Modifier, model: UIModel,
        bnCRange: State<FloatRange>, enabled: Boolean,
        rawPageRangeState: State<HORange?>
    ) {
        // Get a coroutine scope linked to the Compose scope, using the main UI thread.
        val scope = rememberCoroutineScope()

        // CONFLATED so that multiple events are replaced with the single most recent:
        val sliderEvents = remember { Channel<ClosedFloatingPointRange<Float>>(capacity = Channel.CONFLATED) }

        LaunchedEffect(Unit) {
            for (range in sliderEvents) {
                // CPU heavy processing in the worker thread pool:
                withContext(Dispatchers.Default) {
                    model.applyBnC(FloatRange(range), true)
                }
            }
        }

        // Column to hold the RangeSlider and the selected range text
        Column(
            modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val innerMargin = 5.dp

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Icon(
                        ImageVector.vectorResource(R.drawable.baseline_brightness_high_24),
                        "brightness/contrast"
                    )
                }
                Column(Modifier
                    .padding(start = innerMargin)
                    .weight(1f)) {
                    RangeSlider(
                        value = bnCRange.value,
                        onValueChange = { newRange ->
                            sliderEvents.trySend(newRange)
                        },
                        valueRange = maxRange,
                        enabled = enabled
                    )
                }
            }
        }
    }
}