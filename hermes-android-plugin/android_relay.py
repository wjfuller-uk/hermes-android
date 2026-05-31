"""
android_relay — WebSocket relay that bridges HTTP tool calls to Android phones.

Multi-device architecture: supports multiple phones connecting simultaneously.
Each phone is identified by a device_id (sent as ?device_id= on the WS URL).
Tool calls are routed to a specific device via ?device=<id> query param.

Flow:
  1. Phone connects via WebSocket with ?token=<pairing_code>&device_id=<id>
  2. Python tool makes an HTTP request to e.g. /screen?device=<id>
  3. Relay wraps request as JSON command, sends over WS to the right phone
  4. Phone executes command, sends JSON response back over WS
  5. Relay returns the phone's response to the HTTP caller

Command JSON format:
  Relay -> Phone:  {"request_id": "uuid", "method": "GET|POST", "path": "/screen",
                    "params": {...}, "body": {...}}
  Phone -> Relay:  {"request_id": "uuid", "result": {...}, "status": 200}
"""

import asyncio
import hmac
import json
import logging
import os
import threading
import time
import uuid
from dataclasses import dataclass, field
from typing import Optional

import aiohttp
from aiohttp import web

from . import android_voice

logger = logging.getLogger("android_relay")

# ── Module-level state ────────────────────────────────────────────────────────

_relay_lock = threading.Lock()
_relay_instance: Optional["_RelayState"] = None


@dataclass
class DeviceConnection:
    """State for a single connected phone."""
    device_id: str
    ws: web.WebSocketResponse
    pending: dict = field(default_factory=dict)  # request_id -> Future
    voice_session: Optional["android_voice.VoiceSession"] = None
    connected_at: float = field(default_factory=time.time)
    remote_ip: str = ""
    device_info: dict = field(default_factory=dict)  # model, brand, etc.


class _RelayState:
    """Holds all mutable state for one running relay instance."""

    def __init__(self, pairing_code: str, port: int):
        self.pairing_code: str = pairing_code
        self.port: int = port

        # asyncio loop running in the background thread
        self.loop: Optional[asyncio.AbstractEventLoop] = None
        self.thread: Optional[threading.Thread] = None

        # aiohttp plumbing
        self.app: Optional[web.Application] = None
        self.runner: Optional[web.AppRunner] = None
        self.site: Optional[web.TCPSite] = None

        # Multi-device registry: device_id -> DeviceConnection
        self.devices: dict[str, DeviceConnection] = {}
        self.devices_lock: Optional[asyncio.Lock] = None  # created lazily

        # Backward-compat: "default" device for single-device callers
        self.default_device_id: Optional[str] = None

        # Pending requests lock (shared, per-device pending dicts)
        self.pending_lock: Optional[asyncio.Lock] = None

        # Shutdown event
        self.shutdown_event: Optional[asyncio.Event] = None

        # Voice pipeline (shared, lazily initialized)
        self.voice_pipeline: Optional["android_voice.VoicePipeline"] = None


# ── Public API (called from sync code) ────────────────────────────────────────


def start_relay(pairing_code: str, port: int = 0) -> None:
    """Start the relay in a background thread.  No-op if already running."""
    global _relay_instance
    if port == 0:
        port = int(os.getenv("ANDROID_RELAY_PORT", "8766"))

    with _relay_lock:
        if (
            _relay_instance is not None
            and _relay_instance.thread is not None
            and _relay_instance.thread.is_alive()
        ):
            logger.info("Relay already running on port %d", _relay_instance.port)
            return

        state = _RelayState(pairing_code, port)
        _relay_instance = state

        ready = threading.Event()
        t = threading.Thread(
            target=_run_loop, args=(state, ready), daemon=True, name="android-relay"
        )
        state.thread = t
        t.start()
        if not ready.wait(timeout=10):
            logger.error("Relay failed to start within 10 seconds")
            raise RuntimeError("Relay failed to start")
        logger.info("Relay started on port %d", port)


