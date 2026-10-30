package io.csync.app

import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import rikka.shizuku.Shizuku

@RunWith(AndroidJUnit4::class)
class DeviceIntegrationTest {
    private fun await(label: String, timeoutMs: Long = 20000, condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        fail("Timed out: $label")
    }
    @Test fun backgroundClipboardAndPinnedTls() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val settings = Settings(context)
        val mainIntent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        var activity = instrument.startActivitySync(mainIntent)
        await("Shizuku binder") { Shizuku.pingBinder() }
        assertEquals("请先在 CSync 首页授权 Shizuku", PackageManager.PERMISSION_GRANTED, Shizuku.checkSelfPermission())
        val host = InstrumentationRegistry.getArguments().getString("host") ?: error("missing test host")
        val port = 45877
        val runId = InstrumentationRegistry.getArguments().getString("runId") ?: error("missing test run id")
        val pcFirst = "CSync PC→Android 前台验证一 🙂 [$runId]"
        val pcSecond = "CSync PC→Android 后台验证二 🙂 [$runId]"
        val androidText = "CSync Android→PC 后台验证 🙂 [$runId]"
        val identity = DeviceIdentity()
        val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val signer = java.security.Signature.getInstance("NONEwithECDSA")
        signer.initSign(keys.getKey(DeviceIdentity.ALIAS, null) as java.security.PrivateKey)
        signer.update(ByteArray(32) { 42 })
        assertTrue("TLS delegated signature must be supported", signer.sign().isNotEmpty())
        // A wrong pin must fail before enrollment, leaving the real token usable.
        assertThrows(Exception::class.java) { Transport.tcp("127.0.0.1", port, identity, "0".repeat(64)).close() }
        val (pin, name) = Transport.pair(host, port, identity, settings.deviceName) { true }
        settings.savePair(host, port, pin, name)
        settings.paused = false
        settings.enabled = true
        val manager = context.getSystemService(ClipboardManager::class.java)
        val copied = java.util.concurrent.CountDownLatch(1)
        val nonce = java.util.UUID.randomUUID().toString()
        var copySuccess = false
        val copiedReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.getStringExtra("nonce") == nonce) { copySuccess = intent.getBooleanExtra("success", false); copied.countDown() }
            }
        }
        context.registerReceiver(copiedReceiver, IntentFilter("io.csync.test.COPIED"), if (android.os.Build.VERSION.SDK_INT >= 33) Context.RECEIVER_EXPORTED else 0)
        fun currentText(): String? {
            var value: String? = null
            instrument.runOnMainSync { value = manager.primaryClip?.getItemAt(0)?.text?.toString() }
            return value
        }
        try {
            instrument.runOnMainSync { context.startForegroundService(Intent(context, SyncService::class.java)) }
            await("foreground service and privileged listener") { settings.status.startsWith("已连接") && settings.shizukuStatus == "剪贴板监听已就绪" }
            val transport = settings.status
            await("PC to Android foreground clipboard") { currentText() == pcFirst }
            // Another app has focus while CSync observes its clipboard in the background.
            val copy = Intent().setComponent(ComponentName("io.csync.app.test", "io.csync.app.test.CopyActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            instrument.sendStatus(1, android.os.Bundle().apply { putString("stage", "starting copy helper") })
            instrument.runOnMainSync { activity.startActivity(copy.putExtra("nonce", nonce).putExtra("runId", runId)) }
            assertTrue("copy helper must get focus and execute a fresh copy", copied.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("copy helper readback must match synthetic content", copySuccess)
            Thread.sleep(4000)
            activity = instrument.startActivitySync(mainIntent)
            await("foreground activity focus") { activity.hasWindowFocus() }
            await("PC to Android background write") { currentText() == pcSecond }
            val result = JSONObject().put("success", true).put("transport", transport)
                .put("api", android.os.Build.VERSION.SDK_INT).put("model", android.os.Build.MODEL)
                .put("checks", "pinned TLS, pairing, Shizuku background read/write, Unicode, no echo")
            context.filesDir.resolve("device-result.json").writeText(result.toString())
        } catch (error: Throwable) {
            context.filesDir.resolve("device-diagnostic.json").writeText(JSONObject()
                .put("status", settings.status).put("bridge", settings.shizukuStatus)
                .put("focused", activity.hasWindowFocus())
                .put("stillFirst", currentText() == pcFirst)
                .put("stillHelper", currentText() == androidText).toString())
            throw error
        } finally {
            settings.enabled = false
            instrument.runOnMainSync { context.stopService(Intent(context, SyncService::class.java)) }
            context.unregisterReceiver(copiedReceiver)
        }
    }
}
