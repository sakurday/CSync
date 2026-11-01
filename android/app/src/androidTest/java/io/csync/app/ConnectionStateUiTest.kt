package io.csync.app

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Exercises the real home screen without altering hosts, messages or the network session. */
class ConnectionStateUiTest {
    @Test fun liveConnectionRefreshesHomeWhenSavedMessagesStayTheSame() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val settings = Settings(context)
        assumeTrue("Requires the existing enabled, unpaused sync configuration", settings.enabled && !settings.paused)
        val original = SyncRuntime.connected
        val message = settings.statusMessage
        val transport = settings.transport
        var activity: MainActivity? = null
        fun visible(text: String): Boolean {
            fun search(node: AccessibilityNodeInfo): Boolean {
                if (node.text?.toString() == text) return true
                for (i in 0 until node.childCount) node.getChild(i)?.let { if (search(it)) return true }
                return false
            }
            return instrument.uiAutomation.rootInActiveWindow?.let { it.refresh(); search(it) } == true
        }
        fun awaitText(text: String) {
            repeat(60) { if (visible(text)) return; Thread.sleep(100) }
            error("Home did not refresh: $text")
        }
        try {
            instrument.runOnMainSync { SyncRuntime.connected = false }
            activity = instrument.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            awaitText(tr("sync.connecting"))
            // Model the IO worker publishing a handshake without a preference callback.
            Thread { SyncRuntime.connected = true }.apply { start(); join() }
            awaitText(tr("sync.connected_computer"))
            assertFalse(visible(tr("sync.connecting")))
            Thread { SyncRuntime.connected = false }.apply { start(); join() }
            awaitText(tr("sync.connecting"))
            assertFalse(visible(tr("sync.connected_computer")))
            assertEquals(message, settings.statusMessage)
            assertEquals(transport, settings.transport)
        } finally {
            instrument.runOnMainSync { SyncRuntime.connected = original; activity?.finish() }
        }
    }
}