def stop_relay() -> None:
    """Gracefully stop the relay."""
    global _relay_instance
    with _relay_lock:
        state = _relay_instance
        if state is None:
            return
        _relay_instance = None

    if state.loop is not None and state.shutdown_event is not None:
        state.loop.call_soon_threadsafe(state.shutdown_event.set)
    if state.thread is not None:
        state.thread.join(timeout=5)
    logger.info("Relay stopped")


def is_relay_running() -> bool:
    with _relay_lock:
        s = _relay_instance
        return s is not None and s.thread is not None and s.thread.is_alive()


def is_phone_connected() -> bool:
    with _relay_lock:
        s = _relay_instance
        if s is None:
            return False
        return len(s.devices) > 0


def get_connected_devices() -> list[dict]:
    """Return info about all connected devices."""
    with _relay_lock:
        s = _relay_instance
        if s is None:
            return []
        return [
            {
                "device_id": d.device_id,
                "remote_ip": d.remote_ip,
                "connected_at": d.connected_at,
                "device_info": d.device_info,
                "voice_active": d.voice_session is not None,
            }
            for d in s.devices.values()
        ]


def get_relay_url() -> str:
    with _relay_lock:
        s = _relay_instance
        port = s.port if s else int(os.getenv("ANDROID_RELAY_PORT", "8766"))
    return f"http://localhost:{port}"


def set_pairing_code(code: str) -> None:
    """Update the pairing code (e.g. when user reconnects with new code)."""
    with _relay_lock:
        s = _relay_instance
        if s is not None:
            s.pairing_code = code
            logger.info("Pairing code updated")


# ── Background event-loop entry point ─────────────────────────────────────────


def _run_loop(state: _RelayState, ready: threading.Event) -> None:
    """Runs in the background thread — creates an event loop and serves."""
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    state.loop = loop
    state.devices_lock = asyncio.Lock()
    state.pending_lock = asyncio.Lock()
    state.shutdown_event = asyncio.Event()

    try:
        loop.run_until_complete(_serve(state, ready))
    except Exception:
        logger.exception("Relay event loop crashed")
    finally:
        loop.run_until_complete(loop.shutdown_asyncgens())
        loop.close()


