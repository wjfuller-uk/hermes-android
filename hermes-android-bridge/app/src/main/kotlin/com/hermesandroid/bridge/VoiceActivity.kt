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
import com.hermesandroid.bridge.service.VoiceService
import com.hermesandroid.bridge.ui.VoiceAssistantScreen
import com.hermesandroid.bridge.ui.VoiceViewModel
import com.hermesandroid.bridge.ui.VoiceState
import com.hermesandroid.bridge.util.PermissionHelper

class VoiceActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request all permissions on first launch — voice, camera, notifications, etc.
        PermissionHelper.requestAllPermissions(this)

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
                val originalChatCallback = RelayClient.onChatResponse
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
                RelayClient.onChatResponse = { text ->
                    viewModel.addMessage(text, isUser = false)
                }
                onDispose {
                    RelayClient.onStatusChanged = originalCallback
                    RelayClient.onVoiceStateChanged = originalVoiceCallback
                    RelayClient.onChatResponse = originalChatCallback
                }
            }

            VoiceAssistantScreen(
                viewModel = viewModel,
                onOpenSettings = {
                    startActivity(Intent(this@VoiceActivity, MainActivity::class.java))
                },
                onOpenDiagnostics = {
                    startActivity(Intent(this@VoiceActivity, DiagnosticsActivity::class.java))
                },
                onConnect = { url ->
                    RelayClient.connect(url)
                },
                onDisconnect = {
                    RelayClient.disconnect()
                },
                onSendText = { text ->
                    viewModel.addMessage(text, isUser = true)
                    RelayClient.sendChat(text)
                },
                onStartVoice = {
                    // Start the mic streaming service AND tell the relay
                    val intent = Intent(this@VoiceActivity, VoiceService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        @Suppress("DEPRECATION")
                        startService(intent)
                    }
                    RelayClient.sendCommand("POST", "/voice/start")
                    viewModel.updateState(VoiceState.LISTENING)
                },
                onStopVoice = {
                    // Stop the mic streaming service AND tell the relay
                    stopService(Intent(this@VoiceActivity, VoiceService::class.java))
                    RelayClient.sendCommand("POST", "/voice/stop")
                    viewModel.updateState(VoiceState.IDLE)
                }
            )
        }
    }
}
