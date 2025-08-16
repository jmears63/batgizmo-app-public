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

import android.graphics.Paint
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.core.graphics.withRotation
import org.batgizmo.app.FloatRange
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

abstract class BorderBase {
    data class CalculatedDimensions(
        val breadthDp: Dp,
        val lengthDp: Dp,
        val axisLengthDp: Dp,
        val marginsDp: Pair<Dp, Dp>,
    )

    val textHeightDp: Dp = 12.dp
    val paddingDp: Dp = textHeightDp / 3
    val titleTextHeightDp: Dp = 14.dp
    val titlePaddingDp: Dp = titleTextHeightDp / 2
    val tickLengthDp: Dp = textHeightDp / 3
    val lineWidthDp: Dp = 1.dp
    val blankBreadthDp: Dp = 16.dp

    protected var calc: CalculatedDimensions? = null

    fun getDimensions(): CalculatedDimensions? {
        return calc?.copy()
    }

    open fun reset() {
        calc = null
    }

    /**
     * Do first pass layout calculations, return the resulting border breadth.
     */
    abstract fun doLayoutFirstPass(length: Dp): Dp

    /**
     * Do second pass layout calculations, return the resulting axis length.
     */
    fun doLayoutSecondPass(firstBreadth: Dp, secondBreadth: Dp): Dp {
        if (calc != null) {
            val updatedDimensions = CalculatedDimensions(
                breadthDp = calc!!.breadthDp,
                lengthDp = calc!!.lengthDp,
                axisLengthDp = calc!!.lengthDp - firstBreadth - secondBreadth,
                marginsDp = Pair(firstBreadth, secondBreadth)
            )
            calc = updatedDimensions
        } else {
            // Should never get here.
        }

        return calc?.axisLengthDp ?: 0.dp
    }

    /**
     * Actually draw the border. The first and second pass of layouts must
     * have been done previously. Drawing is done relative to the left,top origin
     * provided.
     */
    open fun draw(
        colorScheme: ColorScheme,
        drawScope: DrawScope, density: Density, origin: DpOffset,
        axisRange: FloatRange? = null,
    ) {
    }

    /**
     * Draw any graticule elements required for this axis border.
     * draw() must have been called first, to cache values needed.
     */
    open fun drawGraticule(
        colorScheme: ColorScheme, drawScope: DrawScope,
        density: Density, sizePx: Size,
        showGrid: Boolean
    ) {
    }
}

class TitleBorder(private var title: String?) : BorderBase() {
    override fun doLayoutFirstPass(length: Dp): Dp {
        reset()
        val cd = CalculatedDimensions(
            breadthDp = titlePaddingDp + titleTextHeightDp + titlePaddingDp,
            lengthDp = length,
            // Slight hack: will be replaced with real values in the second pass.
            axisLengthDp = 0.dp,
            marginsDp = Pair(0.dp, 0.dp)
        )
        calc = cd
        return cd.breadthDp
    }

    override fun draw(
        colorScheme: ColorScheme,
        drawScope: DrawScope,
        density: Density,
        origin: DpOffset,
        axisRange: FloatRange?
    ) {
        calc?.let {
            drawScope.drawIntoCanvas { canvas ->

                /**
                 *  We have to use native android canvas and paint types -
                 *  non declarative coding is not the Compose way, and it doesn't
                 *  support it.
                 */

                val nativePaint = Paint().apply {
                    color = colorScheme.onPrimaryContainer.toArgb()
                    textSize = with(density) { titleTextHeightDp.toPx() }
                    textAlign = Paint.Align.CENTER
                    strokeWidth = 1f    // Used for drawing lines.
                }

                val x = with(density) { (origin.x.toPx()) + (it.lengthDp.toPx()) / 2 }
                val y = with(density) { (origin.y + titlePaddingDp + titleTextHeightDp).toPx() }

                // Draw text using Android's Canvas. Note that the text height ends up looking somewhat
                // less than we asked for because it allows space for accents above capital letters.
                canvas.nativeCanvas.drawText(title ?: "", x, y, nativePaint)
            }
        }
    }

    fun setTitle(title: String?) {
        this.title = title
    }
}