def _ssl_context():
    """Build SSL context from env vars if configured."""
    import ssl as _ssl

    cert_path = os.getenv("ANDROID_RELAY_CERT")
    key_path = os.getenv("ANDROID_RELAY_KEY")

    if not cert_path or not key_path:
        return None

    ctx = _ssl.SSLContext(_ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(cert_path, key_path)
    return ctx


async def _serve(state: _RelayState, ready: threading.Event) -> None:
    """Build the aiohttp app, start the site, and block until shutdown."""
    app = web.Application(client_max_size=10 * 1024 * 1024)  # 10MB max request
    state.app = app

    # WebSocket endpoint
    app.router.add_get("/ws", lambda req: _handle_ws(req, state))

    # Device management
    app.router.add_get("/devices", lambda req: _handle_devices(req, state))

    # HTTP bridge endpoints — method per path
    ROUTES = {
        "/ping": "GET", "/screen": "GET", "/screenshot": "GET",
        "/apps": "GET", "/current_app": "GET", "/notifications": "GET",
        "/contacts": "GET", "/events": "GET", "/screen_hash": "GET",
        "/location": "GET", "/widgets": "GET",
        "/tap": "POST", "/tap_text": "POST", "/type": "POST",
        "/swipe": "POST", "/open_app": "POST", "/press_key": "POST",
        "/scroll": "POST", "/wait": "POST", "/long_press": "POST",
        "/drag": "POST", "/describe_node": "POST", "/find_nodes": "POST",
        "/diff_screen": "POST", "/pinch": "POST", "/send_sms": "POST",
        "/call": "POST", "/media": "POST", "/intent": "POST",
        "/broadcast": "POST", "/speak": "POST", "/stop_speaking": "POST",
        "/screen_record": "POST", "/events/stream": "POST",
        "/voice/start": "POST", "/voice/stop": "POST",
        "/camera": "GET", "/shell": "POST",
        "/clipboard": "BOTH",
    }

    for path, method in ROUTES.items():
        handler = lambda req, p=path: _handle_http(req, state, p)
        if method in ("GET", "BOTH"):
            app.router.add_get(path, handler)
        if method in ("POST", "BOTH"):
            app.router.add_post(path, handler)

    runner = web.AppRunner(app)
    state.runner = runner
    await runner.setup()

    ssl_ctx = _ssl_context()
    site = web.TCPSite(runner, "0.0.0.0", state.port, ssl_context=ssl_ctx)
    state.site = site
    await site.start()

    scheme = "https" if ssl_ctx else "http"
    logger.info("Relay listening on %s://0.0.0.0:%d", scheme, state.port)

    if not ssl_ctx:
        logger.warning(
            "TLS not configured — set ANDROID_RELAY_CERT and ANDROID_RELAY_KEY for wss://"
        )
    ready.set()

    await state.shutdown_event.wait()

    # Cleanup all devices
    async with state.devices_lock:
        for dev in list(state.devices.values()):
            if dev.voice_session is not None:
                pipeline = android_voice.get_pipeline()
                await pipeline.force_stop(dev.voice_session)
            if not dev.ws.closed:
                await dev.ws.close()
        state.devices.clear()
    await runner.cleanup()
    logger.info("Relay server cleaned up")


# ── Rate limiting ────────────────────────────────────────────────────────────

_AUTH_MAX_ATTEMPTS = 5
_AUTH_WINDOW_SECONDS = 60
_AUTH_BLOCK_SECONDS = 300
_AUTH_CLEANUP_INTERVAL = 120

_auth_failures: dict[str, list[float]] = {}
_auth_blocked: dict[str, float] = {}
_auth_lock = threading.Lock()
_auth_last_cleanup: float = 0.0


def _auth_cleanup() -> None:
    global _auth_last_cleanup
    now = time.monotonic()
    if now - _auth_last_cleanup < _AUTH_CLEANUP_INTERVAL:
        return
    _auth_last_cleanup = now
    expired = [ip for ip, until in _auth_blocked.items() if now >= until]
    for ip in expired:
        del _auth_blocked[ip]
    cutoff = now - _AUTH_WINDOW_SECONDS
    stale = []
    for ip, timestamps in _auth_failures.items():
        _auth_failures[ip] = [t for t in timestamps if t > cutoff]
        if not _auth_failures[ip]:
            stale.append(ip)
    for ip in stale:
        del _auth_failures[ip]


def _auth_is_blocked(ip: str) -> bool:
    now = time.monotonic()
    with _auth_lock:
        _auth_cleanup()
        until = _auth_blocked.get(ip)
        if until is not None:
            if now < until:
                return True
            del _auth_blocked[ip]
    return False


def _auth_record_failure(ip: str) -> None:
    now = time.monotonic()
    with _auth_lock:
        timestamps = _auth_failures.setdefault(ip, [])
        timestamps.append(now)
        cutoff = now - _AUTH_WINDOW_SECONDS
        _auth_failures[ip] = [t for t in timestamps if t > cutoff]
        if len(_auth_failures[ip]) >= _AUTH_MAX_ATTEMPTS:
            _auth_blocked[ip] = now + _AUTH_BLOCK_SECONDS
            _auth_failures.pop(ip, None)
            logger.warning("IP %s blocked for %ds after %d failed auth attempts",
                           ip, _AUTH_BLOCK_SECONDS, _AUTH_MAX_ATTEMPTS)


def _mask_token(token: str) -> str:
    return (token[:2] + "****") if len(token) >= 2 else "****"


# ── WebSocket handler (phone side) ───────────────────────────────────────────


async def _handle_ws(request: web.Request, state: _RelayState) -> web.WebSocketResponse:
    remote_ip = request.remote or "unknown"
    if _auth_is_blocked(remote_ip):
        raise web.HTTPTooManyRequests(text="Too many failed attempts. Try again later.")

    token = request.query.get("token", "")
    if not hmac.compare_digest(token.upper(), state.pairing_code.upper()):
        _auth_record_failure(remote_ip)
        logger.warning("WS rejected — bad token from %s", remote_ip)
        raise web.HTTPForbidden(text="Invalid pairing code")

    # Device identification
    device_id = request.query.get("device_id", "").strip()
    device_model = request.query.get("model", "unknown")
    device_brand = request.query.get("brand", "")

    if not device_id:
        # Auto-generate from IP + timestamp for backward compat
        device_id = f"device-{remote_ip.replace('.', '-')}-{int(time.time()) % 10000}"

    ws = web.WebSocketResponse(heartbeat=30.0)
    await ws.prepare(request)

    # Register device
    async with state.devices_lock:
        # Kick existing connection for same device_id
        if device_id in state.devices:
            old = state.devices[device_id]
            logger.info("Replacing connection for device %s", device_id)
            if not old.ws.closed:
                await old.ws.close(code=aiohttp.WSCloseCode.GOING_AWAY, message=b"replaced")

        dev = DeviceConnection(
            device_id=device_id,
            ws=ws,
            remote_ip=remote_ip,
            device_info={"model": device_model, "brand": device_brand},
        )
        state.devices[device_id] = dev

        # Set default device if first/only
        if state.default_device_id is None or len(state.devices) == 1:
            state.default_device_id = device_id

    logger.info("Device connected: %s from %s (%s %s)",
                device_id, remote_ip, device_brand, device_model)

    try:
        async for msg in ws:
            if msg.type == aiohttp.WSMsgType.TEXT:
                await _on_device_message(state, device_id, msg.data)
            elif msg.type == aiohttp.WSMsgType.BINARY:
                await _on_device_binary(state, device_id, msg.data)
            elif msg.type == aiohttp.WSMsgType.ERROR:
                logger.error("Device %s WS error: %s", device_id, ws.exception())
                break
            elif msg.type in (aiohttp.WSMsgType.CLOSE, aiohttp.WSMsgType.CLOSING, aiohttp.WSMsgType.CLOSED):
                logger.info("Device %s WS closed: code=%s", device_id, ws.close_code)
                break
        logger.info("Device %s WS loop exited (close_code=%s)", device_id, ws.close_code)
    except Exception as e:
        logger.exception("Device %s WS exception: %s", device_id, e)
    finally:
        await _cleanup_device(state, device_id, reason="disconnected")

    return ws


async def _on_device_message(state: _RelayState, device_id: str, raw: str) -> None:
    """Route an incoming message from a device to the matching pending future."""
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        logger.warning("Non-JSON from device %s: %s", device_id, raw[:200])
        return

    request_id = data.get("request_id")
    if not request_id:
        logger.warning("Device %s message missing request_id: %s", device_id, raw[:200])
        return

    dev = state.devices.get(device_id)
    if dev is None:
        return

    async with state.pending_lock:
        future = dev.pending.pop(request_id, None)

    if future is None:
        logger.debug("No pending future for %s from device %s", request_id, device_id)
        return

    if not future.done():
        future.set_result(data)


async def _on_device_binary(state: _RelayState, device_id: str, data: bytes) -> None:
    """Route binary audio data from device to the voice pipeline."""
    dev = state.devices.get(device_id)
    if dev is None:
        return

    if dev.voice_session is None or dev.voice_session.state == android_voice.VoiceState.IDLE:
        return

    if state.voice_pipeline is None:
        state.voice_pipeline = android_voice.get_pipeline()

    try:
        await state.voice_pipeline.feed_audio(dev.voice_session, data)
    except Exception:
        logger.exception("Voice pipeline error for device %s", device_id)


async def _cleanup_device(state: _RelayState, device_id: str, reason: str = "") -> None:
    """Clean up a specific device connection."""
    async with state.devices_lock:
        dev = state.devices.pop(device_id, None)
        if dev is None:
            return

        # Stop voice session if active
        if dev.voice_session is not None:
            pipeline = android_voice.get_pipeline()
            await pipeline.force_stop(dev.voice_session)
            dev.voice_session = None

        # Update default device
        if state.default_device_id == device_id:
            state.default_device_id = next(iter(state.devices), None)

    if dev.ws is not None and not dev.ws.closed:
        await dev.ws.close()

    # Fail pending futures for this device
    async with state.pending_lock:
        pending = dict(dev.pending)
        dev.pending.clear()

    for rid, fut in pending.items():
        if not fut.done():
            fut.set_exception(ConnectionError(f"Device {device_id} disconnected ({reason})"))

    if pending:
        logger.info("Cancelled %d pending requests for device %s (%s)", len(pending), device_id, reason)

    logger.info("Device %s cleaned up (%s)", device_id, reason)


# ── Device routing ────────────────────────────────────────────────────────────


def _resolve_device(state: _RelayState, request: web.Request) -> Optional[DeviceConnection]:
    """Resolve which device to route to. Returns None if not found."""
    device_id = request.query.get("device", "").strip()

    if device_id:
        return state.devices.get(device_id)

    # No device specified — use default
    if state.default_device_id:
        return state.devices.get(state.default_device_id)

    # No default — use the only connected device
    if len(state.devices) == 1:
        return next(iter(state.devices.values()))

    return None


# ── Device list endpoint ─────────────────────────────────────────────────────


async def _handle_devices(request: web.Request, state: _RelayState) -> web.Response:
    """List all connected devices. Auth required."""
    remote_ip = request.remote or "unknown"
    if _auth_is_blocked(remote_ip):
        return web.json_response({"error": "Rate limited"}, status=429)

    auth_header = request.headers.get("Authorization", "")
    token = auth_header.removeprefix("Bearer ").strip() if auth_header.startswith("Bearer ") else ""
    if not hmac.compare_digest(token.upper(), state.pairing_code.upper()):
        return web.json_response({"error": "Unauthorized"}, status=401)

    devices = []
    for dev in state.devices.values():
        devices.append({
            "device_id": dev.device_id,
            "model": dev.device_info.get("model", "unknown"),
            "brand": dev.device_info.get("brand", ""),
            "remote_ip": dev.remote_ip,
            "connected_at": dev.connected_at,
            "voice_active": dev.voice_session is not None,
            "is_default": dev.device_id == state.default_device_id,
        })

    return web.json_response({
        "devices": devices,
        "default_device": state.default_device_id,
        "count": len(devices),
    })


# ── HTTP handler (tool side) ─────────────────────────────────────────────────

_RESPONSE_TIMEOUT = 30


async def _handle_http(
    request: web.Request, state: _RelayState, path: str
) -> web.Response:
    """Forward an HTTP request from a tool to the appropriate phone."""
    remote_ip = request.remote or "unknown"
    if _auth_is_blocked(remote_ip):
        return web.json_response({"error": "Rate limited"}, status=429)

    auth_header = request.headers.get("Authorization", "")
    token = auth_header.removeprefix("Bearer ").strip() if auth_header.startswith("Bearer ") else ""
    if not hmac.compare_digest(token.upper(), state.pairing_code.upper()):
        _auth_record_failure(remote_ip)
        return web.json_response({"error": "Unauthorized"}, status=401)

    # Voice control routes (handled locally)
    if path == "/voice/start":
        return await _handle_voice_start(state, request)
    if path == "/voice/stop":
        return await _handle_voice_stop(state, request)

    # Ping returns device info
    if path == "/ping":
        dev = _resolve_device(state, request)
        if dev is None:
            return web.json_response({
                "error": "No phone connected. Open the Hermes app on your phone and connect.",
                "devices": [{"device_id": d.device_id, "model": d.device_info.get("model")}
                            for d in state.devices.values()],
            }, status=503)
        return web.json_response({
            "status": "ok",
            "phone_connected": True,
            "device_id": dev.device_id,
            "model": dev.device_info.get("model", "unknown"),
            "devices_count": len(state.devices),
        })

    # Resolve target device
    dev = _resolve_device(state, request)
    if dev is None:
        available = [{"device_id": d.device_id, "model": d.device_info.get("model")}
                     for d in state.devices.values()]
        if len(state.devices) > 1:
            return web.json_response({
                "error": f"Multiple devices connected. Specify ?device=<id>.",
                "available_devices": available,
            }, status=409)
        return web.json_response({
            "error": "No phone connected. Open the Hermes app on your phone and connect.",
        }, status=503)

    # Build command envelope
    request_id = str(uuid.uuid4())
    method = request.method
    params = dict(request.query)
    params.pop("device", None)  # Don't forward the routing param

    body = {}
    if method == "POST":
        try:
            body = await request.json()
        except Exception:
            body = {}

    command = {
        "request_id": request_id,
        "method": method,
        "path": path,
        "params": params,
        "body": body,
    }
    logger.info(">>> [%s] %s %s", dev.device_id, method, path)

    # Register future before sending
    future = state.loop.create_future()
    async with state.pending_lock:
        dev.pending[request_id] = future

    try:
        await dev.ws.send_json(command)
    except Exception as exc:
        async with state.pending_lock:
            dev.pending.pop(request_id, None)
        logger.error("Failed to send to device %s: %s", dev.device_id, exc)
        return web.json_response({"error": f"Failed to send to phone: {exc}"}, status=502)

    try:
        response_data = await asyncio.wait_for(future, timeout=_RESPONSE_TIMEOUT)
    except asyncio.TimeoutError:
        async with state.pending_lock:
            dev.pending.pop(request_id, None)
        logger.warning("Device %s timeout for %s %s", dev.device_id, method, path)
        return web.json_response({"error": f"Phone did not respond within {_RESPONSE_TIMEOUT}s"}, status=504)
    except ConnectionError as exc:
        return web.json_response({"error": str(exc)}, status=502)

    status = response_data.get("status", 200)
    result = response_data.get("result", {})
    return web.json_response(result, status=status)


# ── Voice control handlers ────────────────────────────────────────────────────


async def _handle_voice_start(state: _RelayState, request: web.Request) -> web.Response:
    """Activate voice listening mode on a specific device."""
    dev = _resolve_device(state, request)
    if dev is None:
        return web.json_response({"error": "No phone connected"}, status=503)

    if dev.voice_session is not None:
        return web.json_response({"error": "Voice already active on this device"}, status=409)

    ws = dev.ws

    async def send_binary(data: bytes) -> None:
        if ws and not ws.closed:
            await ws.send_bytes(data)

    pipeline = android_voice.get_pipeline()
    dev.voice_session = pipeline.create_session(send_binary)

    logger.info("Voice mode activated on device %s", dev.device_id)
    return web.json_response({"status": "ok", "voice_active": True, "device_id": dev.device_id})


async def _handle_voice_stop(state: _RelayState, request: web.Request) -> web.Response:
    """Deactivate voice listening mode on a specific device."""
    dev = _resolve_device(state, request)
    if dev is None:
        return web.json_response({"error": "No phone connected"}, status=503)

    if dev.voice_session is not None:
        pipeline = android_voice.get_pipeline()
        await pipeline.force_stop(dev.voice_session)
        dev.voice_session = None

    logger.info("Voice mode deactivated on device %s", dev.device_id)
    return web.json_response({"status": "ok", "voice_active": False, "device_id": dev.device_id})
