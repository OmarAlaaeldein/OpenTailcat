package com.tailcat.vpn.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tailcat.vpn.core.dns.DnsValidationResult
import com.tailcat.vpn.core.dns.DnsValidator
import com.tailcat.vpn.data.PreferencesStorage
import com.tailcat.vpn.ui.theme.RedDegraded
import com.tailcat.vpn.ui.theme.TextSecondary

/** Default MTU and DNS resolver for newly paired profiles. */
@Composable
internal fun ProfileDefaultsCard(
    store: PreferencesStorage,
    mtuText: String,
    onMtuTextChange: (String) -> Unit,
    dnsText: String,
    onDnsTextChange: (String) -> Unit
) {
    SettingsCard(icon = Icons.Default.Dns, title = "Defaults for new profiles") {
        Text(
            "MTU 1280 is the safe default for mobile and nested tunnels.",
            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = mtuText,
            onValueChange = { input ->
                if (input.length <= 4 && input.all(Char::isDigit)) {
                    onMtuTextChange(input)
                    input.toIntOrNull()?.takeIf { it in 1280..1500 }?.let {
                        store.defaultMtu = it
                    }
                }
            },
            label = { Text("TUN MTU (1280–1500)") },
            supportingText = {
                if (mtuText.toIntOrNull() !in 1280..1500) {
                    Text("Enter a value from 1280 to 1500")
                }
            },
            isError = mtuText.toIntOrNull() !in 1280..1500,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))
        val dnsValidation = remember(dnsText) { DnsValidator.validate(dnsText) }
        OutlinedTextField(
            value = dnsText,
            onValueChange = { input ->
                onDnsTextChange(input)
                if (DnsValidator.isValid(input)) {
                    store.defaultDns = input.trim()
                }
            },
            label = { Text("Default DNS Resolver IP") },
            supportingText = {
                if (dnsValidation is DnsValidationResult.Invalid) {
                    Text(dnsValidation.reason, color = RedDegraded, fontSize = 11.sp)
                } else {
                    Text("Default DNS for new profiles (e.g. 1.1.1.1, 9.9.9.9)", color = TextSecondary, fontSize = 11.sp)
                }
            },
            isError = dnsValidation is DnsValidationResult.Invalid,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
