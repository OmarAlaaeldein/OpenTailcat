package com.tailcat.vpn.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailcat.vpn.TailcatApplication
import com.tailcat.vpn.ui.theme.AccentCyan
import com.tailcat.vpn.ui.theme.BgDark
import com.tailcat.vpn.ui.theme.SurfaceDark
import com.tailcat.vpn.ui.theme.TextPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onNavigateBack: () -> Unit = {}) {
    val context = LocalContext.current
    val app = remember { TailcatApplication.instance }
    val store = app.preferencesStore
    val engineAvailability = app.tunnelEngine.availability
    val updatesViewModel: SettingsViewModel = viewModel()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(updatesViewModel) {
        updatesViewModel.uiEvent.collect { message ->
            scope.launch { snackbarHostState.showSnackbar(message) }
        }
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    var mtuText by remember { mutableStateOf(store.defaultMtu.toString()) }
    var dnsText by remember { mutableStateOf(store.defaultDns) }
    var excludedApps by remember { mutableStateOf(store.splitTunnelExcludedApps) }
    var appQuery by remember { mutableStateOf("") }
    var debugMode by remember { mutableStateOf(store.debugMode) }

    // Every package installed for this user, not only apps with a launcher
    // icon; headless/background apps must be selectable for exclusions.
    val installedApps by produceState<List<AppInfoItem>?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) {
            loadInstalledApps(context)
        }
    }

    Scaffold(
        containerColor = BgDark,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgDark)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = SurfaceDark,
                contentColor = AccentCyan
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Connection") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Apps (${excludedApps.size})") }
                )
            }

            if (selectedTab == 0) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    EngineStatusCard(engineAvailability)

                    AlwaysOnCard()

                    DiagnosticsCard(
                        debugMode = debugMode,
                        onDebugModeChange = { checked ->
                            debugMode = checked
                            store.debugMode = checked
                        }
                    )

                    ProfileDefaultsCard(
                        store = store,
                        mtuText = mtuText,
                        onMtuTextChange = { mtuText = it },
                        dnsText = dnsText,
                        onDnsTextChange = { dnsText = it }
                    )

                    ActiveProfileDnsCard(app)

                    SettingsUpdatesCard(viewModel = updatesViewModel)

                    AboutCard()
                }
            } else {
                AppExclusionsTab(
                    app = app,
                    installedApps = installedApps,
                    appQuery = appQuery,
                    onAppQueryChange = { appQuery = it },
                    excludedApps = excludedApps,
                    onExcludedAppsChange = { updated ->
                        excludedApps = updated
                        store.splitTunnelExcludedApps = updated
                    }
                )
            }
        }
    }
}
