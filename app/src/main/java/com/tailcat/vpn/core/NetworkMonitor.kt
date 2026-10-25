package com.tailcat.vpn.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class NetworkType {
    WIFI,
    CELLULAR,
    ETHERNET,
    NONE
}

/** Application-owned monitor; callbacks and listener notifications run off the main thread. */
class NetworkMonitor(context: Context) : AutoCloseable {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val stateLock = Any()
    // All network state, including JSON reads by the VPN service, shares this lock.
    private val capabilitiesByNetwork = mutableMapOf<Network, NetworkCapabilities>()
    private val linkPropertiesByNetwork = mutableMapOf<Network, LinkProperties>()
    private var defaultNetwork: Network? = null
    private var bestMatchingNetwork: Network? = null
    private var closed = false

    private val _activeNetworkType = MutableStateFlow(NetworkType.NONE)
    val activeNetworkType: StateFlow<NetworkType> = _activeNetworkType.asStateFlow()

    val isOnline: Boolean
        get() = _activeNetworkType.value != NetworkType.NONE

    @Volatile
    private var onNetworkStateChangedListener: ((NetworkType, String) -> Unit)? = null
    private var lastNetworkStateJson = ""

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) = updateNetworkStatus {
            capabilitiesByNetwork.remove(network)
            linkPropertiesByNetwork.remove(network)
            if (defaultNetwork == network) defaultNetwork = null
            if (bestMatchingNetwork == network) bestMatchingNetwork = null
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            updateNetworkStatus { capabilitiesByNetwork[network] = capabilities }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            updateNetworkStatus { linkPropertiesByNetwork[network] = linkProperties }
    }

    private val defaultNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateNetworkStatus {
            // VPN capabilities must never become the transport's underlay metadata.
            defaultNetwork?.takeIf {
                capabilitiesByNetwork[it]?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }?.let {
                capabilitiesByNetwork.remove(it)
                linkPropertiesByNetwork.remove(it)
            }
            defaultNetwork = network
        }

        override fun onLost(network: Network) = updateNetworkStatus {
            if (defaultNetwork == network) defaultNetwork = null
            if (capabilitiesByNetwork[network]?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                capabilitiesByNetwork.remove(network)
                linkPropertiesByNetwork.remove(network)
            }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            updateNetworkStatus {
                if (defaultNetwork == network) capabilitiesByNetwork[network] = capabilities
            }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            updateNetworkStatus {
                if (defaultNetwork == network) linkPropertiesByNetwork[network] = linkProperties
            }
    }

    // Public since API 31. Unlike registerNetworkCallback, Android chooses one
    // best non-VPN network, without this monitor requesting another connection.
    private val bestMatchingNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateNetworkStatus {
            bestMatchingNetwork = network
        }

        override fun onLost(network: Network) = updateNetworkStatus {
            if (bestMatchingNetwork == network) bestMatchingNetwork = null
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            updateNetworkStatus {
                if (bestMatchingNetwork == network) capabilitiesByNetwork[network] = capabilities
            }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            updateNetworkStatus {
                if (bestMatchingNetwork == network) linkPropertiesByNetwork[network] = linkProperties
            }
    }

    private val callbackThread = HandlerThread("OpenTailcat-NetworkMonitor").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)
    private val registeredCallbacks = mutableListOf<ConnectivityManager.NetworkCallback>()

    init {
        // Seed before registering: onAvailable is followed by capabilities/link
        // callbacks on API 26+, where synchronous getters can race those events.
        defaultNetwork = connectivityManager.activeNetwork
        for (network in connectivityManager.allNetworks) {
            connectivityManager.getNetworkCapabilities(network)?.let {
                capabilitiesByNetwork[network] = it
            }
            connectivityManager.getLinkProperties(network)?.let {
                linkPropertiesByNetwork[network] = it
            }
        }
        updateNetworkStatus()

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching {
            connectivityManager.registerNetworkCallback(request, networkCallback, callbackHandler)
            registeredCallbacks += networkCallback
        }
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(defaultNetworkCallback, callbackHandler)
            registeredCallbacks += defaultNetworkCallback
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                connectivityManager.registerBestMatchingNetworkCallback(
                    request, bestMatchingNetworkCallback, callbackHandler
                )
                registeredCallbacks += bestMatchingNetworkCallback
            }
        }
    }

    fun setOnNetworkStateChangedListener(listener: (NetworkType, String) -> Unit) {
        callbackHandler.post {
            synchronized(stateLock) {
                if (closed) return@post
                onNetworkStateChangedListener = listener
            }
            updateNetworkStatus(force = true)
        }
    }

    fun getNetworkStateJSON(): String = synchronized(stateLock) { buildNetworkState().second }

    /** Called with stateLock held; interface order is stable, never a route preference. */
    private fun buildNetworkState(): Pair<NetworkType, String> {
        val networks = capabilitiesByNetwork.keys
            .filter { network ->
                val caps = capabilitiesByNetwork.getValue(network)
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
            .sortedBy { it.networkHandle }
        val reportedTransport = legacyDefaultTransport()
        val selected = selectUnderlyingNetwork(
            networks, defaultNetwork, bestMatchingNetwork, reportedTransport
        ) { transportOf(capabilitiesByNetwork.getValue(it)) }
        val selectedCaps = selected?.let { capabilitiesByNetwork[it] }
        val currentType = if (selectedCaps != null) {
            if (selectedCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                transportOf(selectedCaps)
            } else NetworkType.NONE
        } else {
            // A legacy platform answer can identify the transport even when
            // multiple networks of that type make the interface ambiguous.
            reportedTransport.takeIf { type ->
                networks.any {
                    val caps = capabilitiesByNetwork.getValue(it)
                    transportOf(caps) == type &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                }
            } ?: NetworkType.NONE
        }
        val root = JSONObject()
        root.put("isOnline", currentType != NetworkType.NONE)
        root.put("networkType", currentType.name)

        val ifArray = JSONArray()
        for (network in networks) {
            val lp = linkPropertiesByNetwork[network] ?: continue
            val ifName = lp.interfaceName?.takeIf { it.isNotBlank() } ?: continue
            val ifObj = JSONObject()
            ifObj.put("name", ifName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ifObj.put("mtu", lp.mtu)
            val addresses = JSONArray()
            for (linkAddr in lp.linkAddresses) {
                addresses.put("${linkAddr.address.hostAddress}/${linkAddr.prefixLength}")
            }
            ifObj.put("addresses", addresses)
            ifArray.put(ifObj)
        }
        root.put("interfaces", ifArray)

        // Only the selected network supplies resolver/gateway metadata. A
        // carrier-private resolver from a background network may be unreachable
        // on the default underlay. Never substitute the VPN's own DNS servers.
        val lp = selected?.let { linkPropertiesByNetwork[it] }
        root.put("defaultInterface", lp?.interfaceName?.takeIf { it.isNotBlank() } ?: "")
        val gateways = JSONArray()
        lp?.routes?.mapNotNull { it.gateway?.hostAddress?.takeIf(String::isNotBlank) }
            ?.distinct()?.forEach { gateways.put(it) }
        root.put("gateways", gateways)
        val dnsServers = JSONArray()
        lp?.dnsServers?.mapNotNull { it.hostAddress?.takeIf(String::isNotBlank) }
            ?.distinct()?.forEach { dnsServers.put(it) }
        root.put("dnsServers", dnsServers)
        return currentType to root.toString()
    }

    private fun updateNetworkStatus(force: Boolean = false, change: () -> Unit = {}) {
        val notification = synchronized(stateLock) {
            if (closed) return
            change()
            val (type, json) = buildNetworkState()
            if (!force && _activeNetworkType.value == type && lastNetworkStateJson == json) return
            _activeNetworkType.value = type
            lastNetworkStateJson = json
            type to json
        }
        // Listener code can call back into the monitor or JNI without holding
        // its state lock. Callback delivery is serialized by callbackHandler.
        onNetworkStateChangedListener?.invoke(notification.first, notification.second)
    }

    @Suppress("DEPRECATION")
    private fun legacyDefaultTransport(): NetworkType {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return NetworkType.NONE
        // API 26-30 has no public best-matching callback. This legacy getter
        // reports an underlying transport when the app default is a VPN; null
        // is allowed. It cannot identify a Network among multiple of that type.
        val info = connectivityManager.activeNetworkInfo?.takeIf { it.isConnected }
            ?: return NetworkType.NONE
        return when (info.type) {
            ConnectivityManager.TYPE_WIFI -> NetworkType.WIFI
            ConnectivityManager.TYPE_ETHERNET -> NetworkType.ETHERNET
            ConnectivityManager.TYPE_MOBILE -> NetworkType.CELLULAR
            else -> NetworkType.NONE
        }
    }

    private fun transportOf(caps: NetworkCapabilities): NetworkType = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
        else -> NetworkType.NONE
    }

    override fun close() {
        synchronized(stateLock) {
            if (closed) return
            closed = true
            onNetworkStateChangedListener = null
            capabilitiesByNetwork.clear()
            linkPropertiesByNetwork.clear()
            defaultNetwork = null
            bestMatchingNetwork = null
            _activeNetworkType.value = NetworkType.NONE
        }
        registeredCallbacks.forEach { callback ->
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
        callbackThread.quitSafely()
    }

    companion object {
        /**
         * Candidates must be visible non-VPN Internet networks. A platform
         * selection wins even when unvalidated; validation is connectivity
         * state, not evidence that another network carries the default route.
         *
         * This is an underlay hint for netmon/DNS, not socket binding or proof
         * of VPN bypass. Transport sockets still require VpnService.protect.
         */
        internal fun <T : Any> selectUnderlyingNetwork(
            candidates: List<T>,
            defaultNetwork: T?,
            bestMatchingNetwork: T?,
            reportedTransport: NetworkType = NetworkType.NONE,
            transportOf: (T) -> NetworkType
        ): T? {
            if (defaultNetwork != null && defaultNetwork in candidates) return defaultNetwork
            if (bestMatchingNetwork != null && bestMatchingNetwork in candidates) return bestMatchingNetwork
            if (reportedTransport != NetworkType.NONE) {
                return candidates.filter { transportOf(it) == reportedTransport }.singleOrNull()
            }
            // With multiple candidates, transport rankings or list order would
            // invent a platform choice. Leave the interface and DNS unknown.
            return candidates.singleOrNull()
        }
    }
}
