package com.kp.p2pvid.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VerticalSplit
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kp.p2pvid.call.CallUi
import com.kp.p2pvid.call.CallViewModel
import com.kp.p2pvid.call.Phase
import com.kp.p2pvid.ui.theme.P2PColors
import com.kp.p2pvid.view.Party
import com.kp.p2pvid.view.ViewMode
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun CallScreen(vm: CallViewModel, ui: CallUi, onOpenSettings: () -> Unit) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val context = LocalContext.current

    // Controls auto-hide after a few seconds; any tap brings them back.
    var controlsVisible by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    fun touch() { lastInteraction = SystemClock.elapsedRealtime(); controlsVisible = true }
    LaunchedEffect(lastInteraction, ui.phase) {
        delay(4_000)
        if (ui.phase == Phase.IN_CALL) controlsVisible = false
    }
    val settings by vm.settings.collectAsStateWithLifecycle()
    // Overlay (status pills, name tags, PiP frame): always on, or only while controls are shown.
    val overlayVisible = settings.showOverlay || controlsVisible || ui.phase != Phase.IN_CALL

    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.toggleRecording(landscape) else vm.message("Storage access is needed to save recordings.")
    }
    fun onRecord() {
        touch()
        val needsLegacyStorage = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !ui.recording &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (needsLegacyStorage) storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else vm.toggleRecording(landscape)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures { if (controlsVisible) controlsVisible = false else touch() }
            },
    ) {
        Stage(
            vm = vm,
            ui = ui,
            feedOf = { if (it == ui.self) vm.localFeed else vm.remoteFeed },
            onPipTap = { party -> touch(); vm.focusOn(party) },
            overlayVisible = overlayVisible,
        )

        if (ui.phase == Phase.CONNECTING) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(16.dp))
                Text("Connecting…", color = Color.White)
            }
        }

        // Same plain 12dp margin as the Host/Guest tags (no system-inset padding, which
        // pushed the pills toward the middle in landscape).
        AnimatedVisibility(
            visible = overlayVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
        ) {
            StatusChips(ui, Modifier)
        }

        AnimatedVisibility(
            visible = controlsVisible || ui.phase != Phase.IN_CALL,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Controls(
                ui = ui,
                onAny = ::touch,
                onMode = vm::setMode,
                onDirector = vm::setDirector,
                onMic = vm::toggleMic,
                onCam = vm::toggleCamera,
                onFlip = { vm.switchCamera() },
                onRecord = ::onRecord,
                onSettings = onOpenSettings,
                onHangUp = vm::hangUp,
                modifier = Modifier.safeDrawingPadding().padding(bottom = 12.dp),
            )
        }
    }
}

/** Video layout. Mirrors [com.kp.p2pvid.view.Layout], which the recorder uses. */
@Composable
private fun Stage(
    vm: CallViewModel,
    ui: CallUi,
    feedOf: (Party) -> com.kp.p2pvid.rtc.VideoFanout,
    onPipTap: (Party) -> Unit,
    overlayVisible: Boolean,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        val dual = ui.view.mode == ViewMode.DUAL
        val mainParty = if (dual) Party.HOST else ui.view.main
        val secondParty = mainParty.other
        fun mirrorOf(p: Party) = p == ui.self && ui.frontCamera

        // PiP geometry (single-stream modes)
        val short = if (w < h) w else h
        val pipW: Dp
        val pipH: Dp
        if (w < h) { pipW = short / 3.6f; pipH = pipW * 16 / 9 } else { pipH = short / 3.6f; pipW = pipH * 16 / 9 }
        var drag by remember { mutableStateOf(Offset.Zero) }
        val margin = 16.dp

        val bandH = if (w >= h) h else w * 9 / 16
        val mainModifier = if (dual) {
            Modifier.width(w / 2).height(bandH).align(Alignment.CenterStart)
        } else Modifier.fillMaxSize()

        val pipBase = Modifier
            .align(Alignment.TopStart)
            .offset {
                val maxX = (w - pipW - margin).roundToPx().toFloat()
                val maxY = (h - pipH - margin * 6).roundToPx().toFloat()
                val x = (maxX + drag.x).coerceIn(margin.roundToPx().toFloat(), maxX)
                val y = (maxY + drag.y).coerceIn(margin.roundToPx().toFloat() * 4, maxY)
                IntOffset(x.roundToInt(), y.roundToInt())
            }
            .size(pipW, pipH)
        val secondModifier = if (dual) {
            Modifier.width(w / 2).height(bandH).align(Alignment.CenterEnd)
        } else pipBase

        VideoSurface(vm.eglBase, feedOf(mainParty), mirrorOf(mainParty), overlay = false, modifier = mainModifier)
        VideoSurface(vm.eglBase, feedOf(secondParty), mirrorOf(secondParty), overlay = true, modifier = secondModifier)

        if (!ui.camOn) {
            CameraOffBadge(if (mainParty == ui.self) mainModifier else secondModifier)
        }
        if (dual) {
            if (overlayVisible) {
                NameTag("Host", Modifier.align(Alignment.CenterStart).offset(y = bandH / 2 - 28.dp).padding(start = 12.dp))
                NameTag("Guest", Modifier.align(Alignment.CenterEnd).offset(y = bandH / 2 - 28.dp).padding(end = 12.dp))
            }
        } else {
            // Touch target over the PiP: drag to move, tap to pin that person.
            Box(
                pipBase
                    .then(
                        if (overlayVisible) Modifier.border(1.5.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                        else Modifier,
                    )
                    .pointerInput(Unit) {
                        detectDragGestures { change, amount ->
                            change.consume()
                            drag += amount
                        }
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = !ui.isFollower,
                    ) { onPipTap(secondParty) },
            )
        }
    }
}

