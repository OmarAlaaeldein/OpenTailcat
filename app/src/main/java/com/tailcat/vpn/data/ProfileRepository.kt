package com.tailcat.vpn.data

import com.tailcat.vpn.core.dns.DnsValidationResult
import com.tailcat.vpn.core.dns.DnsValidator
import com.tailcat.vpn.core.model.DnsPolicy
import com.tailcat.vpn.core.model.GatewayProfile
import com.tailcat.vpn.core.token.TokenParser
import com.tailcat.vpn.core.token.TokenValidationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ProfileRepository(private val preferencesStore: PreferencesStorage) {

    private val _profiles = MutableStateFlow<List<GatewayProfile>>(emptyList())
    val profiles: StateFlow<List<GatewayProfile>> = _profiles.asStateFlow()

    private val _activeProfile = MutableStateFlow<GatewayProfile?>(null)
    val activeProfile: StateFlow<GatewayProfile?> = _activeProfile.asStateFlow()

    init {
        loadProfiles()
    }

    private fun loadProfiles() {
        val list = parseProfiles(preferencesStore.savedProfilesJson)
        _profiles.value = list

        val activeId = preferencesStore.activeProfileId
        val active = list.find { it.id == activeId } ?: list.find { it.isDefault } ?: list.firstOrNull()
        _activeProfile.value = active
    }

    /**
     * Each saved entry is parsed on its own, so a corrupt entry drops only
     * itself. An unreadable array yields no profiles. Nothing is logged:
     * entries hold tokens.
     */
    private fun parseProfiles(json: String?): List<GatewayProfile> {
        if (json.isNullOrBlank()) return emptyList()
        val array = try {
            JSONArray(json)
        } catch (e: Exception) {
            return emptyList()
        }
        return (0 until array.length()).mapNotNull { i ->
            try {
                parseProfile(array.getJSONObject(i))
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun parseProfile(obj: JSONObject): GatewayProfile {
        val rawDns = obj.optString("customDns", "1.1.1.1")
        val validatedDns = if (DnsValidator.isValid(rawDns)) rawDns else "1.1.1.1"
        val policy = DnsPolicy.fromString(obj.optString("dnsPolicy", DnsPolicy.PROFILE_RESOLVER.name))

        return GatewayProfile(
            id = obj.getString("id"),
            name = obj.getString("name"),
            token = obj.getString("token"),
            serverPublicKey = obj.getString("serverPublicKey"),
            derpRegionId = if (obj.has("derpRegionId") && !obj.isNull("derpRegionId")) obj.getInt("derpRegionId") else null,
            customDns = validatedDns,
            dnsPolicy = policy,
            mtu = obj.optInt("mtu", 1280),
            isDefault = obj.optBoolean("isDefault", false),
            createdAt = obj.optLong("createdAt", System.currentTimeMillis())
        )
    }

    private fun saveProfiles() {
        val array = JSONArray()
        for (p in _profiles.value) {
            val obj = JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("token", p.token)
                put("serverPublicKey", p.serverPublicKey)
                put("derpRegionId", p.derpRegionId)
                put("customDns", p.customDns)
                put("dnsPolicy", p.dnsPolicy.name)
                put("mtu", p.mtu)
                put("isDefault", p.isDefault)
                put("createdAt", p.createdAt)
            }
            array.put(obj)
        }
        preferencesStore.savedProfilesJson = array.toString()
    }

    fun addOrUpdateFromToken(
        name: String,
        rawToken: String,
        customDns: String = "1.1.1.1",
        dnsPolicy: DnsPolicy = DnsPolicy.PROFILE_RESOLVER
    ): Result<GatewayProfile> {
        val dnsValidation = DnsValidator.validate(customDns)
        if (dnsValidation !is DnsValidationResult.Valid) {
            val reason = (dnsValidation as DnsValidationResult.Invalid).reason
            return Result.failure(IllegalArgumentException("Invalid DNS resolver: $reason"))
        }

        val validation = TokenParser.validate(rawToken)
        if (validation !is TokenValidationState.Valid) {
            val message = when (validation) {
                is TokenValidationState.Expired -> "This gateway token expired on ${validation.expiredDate}"
                is TokenValidationState.LegacyReissueRequired -> "Legacy token schema lacks separate disco key; reissue required"
                is TokenValidationState.Invalid -> validation.reason
                TokenValidationState.Empty -> "Connection token cannot be empty"
                is TokenValidationState.Valid -> error("unreachable")
            }
            return Result.failure(IllegalArgumentException(message))
        }

        val tokenData = validation.parsed
        val existing = _profiles.value.find { it.serverPublicKey == tokenData.serverPublicKeyHex }

        val profile = GatewayProfile(
            id = existing?.id ?: UUID.randomUUID().toString(),
            // Re-pairing the same gateway (e.g. a refreshed token) keeps its name and MTU.
            name = name.ifBlank { existing?.name ?: "Gateway-${tokenData.serverPublicKeyHex.take(6)}" },
            token = tokenData.rawToken,
            serverPublicKey = tokenData.serverPublicKeyHex,
            derpRegionId = tokenData.derpRegionId,
            customDns = dnsValidation.ip,
            dnsPolicy = dnsPolicy,
            mtu = existing?.mtu ?: preferencesStore.defaultMtu,
            isDefault = existing?.isDefault ?: _profiles.value.isEmpty(),
            createdAt = existing?.createdAt ?: System.currentTimeMillis()
        )

        val updatedList = _profiles.value.filter { it.id != profile.id } + profile
        _profiles.value = updatedList
        saveProfiles()

        // Always activate the newly paired or updated profile immediately
        setActiveProfile(profile)

        return Result.success(profile)
    }

    fun updateProfileDns(
        profileId: String,
        customDns: String,
        dnsPolicy: DnsPolicy
    ): Result<GatewayProfile> {
        val existing = _profiles.value.find { it.id == profileId }
            ?: return Result.failure(IllegalArgumentException("Profile not found"))

        val dnsValidation = DnsValidator.validate(customDns)
        if (dnsValidation !is DnsValidationResult.Valid) {
            val reason = (dnsValidation as DnsValidationResult.Invalid).reason
            return Result.failure(IllegalArgumentException("Invalid DNS resolver: $reason"))
        }

        val updated = existing.copy(
            customDns = dnsValidation.ip,
            dnsPolicy = dnsPolicy
        )

        _profiles.value = _profiles.value.map { if (it.id == profileId) updated else it }
        saveProfiles()

        if (_activeProfile.value?.id == profileId) {
            _activeProfile.value = updated
        }

        return Result.success(updated)
    }

    fun setActiveProfile(profile: GatewayProfile) {
        require(_profiles.value.any { it.id == profile.id }) { "Profile is not saved" }
        _activeProfile.value = profile
        preferencesStore.activeProfileId = profile.id
    }

    fun deleteProfile(id: String) {
        val updatedList = _profiles.value.filter { it.id != id }
        _profiles.value = updatedList
        saveProfiles()

        if (_activeProfile.value?.id == id) {
            _activeProfile.value = updatedList.firstOrNull()
            preferencesStore.activeProfileId = _activeProfile.value?.id
        }
    }
}
