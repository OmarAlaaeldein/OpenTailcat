package com.tailcat.vpn.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailcat.vpn.TailcatApplication
import com.tailcat.vpn.core.NetworkType
import com.tailcat.vpn.core.model.GatewayProfile
import com.tailcat.vpn.core.model.NetworkMetrics
import com.tailcat.vpn.core.model.TunnelState
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

    fun refreshIp() {
        if (!networkMonitor.isOnline) {
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
            TunnelState.DISCONNECTED -> {
                return tunnelController.startTunnel()
            }
            TunnelState.CONNECTED,
            TunnelState.CONNECTING,
            TunnelState.RECONNECTING,
            TunnelState.DEGRADED -> {
                tunnelController.stopTunnel()
            }
        }
        return true
    }

    /**
     * Starts the VPN only when it is off. Permission callbacks land after the
     * Activity resumes, when a restore may already have started it; a toggle
     * would stop that start.
     */
    fun connect(): Boolean =
        if (tunnelState.value == TunnelState.DISCONNECTED) tunnelController.startTunnel() else true

    fun selectProfile(profile: GatewayProfile) {
        val changed = profile.id != activeProfile.value?.id
        profileRepository.setActiveProfile(profile)
        if (changed) stopSessionForProfileChange()
    }

    /**
     * A running session keeps using the gateway it started with; stop it so
     * the screen never names one gateway while traffic goes to another.
     */
    private fun stopSessionForProfileChange() {
        if (tunnelState.value == TunnelState.DISCONNECTED) return
        tunnelController.stopTunnel()
        viewModelScope.launch {
            _uiEvent.emit("Gateway changed. Tap connect to start the new tunnel.")
        }
    }

    fun addProfileFromToken(
        name: String,
        token: String,
        customDns: String = app.preferencesStore.defaultDns,
        dnsPolicy: com.tailcat.vpn.core.model.DnsPolicy = com.tailcat.vpn.core.model.DnsPolicy.PROFILE_RESOLVER
    ): Result<GatewayProfile> {
        val previous = activeProfile.value?.id
        return profileRepository.addOrUpdateFromToken(name, token, customDns, dnsPolicy).onSuccess { saved ->
            if (saved.id != previous) {
                stopSessionForProfileChange()
            } else if (tunnelState.value != TunnelState.DISCONNECTED) {
                showMessage("Gateway updated. Disconnect and connect again to use the new token.")
            }
        }
    }

    fun updateActiveProfileDns(
        customDns: String,
        dnsPolicy: com.tailcat.vpn.core.model.DnsPolicy
    ): Result<GatewayProfile> {
        val profile = activeProfile.value
            ?: return Result.failure(IllegalArgumentException("No gateway profile is selected"))
        return profileRepository.updateProfileDns(profile.id, customDns, dnsPolicy)
    }

    fun deleteProfile(id: String) {
        if (activeProfile.value?.id == id && tunnelState.value != TunnelState.DISCONNECTED) {
            tunnelController.stopTunnel()
        }
        profileRepository.deleteProfile(id)
        showMessage("Gateway profile deleted")
    }
}
