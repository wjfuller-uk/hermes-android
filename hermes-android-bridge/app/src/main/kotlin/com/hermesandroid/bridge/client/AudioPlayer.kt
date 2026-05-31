package com.hermesandroid.bridge.client

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Plays raw PCM 16-bit 16kHz mono audio through the phone speaker.
 * Used for TTS output from the relay server.
 *
 * Audio format matches VPS pipeline (android_voice.py):
 *   - 16kHz sample rate
 *   - Mono
 *   - 16-bit PCM signed, little-endian
 */
object AudioPlayer {

    private const val TAG = "AudioPlayer"
    private const val SAMPLE_RATE = 16000
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    private var audioTrack: AudioTrack? = null

    @Volatile
    private var isPlaying: Boolean = false

    /**
     * Play a chunk of raw PCM audio. Blocks until playback completes.
     * Thread-safe: can be called from any thread.
     */
    @Synchronized
    fun play(pcmData: ByteArray) {
        try {
            ensureAudioTrack()

            val track = audioTrack ?: return

            track.write(pcmData, 0, pcmData.size)
            if (!isPlaying) {
                track.play()
                isPlaying = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio playback error: ${e.message}")
        }
    }

    /**
     * Stop playback and release resources.
     */
    @Synchronized
    fun stop() {
        try {
            audioTrack?.stop()
            isPlaying = false
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audio: ${e.message}")
        }
    }

    /**
     * Release the AudioTrack completely. Call when disconnecting.
     */
    @Synchronized
    fun release() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            isPlaying = false
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing audio: ${e.message}")
        }
    }

    private fun ensureAudioTrack() {
        if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) return

        // Release old one if it exists
        try { audioTrack?.release() } catch (_: Exception) {}

        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
        )
        // Use a 1-second buffer for smooth playback
        val bufferSize = maxOf(minBufferSize, SAMPLE_RATE * 2)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG)
                    .setEncoding(AUDIO_FORMAT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        isPlaying = false
    }
}
