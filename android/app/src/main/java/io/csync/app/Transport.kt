package io.csync.app

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Link(private val tls: TlsStreams, val label: String) : Closeable {
    val peerPin: String get() = tls.peerPin
    val input = DataInputStream(tls.input)
    val output = DataOutputStream(tls.output)
    override fun close() { tls.close() }
}

object Transport {
    private val deadlines = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "csync-deadlines").apply { isDaemon = true } }
    fun tcp(host: String, port: Int, identity: DeviceIdentity, pin: String?, onSocketOpened: (Closeable) -> Unit = {}): Link {
        val socket = Socket()
        val deadline = deadlines.schedule({ runCatching { socket.close() } }, 12, TimeUnit.SECONDS)
        try {
            onSocketOpened(socket)
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), 5000)
            socket.soTimeout = 110000
            val tls = TlsStreams(socket.getInputStream(), socket.getOutputStream(), socket, identity.context(pin).createSSLEngine("csync.local", port))
            return Link(tls, msg("transport.tcp", "host" to (host)))
        } catch (error: Exception) { runCatching { socket.close() }; throw error }
        finally { deadline.cancel(false) }
    }
    fun startSession(link: Link, pin: String, name: String) {
        val deadline = deadlines.schedule({ runCatching { link.close() } }, 8, TimeUnit.SECONDS)
        try {
            Protocol.write(link.output, Protocol.message("hello", "version" to Protocol.VERSION, "name" to name))
            val ready = Protocol.read(link.input)
            check(ready.optString("type") == "ready" && ready.optInt("version") == Protocol.VERSION && ready.optString("server") == pin) { msg("protocol.handshake_failed") }
        } finally { deadline.cancel(false) }
    }

    fun pair(host: String, port: Int, identity: DeviceIdentity, name: String, confirm: (String) -> Boolean): Pair<String, String> {
        tcp(host, port, identity, null).use { link ->
            return enroll(link, identity, name, "", confirm)
        }
    }

    data class PairedHost(val host: String, val pin: String, val name: String)

    fun pairQr(invitation: PairingQr, identity: DeviceIdentity, name: String, confirm: (String) -> Boolean): PairedHost {
        // Retry only connection/handshake failures. Once enrollment starts, never
        // replay its one-time token or fall back to manual (unpinned) pairing.
        var connected: Link? = null
        var selectedHost = ""
        for (host in invitation.hosts) {
            val candidate = runCatching { tcp(host, invitation.port, identity, invitation.pin) }.getOrNull()
            if (candidate != null) { connected = candidate; selectedHost = host; break }
        }
        val link = checkNotNull(connected) { msg("pair.qr_unreachable") }
        link.use {
            val (pin, serverName) = enroll(link, identity, name, invitation.token, confirm)
            return PairedHost(selectedHost, pin, serverName)
        }
    }

    private fun enroll(link: Link, identity: DeviceIdentity, name: String, token: String, confirm: (String) -> Boolean): Pair<String, String> {
            val pin = link.peerPin
            Protocol.write(link.output, Protocol.message("pair", "version" to Protocol.VERSION, "token" to token, "name" to name))
            val code = Protocol.hash("$pin:${identity.id}").take(12)
            check(confirm(code)) { msg("pair.cancelled") }
            check(Protocol.read(link.input).optString("type") == "pair_challenge") { msg("pair.not_approved") }
            Protocol.write(link.output, Protocol.message("pair_confirm"))
            val response = Protocol.read(link.input)
            check(response.optString("code") != "pairing_storage_failed") { msg("pair.storage_failed") }
            check(response.optString("type") == "paired" && response.optInt("version") == Protocol.VERSION && response.optString("server") == pin) { msg("pair.expired") }
            return pin to response.getString("name")
    }
}