class BlankBorderVertical : BorderBase() {
    override fun doLayoutFirstPass(length: Dp): Dp {
        reset()
        val cd = CalculatedDimensions(
            lengthDp = length,
            breadthDp = blankBreadthDp,
            // Slight hack: will be replaced with real values in the second pass.
            axisLengthDp = 0.dp,
            marginsDp = Pair(0.dp, 0.dp)
        )
        calc = cd
        return cd.breadthDp
    }
}

class BlankBorderHorizontal : BorderBase() {
    override fun doLayoutFirstPass(length: Dp): Dp {
        reset()
        val cd = CalculatedDimensions(
            lengthDp = length,
            breadthDp = blankBreadthDp,
            // Slight hack: will be replaced with real values in the second pass.
            axisLengthDp = 0.dp,
            marginsDp = Pair(0.dp, 0.dp)
        )
        calc = cd
        return cd.breadthDp
    }
}

class NullBorderHorizontal : BorderBase() {
    override fun doLayoutFirstPass(length: Dp): Dp {
        reset()
        val cd = CalculatedDimensions(
            lengthDp = length,
            breadthDp = 0.dp,
            // Slight hack: will be replaced with real values in the second pass.
            axisLengthDp = 0.dp,
            marginsDp = Pair(0.dp, 0.dp)
        )
        calc = cd
        return cd.breadthDp
    }
}

typealias TickData = List<Pair<Float, Dp>>

