package com.hermesandroid.bridge

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermesandroid.bridge.auth.PairingManager
import com.hermesandroid.bridge.client.RelayClient
import com.hermesandroid.bridge.service.VoiceService
import com.hermesandroid.bridge.widgets.CardData
import com.hermesandroid.bridge.ui.VoiceAssistantScreen
import com.hermesandroid.bridge.ui.VoiceViewModel
import com.hermesandroid.bridge.ui.VoiceState
import com.hermesandroid.bridge.util.PermissionHelper
import java.util.*

class VoiceActivity : ComponentActivity() {

    private var tts: TextToSpeech? = null
    private var isVoiceMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request all permissions on first launch — voice, camera, notifications, etc.
        PermissionHelper.requestAllPermissions(this)

        // Initialize TTS for speaking Hermes responses
        initTts()

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
                val originalStatusCallback = RelayClient.onStatusChanged
                val originalVoiceCallback = RelayClient.onVoiceStateChanged
                val originalChatCallback = RelayClient.onChatResponse
                val originalTranscriptCallback = RelayClient.onTranscript
                val originalVoiceStatusCallback = RelayClient.onVoiceStatus

                RelayClient.onStatusChanged = { connected, message ->
                    viewModel.updateConnection(connected)
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
                RelayClient.onChatResponse = { text, cardsJson ->
                    viewModel.addMessage(text, isUser = false)
                    // Parse cards from relay response
                    try {
                        val gson = com.google.gson.Gson()
                        val listType = object : com.google.gson.reflect.TypeToken<List<Map<String, Any?>>>() {}.type
                        val cardsList: List<Map<String, Any?>> = gson.fromJson(cardsJson, listType)
                        if (cardsList.isNotEmpty()) {
                            val cards = cardsList.mapNotNull { cardMap ->
                                val type = cardMap["type"] as? String ?: return@mapNotNull null
                                CardData(type = type, data = cardMap["data"])
                            }
                            viewModel.addCards(cards)
                        }
                    } catch (_: Exception) { }
                    // Speak Hermes response when in voice mode
                    if (isVoiceMode) {
                        speak(text)
                        isVoiceMode = false  // One-shot: reset after speaking
                    }
                    // Return to idle after response
                    viewModel.updateState(VoiceState.IDLE)
                }
                RelayClient.onTranscript = { text, isFinal ->
                    viewModel.updateTranscript(text, isFinal)
                }
                RelayClient.onVoiceStatus = { message, isError ->
                    if (isError) {
                        android.widget.Toast.makeText(this@VoiceActivity, message, android.widget.Toast.LENGTH_LONG).show()
                    }
                    // Non-error status messages just flow to the status footer
                    viewModel.voiceStatusMessage = message
                }
                onDispose {
                    RelayClient.onStatusChanged = originalStatusCallback
                    RelayClient.onVoiceStateChanged = originalVoiceCallback
                    RelayClient.onChatResponse = originalChatCallback
                    RelayClient.onTranscript = originalTranscriptCallback
                    RelayClient.onVoiceStatus = originalVoiceStatusCallback
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
                    isVoiceMode = true
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
                    isVoiceMode = false
                    stopService(Intent(this@VoiceActivity, VoiceService::class.java))
                    RelayClient.sendCommand("POST", "/voice/stop")
                    viewModel.updateState(VoiceState.IDLE)
                    viewModel.voiceStatusMessage = ""
                }
            )
        }
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.UK
                tts?.setSpeechRate(1.0f)
            }
        }
    }

    private fun speak(text: String) {
        tts?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                it.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hermes_tts_${System.currentTimeMillis()}")
            } else {
                @Suppress("DEPRECATION")
                it.speak(text, TextToSpeech.QUEUE_FLUSH, null)
            }
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
