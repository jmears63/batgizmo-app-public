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

package org.batgizmo.app

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class FloatRange(override val start: Float = 0f,
                      override val endInclusive: Float = 1f)
    : ClosedFloatingPointRange<Float>, Parcelable {

    constructor(range: ClosedFloatingPointRange<Float>) :
            this(start = range.start,
                endInclusive = range.endInclusive) {

    }

    override fun lessThanOrEquals(a: Float, b: Float): Boolean = a <= b

    fun addOffset(offset: Float): FloatRange {
        return FloatRange(start + offset, endInclusive + offset)
    }

    fun mean(): Float {
        return (start + endInclusive) / 2
    }

    fun difference(): Float {
        return (endInclusive - start)
    }

    override fun toString(): String {
        return "[$start..$endInclusive]"
    }
}