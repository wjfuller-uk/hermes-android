package com.hermesandroid.bridge.ui

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.hermesandroid.bridge.audio.AudioCapture
import com.hermesandroid.bridge.client.AudioPlayer
import com.hermesandroid.bridge.client.RelayClient
import com.hermesandroid.bridge.util.AppLogger

/** Overlay state machine. */
enum class OverlayState {
    LISTENING,    // Mic active, showing animated bars
    PROCESSING,   // Relay is transcribing/thinking
    RESPONDING,   // Showing response text + cards
    IDLE          // Fading out
}

/**
 * Translucent full-screen overlay for Hermes voice interactions on TV.
 *
 * Launched by [OverlayService] when the wake word is detected.
 * Shows animated listening bars, live transcription, and response cards.
 * Auto-dismisses after idle or on D-pad back press.
 *
 * Design matches Google Assistant's dark translucent overlay with
 * gradient bars at the bottom and centered text.
 */
class HermesOverlayActivity : ComponentActivity() {

    companion object {
        private const val TAG = "HermesOverlay"
        private const val AUTO_DISMISS_MS = 5_000L
    }

    private var currentState = OverlayState.LISTENING
    private val audioCapture = AudioCapture()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null
    private var transcriptionText = mutableStateOf("")
    private var responseText = mutableStateOf("")
    private var responseCards = mutableStateOf<List<DisplayCard>>(emptyList())
    private var relayConnected = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full translucent overlay — dark, over content, no system bars.
        // The Theme.HermesOverlay already has windowIsTranslucent=true, windowFullscreen=true.
        // R+ insetsController can crash on some devices with translucent theme — guard it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                window.setDecorFitsSystemWindows(false)
                val controller = window.insetsController
                controller?.let {
                    it.hide(WindowInsets.Type.systemBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } catch (_: Exception) {
                // Theme handles translucency; this is just the icing
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        relayConnected.value = RelayClient.isConnected
        AppLogger.i(TAG, "Overlay created — state=LISTENING")

        setContent {
            HermesOverlayContent(
                state = currentState,
                transcription = transcriptionText.value,
                response = responseText.value,
                cards = responseCards.value,
                connected = relayConnected.value
            )
        }

        startListening()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_DPAD_CENTER -> {
                dismiss()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onDestroy() {
        cancelDismiss()
        audioCapture.destroy()
        AudioPlayer.release()
        super.onDestroy()
    }

    // ── State transitions ───────────────────────────────────────────────────

    private fun startListening() {
        currentState = OverlayState.LISTENING
        transcriptionText.value = ""
        responseText.value = ""

        // Hook up relay callbacks
        RelayClient.onTranscript = { text, isFinal ->
            mainHandler.post {
                transcriptionText.value = text
                if (isFinal) {
                    currentState = OverlayState.PROCESSING
                    // Re-render composable with new state
                    setContent {
                        HermesOverlayContent(
                            state = currentState,
                            transcription = transcriptionText.value,
                            response = "",
                            cards = emptyList(),
                            connected = true
                        )
                    }
                }
            }
        }

        RelayClient.onChatResponse = { text, cardsJson ->
            mainHandler.post {
                currentState = OverlayState.RESPONDING
                responseText.value = text
                responseCards.value = parseCards(cardsJson)
                scheduleDismiss()
                setContent {
                    HermesOverlayContent(
                        state = currentState,
                        transcription = "",
                        response = text,
                        cards = responseCards.value,
                        connected = true
                    )
                }
            }
        }

        val started = audioCapture.start { error ->
            mainHandler.post {
                transcriptionText.value = "Error: $error"
                scheduleDismiss()
            }
        }

        if (!started) {
            AppLogger.e(TAG, "Audio capture failed to start")
            dismiss()
        }
    }

    private fun parseCards(cardsJson: String): List<DisplayCard> {
        if (cardsJson.isBlank() || cardsJson == "[]") return emptyList()
        return try {
            val gson = com.google.gson.Gson()
            val listType = object : com.google.gson.reflect.TypeToken<List<Map<String, Any?>>>() {}.type
            val rawList: List<Map<String, Any?>> = gson.fromJson(cardsJson, listType)
            rawList.mapNotNull { map ->
                val type = map["type"] as? String ?: return@mapNotNull null
                DisplayCard(
                    type = type,
                    title = map["title"] as? String ?: "",
                    body = map["body"] as? String ?: "",
                    priority = map["priority"] as? String ?: "normal",
                    icon = map["icon"] as? String
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun scheduleDismiss() {
        cancelDismiss()
        dismissRunnable = Runnable { dismiss() }
        mainHandler.postDelayed(dismissRunnable!!, AUTO_DISMISS_MS)
    }

    private fun cancelDismiss() {
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        dismissRunnable = null
    }

    private fun dismiss() {
        cancelDismiss()
        audioCapture.stop()
        currentState = OverlayState.IDLE
        // Resume Vosk wake word detection (it owns the mic)
        try {
            val serviceIntent = android.content.Intent(this, com.hermesandroid.bridge.service.OverlayService::class.java)
            val conn = object : android.content.ServiceConnection {
                override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) {
                    val svc = (binder as? com.hermesandroid.bridge.service.OverlayService.LocalBinder)?.getService()
                    svc?.onResume()
                    unbindService(this)
                }
                override fun onServiceDisconnected(name: android.content.ComponentName?) {}
            }
            bindService(serviceIntent, conn, android.content.Context.BIND_AUTO_CREATE)
        } catch (_: Exception) {
            // Service not running — fine
        }
        finish()
    }
}

/** Data class for cards received from relay. */
data class DisplayCard(
    val type: String,
    val title: String,
    val body: String,
    val priority: String = "normal",
    val icon: String? = null
)
