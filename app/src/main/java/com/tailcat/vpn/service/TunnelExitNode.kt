package com.tailcat.vpn.service

import com.tailcat.golib.engine.Engine
import org.json.JSONObject

/**
 * gomobile implementation of [NativeExitNode]. Thin on purpose: it turns Go's
 * checked exceptions into IllegalStateException with the Go message, and
 * nothing here logs the token or device keys.
 */
class TunnelExitNode : NativeExitNode {

    override val availability: EngineAvailability by lazy { inspectAvailability() }

    override fun start(config: ExitNodeConfig): String {
        check(availability.isAvailable) { availability.message }
        return call("startExitNode") { Engine.startExitNode(configJson(config)) }
            ?.takeIf { it.isNotBlank() }
            ?: error("The sharing engine returned no code")
    }

    override fun statsJson(): String =
        call("exitNodeStatsJSON") { Engine.exitNodeStatsJSON() } ?: error("The sharing engine returned no status")

    override fun approve(deviceKey: String) = call("exitNodeApprove") { Engine.exitNodeApprove(deviceKey) }

    override fun deny(deviceKey: String) = call("exitNodeDeny") { Engine.exitNodeDeny(deviceKey) }

    override fun revoke(deviceKey: String): Boolean = call("exitNodeRevoke") { Engine.exitNodeRevoke(deviceKey) }

    override fun stop() {
        if (!availability.isAvailable) return
        call("stopExitNode") { Engine.stopExitNode() }
    }

    private fun inspectAvailability(): EngineAvailability {
        if (runCatching { Engine.touch() }.isFailure) {
            return EngineAvailability(false, "The native engine is not installed in this build")
        }
        // A side-effect-free probe: an AAR without the exports throws a
        // LinkageError; one that has them answers (here, with "never started").
        return try {
            Engine.exitNodeStatsJSON()
            EngineAvailability(true, "Sharing engine ready")
        } catch (_: LinkageError) {
            EngineAvailability(false, "Sharing is not included in this build's native engine")
        } catch (_: Exception) {
            EngineAvailability(true, "Sharing engine ready")
        }
    }

    private inline fun <T> call(name: String, block: () -> T): T = try {
        block()
    } catch (error: LinkageError) {
        throw IllegalStateException("The native engine is missing $name", error)
    } catch (error: Exception) {
        throw IllegalStateException(error.message ?: "The sharing engine failed", error)
    }

    companion object {
        /** The JSON the native `startExitNode` expects; unknown fields are rejected there. */
        fun configJson(config: ExitNodeConfig): String = JSONObject()
            .put("ttlSeconds", config.ttlSeconds)
            .put(
                "admission",
                when (config.admission) {
                    ExitNodeAdmission.APPROVAL -> "approval"
                    ExitNodeAdmission.OPEN -> "open"
                }
            )
            .toString()
    }
}
