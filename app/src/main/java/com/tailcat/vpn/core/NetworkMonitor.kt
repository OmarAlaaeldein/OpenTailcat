package com.tailcat.vpn.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NetworkType {
    WIFI,
    CELLULAR,
    ETHERNET,
    NONE
}

class NetworkMonitor(context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _activeNetworkType = MutableStateFlow(getCurrentNetworkType())
    val activeNetworkType: StateFlow<NetworkType> = _activeNetworkType.asStateFlow()

    val isOnline: Boolean
        get() = _activeNetworkType.value != NetworkType.NONE

    private val _isEnterpriseOrIsolatedWifi = MutableStateFlow(false)
    val isEnterpriseOrIsolatedWifi: StateFlow<Boolean> = _isEnterpriseOrIsolatedWifi.asStateFlow()

    private var onNetworkChangedListener: ((NetworkType) -> Unit)? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateNetworkStatus(network)
        }

        override fun onLost(network: Network) {
            val current = getCurrentNetworkType()
            _activeNetworkType.value = current
            onNetworkChangedListener?.invoke(current)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            updateNetworkStatus(network)
        }
    }

    init {
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            // Fallback if permission restricted
        }
    }

    fun setOnNetworkChangedListener(listener: (NetworkType) -> Unit) {
        onNetworkChangedListener = listener
    }

    fun checkConnectivityNow(): NetworkType {
        val current = getCurrentNetworkType()
        _activeNetworkType.value = current
        return current
    }

    private fun getCurrentNetworkType(): NetworkType {
        return try {
            val activeNetwork = connectivityManager.activeNetwork ?: return NetworkType.NONE
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return NetworkType.NONE
            when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
                else -> NetworkType.NONE
            }
        } catch (e: Exception) {
            NetworkType.NONE
        }
    }

    private fun updateNetworkStatus(network: Network) {
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return
        val type = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            else -> NetworkType.NONE
        }

        _activeNetworkType.value = type
        onNetworkChangedListener?.invoke(type)
    }
}
