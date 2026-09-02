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
    val lastError = tunnelController.lastError
    val engineAvailability = tunnelController.engineAvailability

    private val _uiEvent = MutableSharedFlow<String>()
    val uiEvent: SharedFlow<String> = _uiEvent.asSharedFlow()

    init {
        viewModelScope.launch {
            tunnelController.tunnelEvents.collect { message ->
                _uiEvent.emit(message)
            }
        }
    }

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
        tunnelController.refreshPublicIp()
    }

    fun canStartTunnel(): Boolean {
        val error = tunnelController.validateStartRequest()
        if (error != null) {
            viewModelScope.launch { _uiEvent.emit(error) }
            return false
        }
        return true
    }

    fun showMessage(message: String) {
        viewModelScope.launch { _uiEvent.emit(message) }
    }

    fun toggleVpn(): Boolean {
        when (tunnelState.value) {
            TunnelState.DISCONNECTED, TunnelState.DEGRADED -> {
                return tunnelController.startTunnel()
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
            viewModelScope.launch {
                _uiEvent.emit("Gateway changed. Tap connect to start the new tunnel.")
            }
        }
    }

    fun addProfileFromToken(
        name: String,
        token: String,
        customDns: String = "1.1.1.1",
        dnsPolicy: com.tailcat.vpn.core.model.DnsPolicy = com.tailcat.vpn.core.model.DnsPolicy.PROFILE_RESOLVER
    ): Result<GatewayProfile> {
        return profileRepository.addOrUpdateFromToken(name, token, customDns, dnsPolicy)
    }

    fun deleteProfile(id: String) {
        if (activeProfile.value?.id == id && tunnelState.value != TunnelState.DISCONNECTED) {
            tunnelController.stopTunnel()
        }
        profileRepository.deleteProfile(id)
        showMessage("Gateway profile deleted")
    }
}
