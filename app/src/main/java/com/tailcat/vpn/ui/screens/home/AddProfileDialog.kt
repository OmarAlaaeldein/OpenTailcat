package com.tailcat.vpn.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.tailcat.vpn.TailcatApplication
import com.tailcat.vpn.core.dns.DnsValidationResult
import com.tailcat.vpn.core.dns.DnsValidator
import com.tailcat.vpn.core.model.DnsPolicy
import com.tailcat.vpn.core.model.GatewayProfile
import com.tailcat.vpn.core.token.TokenParser
import com.tailcat.vpn.core.token.TokenValidationState
import com.tailcat.vpn.ui.theme.AccentCyan
import com.tailcat.vpn.ui.theme.BgDark
import com.tailcat.vpn.ui.theme.BorderSubtle
import com.tailcat.vpn.ui.theme.EmeraldConnected
import com.tailcat.vpn.ui.theme.RedDegraded
import com.tailcat.vpn.ui.theme.SurfaceElevated
import com.tailcat.vpn.ui.theme.TextMuted
import com.tailcat.vpn.ui.theme.TextPrimary
import com.tailcat.vpn.ui.theme.TextSecondary
import com.tailcat.vpn.ui.theme.YellowWarning

/** Token pairing dialog with live token and DNS validation. */
@Composable
fun AddProfileDialog(
    isDeviceOffline: Boolean,
    onSave: (name: String, token: String, dns: String, policy: DnsPolicy) -> Result<GatewayProfile>,
    onDismiss: () -> Unit
) {
    var tokenInput by remember { mutableStateOf("") }
    var nameInput by remember { mutableStateOf("") }
    var dnsInput by remember {
        mutableStateOf(TailcatApplication.instance.preferencesStore.defaultDns)
    }
    var dnsPolicy by remember {
        mutableStateOf(DnsPolicy.PROFILE_RESOLVER)
    }
    var errorMessage by remember { mutableStateOf<String?>(null) }

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
            Text("Pair Gateway Token", color = TextPrimary)
        },
        text = {
            Column {
                Text(
                    text = "Paste a Tailcat connection token (tc...) to establish a Tailcat gateway session.",
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
                                text = "Device is offline. Token will save locally, but connection requires internet.",
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
                    onValueChange = {
                        tokenInput = it
                        errorMessage = null
                    },
                    label = { Text("Connection Token (tc...)") },
                    // Password type tells keyboards not to learn or suggest the credential.
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false
                    ),
                    supportingText = {
                        Text(
                            "Generated by an exit gateway (e.g. tailcat serve exit-node)",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Gateway Name (Optional)") },
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
                    label = { Text("DNS Resolver IP") },
                    supportingText = {
                        if (dnsValidation is DnsValidationResult.Invalid) {
                            Text(dnsValidation.reason, color = RedDegraded, fontSize = 11.sp)
                        } else {
                            Text(
                                if (dnsPolicy == DnsPolicy.FORCED_RESOLVER) {
                                    "Forced resolver: all tunnel DNS goes here"
                                } else {
                                    "Profile resolver: DNS follows the TUN destination"
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    listOf(
                        DnsPolicy.PROFILE_RESOLVER to "Profile resolver",
                        DnsPolicy.FORCED_RESOLVER to "Forced resolver"
                    ).forEach { (policy, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { dnsPolicy = policy }
                        ) {
                            RadioButton(
                                selected = dnsPolicy == policy,
                                onClick = { dnsPolicy = policy },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = AccentCyan,
                                    unselectedColor = BorderSubtle
                                )
                            )
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
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                enabled = validationState is TokenValidationState.Valid && dnsValidation is DnsValidationResult.Valid
            ) {
                Text("Save & Pair", color = BgDark)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
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
                        text = "Token expired on ${validationState.expiredDate}. Request a new token.",
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
                        text = "Legacy token format without disco key. Gateway reissue required.",
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