abstract class AxisBorder(
    units: List<Unit>,
    private val showAxis: Boolean,    // Actually draw the axis?
    protected val layoutType: Layout,
    // private val showGrid: Boolean
)   // Determines the space it takes.
    : BorderBase() {

    enum class Layout {
        FULL,           // Axis title and units in all on their own row.
        COMPACT,        // Units as the last tick label.
        MINIMAL,        // Just ticks.
        NONE            // Zero breadth border.
    }       // Ticks only.

    /**
     * Representation of units alternatives for an axis:
     */
    data class Unit(
        val limit: Float = Float.MAX_VALUE, // Upper limit for this unit, or MAX_VALUE if it is the default.
        val units: String? = null,          // The optional unit text.
        val scaling: Float = 1f,            // Multiplies the tick values.
    )

    // We have to have the possible units sorted in ascending order:
    private val sortedUnits: List<Unit> = units.sortedWith(compareBy { it.limit })

    // Some data cached by draw() for use in drawGraticule():
    private var cachedTickData: TickData? = null

    private val graticuleColour = Color.DarkGray

    override fun reset() {
        super.reset()
        cachedTickData = null
    }

    override fun doLayoutFirstPass(length: Dp): Dp {
        reset()
        val cd: CalculatedDimensions = when (layoutType) {
            Layout.FULL -> {
                CalculatedDimensions(
                    lengthDp = length,
                    // Axis border consists of the axis line, ticks, padding, label text, padding, axis title, padding.
                    breadthDp = lineWidthDp + tickLengthDp + paddingDp + textHeightDp + paddingDp + textHeightDp + paddingDp,
                    // Slight hack: will be replaced with real values in the second pass.
                    axisLengthDp = 0.dp,
                    marginsDp = Pair(0.dp, 0.dp)
                )
            }

            Layout.COMPACT -> {
                CalculatedDimensions(
                    lengthDp = length,
                    // It's a minimal axis, the same size as a blank axis:
                    breadthDp = lineWidthDp + tickLengthDp + paddingDp + textHeightDp + paddingDp,
                    // Slight hack: will be replaced with real values in the second pass.
                    axisLengthDp = 0.dp,
                    marginsDp = Pair(0.dp, 0.dp)
                )
            }

            Layout.MINIMAL -> {
                CalculatedDimensions(
                    lengthDp = length,
                    // It's a minimal axis, the same size as a blank axis:
                    breadthDp = lineWidthDp + tickLengthDp + paddingDp,
                    // Slight hack: will be replaced with real values in the second pass.
                    axisLengthDp = 0.dp,
                    marginsDp = Pair(0.dp, 0.dp)
                )
            }

            Layout.NONE -> {
                CalculatedDimensions(
                    lengthDp = length,
                    // It's a minimal axis, the same size as a blank axis:
                    breadthDp = 0.dp,
                    // Slight hack: will be replaced with real values in the second pass.
                    axisLengthDp = 0.dp,
                    marginsDp = Pair(0.dp, 0.dp)
                )
            }
        }

        calc = cd
        return cd.breadthDp
    }

    override fun draw(
        colorScheme: ColorScheme,
        drawScope: DrawScope,
        density: Density,
        origin: DpOffset,
        axisRange: FloatRange?
    ) {
        calc?.let {
            val safeAxisRange = axisRange ?: FloatRange(0f, 1f)
            val safeAxisLengthDp = it.axisLengthDp

            /**
             *  We have to use native android canvas and paint types -
             *  non declarative coding is not the Compose way, and it doesn't
             *  support it.
             */

            val nativePaint = Paint().apply {
                color = colorScheme.onPrimaryContainer.toArgb()
                textSize = with(density) { textHeightDp.toPx() }
                textAlign = Paint.Align.CENTER
                strokeWidth = with(density) { lineWidthDp.toPx() }
            }

            val unitToUse = getUnits(safeAxisRange)

            // Calculate the tick placement from the axis range:
            val (ticks, decimalPlaces) = calculateTicks(
                density,
                axisRange = safeAxisRange,
                multiplier = unitToUse.scaling,
                dpRange = safeAxisLengthDp - 1.dp       // -1.dp because if the length is 100, the range is 0 to 99.
            )

            // Cache the tick positions for drawing the graticule later:
            cachedTickData = ticks

            if (showAxis) {
                drawScope.drawIntoCanvas { canvas ->
                    doDraw(density, origin, canvas, nativePaint, ticks, decimalPlaces, unitToUse)
                }
            }
        }
    }

    /**
     *  Come up with sane values and positions for ticks, based loosely on have a tick
     *  per set number of pixels.
     *
     *  Return: (List of (value, DPs) per tick, number of decimal places.)
     */
    private fun calculateTicks(
        density: Density,
        axisRange: FloatRange,
        multiplier: Float,
        dpRange: Dp,
        zeroBasedAxis: Boolean = false,
        targetSpacingDp: Dp = 100.dp

    ): Pair<TickData, Int> {

        val offsetAxisRange = if (zeroBasedAxis) {
            // Offset the time ticks so that they always start at zero:
            FloatRange(0f, axisRange.start - axisRange.endInclusive)
        } else {
            axisRange
        }

        val minValue: Float = offsetAxisRange.start / multiplier
        val maxValue: Float = offsetAxisRange.endInclusive / multiplier

        // Sanity checks:
        if (minValue >= maxValue)
            return Pair(emptyList(), 0)

        if (dpRange <= 0.dp)
            return Pair(emptyList(), 0)

        val rawSpan = maxValue - minValue
        val rawInterval = rawSpan * (targetSpacingDp / dpRange)
        val scaling = 10.0.pow(floor(log10(rawInterval)).toDouble())
        val normalizedInterval = rawInterval / scaling

        val roundedNormalizedInterval = when {
            normalizedInterval >= 5 -> 5
            normalizedInterval >= 2 -> 2
            else -> 1
        }

        val roundedInterval: Float = (roundedNormalizedInterval * scaling).toFloat()
        val roundedMin = floor(minValue / roundedInterval) * roundedInterval

        val tickValues = mutableListOf<Float>()
        var v: Float = roundedMin.toFloat()

        while (v < maxValue + roundedInterval) {
            if (v in minValue..maxValue) {
                tickValues.add(v)
            }
            v += roundedInterval
        }

        // We *could* do something smarter with logs. But will we?
        val decimalPlaces: Int = when {
            roundedInterval < 0.0001 -> 5
            roundedInterval < 0.001 -> 4
            roundedInterval < 0.01 -> 3
            roundedInterval < 0.1 -> 2
            roundedInterval < 1 -> 1
            else -> 0
        }

        val ticks: List<Pair<Float, Dp>> = with(density) {
            tickValues.map { it: Float ->
                Pair(it, (((it - minValue) / (maxValue - minValue)) * dpRange.toPx() + 0.5f).toDp())
            }
        }

        return Pair(ticks, decimalPlaces)
    }

    private fun getUnits(axisRange: FloatRange): Unit {
        // Decide what units to use based on the axis range.

        if (sortedUnits.size == 1) {
            return sortedUnits[0]
        } else {
            val absMax = maxOf(abs(axisRange.start), abs(axisRange.endInclusive))
            // The possible units are already sorted in ascending order.
            var u = sortedUnits[0] // Avoid a warning.
            for (unit in sortedUnits) {
                if (absMax < unit.limit) {
                    return unit
                }
                u = unit
            }
            return u
        }
    }

    protected abstract fun doDraw(
        density: Density,
        origin: DpOffset,
        canvas: Canvas,
        nativePaint: Paint,
        ticks: List<Pair<Float, Dp>>,
        decimalPlaces: Int,
        unitToUse: Unit
    )

    override fun drawGraticule(
        colorScheme: ColorScheme,
        drawScope: DrawScope,
        density: Density,
        sizePx: Size,
        showGrid: Boolean
    ) {
        calc?.let {
            val gridPaint = Paint().apply {
                // Use a hard coded colour as the data area doesn't use theme colours:
                color = graticuleColour.toArgb()
                textSize = with(density) { textHeightDp.toPx() }
                textAlign = Paint.Align.CENTER
                strokeWidth = with(density) { lineWidthDp.toPx() }
            }

            drawScope.drawIntoCanvas { canvas ->
                cachedTickData?.let {
                    doDrawGraticule(density, sizePx, canvas, gridPaint, it, showGrid)
                }
            }
        }
    }

    protected abstract fun doDrawGraticule(
        density: Density, sizePx: Size, canvas: Canvas,
        nativePaint: Paint, ticks: TickData,
        showGrid: Boolean
    )
}