@Composable
private fun CameraOffBadge(modifier: Modifier) {
    Box(modifier.background(Color(0xFF1A1C21)), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.VideocamOff, "Camera off", tint = P2PColors.Muted)
    }
}

@Composable
private fun NameTag(text: String, modifier: Modifier) {
    Text(
        text,
        color = Color.White,
        fontSize = 12.sp,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(P2PColors.Scrim).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun StatusChips(ui: CallUi, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (ui.recording) {
            var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
            LaunchedEffect(Unit) { while (true) { now = SystemClock.elapsedRealtime(); delay(500) } }
            val s = ((now - ui.recordingSinceMs) / 1000).coerceAtLeast(0)
            Chip(dot = P2PColors.Rec, text = "REC  %d:%02d".format(s / 60, s % 60), mono = true)
        }
        if (ui.peerRecording) Chip(dot = P2PColors.Rec, text = "Other side is recording")
        if (ui.isFollower) Chip(icon = Icons.Outlined.Movie, text = "Directed by other side")
        if (ui.isDirector) Chip(icon = Icons.Outlined.Movie, text = "You're directing")
        if (ui.reconnecting) Chip(dot = Color(0xFFFFC53D), text = "Reconnecting…")
    }
}

@Composable
private fun Chip(text: String, dot: Color? = null, icon: ImageVector? = null, mono: Boolean = false) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(P2PColors.Scrim).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        dot?.let { Box(Modifier.size(8.dp).clip(CircleShape).background(it)) }
        icon?.let { Icon(it, null, Modifier.size(14.dp), tint = Color.White) }
        Spacer(Modifier.width(6.dp))
        Text(text, color = Color.White, fontSize = 12.sp, fontFamily = if (mono) FontFamily.Monospace else null)
    }
}

@Composable
private fun Controls(
    ui: CallUi,
    onAny: () -> Unit,
    onMode: (ViewMode) -> Unit,
    onDirector: (Boolean) -> Unit,
    onMic: () -> Unit,
    onCam: () -> Unit,
    onFlip: () -> Unit,
    onRecord: () -> Unit,
    onSettings: () -> Unit,
    onHangUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // View row: mode selector + director switch
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(P2PColors.Glass).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val enabled = !ui.isFollower
            ModeTab(Icons.Outlined.GraphicEq, "Dynamic", ui.view.mode == ViewMode.DYNAMIC, enabled) { onAny(); onMode(ViewMode.DYNAMIC) }
            ModeTab(Icons.Outlined.Lock, "Locked", ui.view.mode == ViewMode.LOCKED, enabled) { onAny(); onMode(ViewMode.LOCKED) }
            ModeTab(Icons.Outlined.VerticalSplit, "Dual", ui.view.mode == ViewMode.DUAL, enabled) { onAny(); onMode(ViewMode.DUAL) }
            Box(Modifier.padding(horizontal = 4.dp).size(1.dp, 24.dp).background(Color.White.copy(alpha = 0.2f)))
            ModeTab(Icons.Outlined.Movie, "Director", ui.isDirector, enabled = true) { onAny(); onDirector(!ui.isDirector) }
        }
        // Media row
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(P2PColors.Glass).padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton(if (ui.micOn) Icons.Outlined.Mic else Icons.Outlined.MicOff, if (ui.micOn) "Mute" else "Unmute", active = !ui.micOn) { onAny(); onMic() }
            RoundButton(if (ui.camOn) Icons.Outlined.Videocam else Icons.Outlined.VideocamOff, if (ui.camOn) "Camera off" else "Camera on", active = !ui.camOn) { onAny(); onCam() }
            RoundButton(Icons.Outlined.Cameraswitch, "Switch camera") { onAny(); onFlip() }
            RoundButton(
                if (ui.recording) Icons.Outlined.StopCircle else Icons.Outlined.FiberManualRecord,
                if (ui.recording) "Stop recording" else "Record",
                tint = P2PColors.Rec,
                enabled = ui.phase == Phase.IN_CALL,
            ) { onRecord() }
            RoundButton(Icons.Outlined.Tune, "Settings") { onAny(); onSettings() }
            RoundButton(Icons.Outlined.CallEnd, "Hang up", background = P2PColors.Danger) { onHangUp() }
        }
    }
}

@Composable
private fun ModeTab(icon: ImageVector, label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val bg = if (selected) Color.White else Color.Transparent
    val fg = when {
        selected -> Color.Black
        enabled -> Color.White
        else -> Color.White.copy(alpha = 0.35f)
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, label, Modifier.size(16.dp), tint = fg)
        // Narrow screens: only the selected tab shows its label, so the row fits a phone in portrait.
        if (selected || LocalConfiguration.current.screenWidthDp >= 560) {
            Spacer(Modifier.width(6.dp))
            Text(label, color = fg, fontSize = 13.sp)
        }
    }
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    description: String,
    active: Boolean = false,
    tint: Color = Color.White,
    background: Color? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = background ?: if (active) Color.White else Color.White.copy(alpha = 0.08f)
    val fg = if (active && background == null) Color.Black else tint
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (enabled) fg else fg.copy(alpha = 0.35f))
    }
}
