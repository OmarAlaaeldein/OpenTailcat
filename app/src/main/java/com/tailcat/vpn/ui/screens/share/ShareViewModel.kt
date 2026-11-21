package com.tailcat.vpn.ui.screens.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailcat.vpn.TailcatApplication
import com.tailcat.vpn.core.model.TunnelState
import com.tailcat.vpn.service.ExitNodeAdmission
import com.tailcat.vpn.service.ExitNodeConfig
import com.tailcat.vpn.service.ExitNodeRelay
import com.tailcat.vpn.service.ExitNodeService
import com.tailcat.vpn.service.ExitNodeUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * How long a share lasts. Timed shares are capped at the native MaxTTL; the
 * default is deliberately short. [NO_LIMIT] (seconds null) runs until the host
 * stops it and is allowed only with approval admission.
 */
enum class ShareDuration(val label: String, val seconds: Long?) {
    QUARTER_HOUR("15 min", 15 * 60L),
    HOUR("1 hour", 60 * 60L),
    FOUR_HOURS("4 hours", 4 * 60 * 60L),
    DAY("24 hours", ExitNodeConfig.MAX_TTL_SECONDS),
    NO_LIMIT("Until I stop", null);

    val isTimed: Boolean get() = seconds != null
}

/**
 * What the setup screen has typed for a self-hosted relay. Disabled means the
 * nearest public relay, which is the default and needs no input.
 */
data class ShareRelay(
    val enabled: Boolean = false,
    val hostname: String = "",
    val derpPort: String = ExitNodeRelay.DEFAULT_DERP_PORT.toString(),
    val stunPort: String = ExitNodeRelay.DEFAULT_STUN_PORT.toString()
) {
    /** A user-facing problem with the current fields, or null. */
    val problem: String?
        get() = if (enabled) ExitNodeRelay.problem(hostname, derpPort, stunPort) else null

    /** Only valid when [problem] is null. */
    fun toConfig(): ExitNodeRelay? = if (enabled) {
        ExitNodeRelay(hostname, derpPort.toInt(), stunPort.toInt())
    } else {
        null
    }
}

class ShareViewModel : ViewModel() {
    private val app = TailcatApplication.instance
    private val controller = app.exitNodeController

    val state: StateFlow<ExitNodeUiState> = controller.state
    val availability get() = controller.availability
    val tunnelState: StateFlow<TunnelState> = app.tunnelController.tunnelState

    private val _duration = MutableStateFlow(ShareDuration.HOUR)
    val duration: StateFlow<ShareDuration> = _duration.asStateFlow()

    private val _admission = MutableStateFlow(ExitNodeAdmission.APPROVAL)
    val admission: StateFlow<ExitNodeAdmission> = _admission.asStateFlow()

    private val _relay = MutableStateFlow(ShareRelay())
    val relay: StateFlow<ShareRelay> = _relay.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    /** Choosing no time limit also switches to approval: an open share must expire. */
    fun selectDuration(value: ShareDuration) {
        _duration.value = value
        if (!value.isTimed) _admission.value = ExitNodeAdmission.APPROVAL
    }

    fun selectAdmission(value: ExitNodeAdmission) {
        if (value == ExitNodeAdmission.OPEN && !_duration.value.isTimed) return
        _admission.value = value
    }

    fun selectRelayEnabled(value: Boolean) = _relay.update { it.copy(enabled = value) }

    fun setRelayHostname(value: String) {
        if (value.length <= MAX_RELAY_HOSTNAME) _relay.update { it.copy(hostname = value) }
    }

    fun setRelayDerpPort(value: String) {
        if (value.length <= 5 && value.all(Char::isDigit)) _relay.update { it.copy(derpPort = value) }
    }

    fun setRelayStunPort(value: String) {
        if (value.length <= 5 && value.all(Char::isDigit)) _relay.update { it.copy(stunPort = value) }
    }

    /**
     * Hands the session to the foreground service, which owns the poll loop,
     * the notification and the wake lock. Refusals (the VPN is up) are
     * recorded by the service as an ended session, which this screen shows.
     */
    fun start() {
        val relayInput = _relay.value
        relayInput.problem?.let {
            _events.tryEmit(it)
            return
        }
        ExitNodeService.start(
            app,
            ExitNodeConfig(_duration.value.seconds, _admission.value, relayInput.toConfig())
        )
    }

    fun stop() {
        ExitNodeService.stop(app)
    }

    fun approve(key: String) = deviceAction { controller.approve(key) }
    fun deny(key: String) = deviceAction { controller.deny(key) }
    fun revoke(key: String) = deviceAction { controller.revoke(key) }

    fun acknowledgeEnded() = controller.acknowledgeEnded()

    private fun deviceAction(block: () -> String?) {
        viewModelScope.launch(Dispatchers.IO) { block()?.let { _events.emit(it) } }
    }

    private companion object {
        /** The DNS limit; longer names cannot resolve, so they never reach the engine. */
        const val MAX_RELAY_HOSTNAME = 253
    }
}