class AxisBorderHorizontal(
    private val axisTitle: String, units: List<Unit>,
    showAxis: Boolean = true,
    layoutType: Layout
) : AxisBorder(units, showAxis, layoutType) {

    override fun doDraw(
        density: Density,
        origin: DpOffset,
        canvas: Canvas,
        nativePaint: Paint,
        ticks: List<Pair<Float, Dp>>,
        decimalPlaces: Int,
        unitToUse: Unit
    ) {
        if (layoutType == Layout.NONE)
            return

        calc?.let {
            with(density) {
                // Draw the axis line:
                val (x1, x2) = Pair<Float, Float>(
                    (origin.x + it.marginsDp.first - lineWidthDp).toPx(),   // One lineWidthDp overlap with the other axis.
                    (origin.x + it.lengthDp - it.marginsDp.second).toPx()
                )
                val y = origin.y.toPx()

                // Note: drawLine doesn't include the final point:
                canvas.nativeCanvas.drawLine(x1, y, x2, y, nativePaint)

                // Draw the ticks and labels:
                for ((i, t) in ticks.withIndex()) {
                    val (value, dp) = t
                    val x: Float = (dp + origin.x + it.marginsDp.first).toPx()
                    val y1: Dp = origin.y
                    val y2: Dp = y1 + tickLengthDp
                    val y3: Dp = y2 + paddingDp + textHeightDp
                    canvas.nativeCanvas.drawLine(x, y1.toPx(), x, y2.toPx(), nativePaint)

                    if (layoutType != Layout.MINIMAL) {
                        var stringValue = ""
                        if (layoutType == Layout.COMPACT
                            && i == ticks.size - 1
                            && unitToUse.units != null
                        ) {
                            stringValue = "(%s)".format(unitToUse.units)
                        } else {
                            val format = "%.${decimalPlaces}f"
                            stringValue = String.format(format, value)
                        }
                        canvas.nativeCanvas.drawText(stringValue, x, y3.toPx(), nativePaint)
                    }
                }

                if (layoutType == Layout.FULL) {
                    // Draw the axis label and units:
                    val x4: Float = (origin.x + it.marginsDp.first + it.axisLengthDp / 2).toPx()
                    val y4: Dp =
                        origin.y + tickLengthDp + paddingDp + textHeightDp + paddingDp + textHeightDp
                    val title = if (unitToUse.units == null) axisTitle else {
                        "%s (%s)".format(axisTitle, unitToUse.units)
                    }
                    canvas.nativeCanvas.drawText(title, x4, y4.toPx(), nativePaint)
                }
            }
        }
    }

    override fun doDrawGraticule(
        density: Density, sizePx: Size, canvas: Canvas,
        nativePaint: Paint, ticks: TickData,
        showGrid: Boolean
    ) {

        if (showGrid) {
            // Note: line drawing is exclusive of the second point, so we intentionally
            // provide a value beyond the size available:
            with(density) {
                val (y1, y2) = Pair(0.dp.toPx(), sizePx.height)

                for ((_, dp) in ticks) {
                    val x = dp.toPx()
                    canvas.nativeCanvas.drawLine(x, y1, x, y2, nativePaint)
                }
            }
        }
    }
}

