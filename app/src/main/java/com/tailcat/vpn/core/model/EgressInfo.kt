package com.tailcat.vpn.core.model

data class EgressInfo(
    val ip: String = "Not checked",
    val country: String? = null,
    val city: String? = null,
    val isChecking: Boolean = false
)
