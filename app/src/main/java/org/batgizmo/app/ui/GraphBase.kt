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

import android.os.Parcelable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import kotlinx.parcelize.Parcelize
import org.batgizmo.app.FloatRange
import org.batgizmo.app.HORange
import org.batgizmo.app.UIModel
import org.batgizmo.app.pipeline.RendererBase


/**
 * This is a base class that factors out things common to any kind of graph.
 */
abstract class GraphBase(
    protected val model: UIModel,
    protected val rawPageRangeState: MutableState<HORange?>,
    protected val xAxisRangeFlow: StateFlow<FloatRange>,
    protected val yAxisRangeFlow: StateFlow<FloatRange>,
    private val supportCursor: Boolean) {

    protected lateinit var topBorder: BorderBase
    protected lateinit var rightBorder: BorderBase
    protected lateinit var leftBorder: BorderBase
    protected lateinit var bottomBorder: BorderBase
    private var dataRectDp: DpRect? = null

    protected lateinit var renderer: RendererBase

    @Parcelize
    data class GraphPadding(
        val leftDp: Float, val topDp: Float, val rightDp: Float, val bottomDp: Float
    ) : Parcelable

    fun reset() {
        renderer.reset()
    }

    @Composable
    fun ComposeFrame(
        modifier: Modifier,
        showGrid: Boolean,
        overlayComposer: (@Composable (Modifier) -> Unit) ? = null
    ) {
        /**
         * This code needs some explanation. It is a mix of declarative (composition) and
         * imperative (the lambdas passed to the canvases). This is not obvious from
         * reading the code - things are not executed from top to bottom. Here is the sequence:
         *
         * - First pass at composition composes the border canvas, but no more, as the border
         * padding values are not yet known.
         * - A bit later, the border canvas lambda is called, which calculates the size
         * that things are going to be, and assigns a value to the border padding values.
         * - Assign to the border padding values triggers a recompose, which now includes
         * all the UI elements.
         * - A bit later, the border canvas lambda is called again, and it is redrawn, rather
         * redundantly.
         *
         * Possibly the values calculated as a side effect of drawing (such as the tick values)
         * should be stored in a saveable remember - but it seems to work OK without.
         *
         * The x and y axis ranges are declared at a position the compose tree so that the borders
         * (and hence axes) get redrawn, but nothing else it.
         */

        Box(modifier) {

            val density = LocalDensity.current
            val colorScheme = MaterialTheme.colorScheme

            val borderPadding: MutableState<GraphPadding?> =
                rememberSaveable { mutableStateOf(null) }

            Box(Modifier.fillMaxSize()) {
                // Axis ranges:
                // Note: I had to specify the value parameters to rememberSaveable explicitly
                // to avoid an issue whereby changes were sometimes ignored. Perhaps there
                // is a quirk in relation to its change detection optimisation?
                // Also tried using the key parameter so that this state followed the graph
                // around the UI as the layout changes on configuration, but that didn't work for some reason.
                val xAxisRange = xAxisRangeFlow.collectAsStateWithLifecycle()
                val yAxisRange = yAxisRangeFlow.collectAsStateWithLifecycle()

                // Background and borders:
                Canvas(modifier
                        .fillMaxSize()
                        .background(color = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    /**
                     * We can't lay out the borders until we have the canvas size, which is finally
                     * available to us in this lambda.
                     */
                    layoutBorders(size, density)

                    drawBorders(colorScheme, this, size, density, xAxisRange.value, yAxisRange.value)

                    /**
                     * Now we know the border padding, we can trigger a recompose of the UI which
                     * includes elements that depend on the padding.
                     */
                    borderPadding.value = GraphPadding(
                        leftBorder.getDimensions()?.breadthDp?.value ?: 0f,
                        topBorder.getDimensions()?.breadthDp?.value ?: 0f,
                        rightBorder.getDimensions()?.breadthDp?.value ?: 0f,
                        bottomBorder.getDimensions()?.breadthDp?.value ?: 0f
                    )
                }
            }

            // Don't compose the following things until we know how big the graph borders are.
            val p = borderPadding.value
            if (p != null) {
                val safeBorderPadding: GraphPadding = p

                val padding = PaddingValues(
                    start = maxOf(safeBorderPadding.leftDp.dp, 0.dp),
                    top = safeBorderPadding.topDp.dp,
                    end = safeBorderPadding.rightDp.dp,
                    bottom = safeBorderPadding.bottomDp.dp + 0.5.dp
                )

                // The actual graph:
                renderer.Compose(Modifier.padding(padding), model.settings)

                // The graticule:
                Canvas(
                    modifier
                        .padding(padding)
                        .fillMaxSize()
                        .background(color = Color.Transparent)
                ) {
                    drawGraticule(colorScheme, this, size, density, showGrid)
                }

                if (overlayComposer != null) {
                    overlayComposer(Modifier.padding(padding))
                }
            }
        }
    }

    /**
     * Lay out and draw the borders.
     * Return the rectangle remaining for data.
     */
    private fun layoutBorders(
        frameSize: Size,
        density: Density
    ): DpRect {
        val sizeDp = with(density) {
            DpSize(frameSize.width.toDp(), frameSize.height.toDp())
        }

        // Lay out pass one:
        val leftBreadth: Dp = leftBorder.doLayoutFirstPass(sizeDp.height)
        val topBreadth: Dp = topBorder.doLayoutFirstPass(sizeDp.width)
        val rightBreadth: Dp = rightBorder.doLayoutFirstPass(sizeDp.height)
        val bottomBreadth: Dp = bottomBorder.doLayoutFirstPass(sizeDp.width)

        // Layout pass two, uses some of the results of layout part 1:
        val leftAxisLengthDp = leftBorder.doLayoutSecondPass(bottomBreadth, topBreadth)
        val topAxisLengthDp = topBorder.doLayoutSecondPass(leftBreadth, rightBreadth)
        rightBorder.doLayoutSecondPass(bottomBreadth, topBreadth)
        bottomBorder.doLayoutSecondPass(leftBreadth, rightBreadth)


        // Calculate the data rectangle inclusive of top and left, exclusive of
        // right and bottom:

        dataRectDp = DpRect(
            DpOffset(leftBreadth, sizeDp.width - rightBreadth),
            DpSize(topAxisLengthDp, leftAxisLengthDp)
        )

        return requireNotNull(dataRectDp)
    }

    /**
     * Lay out and draw the borders.
     * Return the rectangle remaining for data.
     */
    private fun drawBorders(
        colorScheme: ColorScheme,
        drawScope: DrawScope,
        frameSize: Size,
        density: Density,
        xAxisRange: FloatRange,
        yAxisRange: FloatRange
    ): DpRect {

        val sizeDp = with(density) {
            DpSize(frameSize.width.toDp(), frameSize.height.toDp())
        }

        leftBorder.draw(colorScheme, drawScope, density, DpOffset(0.dp, 0.dp), yAxisRange)
        topBorder.draw(colorScheme, drawScope, density, DpOffset(0.dp, 0.dp))

        val rightBreadth = rightBorder.getDimensions()?.breadthDp
        require(rightBreadth != null) { "layout calculations must be done before drawing (1)" }
        rightBorder.draw(
            colorScheme,
            drawScope,
            density,
            DpOffset(sizeDp.width - rightBreadth, 0.dp)
        )

        // sizeDP should be an inclusive DP pixel count, also bottomBreadth. So
        // the DP pixel index of the top line of the bottom margin should be
        // height - breath. Example: h=100, b=10. bottom y index is 99, so
        // the first index of the border should be 90, ie allowing 90-99 pixels, 10.

        val bottomBreadth = bottomBorder.getDimensions()?.breadthDp
        require(bottomBreadth != null) { "layout calculations must be done before drawing (2)" }
        bottomBorder.draw(
            colorScheme, drawScope, density, DpOffset(0.dp, sizeDp.height - bottomBreadth),
            xAxisRange
        )

        return requireNotNull(dataRectDp)
    }

    /**
     * Lay out and draw the borders.
     * Return the rectangle remaining for data.
     */
    private fun drawGraticule(
        colorScheme: ColorScheme,
        drawScope: DrawScope,
        dataSizePx: Size,
        density: Density,
        showGrid: Boolean
    ) {
        leftBorder.drawGraticule(colorScheme, drawScope, density, dataSizePx, showGrid)
        topBorder.drawGraticule(colorScheme, drawScope, density, dataSizePx, showGrid)
        rightBorder.drawGraticule(colorScheme, drawScope, density, dataSizePx, showGrid)
        bottomBorder.drawGraticule(colorScheme, drawScope, density, dataSizePx, showGrid)
    }

    /**
     * Called by renderers to signal that a change of visible range
     * has occurred. This allows the graph to adjust its axis ranges etc
     * accordingly, and potentially rebuild the pipeline if auto settings
     * require it.
     */
    fun onVisibleRangeChange(shouldAutoBnC: Boolean) {
        // The visible range has changed so we need to notify the rendering pipeline, which
        // updates the axis ranges.
        model.onVisibleRangeChange(model.settings, shouldAutoBnC, rawPageRangeState.value)
    }
}
