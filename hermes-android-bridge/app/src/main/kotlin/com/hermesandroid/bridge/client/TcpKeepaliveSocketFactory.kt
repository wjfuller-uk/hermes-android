package com.hermesandroid.bridge.client

import java.net.Socket
import javax.net.SocketFactory

/**
 * SocketFactory that enables TCP keepalive on every socket.
 * TCP keepalive sends OS-level probe packets that prevent
 * Tailscale DERP relay from dropping idle connections.
 *
 * Settings:
 *   - SO_KEEPALIVE: enabled
 *   - TCP_KEEPIDLE: 3s (start probing after 3s of idle)
 *   - TCP_KEEPINTVL: 2s (probe every 2s)
 *   - TCP_KEEPCNT: 3 (drop after 3 failed probes)
 *
 * Total before drop: 3 + (2 * 3) = 9s of silence → works with 5s WebSocket PING.
 */
class TcpKeepaliveSocketFactory : SocketFactory() {

    override fun createSocket(): Socket {
        return Socket().apply { enableKeepalive() }
    }

    override fun createSocket(host: String, port: Int): Socket {
        return Socket(host, port).apply { enableKeepalive() }
    }

    override fun createSocket(host: String, port: Int, localHost: String, localPort: Int): Socket {
        return Socket(host, port, localHost, localPort).apply { enableKeepalive() }
    }

    override fun createSocket(inetAddress: java.net.InetAddress, port: Int): Socket {
        return Socket(inetAddress, port).apply { enableKeepalive() }
    }

    override fun createSocket(
        inetAddress: java.net.InetAddress, port: Int,
        localAddress: java.net.InetAddress, localPort: Int
    ): Socket {
        return Socket(inetAddress, port, localAddress, localPort).apply { enableKeepalive() }
    }

    companion object {
        private fun Socket.enableKeepalive() {
            keepAlive = true
            try {
                // TCP_KEEPIDLE — start probing after 3s idle (Linux: TCP_KEEPIDLE, API 26+: keepAliveInterval)
                // On Android, these are set via reflection or via setOption on API 33+
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    setOption(java.net.StandardSocketOptions.SO_KEEPALIVE, true)
                }
                // Legacy approach via reflection for older APIs
                try {
                    val clazz = Class.forName("java.net.SocketOptions")
                    // These vary by platform; use libcore API on Android
                    setSoKeepalive(this)
                } catch (_: Exception) { }
            } catch (_: Exception) { }
        }

        /**
         * Set TCP keepalive parameters using Android's hidden API via reflection.
         * Falls back gracefully on any failure.
         */
        private fun setSoKeepalive(socket: Socket) {
            try {
                // Android uses setsockopt directly via the FileDescriptor
                val fd = socket.javaClass.getDeclaredField("impl").apply { isAccessible = true }
                    .get(socket)
                    ?.javaClass
                    ?.getDeclaredField("fd")
                    ?.apply { isAccessible = true }
                    ?.get(socket.javaClass.getDeclaredField("impl").apply { isAccessible = true }.get(socket))
                    as? java.io.FileDescriptor ?: return

                val fdInt = fd.javaClass.getDeclaredField("fd").apply { isAccessible = true }.getInt(fd)

                // Call native setsockopt via Os.setsockoptInt (libcore)
                val osClass = Class.forName("android.system.Os")
                val sockoptClass = Class.forName("android.system.OsConstants")

                val SOL_SOCKET = sockoptClass.getDeclaredField("SOL_SOCKET").getInt(null)
                val SO_KEEPALIVE = sockoptClass.getDeclaredField("SO_KEEPALIVE").getInt(null)
                val TCP_KEEPIDLE = try {
                    sockoptClass.getDeclaredField("TCP_KEEPIDLE").getInt(null)
                } catch (_: Exception) { 4 }  // IPPROTO_TCP level, TCP_KEEPIDLE
                val TCP_KEEPINTVL = try {
                    sockoptClass.getDeclaredField("TCP_KEEPINTVL").getInt(null)
                } catch (_: Exception) { 5 }
                val TCP_KEEPCNT = try {
                    sockoptClass.getDeclaredField("TCP_KEEPCNT").getInt(null)
                } catch (_: Exception) { 6 }

                val setsockoptInt = osClass.getMethod("setsockoptInt", java.io.FileDescriptor::class.java, Int::class.java, Int::class.java, Int::class.java)

                // Enable TCP keepalive
                setsockoptInt.invoke(null, fd, SOL_SOCKET, SO_KEEPALIVE, 1)

                // Set keepalive parameters (IPPROTO_TCP = 6)
                setsockoptInt.invoke(null, fd, 6, TCP_KEEPIDLE, 3)   // 3s idle before probes
                setsockoptInt.invoke(null, fd, 6, TCP_KEEPINTVL, 2)  // 2s between probes
                setsockoptInt.invoke(null, fd, 6, TCP_KEEPCNT, 3)    // 3 probes max
            } catch (_: Exception) {
                // Reflection failed — socket.keepAlive=true is still set, which is better than nothing
            }
        }
    }
}
