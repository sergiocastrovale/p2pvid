package com.kp.p2pvid

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kp.p2pvid.call.CallViewModel
import com.kp.p2pvid.call.Phase
import com.kp.p2pvid.ui.App
import com.kp.p2pvid.ui.theme.P2PTheme
import com.kp.p2pvid.view.ViewMode

class MainActivity : ComponentActivity() {
    private val vm: CallViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val ui by vm.ui.collectAsStateWithLifecycle()
            val settings by vm.settings.collectAsStateWithLifecycle()
            val inCall = ui.phase == Phase.CONNECTING || ui.phase == Phase.IN_CALL

            LaunchedEffect(inCall, ui.view.mode, settings.keepScreenOn) {
                // Dual view is landscape-only; every other view follows the sensor.
                requestedOrientation = if (inCall && ui.view.mode == ViewMode.DUAL) {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
                if (inCall && settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                val insets = WindowCompat.getInsetsController(window, window.decorView)
                if (inCall) {
                    insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    insets.hide(WindowInsetsCompat.Type.systemBars())
                } else {
                    insets.show(WindowInsetsCompat.Type.systemBars())
                }
            }

            P2PTheme { App(vm) }
        }
    }
}
