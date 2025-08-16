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

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import org.batgizmo.app.FileWriter
import org.batgizmo.app.UIModel
import uk.org.gimell.batgimzoapp.R
import java.util.Locale

@Composable
fun FileMetadata(context: Context, model: UIModel, onDismiss: () -> Unit) {
    val wfi = model.getWavFileInfo()

    val entries = mutableListOf<Pair<String, String?>>()

    wfi?.let {
        entries.add(Pair("WAV data:", null))
        entries.add(Pair("File name", (it.fileName ?: "(none)")))
        entries.add(Pair("Sample rate", "%.1f kHz".format(it.sampleRate / 1000f)))
        entries.add(Pair("Channels", "${it.numChannels}"))
        entries.add(Pair("Duration (s)", FileWriter.prettyFloat3Dps(it.lengthSeconds)))
        val guano = it.guanoChunkInfo
        if (guano != null) {
            entries.add(Pair("GUANO data:", null))
            for (g in guano.entriesList) {
                entries.add(Pair(g.key, g.value))
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        },
        title = { Text("File Details: ") },
        text = {
            LazyColumn(
                Modifier,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for ((key, value) in entries) {
                    item {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (value == null) {
                                Text(text = key, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
                            }
                            else {
                                Text(text = "$key:  $value")
                                if (key.lowercase() == "loc position") {
                                    Spacer(Modifier.width(10.dp))
                                    MyButton(
                                        ImageVector.vectorResource(R.drawable.baseline_location_pin_24),
                                        "Location"
                                    )
                                    {
                                        try {
                                            val (longitude, latitude) = value.trim().split(" ")
                                                .map { it.toDouble() }
                                            openMap(context, longitude, latitude)
                                        } catch (e: Exception) {
                                            Log.e(
                                                this::class.simpleName,
                                                "unable to parse location string $value"
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}

private fun openMap(context: Context, latitude: Double, longitude: Double) {
    // This opens the maps app on the location supplied.
    // Unfortunately, it opens it with its left pane open. I don't know how to avoid that.
    val uri: String = java.lang.String.format(Locale.ENGLISH, "geo:0,0?q=$latitude,$longitude(bat location)", latitude, longitude)
    val intent = Intent(Intent.ACTION_VIEW, uri.toUri())
    // intent.setPackage("com.google.android.apps.maps") // Force Google Maps (optional)
    context.startActivity(intent)
}