class AxisBorderVertical(
    private val axisTitle: String, units: List<Unit>,
    showAxis: Boolean = true, layoutType: Layout
) : AxisBorder(units, showAxis, layoutType) {

    override fun doDraw(
        density: Density,
        origin: DpOffset,
        canvas: Canvas,
        nativePaint: Paint,
        ticks: List<Pair<Float, Dp>>,
        decimalPlaces: Int,
        unitToUse: Unit
    ) {
        if (layoutType == Layout.NONE)
            return

        calc?.let {
            with(density) {
                // Draw the axis line:
                val (y11, y12) = Pair<Float, Float>(
                    (origin.y + it.marginsDp.second + it.axisLengthDp).toPx(),  // Overlaps the other axis by 1.dp.
                    (origin.y + it.marginsDp.second - 1.dp).toPx()              // + 1.dp because the end of the line is exclusive.
                )
                val x1 = (origin.x + it.breadthDp - 1.dp).toPx()

                // Note: drawLine doesn't include the final point:
                canvas.nativeCanvas.drawLine(x1, y11, x1, y12, nativePaint)

                // Draw the ticks and labels:
                val x21: Dp = origin.x + it.breadthDp - 1.dp
                val x22: Dp = x21 - tickLengthDp
                val x23: Dp = x22 - paddingDp
                for ((i, t) in ticks.withIndex()) {
                    val (value, dp) = t
                    // - 1.dp to be above the other border.
                    val y21: Float =
                        (origin.y + it.marginsDp.second + it.axisLengthDp - dp - 1.dp).toPx()
                    canvas.nativeCanvas.drawLine(x21.toPx(), y21, x22.toPx(), y21, nativePaint)

                    if (layoutType != Layout.MINIMAL) {
                        var stringValue = ""
                        if (layoutType == Layout.COMPACT
                            && i == ticks.size - 1
                            && unitToUse.units != null
                        ) {
                            stringValue = "(%s)".format(unitToUse.units)
                        } else {
                            val format = "%.${decimalPlaces}f"
                            stringValue = String.format(format, value)
                        }
                        canvas.nativeCanvas.withRotation(
                            degrees = -90f,
                            pivotX = x23.toPx(),
                            pivotY = y21
                        ) {
                            canvas.nativeCanvas.drawText(stringValue, x23.toPx(), y21, nativePaint)
                        }
                    }
                }

                if (layoutType == Layout.FULL) {
                    // Draw the axis title and units:
                    val x4: Float = (x23 - paddingDp - textHeightDp).toPx()
                    val y4: Dp = origin.y + (it.marginsDp.second + it.axisLengthDp / 2)
                    val title = if (unitToUse.units == null) axisTitle else {
                        "%s (%s)".format(axisTitle, unitToUse.units)
                    }
                    canvas.nativeCanvas.withRotation(
                        degrees = -90f,
                        pivotX = x4,
                        pivotY = y4.toPx()
                    ) {
                        canvas.nativeCanvas.drawText(title, x4, y4.toPx(), nativePaint)
                    }
                }
            }
        }
    }

    override fun doDrawGraticule(
        density: Density, sizePx: Size, canvas: Canvas,
        gridPaint: Paint, ticks: TickData,
        showGrid: Boolean
    ) {
        // Note: line drawing is exclusive of the second point, so we intentionally
        // provide a value beyond the size available:
        if (showGrid) {
            with(density) {
                val (x1, x2) = Pair(0.dp.toPx(), sizePx.width)

                calc?.let {
                    for ((_, dp) in ticks) {
                        val y: Float = (it.axisLengthDp - dp - 1.dp).toPx()
                        canvas.nativeCanvas.drawLine(x1, y, x2, y, gridPaint)
                    }
                }
            }
        }

        /*
        val showHeterodyneCursors = true
        if (showHeterodyneCursors) {
            val cursorPaint = Paint().apply {
                // Use a hard coded colour as the data area doesn't use theme colours:
                color = cursorColour.toArgb()
                textSize = with(density) { textHeightDp.toPx() }
                textAlign = Paint.Align.CENTER
                strokeWidth = with(density) { lineWidthDp.toPx() }
            }

            val y = 100f
            canvas.nativeCanvas.drawLine(0f, y, sizePx.width, y, cursorPaint)
        }
         */
    }
}

