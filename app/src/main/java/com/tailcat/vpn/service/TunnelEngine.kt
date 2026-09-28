package com.tailcat.vpn.service

import com.tailcat.vpn.core.model.NetworkMetrics
import com.tailcat.golib.engine.Engine
import com.tailcat.golib.engine.SocketProtector
import com.tailcat.golib.engine.SpeedProgress
import org.json.JSONObject

data class EngineAvailability(
    val isAvailable: Boolean,
    val message: String,
    val testRouting: Boolean = false
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
    val cancelSafeLifecycle: Boolean,
    /**
     * True while native flags advertise an implemented test-routing data plane
     * that has not passed Phase 8 physical leak acceptance. Display-only; does
     * not gate Connect.
     */
    val testRouting: Boolean = false
) {
    /**
     * Verifies dual-stack default-route capabilities. OpenTailcat installs
     * 0.0.0.0/0 and ::/0 after pumps, so requireIpv6 defaults to true.
     * Older API versions (< 2) or any false/missing required capability will fail closed.
     */
    fun satisfiesRouteRequirements(requireIpv6: Boolean = true): Boolean {
        if (apiVersion < REQUIRED_API_VERSION) return false
        if (!dataPlane || !wireGuard || !magicsock || !twoPhaseStart) return false
        if (!ipv4 || !tcp || !udp || !dns || !liveStats || !cancelSafeLifecycle) return false
        if (requireIpv6 && !ipv6) return false
        return true
    }

    companion object {
        const val REQUIRED_API_VERSION = 2

        private val KNOWN_FIELDS = setOf(
            "apiVersion",
            "dataPlane",
            "wireGuard",
            "magicsock",
            "twoPhaseStart",
            "ipv4",
            "ipv6",
            "tcp",
            "udp",
            "dns",
            "liveStats",
            "cancelSafeLifecycle",
            "testRouting"
        )

        fun fromJson(raw: String): EngineCapabilities {
            val json = JSONObject(raw)
            val unknown = json.keys().asSequence().filterNot { it in KNOWN_FIELDS }.sorted().toList()
            if (unknown.isNotEmpty()) {
                error("VPN engine advertised unknown capability fields: ${unknown.joinToString()}")
            }
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
                cancelSafeLifecycle = json.optBoolean("cancelSafeLifecycle", false),
                testRouting = json.optBoolean("testRouting", false)
            )
        }
    }
}

/**
 * Fail-closed boundary around the Go Mobile AAR.
 *
 * A compatible engine must explicitly advertise a working data plane with complete
 * protocol capabilities (API v2). Bundling a scaffold, outdated AAR, or incomplete
 * engine can therefore never create a full-device route that nobody pumps.
 */
class TunnelEngine : NativeEngine {

    /** False when the Go library cannot be loaded (for example on a host JVM). */
    private val loaded: Boolean by lazy { runCatching { Engine.touch() }.isSuccess }

    override val availability: EngineAvailability by lazy { inspectAvailability() }

    override fun prepare(token: String) {
        check(availability.isAvailable) { availability.message }
        require(token.isNotBlank()) { "Connection token is empty" }
        call("prepare") { Engine.prepare(token) }
    }

    override fun attachTun(tunFd: Int) {
        check(availability.isAvailable) { availability.message }
        require(tunFd >= 0) { "Invalid TUN file descriptor" }
        call("attachTun") { Engine.attachTun(tunFd.toLong()) }
    }

    override fun detachTun() {
        if (!loaded) return
        call("detachTun") { Engine.detachTun() }
    }

    override fun disarmPumps() {
        if (!loaded) return
        call("disarmPumps") { Engine.disarmPumps() }
    }

    override fun setSocketProtector(protect: (Int) -> Boolean) {
        if (!loaded) return
        call("setSocketProtector") {
            Engine.setSocketProtector(SocketProtector { fd -> protect(fd.toInt()) })
        }
    }

    override fun ensureTransportProtect() {
        if (!loaded) return
        call("ensureTransportProtect") { Engine.ensureTransportProtect() }
    }

    override fun updateNetworkState(networkStateJson: String) {
        if (!loaded) return
        runCatching { call("updateNetworkState") { Engine.updateNetworkState(networkStateJson) } }
    }

    override fun stop() {
        if (!loaded) return
        call("stop") { Engine.stop() }
    }

    override fun getStats(): NetworkMetrics {
        check(availability.isAvailable) { availability.message }
        val raw = call("getStatsJSON") { Engine.getStatsJSON() }
            ?: error("Tunnel engine returned invalid telemetry")
        return NetworkMetrics.fromJson(raw)
    }

    override fun measureTunnelPingMs(): Long {
        check(availability.isAvailable) { availability.message }
        return call("measureTunnelPingMS") { Engine.measureTunnelPingMS() }
    }

    override fun measureTunnelDownloadMbps(onProgress: (Double, Float) -> Unit): Double {
        check(availability.isAvailable) { availability.message }
        return call("measureTunnelDownloadMbps") { Engine.measureTunnelDownloadMbps(speedProgress(onProgress)) }
    }

    override fun measureTunnelUploadMbps(onProgress: (Double, Float) -> Unit): Double {
        check(availability.isAvailable) { availability.message }
        return call("measureTunnelUploadMbps") { Engine.measureTunnelUploadMbps(speedProgress(onProgress)) }
    }

    private fun speedProgress(onProgress: (Double, Float) -> Unit) =
        SpeedProgress { mbps, fraction -> onProgress(mbps, fraction.toFloat()) }

    private fun inspectAvailability(): EngineAvailability {
        if (!loaded) {
            return EngineAvailability(
                isAvailable = false,
                message = "VPN engine is not installed in this build"
            )
        }

        return runCatching {
            val raw = call("getCapabilitiesJSON") { Engine.getCapabilitiesJSON() }
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
                if (!caps.ipv6) missing.add("ipv6")
                if (!caps.tcp) missing.add("tcp")
                if (!caps.udp) missing.add("udp")
                if (!caps.dns) missing.add("dns")
                if (!caps.liveStats) missing.add("liveStats")
                if (!caps.cancelSafeLifecycle) missing.add("cancelSafeLifecycle")
                error("VPN engine data plane is not production-ready (missing: ${missing.joinToString(", ")})")
            }

            EngineAvailability(true, "VPN engine ready", testRouting = caps.testRouting)
        }.getOrElse { EngineAvailability(false, it.message ?: "VPN engine is unavailable") }
    }

    /**
     * Go errors arrive as checked exceptions; callers expect
     * IllegalStateException with the Go message. A LinkageError means this
     * AAR lacks the method, which no retry can fix.
     */
    private inline fun <T> call(name: String, block: () -> T): T {
        return try {
            block()
        } catch (error: LinkageError) {
            throw IllegalStateException("VPN engine is missing $name", error)
        } catch (error: Exception) {
            throw IllegalStateException(error.message ?: "VPN engine operation failed", error)
        }
    }
}
