package io.csync.app

import android.content.ClipData
import android.content.IOnPrimaryClipChangedListener
import android.os.IBinder
import android.os.PersistableBundle
import java.lang.reflect.Method
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** All framework calls run on our own thread: never inherit the app caller's Binder identity. */
class ClipboardUserService : IClipboardBridge.Stub() {
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(16), ThreadPoolExecutor.DiscardOldestPolicy())
    private var clipboard: Any? = null
    private var target: IClipListener? = null
    private var registered = false
    private var remoteId = ""
    private var remoteHash = ""
    private var remoteStamp = -1L
    private var localStamp = -1L
    private var localHash = ""
    private var userId = 0
    private val changeListener = object : IOnPrimaryClipChangedListener.Stub() {
        override fun dispatchPrimaryClipChanged() { runCatching { worker.execute { readChanged() } } }
    }

    override fun start(listener: IClipListener) {
        worker.execute {
            target = listener
            try {
                if (!registered) {
                    val manager = Class.forName("android.os.ServiceManager")
                    val binder = manager.getMethod("getService", String::class.java).invoke(null, "clipboard") as IBinder
                    clipboard = Class.forName("android.content.IClipboard\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
                    userId = Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null) as Int
                    // Seed the baseline without forwarding it. OEM clipboard
                    // services can notify immediately when a listener registers.
                    val baseline = runCatching { call("getPrimaryClip") }.getOrNull() as? ClipData
                    if (baseline != null && baseline.itemCount > 0) {
                        localStamp = baseline.description.timestamp
                        baseline.getItemAt(0).text?.toString()?.takeIf { it.toByteArray().size <= Protocol.MAX_TEXT }?.let { localHash = Protocol.hash(it) }
                    }
                    call("addPrimaryClipChangedListener", changeListener)
                    registered = true
                }
                target?.onStatus("剪贴板监听已就绪")
            } catch (_: Exception) { target?.onStatus("剪贴板接口不可用，请检查系统与 Shizuku 权限") }
        }
    }

    override fun writeText(text: String, eventId: String, hash: String): Boolean {
        if (text.isEmpty() || text.toByteArray().size > Protocol.MAX_TEXT || Protocol.hash(text) != hash) return false
        return runCatching {
            worker.submit<Boolean> {
                if (!registered) return@submit false
                val data = ClipData.newPlainText("CSync", text)
                data.description.extras = PersistableBundle().apply { putString("io.csync.event", eventId); putString("io.csync.hash", hash) }
                remoteId = eventId
                remoteHash = hash
                call("setPrimaryClip", data)
                remoteStamp = (runCatching { call("getPrimaryClip") }.getOrNull() as? ClipData)?.description?.timestamp ?: -1L
                true
            }.get(3, TimeUnit.SECONDS)
        }.getOrDefault(false)
    }

    private fun readChanged() {
        try {
            val data = call("getPrimaryClip") as? ClipData ?: return
            if (data.itemCount == 0 || data.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true) return
            val text = data.getItemAt(0).text?.toString() ?: return
            if (text.isEmpty() || text.toByteArray(Charsets.UTF_8).size > Protocol.MAX_TEXT || text.startsWith("csync://pair?")) return
            val hash = Protocol.hash(text)
            val stamp = data.description.timestamp
            if (hash == remoteHash && (data.description.extras?.getString("io.csync.event") == remoteId || (stamp > 0 && stamp == remoteStamp))) return
            // OEM services can dispatch the same system copy more than once.
            // A later explicit recopy receives a new platform timestamp.
            if (stamp > 0 && stamp == localStamp && hash == localHash) return
            localStamp = stamp
            localHash = hash
            target?.onClip(text)
        } catch (_: Exception) { runCatching { target?.onStatus("剪贴板读取受限；解锁或检查系统权限后重试") } }
    }

    private fun call(name: String, leading: Any? = null): Any? {
        val service = clipboard ?: error("clipboard unavailable")
        val method: Method = service.javaClass.methods.firstOrNull { it.name == name } ?: error("unsupported clipboard interface")
        method.isAccessible = true
        var strings = 0
        var ints = 0
        val args = method.parameterTypes.map { type ->
            when {
                type == String::class.java -> if (strings++ == 0) "com.android.shell" else null
                type == Int::class.javaPrimitiveType -> if (ints++ == 0) userId else 0 // default device
                type == Boolean::class.javaPrimitiveType -> false
                leading != null && type.isInstance(leading) -> leading
                else -> error("unknown clipboard parameter")
            }
        }.toTypedArray()
        return method.invoke(service, *args)
    }

    override fun capability(): String = "Shizuku UID ${android.os.Process.myUid()} · API ${android.os.Build.VERSION.SDK_INT}"
    override fun destroy() {
        runCatching {
            worker.submit {
                if (registered) runCatching { call("removePrimaryClipChangedListener", changeListener) }
                registered = false
                target = null
            }.get(2, TimeUnit.SECONDS)
        }
        worker.shutdownNow()
        kotlin.system.exitProcess(0)
    }
}
