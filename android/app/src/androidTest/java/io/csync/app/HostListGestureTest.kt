package io.csync.app

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class HostListGestureTest {
    @Test fun dragHandleChangesOrderAndButtonsWork() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val initial = listOf(
            HostProfile("192.0.2.1", 45876, "a".repeat(64), "测试甲"),
            HostProfile("192.0.2.2", 45876, "b".repeat(64), "测试乙")
        )
        val order = mutableStateOf(initial)
        val saved = AtomicReference(initial)
        val activity = instrument.startActivitySync(Intent(instrument.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        try {
            instrument.runOnMainSync {
                activity.setContent { MaterialTheme {
                    HostList(order.value, "", {}, { profile ->
                        order.value = order.value.filterNot { it.id == profile.id }
                    }, { profiles -> saved.set(profiles); order.value = profiles })
                } }
            }
            fun find(description: String): AccessibilityNodeInfo? {
                fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                    if (node.contentDescription?.toString() == description) return node
                    for (i in 0 until node.childCount) node.getChild(i)?.let { search(it)?.let { found -> return found } }
                    return null
                }
                return instrument.uiAutomation.rootInActiveWindow?.let(::search)
            }
            fun awaitNode(description: String): AccessibilityNodeInfo {
                repeat(30) { find(description)?.let { return it }; Thread.sleep(100) }
                error("Missing UI node: $description")
            }
            val first = Rect().also { awaitNode(tr("hosts.drag_accessibility", "device" to "测试甲")).getBoundsInScreen(it) }
            val second = Rect().also { awaitNode(tr("hosts.drag_accessibility", "device" to "测试乙")).getBoundsInScreen(it) }
            val down = SystemClock.uptimeMillis()
            fun touch(action: Int, y: Float) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, first.centerX().toFloat(), y, 0)
                try { instrument.sendPointerSync(event) } finally { event.recycle() }
            }
            touch(MotionEvent.ACTION_DOWN, first.centerY().toFloat())
            for (step in 1..20) {
                Thread.sleep(20)
                touch(MotionEvent.ACTION_MOVE, first.centerY() + (second.centerY() - first.centerY()) * step / 20f)
            }
            touch(MotionEvent.ACTION_UP, second.centerY().toFloat())
            instrument.waitForIdleSync()
            assertEquals("Drag must persist the reversed order", initial.reversed(), saved.get())
            fun tap(description: String) {
                val bounds = Rect().also { awaitNode(description).getBoundsInScreen(it) }
                val time = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
                    try { instrument.sendPointerSync(event) } finally { event.recycle() }
                }
            }
            tap(tr("hosts.delete_accessibility", "device" to "测试乙"))
            instrument.waitForIdleSync()
            assertEquals(listOf(initial[0]), order.value)
        } finally { instrument.runOnMainSync { activity.finish() } }
    }
}
