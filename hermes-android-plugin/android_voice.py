"""
android_voice — Voice pipeline for always-on Android assistant.

Handles:
  - Audio streaming from phone (binary WebSocket frames, raw PCM 16kHz mono)
  - VAD (Voice Activity Detection) to detect end of speech
  - STT via faster-whisper
  - Hermes dialogue via `hermes -z` one-shot mode
  - TTS via edge-tts (or MiniMax/Xiaomi fallback)
  - Audio streaming back to phone (binary WebSocket frames)

Architecture:
  Phone ──binary audio──> Relay WS ──> VoicePipeline ──> Whisper ──> Hermes
                                                             │
  Phone <──binary audio── Relay WS <── VoicePipeline <── TTS <──┘

State machine: IDLE → LISTENING → PROCESSING → SPEAKING → IDLE
"""

import asyncio
import collections
import io
import json
import logging
import os
import struct
import subprocess
import tempfile
import time
import wave
from dataclasses import dataclass, field
from enum import Enum, auto
from pathlib import Path
from typing import Optional

logger = logging.getLogger("android_voice")


# ── Configuration ────────────────────────────────────────────────────────────

# Audio format: raw PCM 16-bit signed, little-endian, 16kHz, mono
SAMPLE_RATE = 16000
SAMPLE_WIDTH = 2  # bytes
CHANNELS = 1

# VAD settings
VAD_FRAME_MS = 30  # webrtcvad frame size (10/20/30ms)
VAD_MODE = 1       # 0=least aggressive, 3=most aggressive
VAD_FRAME_SAMPLES = int(SAMPLE_RATE * VAD_FRAME_MS / 1000)  # 480 samples
VAD_FRAME_BYTES = VAD_FRAME_SAMPLES * SAMPLE_WIDTH           # 960 bytes

# Speech detection
SPEECH_START_FRAMES = 3    # consecutive speech frames to start
SPEECH_END_FRAMES = 15     # consecutive silence frames to end (450ms)
MIN_SPEECH_DURATION = 0.5  # seconds — discard shorter utterances
MAX_SPEECH_DURATION = 30.0 # seconds — cut off very long utterances
PRE_SPEECH_BUFFER = 0.3    # seconds of audio to keep before speech start
POST_SPEECH_BUFFER = 0.2   # seconds of audio to keep after speech end

# TTS
TTS_PROVIDER = os.getenv("ANDROID_VOICE_TTS", "edge")  # edge, minimax, xiaomi
TTS_VOICE = os.getenv("ANDROID_VOICE_TTS_VOICE", "en-GB-LibbyNeural")

# Hermes
HERMES_TIMEOUT = 60  # seconds for hermes -z to respond


# ── State machine ─────────────────────────────────────────────────────────────

class VoiceState(Enum):
    IDLE = auto()
    LISTENING = auto()
    PROCESSING = auto()
    SPEAKING = auto()


@dataclass
class VoiceSession:
    """Per-session voice state, attached to the relay's phone_ws."""

    state: VoiceState = VoiceState.IDLE
    audio_buffer: bytearray = field(default_factory=bytearray)
    pre_buffer: collections.deque = field(default_factory=lambda: collections.deque(maxlen=50))
    consecutive_speech: int = 0
    consecutive_silence: int = 0
    speech_started: bool = False
    speech_start_time: float = 0.0

    # For sending TTS audio back to phone
    send_binary: Optional[callable] = None

    # Callbacks
    on_transcription: Optional[callable] = None  # (text: str) -> None
    on_state_change: Optional[callable] = None   # (old: VoiceState, new: VoiceState) -> None

    def reset_audio(self) -> None:
        self.audio_buffer = bytearray()
        self.pre_buffer.clear()
        self.consecutive_speech = 0
        self.consecutive_silence = 0
        self.speech_started = False
        self.speech_start_time = 0.0


# ── VAD wrapper ───────────────────────────────────────────────────────────────

class VADDetector:
    """Voice Activity Detection using webrtcvad or energy-based fallback."""

    def __init__(self, mode: int = VAD_MODE):
        self._mode = mode
        self._vad = None
        self._use_energy = False
        try:
            import webrtcvad
            self._vad = webrtcvad.Vad(mode)
        except ImportError:
            logger.warning("webrtcvad not available — using energy-based VAD fallback")
            self._use_energy = True

    def is_speech(self, frame: bytes) -> bool:
        """Check if a raw PCM frame contains speech."""
        if len(frame) != VAD_FRAME_BYTES:
            return False
        if self._vad is not None:
            return self._vad.is_speech(frame, VAD_FRAME_BYTES)
        # Energy-based fallback: compute RMS and compare against threshold
        return self._energy_is_speech(frame)

    def _energy_is_speech(self, frame: bytes) -> bool:
        """Simple energy-based VAD fallback. Less accurate but works everywhere."""
        import struct
        samples = struct.unpack(f"<{len(frame) // 2}h", frame)
        rms = (sum(s * s for s in samples) / len(samples)) ** 0.5
        # Threshold tuned for 16-bit PCM — speech typically > 200 RMS
        return rms > 300


