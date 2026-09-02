package com.tailcat.vpn.service

import com.tailcat.vpn.core.model.NetworkMetrics
import com.tailcat.vpn.core.model.TransportType
import org.json.JSONObject
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

data class EngineAvailability(
    val isAvailable: Boolean,
    val message: String
)

data class EngineCapabilities(
    val apiVersion: Int,
    val dataPlane: Boolean,
    val wireGuard: Boolean,
    val magicsock: Boolean,
    val twoPhaseStart: Boolean,
    val ipv4: Boolean,
    val ipv6: Boolean,
    val tcp: Boolean,
    val udp: Boolean,
    val dns: Boolean,
    val liveStats: Boolean,
    val cancelSafeLifecycle: Boolean
) {
    /**
     * Verifies whether all capabilities required for a production default-route VPN are present and true.
     * Older API versions (< 2) or any false/missing capability will fail closed.
     */
    fun satisfiesRouteRequirements(requireIpv6: Boolean = false): Boolean {
        if (apiVersion < REQUIRED_API_VERSION) return false
        if (!dataPlane || !wireGuard || !magicsock || !twoPhaseStart) return false
        if (!ipv4 || !tcp || !udp || !dns || !liveStats || !cancelSafeLifecycle) return false
        if (requireIpv6 && !ipv6) return false
        return true
    }

    companion object {
        const val REQUIRED_API_VERSION = 2

        fun fromJson(raw: String): EngineCapabilities {
            val json = JSONObject(raw)
            return EngineCapabilities(
                apiVersion = json.optInt("apiVersion", 0),
                dataPlane = json.optBoolean("dataPlane", false),
                wireGuard = json.optBoolean("wireGuard", false),
                magicsock = json.optBoolean("magicsock", false),
                twoPhaseStart = json.optBoolean("twoPhaseStart", false),
                ipv4 = json.optBoolean("ipv4", false),
                ipv6 = json.optBoolean("ipv6", false),
                tcp = json.optBoolean("tcp", false),
                udp = json.optBoolean("udp", false),
                dns = json.optBoolean("dns", false),
                liveStats = json.optBoolean("liveStats", false),
                cancelSafeLifecycle = json.optBoolean("cancelSafeLifecycle", false)
            )
        }
    }
}

/**
 * Fail-closed boundary around the optional Go Mobile AAR.
 *
 * A compatible engine must explicitly advertise a working data plane with complete
 * protocol capabilities (API v2). Bundling a scaffold, outdated AAR, or incomplete
 * engine can therefore never create a full-device route that nobody pumps.
 */
class TunnelEngine {

    private val engineClass: Class<*>? by lazy {
        ENGINE_CLASS_NAMES.firstNotNullOfOrNull { name ->
            runCatching { Class.forName(name) }.getOrNull()
        }
    }

    val capabilities: EngineCapabilities? by lazy { inspectCapabilities() }
    val availability: EngineAvailability by lazy { inspectAvailability() }

    fun prepare(token: String) {
        check(availability.isAvailable) { availability.message }
        require(token.isNotBlank()) { "Connection token is empty" }
        invoke(requireMethod("prepare", parameterCount = 1), token)
    }

    fun attachTun(tunFd: Int) {
        check(availability.isAvailable) { availability.message }
        require(tunFd >= 0) { "Invalid TUN file descriptor" }
        val method = requireMethod("attachTun", parameterCount = 1)
        val fdArgument: Any = when (method.parameterTypes.firstOrNull()) {
            java.lang.Long.TYPE, java.lang.Long::class.java -> tunFd.toLong()
            else -> tunFd
        }
        invoke(method, fdArgument)
    }

    fun updateNetworkState(networkStateJson: String) {
        val klass = engineClass ?: return
        val method = klass.methods.firstOrNull {
            it.name.equals("updateNetworkState", ignoreCase = true) && it.parameterCount == 1
        } ?: return
        runCatching { invoke(method, networkStateJson) }
    }

