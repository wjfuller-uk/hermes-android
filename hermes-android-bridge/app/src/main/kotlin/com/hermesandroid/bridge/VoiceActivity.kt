package com.hermesandroid.bridge

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermesandroid.bridge.auth.PairingManager
import com.hermesandroid.bridge.client.RelayClient
import com.hermesandroid.bridge.ui.VoiceAssistantScreen
import com.hermesandroid.bridge.ui.VoiceViewModel
import com.hermesandroid.bridge.ui.VoiceState

class VoiceActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Auto-connect if we have saved credentials and aren't already connected
        if (!RelayClient.isConnected) {
            RelayClient.autoConnect()
        }

        setContent {
            val viewModel: VoiceViewModel = viewModel()
            viewModel.updateConnection(RelayClient.isConnected)
            viewModel.updateDeviceName("${Build.BRAND} ${Build.MODEL}")

            // Observe connection status
            DisposableEffect(Unit) {
                val originalCallback = RelayClient.onStatusChanged
                val originalVoiceCallback = RelayClient.onVoiceStateChanged
                RelayClient.onStatusChanged = { connected, message ->
                    viewModel.updateConnection(connected)
                    // Don't finish on disconnect — show reconnect UI instead
                }
                RelayClient.onVoiceStateChanged = { state ->
                    val voiceState = when (state) {
                        "listening" -> VoiceState.LISTENING
                        "processing" -> VoiceState.PROCESSING
                        "speaking" -> VoiceState.SPEAKING
                        else -> VoiceState.IDLE
                    }
                    viewModel.updateState(voiceState)
                }
                onDispose {
                    RelayClient.onStatusChanged = originalCallback
                    RelayClient.onVoiceStateChanged = originalVoiceCallback
                }
            }

            VoiceAssistantScreen(
                viewModel = viewModel,
                onOpenSettings = {
                    startActivity(Intent(this@VoiceActivity, MainActivity::class.java))
                },
                onConnect = { url, code ->
                    RelayClient.connect(url, code)
                },
                onDisconnect = {
                    RelayClient.disconnect()
                }
            )
        }
    }
}
