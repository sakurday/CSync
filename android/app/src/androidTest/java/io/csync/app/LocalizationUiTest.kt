package io.csync.app

import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

/** Changes only the language preference, restores it, and never writes a clipboard or pairing. */
class LocalizationUiTest {
    @Test fun embeddedMessagesRenderOnAndroid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val catalog = TranslationCatalog(JSONObject(context.assets.open("csync-locales.json").bufferedReader().use { it.readText() }))
        assertEquals("同步", catalog.render(msg("nav.sync"), "zh-Hans"))
        assertEquals("已连接 · 网络 · {device} $1 中文🙂", catalog.render(
            msg("sync.connected", "transport" to msg("transport.tcp", "host" to "{device} $1 中文🙂")), "zh-Hans"))
        assertEquals("sync.connected", catalog.render(msg("sync.connected", "transport" to "fixture"), "qps-keys"))
        assertEquals("missing.key", catalog.render(msg("missing.key"), "zh-Hans"))
        assertEquals("Sync", catalog.render(msg("nav.sync"), "en-US"))
        assertEquals("Connected · Network · {device} $1 中文🙂", catalog.render(
            msg("sync.connected", "transport" to msg("transport.tcp", "host" to "{device} $1 中文🙂")), "en-US"))
        assertEquals("1 device online", catalog.render(msg("windows.online_count", "count" to 1), "en-US"))
        assertEquals("2 devices online", catalog.render(msg("windows.online_count", "count" to 2), "en-US"))
        assertEquals("en-US", catalog.resolve("system", java.util.Locale.US))
        assertEquals("en-US", catalog.resolve("system", java.util.Locale.UK))
    }

    @Test fun languagePickerEnglishKeysPersistenceAndChineseRestore() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val original = prefs.getString("language", null)
        var activity: MainActivity? = null
        fun find(text: String): AccessibilityNodeInfo? {
            fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (node.text?.toString() == text) return node
                for (index in 0 until node.childCount) node.getChild(index)?.let { search(it)?.let { found -> return found } }
                return null
            }
            return instrument.uiAutomation.rootInActiveWindow?.let { it.refresh(); search(it) }
        }
        fun awaitText(text: String): AccessibilityNodeInfo {
            repeat(50) { find(text)?.let { return it }; Thread.sleep(100) }
            error("Missing localization UI node: $text")
        }
        fun click(text: String) {
            var node: AccessibilityNodeInfo? = awaitText(text)
            while (node != null && !node.isClickable) node = node.parent
            assertTrue("Cannot click $text", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            instrument.waitForIdleSync()
        }
        fun launch(): MainActivity {
            val launched = instrument.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            repeat(50) { if (launched.hasWindowFocus()) return launched; Thread.sleep(100) }
            error("Localization activity did not receive focus")
        }
        try {
            prefs.edit().putString("language", "zh-Hans").commit()
            activity = launch()
            val debugName = I18n.options.first { it.first == "qps-keys" }.second
            val chineseName = I18n.options.first { it.first == "zh-Hans" }.second
            click("设置")
            click("语言")
            awaitText("选择语言")
            click("English (US)")
            awaitText("Choose language")
            click("Close")
            awaitText("Background & privacy")
            Thread.sleep(400) // Let the dialog's exit animation finish before visual inspection.
            instrument.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(context.cacheDir, "english-settings.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            assertEquals("en-US", prefs.getString("language", ""))
            instrument.runOnMainSync { activity?.finish() }
            activity = launch()
            click("Settings")
            awaitText("Clipboard privacy")
            click("Language")
            click(debugName)
            awaitText("language.choose")
            click("common.close")
            awaitText("nav.sync") // The modal hides the underlying navigation from accessibility.
            assertEquals("qps-keys", prefs.getString("language", ""))
            instrument.runOnMainSync { activity?.finish() }
            activity = launch()
            awaitText("nav.settings")
            click("nav.settings")
            click("language.title")
            click(chineseName)
            awaitText("选择语言")
            click("关闭")
            awaitText("语言")
            awaitText(chineseName)
            assertEquals("zh-Hans", prefs.getString("language", ""))
        } finally {
            prefs.edit().apply { if (original == null) remove("language") else putString("language", original) }.commit()
            instrument.runOnMainSync { activity?.finish() }
        }
    }
}
