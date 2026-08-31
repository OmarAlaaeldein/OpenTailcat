package com.tailcat.vpn.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailcat.vpn.TailcatApplication
import com.tailcat.vpn.core.NetworkType
import com.tailcat.vpn.core.model.GatewayProfile
import com.tailcat.vpn.core.model.NetworkMetrics
import com.tailcat.vpn.core.model.TunnelState
import com.tailcat.vpn.core.token.TokenParser
import com.tailcat.vpn.core.token.TokenValidationState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {

    private val app = TailcatApplication.instance
    private val tunnelController = app.tunnelController
    private val profileRepository = app.profileRepository
    private val networkMonitor = app.networkMonitor

    val tunnelState: StateFlow<TunnelState> = tunnelController.tunnelState
    val networkMetrics: StateFlow<NetworkMetrics> = tunnelController.networkMetrics
    val egressInfo = tunnelController.egressInfo
    val activeProfile: StateFlow<GatewayProfile?> = profileRepository.activeProfile
    val profiles: StateFlow<List<GatewayProfile>> = profileRepository.profiles
    val activeNetworkType: StateFlow<NetworkType> = networkMonitor.activeNetworkType

    private val _uiEvent = MutableSharedFlow<String>()
    val uiEvent: SharedFlow<String> = _uiEvent.asSharedFlow()

    fun isOnline(): Boolean = networkMonitor.isOnline

    fun validateTokenInput(token: String): TokenValidationState {
        return TokenParser.validate(token)
    }

    fun refreshIp() {
        if (!isOnline()) {
            viewModelScope.launch {
                _uiEvent.emit("Cannot refresh IP: Device is offline")
            }
            return
        }
        tunnelController.refreshEgressIp()
    }

    fun toggleVpn(): Boolean {
        if (!isOnline() && tunnelState.value == TunnelState.DISCONNECTED) {
            viewModelScope.launch {
                _uiEvent.emit("Cannot start VPN: No internet connection detected. Connect to Wi-Fi or cellular.")
            }
            return false
        }

        when (tunnelState.value) {
            TunnelState.DISCONNECTED, TunnelState.DEGRADED -> {
                tunnelController.startTunnel()
            }
            TunnelState.CONNECTED, TunnelState.CONNECTING, TunnelState.RECONNECTING -> {
                tunnelController.stopTunnel()
            }
        }
        return true
    }

    fun selectProfile(profile: GatewayProfile) {
        profileRepository.setActiveProfile(profile)
        if (tunnelState.value == TunnelState.CONNECTED) {
            tunnelController.stopTunnel()
            tunnelController.startTunnel()
        }
    }

    fun addProfileFromToken(name: String, token: String): Result<GatewayProfile> {
        return profileRepository.addOrUpdateFromToken(name, token)
    }

    fun deleteProfile(id: String) {
        profileRepository.deleteProfile(id)
    }
}
