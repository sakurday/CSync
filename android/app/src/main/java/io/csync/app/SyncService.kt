package io.csync.app

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import android.net.ConnectivityManager
import android.net.Network
import android.net.LinkProperties
import java.io.Closeable
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class SyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var settings: Settings
    private lateinit var identity: DeviceIdentity
    private var network: Job? = null
    private val link = AtomicReference<Link?>()
    private val outgoing = AtomicReference<Channel<JSONObject>?>()
    private val sequence = AtomicLong(0)
    private val pending = ConcurrentHashMap<String, Long>()
    private val networkChanged = Channel<Unit>(Channel.CONFLATED)
    private val networkGeneration = AtomicLong(0)
    private val connectingSocket = AtomicReference<Closeable?>()
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private var defaultNetwork: Network? = null // Accessed only on the main handler.
    private var networkRoute: String? = null
    private val networkSettled = Runnable {
        if (!destroyed) {
            networkGeneration.incrementAndGet()
            runCatching { connectingSocket.getAndSet(null)?.close() }
            link.get()?.takeUnless { it.bluetooth }?.close()
            networkChanged.trySend(Unit)
        }
    }
    private fun scheduleNetworkRetry() {
        main.removeCallbacks(networkSettled)
        main.postDelayed(networkSettled, 750)
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (defaultNetwork == network) return
            defaultNetwork = network
            networkRoute = null
            scheduleNetworkRetry()
        }
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            if (defaultNetwork != network) return
            val route = "${properties.interfaceName}|${properties.linkAddresses.sortedBy { it.toString() }}|${properties.routes.sortedBy { it.toString() }}"
            if (route == networkRoute) return
            networkRoute = route
            scheduleNetworkRetry()
        }
        override fun onLost(network: Network) {
            if (defaultNetwork != network) return
            defaultNetwork = null
            networkRoute = null
            scheduleNetworkRetry()
        }
    }
    @Volatile private var bridge: IClipboardBridge? = null
    @Volatile private var binding = false
    @Volatile private var destroyed = false
    private val serviceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(this, ClipboardUserService::class.java))
            .daemon(false).processNameSuffix("clipboard").debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)
    }
    private val listener = object : IClipListener.Stub() {
        override fun onClip(text: String) {
            if (settings.paused || !settings.enabled) return
            val channel = outgoing.get() ?: return // No offline replay.
            runCatching {
                val clip = Clip.create(identity.id, sequence.incrementAndGet(), text)
                pending[clip.id] = clip.sequence
                if (!channel.trySend(Protocol.message("clip", "clip" to clip.json())).isSuccess) {
                    pending.remove(clip.id)
                    status("发送队列已满；请重新复制")
                }
            }
        }
        override fun onStatus(status: String) { settings.shizukuStatus = status; notifyStatus() }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binding = false
            if (destroyed || service == null) return
            bridge = IClipboardBridge.Stub.asInterface(service)
            runCatching { bridge?.start(listener) }.onFailure { settings.shizukuStatus = "剪贴板桥接失败"; notifyStatus() }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bridge = null; binding = false
            settings.shizukuStatus = "剪贴板服务断开，等待恢复"; notifyStatus()
            main.postDelayed({ bindBridge() }, 2000)
        }
    }
    private val received = Shizuku.OnBinderReceivedListener { main.post { bindBridge() } }
    private val dead = Shizuku.OnBinderDeadListener {
        bridge = null; binding = false
        settings.shizukuStatus = "Shizuku 已停止，请重新启动"; notifyStatus()
    }

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        settings.activeHostId = ""
        settings.shizukuStatus = "正在连接 Shizuku"
        identity = DeviceIdentity()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "剪贴板同步", NotificationManager.IMPORTANCE_LOW).apply { description = "显示连接与 Shizuku 状态"; setShowBadge(false) })
        startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        connectivity.registerDefaultNetworkCallback(networkCallback, main)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { settings.enabled = false; stopSelf(); return START_NOT_STICKY }
            ACTION_PAUSE -> { settings.paused = !settings.paused; notifyStatus(); return START_STICKY }
        }
        if (!settings.enabled || !settings.paired) { stopSelf(); return START_NOT_STICKY }
        bindBridge()
        if (network == null || network?.isActive != true || intent?.action == ACTION_RECONNECT) {
            link.getAndSet(null)?.close()
            connectingSocket.getAndSet(null)?.close()
            outgoing.getAndSet(null)?.close()
            val previous = network
            previous?.cancel()
            network = scope.launch { previous?.join(); connectLoop() }
        }
        return START_STICKY
    }

    private fun bindBridge() {
        if (destroyed || bridge != null || binding) return
        if (!Shizuku.pingBinder()) { settings.shizukuStatus = "请启动 Shizuku"; notifyStatus(); return }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            settings.shizukuStatus = "请在应用首页授权 Shizuku"; notifyStatus(); return
        }
        binding = true
        runCatching { Shizuku.bindUserService(serviceArgs, connection) }.onFailure {
            binding = false; settings.shizukuStatus = "Shizuku 服务连接失败"; notifyStatus()
        }
    }

    private suspend fun connectLoop() {
        val backoff = ReconnectBackoff()
        while (currentCoroutineContext().isActive && settings.enabled) {
            if (networkChanged.tryReceive().isSuccess) backoff.reset()
            val generation = networkGeneration.get()
            val profiles = settings.profiles
            // No TCP attempts while offline; Bluetooth remains an optional fallback.
            var selected = if (connectivity.activeNetwork != null) connectProfiles(profiles, false, generation) else null
            if (selected == null && settings.bluetoothEnabled && generation == networkGeneration.get()) {
                selected = connectProfiles(profiles.filter { it.bluetoothAddress.isNotEmpty() }, true, generation)
            }
            if (generation != networkGeneration.get()) {
                selected?.second?.close()
                backoff.reset()
                continue
            }
            val current = selected?.second
            if (current == null) {
                status("连接失败；检查电脑、地址、防火墙或配对状态")
            } else {
                val connected = current
                if (!currentCoroutineContext().isActive) { connected.close(); break }
                link.set(connected)
                // Catch a network callback racing with publication of the new TCP link.
                if (!connected.bluetooth && generation != networkGeneration.get()) connected.close()
                val sessionStarted = SystemClock.elapsedRealtime()
                try {
                    settings.activeHostId = selected!!.first.id
                    session(connected)
                }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { status("连接中断，正在恢复") }
                finally {
                    backoff.sessionEnded(SystemClock.elapsedRealtime() - sessionStarted)
                    outgoing.getAndSet(null)?.close()
                    pending.clear()
                    settings.activeHostId = ""
                    if (link.compareAndSet(connected, null)) connected.close() else connected.close()
                }
            }
            if (connectivity.activeNetwork == null && !settings.bluetoothEnabled) {
                status("等待网络恢复")
                awaitReconnect(networkChanged, null) // Event-driven when offline; no periodic socket attempts.
                backoff.reset()
            } else {
                val delayMs = backoff.nextDelayMs()
                status("连接已断开，约 ${delayMs / 1000} 秒后重试；网络变化时立即重试")
                if (awaitReconnect(networkChanged, delayMs)) backoff.reset()
            }
        }
    }

    private suspend fun connectProfiles(profiles: List<HostProfile>, bluetooth: Boolean, generation: Long): Pair<HostProfile, Link>? {
        for (profile in profiles) {
            currentCoroutineContext().ensureActive()
            if (generation != networkGeneration.get()) break
            status(if (bluetooth) "正在尝试蓝牙 · ${profile.name}" else "正在连接 ${profile.name} · ${profile.host}")
            try { return profile to connect(profile, bluetooth, generation) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Try the next host only while this network is current. */ }
        }
        return null
    }

    private suspend fun connect(profile: HostProfile, bluetooth: Boolean, generation: Long): Link {
        val context = currentCoroutineContext()
        val opened: (Closeable) -> Unit = { socket ->
            connectingSocket.set(socket)
            if (!context.isActive || generation != networkGeneration.get()) socket.close()
        }
        var current: Link? = null
        try {
            current = if (bluetooth) Transport.bluetooth(this, profile.bluetoothAddress, identity, profile.pin, opened)
                else Transport.tcp(profile.host, profile.port, identity, profile.pin, opened)
            Transport.startSession(current, profile.pin, settings.deviceName)
            context.ensureActive()
            return current
        } catch (error: Exception) { current?.close(); throw error }
        finally { connectingSocket.set(null) }
    }

    private suspend fun session(current: Link): Unit = coroutineScope {
        val channel = Channel<JSONObject>(8)
        pending.clear()
        outgoing.set(channel)
        status("已连接 · ${current.label}")
        var revision = 0L
        val lastReceived = AtomicLong(SystemClock.elapsedRealtime())
        val sender = launch {
            try { for (message in channel) Protocol.write(current.output, message) }
            catch (_: Exception) { current.close() }
        }
        val heartbeat = launch {
            while (isActive) {
                delay(40000)
                if (SystemClock.elapsedRealtime() - lastReceived.get() > 100000) { current.close(); break }
                channel.send(Protocol.message("ping", "nonce" to SystemClock.elapsedRealtime()))
            }
        }
        try {
            while (isActive) {
                val incoming = Protocol.read(current.input)
                lastReceived.set(SystemClock.elapsedRealtime())
                when (incoming.getString("type")) {
                    "ping" -> channel.send(Protocol.message("pong", "nonce" to incoming.getLong("nonce")))
                    "pong" -> Unit
                    "ack" -> { pending.remove(incoming.getString("id")); status("电脑暂未应用内容：${incoming.optString("status")}") }
                    "commit" -> {
                        val next = incoming.getLong("revision")
                        check(next > revision) { "服务器消息顺序错误" }
                        revision = next
                        val clip = Clip.parse(incoming.getJSONObject("clip"))
                        if (clip.origin == identity.id) {
                            pending.entries.removeIf { it.value <= clip.sequence }
                            continue
                        }
                        val result = when {
                            settings.paused -> "paused"
                            pending.isNotEmpty() -> "superseded"
                            bridge == null -> "shizuku_unavailable"
                            else -> if (runCatching { bridge?.writeText(clip.text, clip.id, clip.hash) == true }.getOrDefault(false)) "applied" else "clipboard_unavailable"
                        }
                        channel.send(Protocol.message("ack", "id" to clip.id, "status" to result))
                        if (result != "applied" && result != "superseded") status("收到内容但未写入：$result")
                    }
                    else -> error("服务器消息不兼容")
                }
            }
        } finally { outgoing.compareAndSet(channel, null); channel.close(); current.close(); sender.cancel(); heartbeat.cancel() }
    }

    private fun status(value: String) { if (settings.status != value) { settings.status = value; notifyStatus() } }
    @android.annotation.SuppressLint("MissingPermission") // Denied notification permission is reflected by Android; FGS still supplies its notification.
    private fun notifyStatus() { if (!destroyed) getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification()) }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun action(request: Int, type: String): PendingIntent = PendingIntent.getService(this, request, Intent(this, SyncService::class.java).setAction(type), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle(if (settings.paused) "CSync · 已暂停" else "CSync · 剪贴板同步")
            .setContentText(settings.status).setStyle(Notification.BigTextStyle().bigText("${settings.status}\n${settings.shizukuStatus}"))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, if (settings.paused) "恢复" else "暂停", action(1, ACTION_PAUSE)).build())
            .addAction(Notification.Action.Builder(null, "重连", action(2, ACTION_RECONNECT)).build())
            .addAction(Notification.Action.Builder(null, "停止", action(3, ACTION_STOP)).build()).build()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        destroyed = true
        main.removeCallbacksAndMessages(null)
        link.getAndSet(null)?.close()
        connectingSocket.getAndSet(null)?.close()
        outgoing.getAndSet(null)?.close()
        scope.cancel()
        Shizuku.removeBinderReceivedListener(received)
        Shizuku.removeBinderDeadListener(dead)
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        networkChanged.close()
        if (Shizuku.pingBinder()) runCatching { Shizuku.unbindUserService(serviceArgs, connection, true) }
        bridge = null
        settings.status = "同步已停止"
        settings.shizukuStatus = "尚未连接"
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "sync"
        private const val NOTIFICATION = 100
        const val ACTION_STOP = "io.csync.STOP"
        const val ACTION_PAUSE = "io.csync.PAUSE"
        const val ACTION_RECONNECT = "io.csync.RECONNECT"
    }
}
