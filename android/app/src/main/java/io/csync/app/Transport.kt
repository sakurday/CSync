package io.csync.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Link(private val tls: TlsStreams, val label: String, val bluetooth: Boolean = false) : Closeable {
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
            return Link(tls, "网络 · $host")
        } catch (error: Exception) { runCatching { socket.close() }; throw error }
        finally { deadline.cancel(false) }
    }
    @SuppressLint("MissingPermission")
    fun bluetooth(context: Context, address: String, identity: DeviceIdentity, pin: String, onSocketOpened: (Closeable) -> Unit = {}): Link {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter ?: error("设备不支持蓝牙")
        check(adapter.isEnabled) { "蓝牙未开启" }
        val socket = adapter.getRemoteDevice(address).createRfcommSocketToServiceRecord(UUID.fromString(Protocol.BT_UUID))
        val deadline = deadlines.schedule({ runCatching { socket.close() } }, 15, TimeUnit.SECONDS)
        try {
            onSocketOpened(socket)
            socket.connect()
            return Link(TlsStreams(socket.inputStream, socket.outputStream, socket, identity.context(pin).createSSLEngine("csync.local", 0)), "蓝牙备用", true)
        } catch (error: Exception) { runCatching { socket.close() }; throw error }
        finally { deadline.cancel(false) }
    }
    fun startSession(link: Link, pin: String, name: String) {
        val deadline = deadlines.schedule({ runCatching { link.close() } }, 8, TimeUnit.SECONDS)
        try {
            Protocol.write(link.output, Protocol.message("hello", "version" to Protocol.VERSION, "name" to name))
            val ready = Protocol.read(link.input)
            check(ready.optString("type") == "ready" && ready.optInt("version") == Protocol.VERSION && ready.optString("server") == pin) { "同步握手失败" }
        } finally { deadline.cancel(false) }
    }

    fun pair(host: String, port: Int, identity: DeviceIdentity, name: String, confirm: (String) -> Boolean): Pair<String, String> {
        tcp(host, port, identity, null).use { link ->
            val pin = link.peerPin
            Protocol.write(link.output, Protocol.message("pair", "version" to Protocol.VERSION, "token" to "", "name" to name))
            val code = Protocol.hash("$pin:${identity.id}").take(12)
            check(confirm(code)) { "已取消配对" }
            check(Protocol.read(link.input).optString("type") == "pair_challenge") { "电脑未批准配对" }
            Protocol.write(link.output, Protocol.message("pair_confirm"))
            val response = Protocol.read(link.input)
            check(response.optString("code") != "pairing_storage_failed") { "电脑无法保存配对记录，请检查电脑端显示的配置目录和写入权限后重试" }
            check(response.optString("type") == "paired" && response.optInt("version") == Protocol.VERSION && response.optString("server") == pin) { "配对未获批准或已过期" }
            return pin to response.getString("name")
        }
    }
}
