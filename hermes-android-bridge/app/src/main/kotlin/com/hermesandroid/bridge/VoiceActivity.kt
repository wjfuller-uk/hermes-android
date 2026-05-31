package com.hermesandroid.bridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermesandroid.bridge.client.RelayClient
import com.hermesandroid.bridge.ui.VoiceAssistantScreen
import com.hermesandroid.bridge.ui.VoiceViewModel
import com.hermesandroid.bridge.ui.VoiceState

class VoiceActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val viewModel: VoiceViewModel = viewModel()
            viewModel.updateConnection(RelayClient.isConnected)

            // Observe connection status
            DisposableEffect(Unit) {
                val originalCallback = RelayClient.onStatusChanged
                val originalVoiceCallback = RelayClient.onVoiceStateChanged
                RelayClient.onStatusChanged = { connected, _ ->
                    viewModel.updateConnection(connected)
                    if (!connected) {
                        finish()
                    }
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
                onDisconnect = {
                    RelayClient.disconnect()
                    finish()
                }
            )
        }
    }
}
