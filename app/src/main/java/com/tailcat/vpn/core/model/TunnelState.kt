package com.tailcat.vpn.core.model

enum class TunnelState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    DEGRADED
}
