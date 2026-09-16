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

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Idle / pulse-floor alpha for [AutoIdSparkleIcon]. */
private const val SPARKLE_DIM = 0.5f

/**
 * Non-interactive Auto Id activity indicator. Occupies layout space while
 * composed. Animation phases while [active]:
 * 1. Start: fade up to full
 * 2. Pulse: [SPARKLE_DIM] ↔ 1 while work continues
 * 3. Idle: when work ends, finish the current down-leg to [SPARKLE_DIM]
 *    and stay visible (never fade fully out while this icon is shown).
 */
@Composable
fun AutoIdSparkleIcon(active: Boolean) {
    val alpha = remember { Animatable(SPARKLE_DIM) }
    var pulsing by remember { mutableStateOf(false) }
    val activeState = rememberUpdatedState(active)

    LaunchedEffect(active) {
        if (active) pulsing = true
    }

    LaunchedEffect(pulsing) {
        if (!pulsing) return@LaunchedEffect
        val halfCycle = tween<Float>(800, easing = FastOutSlowInEasing)
        // (1) Starting: up to full brightness
        alpha.animateTo(1f, animationSpec = halfCycle)
        // (2) Pulsate between dim and full until inactive, then (3) rest at dim.
        while (true) {
            alpha.animateTo(SPARKLE_DIM, animationSpec = halfCycle)
            if (!activeState.value) {
                pulsing = false
                break
            }
            alpha.animateTo(1f, animationSpec = halfCycle)
            if (!activeState.value) {
                alpha.animateTo(SPARKLE_DIM, animationSpec = halfCycle)
                pulsing = false
                break
            }
        }
    }

    val iconSize = with(LocalDensity.current) {
        SpectrogramOverlayStyle.textSize.toDp() * 1.4f
    }
    Icon(
        imageVector = Icons.Filled.AutoAwesome,
        contentDescription = if (active || pulsing) "Auto Id working" else "Auto Id",
        tint = SpectrogramOverlayStyle.textColor,
        modifier = Modifier
            .padding(end = 6.dp, top = 2.dp)
            .size(iconSize)
            .graphicsLayer { this.alpha = alpha.value },
    )
}
