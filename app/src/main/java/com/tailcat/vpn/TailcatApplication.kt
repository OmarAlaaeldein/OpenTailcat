package com.tailcat.vpn

import android.app.Application
import com.tailcat.vpn.core.NetworkMonitor
import com.tailcat.vpn.data.PreferencesStore
import com.tailcat.vpn.data.ProfileRepository
import com.tailcat.vpn.service.TunnelController
import com.tailcat.vpn.service.VpnNotificationManager

class TailcatApplication : Application() {

    lateinit var preferencesStore: PreferencesStore
        private set

    lateinit var profileRepository: ProfileRepository
        private set

    lateinit var tunnelController: TunnelController
        private set

    lateinit var networkMonitor: NetworkMonitor
        private set

    lateinit var notificationManager: VpnNotificationManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        preferencesStore = PreferencesStore(this)
        profileRepository = ProfileRepository(preferencesStore)
        notificationManager = VpnNotificationManager(this)
        networkMonitor = NetworkMonitor(this)
        tunnelController = TunnelController(this, profileRepository, preferencesStore, networkMonitor)

        notificationManager.createNotificationChannels()
    }

    companion object {
        lateinit var instance: TailcatApplication
            private set
    }
}
