package io.csync.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Exercises navigation and dialogs without changing hosts, permissions, or sync preferences. */
class NavigationUiTest {
    @Test fun hostsDestinationSettingsGroupsAndBackNavigation() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        Settings(context) // Finish any upgrade migration before capturing the user's preferences.
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val originalLanguage = prefs.getString("language", null)
        val originalHosts = prefs.getString("hostProfiles", null)
        val originalEnabled = prefs.getBoolean("enabled", false)
        var activity: MainActivity? = null
        fun nodes(text: String): List<AccessibilityNodeInfo> {
            val result = mutableListOf<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo) {
                if (node.text?.toString() == text || node.contentDescription?.toString() == text) result.add(node)
                for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
            }
            instrument.uiAutomation.rootInActiveWindow?.let { it.refresh(); visit(it) }
            return result
        }
        fun awaitText(text: String) {
            repeat(50) { if (nodes(text).isNotEmpty()) return; Thread.sleep(100) }
            val visible = mutableListOf<String>()
            fun describe(node: AccessibilityNodeInfo) {
                node.text?.toString()?.let(visible::add)
                node.contentDescription?.toString()?.let(visible::add)
                for (index in 0 until node.childCount) node.getChild(index)?.let(::describe)
            }
            instrument.uiAutomation.rootInActiveWindow?.let(::describe)
            error("Missing navigation UI node: $text; visible: $visible")
        }
        fun click(text: String) {
            awaitText(text)
            val target = nodes(text).firstNotNullOfOrNull { candidate ->
                var node: AccessibilityNodeInfo? = candidate
                while (node != null && !node.isClickable) node = node.parent
                node
            }
            assertTrue("Cannot click $text", target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            instrument.waitForIdleSync()
        }
        fun capture(name: String) {
            instrument.waitForIdleSync()
            Thread.sleep(300) // Allow the navigation indicator's spring animation to settle.
            val bitmap = checkNotNull(instrument.uiAutomation.takeScreenshot())
            try {
                File(context.externalCacheDir, "ui-review-$name.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { bitmap.recycle() }
        }
        fun scrollForward(): Boolean {
            fun scroll(node: AccessibilityNodeInfo): Boolean {
                if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
                for (index in 0 until node.childCount) node.getChild(index)?.let { if (scroll(it)) return true }
                return false
            }
            return instrument.uiAutomation.rootInActiveWindow?.let(::scroll) == true
        }
        try {
            prefs.edit().putString("language", "zh-Hans").commit()
            activity = instrument.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            awaitText("同步")
            assertTrue("Status belongs inside the sync card", nodes("当前状态").isEmpty())
            capture("sync")
            click("主机")
            awaitText(tr("hosts.manual"))
            awaitText(tr("pair.scan_add"))
            capture("hosts")
            click(tr("hosts.manual"))
            awaitText("电脑 IP")
            awaitText("端口")
            click("取消")
            click("设置")
            awaitText("常规")
            awaitText("语言")
            awaitText("后台与隐私")
            assertTrue(nodes("使用蓝牙").isEmpty())
            assertTrue(nodes("备用连接").isEmpty())
            assertTrue("Host management must be a separate destination", nodes(tr("hosts.manual")).isEmpty())
            assertTrue(nodes(tr("hosts.order_hint")).isEmpty())
            capture("settings")
            repeat(6) {
                if (nodes("关于 CSync").isEmpty()) {
                    scrollForward()
                    Thread.sleep(300)
                }
            }
            awaitText("关于 CSync")
            capture("settings-bottom")
            click("关于 CSync")
            awaitText("在手机和电脑间同步剪贴板")
            click("关闭")
            awaitText("同步") // Wait for the modal window to release focus before sending Back.
            instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitText("CSync")
            assertEquals(originalHosts, prefs.getString("hostProfiles", null))
            assertEquals(originalEnabled, prefs.getBoolean("enabled", false))
        } finally {
            prefs.edit().apply { if (originalLanguage == null) remove("language") else putString("language", originalLanguage) }.commit()
            instrument.runOnMainSync { activity?.finish() }
        }
    }
}
