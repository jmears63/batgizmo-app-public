package org.batgizmo.app.ui
import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.Surface

class ScreenOrientationLocker() {
    companion object {
        fun lockCurrentRotation(activity: Activity) {
            val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity.display?.rotation ?: Surface.ROTATION_0
            } else {
                @Suppress("DEPRECATION")
                activity.windowManager.defaultDisplay.rotation
            }

            activity.requestedOrientation = when (rotation) {
                Surface.ROTATION_0 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                Surface.ROTATION_270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_LOCKED
            }
        }

        fun unlock(activity: Activity) {
            // fullSensor: follow the orientation sensor (all four rotations), ignoring the
            // system auto-rotate lock. The in-app lock button replaces that system setting.
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
    }
}