    fun stop() {
        val klass = engineClass ?: return
        val method = klass.methods.firstOrNull {
            it.name.equals("stop", ignoreCase = true) && it.parameterCount == 0
        } ?: return
        invoke(method)
    }

    fun getStats(): NetworkMetrics {
        check(availability.isAvailable) { availability.message }
        val raw = invoke(requireMethod("getStatsJSON", parameterCount = 0)) as? String
            ?: error("Tunnel engine returned invalid telemetry")
        return NetworkMetrics.fromJson(raw)
    }

    private fun inspectCapabilities(): EngineCapabilities? {
        val klass = engineClass ?: return null
        return runCatching {
            val capabilitiesMethod = klass.methods.firstOrNull {
                it.name.equals("getCapabilitiesJSON", ignoreCase = true) && it.parameterCount == 0
            } ?: return null
            val raw = invoke(capabilitiesMethod) as? String ?: return null
            EngineCapabilities.fromJson(raw)
        }.getOrNull()
    }

    private fun inspectAvailability(): EngineAvailability {
        val klass = engineClass ?: return EngineAvailability(
            isAvailable = false,
            message = "VPN engine is not installed in this build"
        )

        return runCatching {
            val capabilitiesMethod = klass.methods.firstOrNull {
                it.name.equals("getCapabilitiesJSON", ignoreCase = true) && it.parameterCount == 0
            } ?: error("VPN engine does not expose a capability handshake")
            val raw = invoke(capabilitiesMethod) as? String
                ?: error("VPN engine returned invalid capabilities")
            val caps = EngineCapabilities.fromJson(raw)

            if (caps.apiVersion < EngineCapabilities.REQUIRED_API_VERSION) {
                error("VPN engine API version ${caps.apiVersion} is outdated (requires v${EngineCapabilities.REQUIRED_API_VERSION}); failing closed")
            }
            if (!caps.satisfiesRouteRequirements()) {
                val missing = mutableListOf<String>()
                if (!caps.dataPlane) missing.add("dataPlane")
                if (!caps.wireGuard) missing.add("wireGuard")
                if (!caps.magicsock) missing.add("magicsock")
                if (!caps.twoPhaseStart) missing.add("twoPhaseStart")
                if (!caps.ipv4) missing.add("ipv4")
                if (!caps.tcp) missing.add("tcp")
                if (!caps.udp) missing.add("udp")
                if (!caps.dns) missing.add("dns")
                if (!caps.liveStats) missing.add("liveStats")
                if (!caps.cancelSafeLifecycle) missing.add("cancelSafeLifecycle")
                error("VPN engine data plane is not production-ready (missing: ${missing.joinToString(", ")})")
            }

            requireMethod("prepare", parameterCount = 1)
            requireMethod("attachTun", parameterCount = 1)
            requireMethod("getStatsJSON", parameterCount = 0)
            requireMethod("stop", parameterCount = 0)
            EngineAvailability(true, "VPN engine ready")
        }.getOrElse { EngineAvailability(false, it.message ?: "VPN engine is unavailable") }
    }

    private fun requireMethod(name: String, parameterCount: Int): Method {
        val klass = engineClass ?: error("VPN engine is not installed in this build")
        return klass.methods.firstOrNull {
            it.name.equals(name, ignoreCase = true) && it.parameterCount == parameterCount
        } ?: error("VPN engine is missing $name")
    }

    private fun invoke(method: Method, vararg arguments: Any): Any? {
        return try {
            method.invoke(null, *arguments)
        } catch (error: InvocationTargetException) {
            throw IllegalStateException(
                error.targetException?.message ?: "VPN engine operation failed",
                error.targetException ?: error
            )
        }
    }

    private fun JSONObject.optIntOrNull(name: String): Int? =
        if (has(name) && !isNull(name)) optInt(name) else null

    private fun JSONObject.optNullableString(name: String): String? =
        if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() } else null

    companion object {
        private val ENGINE_CLASS_NAMES = listOf(
            "engine.Engine",
            "com.tailcat.vpn.engine.Engine"
        )
    }
}
