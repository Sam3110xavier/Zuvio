package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.network.DnsProvider
import com.nuvio.app.core.network.DnsSettingsRepository
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioTokens
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.settings_advanced_dns
import nuvio.composeapp.generated.resources.settings_advanced_dns_doh_format
import nuvio.composeapp.generated.resources.settings_advanced_dns_note
import nuvio.composeapp.generated.resources.settings_advanced_dns_system
import nuvio.composeapp.generated.resources.settings_advanced_section_network
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.dnsSettingsContent(isTablet: Boolean) {
    if (DnsSettingsRepository.isSupported) {
        item {
            val dnsProvider by remember {
                DnsSettingsRepository.ensureLoaded()
                DnsSettingsRepository.provider
            }.collectAsStateWithLifecycle()
            var showDnsDialog by rememberSaveable { mutableStateOf(false) }
            SettingsSection(
                title = stringResource(Res.string.settings_advanced_section_network),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_advanced_dns),
                        description = dnsProviderLabel(dnsProvider),
                        isTablet = isTablet,
                        onClick = { showDnsDialog = true },
                    )
                }
            }
            if (showDnsDialog) {
                DnsProviderDialog(
                    selected = dnsProvider,
                    onSelected = { provider ->
                        DnsSettingsRepository.setProvider(provider)
                        showDnsDialog = false
                    },
                    onDismiss = { showDnsDialog = false },
                )
            }
        }
    }
}

@Composable
private fun dnsProviderLabel(provider: DnsProvider): String =
    if (provider == DnsProvider.SYSTEM) {
        stringResource(Res.string.settings_advanced_dns_system)
    } else {
        stringResource(Res.string.settings_advanced_dns_doh_format, provider.displayName)
    }

@Composable
private fun DnsProviderDialog(
    selected: DnsProvider,
    onSelected: (DnsProvider) -> Unit,
    onDismiss: () -> Unit,
) {
    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_advanced_dns),
        message = stringResource(Res.string.settings_advanced_dns_note),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
            DnsProvider.entries.forEach { provider ->
                DialogOption(
                    text = if (provider == DnsProvider.SYSTEM) {
                        stringResource(Res.string.settings_advanced_dns_system)
                    } else {
                        provider.displayName
                    },
                    selected = provider == selected,
                    onClick = { onSelected(provider) },
                )
            }
        }
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_cancel),
                onClick = onDismiss,
            )
        }
    }
}
