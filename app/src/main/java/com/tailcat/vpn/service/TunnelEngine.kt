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

/**
 * Fail-closed boundary around the optional Go Mobile AAR.
 *
 * A compatible engine must explicitly advertise a working data plane. Bundling a scaffold
 * or an incompatible AAR can therefore never create a full-device route that nobody pumps.
 */
class TunnelEngine {

    private val engineClass: Class<*>? by lazy {
        ENGINE_CLASS_NAMES.firstNotNullOfOrNull { name ->
            runCatching { Class.forName(name) }.getOrNull()
        }
    }

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
        val json = JSONObject(raw)

        val transport = when (json.optString("transport").uppercase()) {
            "DIRECT", "DIRECT_P2P" -> TransportType.DIRECT_P2P
            "DERP", "DERP_RELAY" -> TransportType.DERP_RELAY
            else -> TransportType.UNKNOWN
        }

        return NetworkMetrics(
            transportType = transport,
            derpRegionId = json.optIntOrNull("derpRegionId"),
            derpRegionName = json.optNullableString("derpRegionName"),
            rttLatencyMs = json.optLong("rttMs", 0L).coerceAtLeast(0L),
            jitterMs = json.optLong("jitterMs", 0L).coerceAtLeast(0L),
            txBytes = json.optLong("txBytes", 0L).coerceAtLeast(0L),
            rxBytes = json.optLong("rxBytes", 0L).coerceAtLeast(0L),
            txRateKbps = json.optLong("txRateKbps", 0L).coerceAtLeast(0L),
            rxRateKbps = json.optLong("rxRateKbps", 0L).coerceAtLeast(0L)
        )
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
            val capabilities = JSONObject(raw)
            val ready = capabilities.optInt("apiVersion", 0) >= REQUIRED_API_VERSION &&
                capabilities.optBoolean("dataPlane", false) &&
                capabilities.optBoolean("wireGuard", false) &&
                capabilities.optBoolean("magicsock", false) &&
                capabilities.optBoolean("twoPhaseStart", false)
            check(ready) { "VPN engine data plane is not production-ready" }

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
        private const val REQUIRED_API_VERSION = 1
        private val ENGINE_CLASS_NAMES = listOf(
            "engine.Engine",
            "com.tailcat.vpn.engine.Engine"
        )
    }
}
