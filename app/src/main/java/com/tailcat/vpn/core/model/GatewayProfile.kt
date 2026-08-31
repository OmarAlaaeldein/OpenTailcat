package com.tailcat.vpn.core.model

import java.util.UUID

data class GatewayProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val token: String,
    val serverPublicKey: String,
    val derpRegionId: Int? = null,
    val customDns: String = "100.100.21.8",
    val mtu: Int = 1280,
    val tcpMss: Int = 1120,
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