# ── Whisper transcriber ──────────────────────────────────────────────────────

class WhisperTranscriber:
    """Transcribe audio using faster-whisper."""

    def __init__(self, model_size: str = "base"):
        from faster_whisper import WhisperModel
        self._model = WhisperModel(model_size, device="cpu", compute_type="int8")

    def transcribe(self, pcm_data: bytes) -> str:
        """Transcribe raw PCM 16kHz mono audio. Returns text or empty string."""
        if len(pcm_data) < SAMPLE_RATE * 0.3 * SAMPLE_WIDTH:  # < 300ms
            return ""

        # Write to temp WAV file (faster-whisper works well with files)
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
            wav_path = f.name
            self._write_wav(f, pcm_data)

        try:
            segments, _ = self._model.transcribe(wav_path, beam_size=5, language="en")
            text = " ".join(seg.text.strip() for seg in segments)
            return text.strip()
        except Exception:
            logger.exception("Whisper transcription failed")
            return ""
        finally:
            try:
                os.unlink(wav_path)
            except OSError:
                pass

    @staticmethod
    def _write_wav(f, pcm_data: bytes) -> None:
        with wave.open(f, "wb") as w:
            w.setnchannels(CHANNELS)
            w.setsampwidth(SAMPLE_WIDTH)
            w.setframerate(SAMPLE_RATE)
            w.writeframes(pcm_data)


# ── TTS engine ────────────────────────────────────────────────────────────────

class TTSEngine:
    """Text-to-speech using edge-tts or MiniMax/Xiaomi fallback."""

    def __init__(self, provider: str = TTS_PROVIDER, voice: str = TTS_VOICE):
        self.provider = provider
        self.voice = voice

    async def synthesize(self, text: str) -> bytes:
        """Convert text to raw PCM 16kHz mono audio. Returns PCM bytes."""
        if self.provider == "edge":
            return await self._edge_tts(text)
        elif self.provider in ("minimax", "xiaomi"):
            return await self._openai_tts(text, self.provider)
        else:
            logger.warning("Unknown TTS provider '%s', falling back to edge", self.provider)
            return await self._edge_tts(text)

    async def _edge_tts(self, text: str) -> bytes:
        """Use edge-tts to generate speech, convert to 16kHz mono PCM."""
        import edge_tts

        mp3_path = None
        try:
            # Generate MP3
            communicate = edge_tts.Communicate(text, self.voice)
            with tempfile.NamedTemporaryFile(suffix=".mp3", delete=False) as f:
                mp3_path = f.name
            await communicate.save(mp3_path)

            # Convert to 16kHz mono PCM via ffmpeg
            pcm_path = mp3_path + ".pcm"
            proc = await asyncio.create_subprocess_exec(
                "ffmpeg", "-y", "-i", mp3_path,
                "-f", "s16le", "-acodec", "pcm_s16le",
                "-ar", str(SAMPLE_RATE), "-ac", str(CHANNELS),
                pcm_path,
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            )
            await proc.wait()

            with open(pcm_path, "rb") as f:
                return f.read()

        except Exception:
            logger.exception("Edge TTS failed")
            return b""
        finally:
            for p in (mp3_path, mp3_path + ".pcm" if mp3_path else None):
                if p:
                    try:
                        os.unlink(p)
                    except OSError:
                        pass

    async def _openai_tts(self, text: str, provider: str) -> bytes:
        """Use MiniMax or Xiaomi OpenAI-compatible TTS endpoint."""
        import aiohttp

        env_prefix = provider.upper()
        api_key = os.getenv(f"{env_prefix}_API_KEY", "")
        base_url = os.getenv(f"{env_prefix}_BASE_URL", "")

        if not api_key or not base_url:
            logger.warning("%s TTS not configured, falling back to edge", provider)
            return await self._edge_tts(text)

        url = f"{base_url.rstrip('/')}/audio/speech"
        headers = {
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        }
        body = {
            "model": "tts-1",
            "input": text,
            "voice": "alloy",
            "response_format": "pcm",
            "speed": 1.0,
        }

        try:
            async with aiohttp.ClientSession() as session:
                async with session.post(url, json=body, headers=headers, timeout=30) as resp:
                    if resp.status == 200:
                        return await resp.read()
                    else:
                        error_text = await resp.text()
                        logger.warning("%s TTS returned %d: %s", provider, resp.status, error_text[:200])
                        return await self._edge_tts(text)
        except Exception:
            logger.exception("%s TTS request failed", provider)
            return await self._edge_tts(text)


# ── Hermes dialogue ──────────────────────────────────────────────────────────

async def ask_hermes(text: str, timeout: float = HERMES_TIMEOUT) -> str:
    """Send text to Hermes via `hermes -z` one-shot mode. Returns response text."""
    try:
        env = os.environ.copy()
        env["HERMES_INFERENCE_MODEL"] = "mimo-v2.5-pro"
        proc = await asyncio.create_subprocess_exec(
            "hermes", "-z", text,
            "--accept-hooks",
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            env=env,
        )
        stdout, stderr = await asyncio.wait_for(proc.communicate(), timeout=timeout)
        if proc.returncode != 0:
            logger.warning("hermes -z exited with code %d: %s", proc.returncode, stderr.decode()[:200])
        return stdout.decode("utf-8", errors="replace").strip()
    except asyncio.TimeoutError:
        logger.warning("Hermes timed out after %ds", timeout)
        return "Sorry, I'm thinking too slowly right now. Try again?"
    except Exception:
        logger.exception("Hermes call failed")
        return "Sorry, something went wrong. Try again?"


