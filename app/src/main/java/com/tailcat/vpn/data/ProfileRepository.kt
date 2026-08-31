package com.tailcat.vpn.data

import com.tailcat.vpn.core.model.GatewayProfile
import com.tailcat.vpn.core.token.TokenParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class ProfileRepository(private val preferencesStore: PreferencesStore) {

    private val _profiles = MutableStateFlow<List<GatewayProfile>>(emptyList())
    val profiles: StateFlow<List<GatewayProfile>> = _profiles.asStateFlow()

    private val _activeProfile = MutableStateFlow<GatewayProfile?>(null)
    val activeProfile: StateFlow<GatewayProfile?> = _activeProfile.asStateFlow()

    init {
        loadProfiles()
    }

    private fun loadProfiles() {
        val json = preferencesStore.savedProfilesJson
        val list = mutableListOf<GatewayProfile>()

        if (!json.isNullOrBlank()) {
            try {
                val array = JSONArray(json)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        GatewayProfile(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            token = obj.getString("token"),
                            serverPublicKey = obj.getString("serverPublicKey"),
                            derpRegionId = if (obj.has("derpRegionId") && !obj.isNull("derpRegionId")) obj.getInt("derpRegionId") else null,
                            customDns = obj.optString("customDns", "100.100.21.8"),
                            mtu = obj.optInt("mtu", 1280),
                            tcpMss = obj.optInt("tcpMss", 1120),
                            isDefault = obj.optBoolean("isDefault", false),
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                        )
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        _profiles.value = list

        val activeId = preferencesStore.activeProfileId
        val active = list.find { it.id == activeId } ?: list.find { it.isDefault } ?: list.firstOrNull()
        _activeProfile.value = active
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
                put("mtu", p.mtu)
                put("tcpMss", p.tcpMss)
                put("isDefault", p.isDefault)
                put("createdAt", p.createdAt)
            }
            array.put(obj)
        }
        preferencesStore.savedProfilesJson = array.toString()
    }

    fun addOrUpdateFromToken(name: String, rawToken: String, customDns: String = "100.100.21.8"): Result<GatewayProfile> {
        val parsed = TokenParser.parse(rawToken)
        if (parsed.isFailure) {
            return Result.failure(parsed.exceptionOrNull() ?: IllegalArgumentException("Invalid token"))
        }

        val tokenData = parsed.getOrThrow()
        val existing = _profiles.value.find { it.serverPublicKey == tokenData.serverPublicKeyHex }

        val profile = GatewayProfile(
            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
            name = name.ifBlank { "Gateway-${tokenData.serverPublicKeyHex.take(6)}" },
            token = tokenData.rawToken,
            serverPublicKey = tokenData.serverPublicKeyHex,
            derpRegionId = tokenData.derpRegionId,
            customDns = customDns,
            mtu = preferencesStore.defaultMtu,
            tcpMss = preferencesStore.defaultTcpMss,
            isDefault = _profiles.value.isEmpty()
        )

        val updatedList = _profiles.value.filter { it.id != profile.id } + profile
        _profiles.value = updatedList
        saveProfiles()

        if (_activeProfile.value == null || profile.isDefault) {
            setActiveProfile(profile)
        }

        return Result.success(profile)
    }

    fun setActiveProfile(profile: GatewayProfile) {
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
