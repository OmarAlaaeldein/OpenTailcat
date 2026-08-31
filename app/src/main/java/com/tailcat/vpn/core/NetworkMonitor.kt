package com.tailcat.vpn.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

enum class NetworkType {
    WIFI,
    CELLULAR,
    ETHERNET,
    NONE
}

class NetworkMonitor(context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val capabilitiesByNetwork = ConcurrentHashMap<Network, NetworkCapabilities>()

    private val _activeNetworkType = MutableStateFlow(NetworkType.NONE)
    val activeNetworkType: StateFlow<NetworkType> = _activeNetworkType.asStateFlow()

    val isOnline: Boolean
        get() = _activeNetworkType.value != NetworkType.NONE

    private var onNetworkChangedListener: ((NetworkType) -> Unit)? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            connectivityManager.getNetworkCapabilities(network)?.let {
                capabilitiesByNetwork[network] = it
            }
            updateNetworkStatus()
        }

        override fun onLost(network: Network) {
            capabilitiesByNetwork.remove(network)
            updateNetworkStatus()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            capabilitiesByNetwork[network] = capabilities
            updateNetworkStatus()
        }
    }

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { connectivityManager.registerNetworkCallback(request, networkCallback) }
    }

    fun setOnNetworkChangedListener(listener: (NetworkType) -> Unit) {
        onNetworkChangedListener = listener
    }

    fun checkConnectivityNow(): NetworkType {
        val current = getCurrentNetworkType()
        _activeNetworkType.value = current
        return current
    }

    private fun updateNetworkStatus() {
        val current = getCurrentNetworkType()
        if (_activeNetworkType.value != current) {
            _activeNetworkType.value = current
            onNetworkChangedListener?.invoke(current)
        }
    }

    private fun getCurrentNetworkType(): NetworkType {
        val capabilities = capabilitiesByNetwork.values
            .asSequence()
            .filter { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
            .filter { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
            .filterNot { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
            .toList()

        return when {
            capabilities.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } -> NetworkType.WIFI
            capabilities.any { it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } -> NetworkType.ETHERNET
            capabilities.any { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } -> NetworkType.CELLULAR
            else -> NetworkType.NONE
        }
    }
}
