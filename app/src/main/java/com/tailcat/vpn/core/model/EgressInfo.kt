package com.tailcat.vpn.core.model

data class EgressInfo(
    val ip: String = "Not checked",
    val country: String? = null,
    val city: String? = null,
    val isp: String? = null,
    val isChecking: Boolean = false,
    val lastUpdated: Long = 0
)