# ── Voice pipeline ────────────────────────────────────────────────────────────

class VoicePipeline:
    """
    Main voice pipeline that bridges phone audio ↔ Hermes.

    Usage:
        pipeline = VoicePipeline()
        # When phone sends audio:
        await pipeline.feed_audio(session, pcm_chunk)
        # session.send_binary is called automatically with TTS output
    """

    def __init__(
        self,
        whisper_model: str = "base",
        tts_provider: str = TTS_PROVIDER,
        tts_voice: str = TTS_VOICE,
    ):
        self.transcriber = WhisperTranscriber(whisper_model)
        self.tts = TTSEngine(tts_provider, tts_voice)
        self.vad = VADDetector()

    def create_session(self, send_binary: callable) -> VoiceSession:
        """Create a new voice session wired to a WebSocket."""
        return VoiceSession(send_binary=send_binary)

    async def feed_audio(self, session: VoiceSession, pcm_chunk: bytes) -> None:
        """Feed a chunk of raw PCM audio from the phone. Drives the state machine."""

        # Accumulate
        session.audio_buffer.extend(pcm_chunk)
        session.pre_buffer.append(pcm_chunk)

        # VAD: process in VAD_FRAME_BYTES chunks
        buffer_view = memoryview(session.audio_buffer)
        offset = 0
        while offset + VAD_FRAME_BYTES <= len(buffer_view):
            frame = bytes(buffer_view[offset:offset + VAD_FRAME_BYTES])
            has_speech = self.vad.is_speech(frame)
            offset += VAD_FRAME_BYTES

            if has_speech:
                session.consecutive_speech += 1
                session.consecutive_silence = 0
            else:
                session.consecutive_silence += 1
                session.consecutive_speech = 0

            # Speech start
            if not session.speech_started and session.consecutive_speech >= SPEECH_START_FRAMES:
                session.speech_started = True
                session.speech_start_time = time.time()
                logger.debug("Speech started")

            # Speech end (silence after speech started)
            if session.speech_started and session.consecutive_silence >= SPEECH_END_FRAMES:
                await self._handle_speech_end(session)
                return

        # Check max duration
        if session.speech_started:
            elapsed = time.time() - session.speech_start_time
            if elapsed > MAX_SPEECH_DURATION:
                logger.debug("Speech max duration reached (%.1fs)", elapsed)
                await self._handle_speech_end(session)
                return

        # Trim processed audio from buffer (keep unprocessed tail)
        if offset > 0:
            session.audio_buffer = session.audio_buffer[offset:]

    async def _handle_speech_end(self, session: VoiceSession) -> None:
        """Speech ended — transcribe, ask Hermes, speak response."""
        duration = time.time() - session.speech_start_time if session.speech_started else 0

        # Too short — discard
        if duration < MIN_SPEECH_DURATION:
            logger.debug("Utterance too short (%.2fs), discarding", duration)
            session.reset_audio()
            session.state = VoiceState.IDLE
            return

        logger.info("Speech ended — duration %.1fs", duration)

        # Capture audio
        audio = bytes(session.audio_buffer)
        session.reset_audio()

        # Transcribe
        session.state = VoiceState.PROCESSING
        text = self.transcriber.transcribe(audio)
        logger.info("Transcribed: %s", text)

        if not text:
            session.state = VoiceState.IDLE
            return

        # Ask Hermes
        response = await ask_hermes(text)
        logger.info("Hermes: %s", response[:100])

        if not response:
            session.state = VoiceState.IDLE
            return

        # TTS
        session.state = VoiceState.SPEAKING
        pcm_audio = await self.tts.synthesize(response)

        if pcm_audio and session.send_binary:
            # Send TTS audio back to phone in chunks
            # (phone expects raw PCM and plays it)
            CHUNK_SIZE = SAMPLE_RATE * SAMPLE_WIDTH * 2  # 2 seconds per chunk
            for i in range(0, len(pcm_audio), CHUNK_SIZE):
                chunk = pcm_audio[i:i + CHUNK_SIZE]
                try:
                    await session.send_binary(chunk)
                except Exception:
                    logger.exception("Failed to send TTS audio chunk")
                    break

        session.state = VoiceState.IDLE

    async def force_stop(self, session: VoiceSession) -> None:
        """Force-stop any active listening/processing/speaking."""
        session.reset_audio()
        session.state = VoiceState.IDLE


# ── Singleton ─────────────────────────────────────────────────────────────────

_pipeline: Optional[VoicePipeline] = None


def get_pipeline() -> VoicePipeline:
    global _pipeline
    if _pipeline is None:
        _pipeline = VoicePipeline()
    return _pipeline
