package com.tailcat.vpn.ui.screens.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailcat.vpn.TailcatApplication
import com.tailcat.vpn.core.model.TunnelState
import com.tailcat.vpn.service.ExitNodeAdmission
import com.tailcat.vpn.service.ExitNodeConfig
import com.tailcat.vpn.service.ExitNodeService
import com.tailcat.vpn.service.ExitNodeUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** How long a share lasts. The cap is the native MaxTTL; the default is deliberately short. */
enum class ShareDuration(val label: String, val seconds: Long) {
    QUARTER_HOUR("15 min", 15 * 60L),
    HOUR("1 hour", 60 * 60L),
    FOUR_HOURS("4 hours", 4 * 60 * 60L),
    DAY("24 hours", ExitNodeConfig.MAX_TTL_SECONDS)
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

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    fun selectDuration(value: ShareDuration) { _duration.value = value }
    fun selectAdmission(value: ExitNodeAdmission) { _admission.value = value }

    /**
     * Hands the session to the foreground service, which owns the poll loop,
     * the notification and the wake lock. Refusals (the VPN is up) are
     * recorded by the service as an ended session, which this screen shows.
     */
    fun start() {
        ExitNodeService.start(app, ExitNodeConfig(_duration.value.seconds, _admission.value))
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
}
