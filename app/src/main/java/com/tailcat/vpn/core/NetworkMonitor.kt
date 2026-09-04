package com.tailcat.vpn.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
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
    private val linkPropertiesByNetwork = ConcurrentHashMap<Network, LinkProperties>()

    private val _activeNetworkType = MutableStateFlow(NetworkType.NONE)
    val activeNetworkType: StateFlow<NetworkType> = _activeNetworkType.asStateFlow()

    val isOnline: Boolean
        get() = _activeNetworkType.value != NetworkType.NONE

    private var onNetworkStateChangedListener: ((NetworkType, String) -> Unit)? = null
    private var lastNetworkStateJson: String = ""

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            connectivityManager.getNetworkCapabilities(network)?.let {
                capabilitiesByNetwork[network] = it
            }
            connectivityManager.getLinkProperties(network)?.let {
                linkPropertiesByNetwork[network] = it
            }
            updateNetworkStatus()
        }

        override fun onLost(network: Network) {
            capabilitiesByNetwork.remove(network)
            linkPropertiesByNetwork.remove(network)
            updateNetworkStatus()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            capabilitiesByNetwork[network] = capabilities
            updateNetworkStatus()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            linkPropertiesByNetwork[network] = linkProperties
            updateNetworkStatus()
        }
    }

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { connectivityManager.registerNetworkCallback(request, networkCallback) }
        for (network in connectivityManager.allNetworks) {
            connectivityManager.getNetworkCapabilities(network)?.let { capabilitiesByNetwork[network] = it }
            connectivityManager.getLinkProperties(network)?.let { linkPropertiesByNetwork[network] = it }
        }
        updateNetworkStatus()
    }

    fun setOnNetworkStateChangedListener(listener: (NetworkType, String) -> Unit) {
        onNetworkStateChangedListener = listener
    }

    fun getNetworkStateJSON(): String {
        val root = JSONObject()
        val currentType = getCurrentNetworkType()
        root.put("isOnline", currentType != NetworkType.NONE)
        root.put("networkType", currentType.name)

        val ifArray = JSONArray()
        val gateways = mutableSetOf<String>()
        val dnsServers = mutableSetOf<String>()

        val validNetworks = capabilitiesByNetwork.entries
            .filter { (_, caps) ->
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
            .map { it.key }

        for (network in validNetworks) {
            val lp = linkPropertiesByNetwork[network] ?: connectivityManager.getLinkProperties(network) ?: continue
            val ifName = lp.interfaceName ?: continue

            val ifObj = JSONObject()
            ifObj.put("name", ifName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ifObj.put("mtu", lp.mtu)
            }

            val addrsArray = JSONArray()
            for (linkAddr in lp.linkAddresses) {
                addrsArray.put("${linkAddr.address.hostAddress}/${linkAddr.prefixLength}")
            }
            ifObj.put("addresses", addrsArray)
            ifArray.put(ifObj)

            for (route in lp.routes) {
                route.gateway?.hostAddress?.takeIf { it.isNotBlank() }?.let { gateways.add(it) }
            }
            for (dns in lp.dnsServers) {
                dns.hostAddress?.takeIf { it.isNotBlank() }?.let { dnsServers.add(it) }
            }
        }

        root.put("interfaces", ifArray)

        val gwArray = JSONArray()
        gateways.forEach { gwArray.put(it) }
        root.put("gateways", gwArray)

        val dnsArray = JSONArray()
        dnsServers.forEach { dnsArray.put(it) }
        root.put("dnsServers", dnsArray)

        return root.toString()
    }

    private fun updateNetworkStatus() {
        val currentType = getCurrentNetworkType()
        val currentStateJson = getNetworkStateJSON()
        val typeChanged = _activeNetworkType.value != currentType
        val stateChanged = lastNetworkStateJson != currentStateJson

        if (typeChanged || stateChanged) {
            _activeNetworkType.value = currentType
            lastNetworkStateJson = currentStateJson
            onNetworkStateChangedListener?.invoke(currentType, currentStateJson)
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
