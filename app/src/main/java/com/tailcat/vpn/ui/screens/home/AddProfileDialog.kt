package com.tailcat.vpn.ui.screens.home

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.tailcat.vpn.TailcatApplication
import com.tailcat.vpn.core.dns.DnsValidationResult
import com.tailcat.vpn.core.dns.DnsValidator
import com.tailcat.vpn.core.model.DnsPolicy
import com.tailcat.vpn.core.model.GatewayProfile
import com.tailcat.vpn.core.qr.QrImageImporter
import com.tailcat.vpn.core.token.TokenParser
import com.tailcat.vpn.core.token.TokenValidationState
import com.tailcat.vpn.ui.theme.AccentCyan
import com.tailcat.vpn.ui.theme.TailcatButtons
import com.tailcat.vpn.ui.theme.BorderSubtle
import com.tailcat.vpn.ui.theme.EmeraldConnected
import com.tailcat.vpn.ui.theme.RedDegraded
import com.tailcat.vpn.ui.theme.SurfaceElevated
import com.tailcat.vpn.ui.theme.TextMuted
import com.tailcat.vpn.ui.theme.TextPrimary
import com.tailcat.vpn.ui.theme.TextSecondary
import com.tailcat.vpn.ui.theme.YellowWarning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Token pairing dialog with live token and DNS validation. */
@Composable
fun AddProfileDialog(
    isDeviceOffline: Boolean,
    onSave: (name: String, token: String, dns: String, policy: DnsPolicy) -> Result<GatewayProfile>,
    onDismiss: () -> Unit
) {
    // Keep the pairing token strictly in memory; never serialize secrets into savedInstanceState.
    var tokenInput by remember { mutableStateOf("") }
    var nameInput by rememberSaveable { mutableStateOf("") }
    var dnsInput by rememberSaveable {
        mutableStateOf(TailcatApplication.instance.preferencesStore.defaultDns)
    }
    var dnsPolicy by rememberSaveable {
        mutableStateOf(DnsPolicy.PROFILE_RESOLVER)
    }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showQr by remember { mutableStateOf(false) }
    var importingQr by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                importingQr = true
                try {
                    tokenInput = QrImageImporter.read(context, uri)
                    errorMessage = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: IllegalArgumentException) {
                    errorMessage = error.message ?: "Could not import the QR image."
                } catch (_: Exception) {
                    errorMessage = "The QR image could not be read. Use another image or paste the token."
                } finally {
                    importingQr = false
                }
            }
        }
    }

    val validationState = remember(tokenInput) {
        TokenParser.validate(tokenInput)
    }
    val dnsValidation = remember(dnsInput) {
        DnsValidator.validate(dnsInput)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // The dialog is its own window: the Activity's FLAG_SECURE does not cover it.
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        containerColor = SurfaceElevated,
        title = {
            Text("Pair gateway token", color = TextPrimary)
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "Paste a Tailcat connection token (tc...) to add a gateway profile.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(14.dp))

                // Offline Warning Badge inside Dialog
                if (isDeviceOffline) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(YellowWarning.copy(alpha = 0.15f))
                            .border(1.dp, YellowWarning.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = "Offline Notice",
                                tint = YellowWarning,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "The device is offline. The token will be saved locally; an internet connection is required to connect.",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = YellowWarning,
                                    fontSize = 11.sp
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                OutlinedTextField(
                    value = tokenInput,
                    enabled = !importingQr,
                    onValueChange = {
                        tokenInput = it
                        errorMessage = null
                    },
                    label = { Text("Connection token (tc...)") },
                    // Password type tells keyboards not to learn or suggest the credential.
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false
                    ),
                    supportingText = {
                        Text(
                            "Generated by an exit gateway (for example, tailcat serve exit-node)",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !importingQr, onClick = {
                        try {
                            imagePicker.launch("image/*")
                        } catch (_: ActivityNotFoundException) {
                            errorMessage = "No image picker is available. Paste the token."
                        }
                    }) { Text(if (importingQr) "Reading QR…" else "Import QR image") }
                    TextButton(
                        onClick = { showQr = true },
                        enabled = !importingQr && validationState is TokenValidationState.Valid
                    ) { Text("Show QR") }
                }
                Text(
                    "Select a QR image. It is decoded on this device; camera access is not required.",
                    color = TextMuted,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { if (it.length <= 64) nameInput = it },
                    label = { Text("Gateway name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = dnsInput,
                    onValueChange = {
                        dnsInput = it
                        errorMessage = null
                    },
                    label = { Text("DNS resolver IP") },
                    supportingText = {
                        if (dnsValidation is DnsValidationResult.Invalid) {
                            Text(dnsValidation.reason, color = RedDegraded, fontSize = 11.sp)
                        } else {
                            Text(
                                if (dnsPolicy == DnsPolicy.FORCED_RESOLVER) {
                                    "Forced resolver: all tunnel DNS queries use this address"
                                } else {
                                    "Profile resolver: DNS queries follow the tunnel destination"
                                },
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }
                    },
                    isError = dnsValidation is DnsValidationResult.Invalid,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        DnsPolicy.PROFILE_RESOLVER to "Profile resolver",
                        DnsPolicy.FORCED_RESOLVER to "Forced resolver"
                    ).forEach { (policy, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = dnsPolicy == policy,
                                    role = Role.RadioButton,
                                    onClick = { dnsPolicy = policy }
                                )
                        ) {
                            RadioButton(
                                selected = dnsPolicy == policy,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = AccentCyan,
                                    unselectedColor = BorderSubtle
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                TokenValidationPreview(validationState)

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(errorMessage!!, color = RedDegraded, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val result = onSave(nameInput, tokenInput, dnsInput, dnsPolicy)
                    if (result.isSuccess) {
                        onDismiss()
                    } else {
                        errorMessage = result.exceptionOrNull()?.message ?: "Invalid profile"
                    }
                },
                colors = TailcatButtons.filled(),
                enabled = !importingQr && validationState is TokenValidationState.Valid && dnsValidation is DnsValidationResult.Valid
            ) {
                Text("Save and pair")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )

    if (showQr) {
        TokenQrDialog(tokenInput, onDismiss = { showQr = false })
    }

}

@Composable
private fun TokenValidationPreview(validationState: TokenValidationState) {
    when (validationState) {
        is TokenValidationState.Valid -> {
            val parsed = validationState.parsed
            val expText = if (parsed.expirationFormatted != null) " • Exp: ${parsed.expirationFormatted}" else ""
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(EmeraldConnected.copy(alpha = 0.12f))
                    .border(1.dp, EmeraldConnected.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Valid",
                        tint = EmeraldConnected,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${parsed.regionDisplayName} • Key: ${parsed.serverKeyShort}$expText",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = EmeraldConnected,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }
            }
        }
        is TokenValidationState.Expired -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(RedDegraded.copy(alpha = 0.15f))
                    .border(1.dp, RedDegraded.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = "Expired",
                        tint = RedDegraded,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "This token expired on ${validationState.expiredDate}. Request a new token.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = RedDegraded,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            }
        }
        is TokenValidationState.LegacyReissueRequired -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(YellowWarning.copy(alpha = 0.12f))
                    .border(1.dp, YellowWarning.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = "Reissue Required",
                        tint = YellowWarning,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "This token uses a legacy format without a disco key. The gateway must issue a new token.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = YellowWarning,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            }
        }
        is TokenValidationState.Invalid -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(RedDegraded.copy(alpha = 0.12f))
                    .border(1.dp, RedDegraded.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = "Invalid",
                        tint = RedDegraded,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = validationState.reason,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = RedDegraded,
                            fontSize = 11.sp
                        )
                    )
                }
            }
        }
        TokenValidationState.Empty -> {
            // Do nothing
        }
    }
}

/**
 * The QR contains the credential itself; never persist it or permit screenshots.
 * Serves both a token being paired and an already-saved profile.
 */
@Composable
internal fun TokenQrDialog(
    token: String,
    profileName: String? = null,
    onDismiss: () -> Unit
) {
    val matrix = remember(token) { runCatching { encodeTokenQr(token) }.getOrNull() }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(if (profileName != null) "Gateway token: $profileName" else "Gateway token") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Scanning this code reveals the gateway token. Share it only with trusted recipients.")
                Spacer(Modifier.height(16.dp))
                if (matrix == null) {
                    Text("This token is too large to display as a QR code.")
                } else {
                    QrMatrixCanvas(matrix, Modifier.fillMaxWidth().height(280.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** Draws a QR matrix black-on-white. Shared by the pairing dialog and the sharing screen. */
@Composable
internal fun QrMatrixCanvas(matrix: BitMatrix, modifier: Modifier = Modifier) {
    Canvas(modifier.background(Color.White)) {
        val scale = minOf(size.width / matrix.width, size.height / matrix.height)
        val left = (size.width - matrix.width * scale) / 2
        val top = (size.height - matrix.height * scale) / 2
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(Color.Black, Offset(left + x * scale, top + y * scale), Size(scale, scale))
        }
    }
}

internal fun encodeTokenQr(token: String) =
    QRCodeWriter().encode(token, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 4))
