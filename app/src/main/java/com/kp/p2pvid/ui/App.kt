package com.kp.p2pvid.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kp.p2pvid.call.CallViewModel
import com.kp.p2pvid.call.Phase

private val callPermissions = buildList {
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

@Composable
fun App(vm: CallViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val essential = result.filterKeys { it != Manifest.permission.POST_NOTIFICATIONS }
        if (essential.values.all { it }) pendingAction?.invoke()
        else vm.message("Camera and microphone access are needed for calls.")
        pendingAction = null
    }

    fun withPermissions(action: () -> Unit) {
        val missing = callPermissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) action() else {
            pendingAction = action
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    LaunchedEffect(ui.message) {
        ui.message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    val inCall = ui.phase == Phase.CONNECTING || ui.phase == Phase.IN_CALL
    BackHandler(enabled = inCall) { confirmLeave = true }
    BackHandler(enabled = ui.phase == Phase.HOSTING || ui.phase == Phase.JOINING) { vm.cancelSetup() }

    Box(Modifier.fillMaxSize()) {
        if (inCall) {
            CallScreen(vm, ui, onOpenSettings = { showSettings = true })
        } else {
            HomeScreen(
                ui = ui,
                lanAddress = vm.lanAddress,
                onHost = { withPermissions(vm::host) },
                onJoin = { code -> withPermissions { vm.join(code) } },
                onCancel = vm::cancelSetup,
                onOpenSettings = { showSettings = true },
            )
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.TopCenter).padding(top = 32.dp).navigationBarsPadding(),
        )
    }

    if (showSettings) {
        SettingsSheet(settings, onChange = vm::updateSettings, onDismiss = { showSettings = false })
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave call?") },
            text = { Text(if (ui.recording) "The recording will be saved." else "The other device will be disconnected.") },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; vm.hangUp() }) {
                    Text("Leave", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Stay") } },
        )
    }
}
