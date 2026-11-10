package com.kp.p2pvid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kp.p2pvid.data.Quality
import com.kp.p2pvid.data.Settings
import com.kp.p2pvid.ui.theme.P2PColors
import com.kp.p2pvid.view.ViewMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(settings: Settings, onChange: (Settings) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(20.dp))

            Section("Stream quality", "What you send. Changes apply immediately, even mid-call.")
            Quality.entries.forEach { q ->
                Row(
                    Modifier.fillMaxWidth().clickable { onChange(settings.copy(quality = q)) }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.quality == q, onClick = { onChange(settings.copy(quality = q)) })
                    Column(Modifier.weight(1f)) {
                        Text(q.label)
                        Text(
                            "${q.width}×${q.height} · ${q.fps} fps · up to ${"%.1f".format(q.maxBitrateBps / 1e6)} Mbps",
                            style = MaterialTheme.typography.bodySmall,
                            color = P2PColors.Muted,
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Section("Starting view", null)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ViewMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = settings.defaultMode == m,
                        onClick = { onChange(settings.copy(defaultMode = m)) },
                        shape = SegmentedButtonDefaults.itemShape(i, ViewMode.entries.size),
                    ) { Text(m.name.lowercase().replaceFirstChar(Char::uppercase)) }
                }
            }

            Spacer(Modifier.height(20.dp))
            Section("Starting camera", null)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(settings.frontCamera, { onChange(settings.copy(frontCamera = true)) }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Front") }
                SegmentedButton(!settings.frontCamera, { onChange(settings.copy(frontCamera = false)) }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Back") }
            }

            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Keep screen on during calls")
                }
                Switch(settings.keepScreenOn, { onChange(settings.copy(keepScreenOn = it)) })
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Show overlay")
                    Text(
                        "Status pills, name tags and the picture-in-picture frame. " +
                            "When off, only the video shows (and is recorded); tap the screen to reveal everything.",
                        style = MaterialTheme.typography.bodySmall,
                        color = P2PColors.Muted,
                    )
                }
                Switch(settings.showOverlay, { onChange(settings.copy(showOverlay = it)) })
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "LAN only: calls connect straight to the other device's local address and never use the internet. " +
                    "Both devices must be on the same Wi-Fi, or one connected to the other's hotspot.",
                style = MaterialTheme.typography.bodySmall,
                color = P2PColors.Muted,
            )
        }
    }
}

@Composable
private fun Section(title: String, subtitle: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = P2PColors.Muted) }
    }
}
