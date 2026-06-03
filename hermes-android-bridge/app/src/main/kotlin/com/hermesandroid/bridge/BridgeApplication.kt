package com.hermesandroid.bridge

import android.app.Application
import com.hermesandroid.bridge.auth.PairingManager
import com.hermesandroid.bridge.client.RelayClient
import com.hermesandroid.bridge.model.DeviceCapabilities
import com.hermesandroid.bridge.notification.Notifier
import com.hermesandroid.bridge.power.WakeLockManager
import com.hermesandroid.bridge.server.BridgeServer
import com.hermesandroid.bridge.util.AppLogger
import com.hermesandroid.bridge.widgets.registerAllWidgets

class BridgeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(applicationContext)
        SettingsManager.init(applicationContext)
        PairingManager.init(applicationContext)
        DeviceCapabilities.init(applicationContext)
        WakeLockManager.init(applicationContext)
        BridgeServer.start(port = 8765)
        Notifier.init(applicationContext)

        // Initialize relay client and auto-connect if previously configured
        RelayClient.init(applicationContext)
        RelayClient.autoConnect()

        // Register widget cards (calendar, jobs, search, etc.)
        registerAllWidgets()
    }
}
