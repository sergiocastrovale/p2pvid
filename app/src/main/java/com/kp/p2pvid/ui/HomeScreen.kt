package com.kp.p2pvid.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VideoCall
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.activity.compose.rememberLauncherForActivityResult
import com.kp.p2pvid.call.CallUi
import com.kp.p2pvid.call.Phase
import com.kp.p2pvid.net.InviteCode
import com.kp.p2pvid.ui.theme.P2PColors

@Composable
fun HomeScreen(
    ui: CallUi,
    lanAddress: String?,
    onHost: () -> Unit,
    onJoin: (String) -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .imePadding()
            .padding(horizontal = 24.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "P2Pvid",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, "Settings") }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AnimatedContent(ui.phase, label = "home") { phase ->
                when (phase) {
                    Phase.HOSTING -> InvitePanel(ui.invite.orEmpty(), onCancel)
                    Phase.JOINING -> Waiting("Connecting…", onCancel)
                    else -> StartPanel(onHost, onJoin)
                }
            }
        }

        NetworkFooter(lanAddress)
    }
}

@Composable
private fun StartPanel(onHost: () -> Unit, onJoin: (String) -> Unit) {
    var code by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    val raw = InviteCode.normalize(code.text)
    val scan = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { onJoin(it) }
    }

    Column(
        Modifier.widthIn(max = 420.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Button(
            onClick = onHost,
            modifier = Modifier.fillMaxWidth().height(64.dp),
            shape = RoundedCornerShape(20.dp),
        ) {
            Icon(Icons.Outlined.VideoCall, null)
            Spacer(Modifier.width(12.dp))
            Text("New call", fontSize = 18.sp)
        }

        Row(Modifier.padding(vertical = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f))
            Text("or join", Modifier.padding(horizontal = 12.dp), color = P2PColors.Muted, style = MaterialTheme.typography.labelLarge)
            HorizontalDivider(Modifier.weight(1f))
        }

        OutlinedTextField(
            value = code,
            onValueChange = {
                val formatted = InviteCode.format(InviteCode.normalize(it.text).take(InviteCode.LENGTH))
                code = TextFieldValue(formatted, TextRange(formatted.length))
            },
            placeholder = { Text("XXXX-XXXX-XXXX-XXXX", fontFamily = FontFamily.Monospace) },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 1.sp),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { if (raw.length == InviteCode.LENGTH) onJoin(raw) }),
            trailingIcon = {
                IconButton(onClick = {
                    scan.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Scan the P2Pvid invite")
                            .setBeepEnabled(false)
                            .setOrientationLocked(false),
                    )
                }) { Icon(Icons.Outlined.QrCodeScanner, "Scan QR code") }
            },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(
            onClick = { onJoin(raw) },
            enabled = raw.length == InviteCode.LENGTH,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
        ) { Text("Join") }
    }
}

@Composable
private fun InvitePanel(code: String, onCancel: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val qr = remember(code) { qrBitmap(InviteCode.QR_PREFIX + code, 720) }

    Column(
        Modifier.widthIn(max = 420.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Invite code", color = P2PColors.Muted, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.size(232.dp).clip(RoundedCornerShape(24.dp)).background(Color.White).padding(12.dp),
        ) {
            Image(qr.asImageBitmap(), "Invite QR code", Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(20.dp))
        Text(
            code,
            fontFamily = FontFamily.Monospace,
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Copy")
            }
            FilledTonalButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "Join my P2Pvid call with code $code (same Wi-Fi or hotspot).")
                }
                context.startActivity(Intent.createChooser(send, "Share invite"))
            }) {
                Icon(Icons.Outlined.Share, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Share")
            }
        }
        Spacer(Modifier.height(32.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text("Waiting for the other device…", color = P2PColors.Muted)
        }
        TextButton(onClick = onCancel, Modifier.padding(top = 8.dp)) { Text("Cancel") }
    }
}

@Composable
private fun Waiting(label: String, onCancel: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(20.dp))
        Text(label)
        TextButton(onClick = onCancel, Modifier.padding(top = 12.dp)) { Text("Cancel") }
    }
}

@Composable
private fun NetworkFooter(ip: String?) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ip == null) {
            Icon(Icons.Outlined.WifiOff, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(8.dp))
            Text("No local network. Join Wi-Fi or turn on a hotspot.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        } else {
            Text("LAN only · $ip", style = MaterialTheme.typography.bodySmall, color = P2PColors.Muted)
        }
    }
}

private fun qrBitmap(text: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 0))
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
