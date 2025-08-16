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

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.batgizmo.app.HORange
import org.batgizmo.app.UIModel
import uk.org.gimell.batgimzoapp.BuildConfig

class PaneSizeHelper {

    private val logTag = this::class.simpleName

    private data class SizeGeneration(val sizePx: IntSize = IntSize(0, 0), val generation: Int = -1)

    private val sizeGenerationSaver: Saver<SizeGeneration, Any> = Saver(
        save = { sizeGen ->
            listOf(sizeGen.sizePx.width, sizeGen.sizePx.height, sizeGen.generation)
        },
        restore = { list ->
            val (width, height, generation) = list as List<Int>
            SizeGeneration(IntSize(width, height), generation)
        }
    )


    /**
    * There is some complexity here to group together values that we receive asynchronously
    * and supply them to the model in consistent groups, initially and also later
    * when the device is rotated etc. Compose makes some complex things very simple, and some
    * simple things very complex. Sigh.
    */
    @Composable
    fun compose(
        model: UIModel, scope: CoroutineScope, showAmplitudePane: Boolean,
        density: Density, rawPageRange: HORange?
    ): Pair<(IntSize)->Unit, (IntSize)->Unit> {
        var configurationGeneration by rememberSaveable { mutableIntStateOf(0) }
        var lastConfigurationHash by rememberSaveable { mutableIntStateOf(0) }
        var spectrogramSizeGen by rememberSaveable(stateSaver=sizeGenerationSaver) { mutableStateOf(SizeGeneration()) }
        var amplitudeSizeGen by rememberSaveable(stateSaver=sizeGenerationSaver) { mutableStateOf(SizeGeneration()) }

        // Detect any changes in configuration, synchronously, before the UI sizes itself:
        val configuration = LocalConfiguration.current
        if (lastConfigurationHash != configuration.hashCode()) {
            lastConfigurationHash = configuration.hashCode()
            // The following line invalidates any cached sizes relating to the previous generation:
            configurationGeneration++
        }

        LaunchedEffect(configurationGeneration, spectrogramSizeGen, amplitudeSizeGen) {
            if (BuildConfig.DEBUG)
                Log.d(logTag, "LaunchedEffect for pane sizes: $configurationGeneration, $spectrogramSizeGen, $amplitudeSizeGen")

            // If we have a consistent set of values we can send to the model asynchronously:
            var consistentSet = spectrogramSizeGen.generation == configurationGeneration
            if (showAmplitudePane) {
                consistentSet = consistentSet && amplitudeSizeGen.generation == configurationGeneration
            }
            if (consistentSet) {
                if (BuildConfig.DEBUG)
                    Log.d(logTag, "Consistent values detected, notifying the model")
                scope.launch {
                    with(density) {
                        model.onUISizeChange(
                            configurationGeneration,
                            DpSize(spectrogramSizeGen.sizePx.width.toDp(), spectrogramSizeGen.sizePx.height.toDp()),
                            DpSize(amplitudeSizeGen.sizePx.width.toDp(), amplitudeSizeGen.sizePx.height.toDp()),
                            model.settings, rawPageRange
                        )
                    }
                }
            }
        }

        val onAmplitudeSizeChange = { sizePx: IntSize ->
            amplitudeSizeGen = SizeGeneration(sizePx, configurationGeneration)
        }

        val onSpectrogramSizeChange = { sizePx: IntSize ->
            spectrogramSizeGen = SizeGeneration(sizePx, configurationGeneration)
        }

        return Pair(onAmplitudeSizeChange, onSpectrogramSizeChange)
    }
}