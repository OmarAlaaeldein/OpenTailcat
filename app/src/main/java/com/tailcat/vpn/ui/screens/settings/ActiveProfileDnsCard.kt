package com.tailcat.vpn.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tailcat.vpn.TailcatApplication
import com.tailcat.vpn.core.dns.DnsValidationResult
import com.tailcat.vpn.core.dns.DnsValidator
import com.tailcat.vpn.core.model.DnsPolicy
import com.tailcat.vpn.core.model.TunnelState
import com.tailcat.vpn.ui.theme.AccentCyan
import com.tailcat.vpn.ui.theme.BorderSubtle
import com.tailcat.vpn.ui.theme.TextMuted
import com.tailcat.vpn.ui.theme.TextSecondary

/** Resolver and DNS policy of the active profile. */
@Composable
internal fun ActiveProfileDnsCard(app: TailcatApplication) {
    val store = app.preferencesStore
    SettingsCard(icon = Icons.Default.Dns, title = "Active profile DNS") {
        val activeProfile by app.profileRepository.activeProfile.collectAsState()
        var activeDnsText by remember(activeProfile?.id) {
            mutableStateOf(activeProfile?.customDns ?: store.defaultDns)
        }
        var activePolicy by remember(activeProfile?.id) {
            mutableStateOf(
                activeProfile?.dnsPolicy
                    ?: DnsPolicy.PROFILE_RESOLVER
            )
        }
        var activeDnsMessage by remember { mutableStateOf<String?>(null) }

        if (activeProfile == null) {
            Text(
                "Pair a gateway profile first.",
                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
            )
        } else {
            Text(
                activeProfile!!.name,
                style = MaterialTheme.typography.bodySmall.copy(color = TextMuted)
            )
            Spacer(Modifier.height(8.dp))
            val activeDnsValidation = remember(activeDnsText) {
                DnsValidator.validate(activeDnsText)
            }
            OutlinedTextField(
                value = activeDnsText,
                onValueChange = { activeDnsText = it; activeDnsMessage = null },
                label = { Text("Resolver IP") },
                isError = activeDnsValidation is DnsValidationResult.Invalid,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            listOf(
                DnsPolicy.PROFILE_RESOLVER to "Profile resolver",
                DnsPolicy.FORCED_RESOLVER to "Forced resolver"
            ).forEach { (policy, label) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { activePolicy = policy }
                ) {
                    RadioButton(
                        selected = activePolicy == policy,
                        onClick = { activePolicy = policy },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = AccentCyan,
                            unselectedColor = BorderSubtle
                        )
                    )
                    Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                enabled = activeDnsValidation is DnsValidationResult.Valid,
                onClick = {
                    val result = app.profileRepository.updateProfileDns(
                        profileId = activeProfile!!.id,
                        customDns = activeDnsText,
                        dnsPolicy = activePolicy
                    )
                    activeDnsMessage = result.fold(
                        onSuccess = {
                            val reconnect = app.tunnelController.tunnelState.value !=
                                TunnelState.DISCONNECTED
                            if (reconnect) {
                                "DNS saved. Reconnect for the new resolver to apply."
                            } else {
                                "DNS saved for ${activeProfile!!.name}."
                            }
                        },
                        onFailure = { it.message ?: "Could not save DNS settings" }
                    )
                }
            ) {
                Text("Save profile DNS")
            }
            activeDnsMessage?.let { msg ->
                Spacer(Modifier.height(6.dp))
                Text(msg, style = MaterialTheme.typography.bodySmall.copy(color = AccentCyan))
            }
        }
    }
}
