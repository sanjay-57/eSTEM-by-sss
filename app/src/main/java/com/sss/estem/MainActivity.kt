package com.sss.estem

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.sss.estem.design.puck.PuckButton
import com.sss.estem.ui.EstemApp

class MainActivity : ComponentActivity() {

    private val container by lazy { (application as EstemApplication).container }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The stage is a light beige, so the system bars need dark icons — the default would
        // paint them white and make the clock invisible.
        val bars = SystemBarStyle.light(STAGE_ARGB, STAGE_ARGB)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        setContent { EstemApp(container) }
    }

    /**
     * The phone's volume rocker is the puck's volume rocker.
     *
     * That is not just a shortcut — it is why VOL_UP/VOL_DOWN are absent from the drawn face. It
     * also keeps the hardware's chords intact: holding volume-up starts a recording, and
     * centre + volume-up opens the effects menu, exactly as on the device.
     *
     * Handled in [dispatchKeyEvent] rather than onKeyDown so it works no matter which view has
     * focus, and only the first event of a hold is forwarded — auto-repeat would otherwise look
     * like a burst of separate presses and never resolve into a long press.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val button = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> PuckButton.VOL_UP
            KeyEvent.KEYCODE_VOLUME_DOWN -> PuckButton.VOL_DOWN
            else -> return super.dispatchKeyEvent(event)
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                container.player.onButtonDown(button)
            }

            KeyEvent.ACTION_UP -> container.player.onButtonUp(button)
        }
        // Consume either way, so the system volume UI never appears over the puck.
        return true
    }

    /**
     * Separation runs in a foreground service and shows minutes of progress. Without this the
     * work still runs, but with no indication of it, which reads as the app hanging.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private companion object {
        /** Must track EstemColors.Stage. */
        const val STAGE_ARGB = 0xFFE9E4D6.toInt()
    }
}
