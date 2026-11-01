package io.csync.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver

class I18nTest {
    private val catalog = TranslationCatalog(JSONObject("""{
      "zh-Hans":{"_meta":{"nativeName":"简体中文","direction":"ltr"},"messages":{
        "nav.sync":"同步","sync.connected":"已连接 {device}","transport.tcp":"网络 · {host}"}},
      "qps-keys":{"_meta":{"nativeName":"键名（测试）","direction":"ltr"},"messages":{}},
      "partial":{"_meta":{"nativeName":"Partial fixture","direction":"ltr"},"messages":{"nav.sync":"translated"}},
      "en-US":{"_meta":{"nativeName":"English (US)","direction":"ltr"},"messages":{}}
    }"""))

    @Test fun missingTranslationsAndTestLanguageShowOnlyKeys() {
        for (locale in listOf("qps-keys", "partial", "unknown")) {
            assertEquals("sync.connected", catalog.render(msg("sync.connected", "device" to "private host"), locale))
        }
        assertEquals("translated", catalog.render(msg("nav.sync"), "partial"))
        assertEquals("missing.key", catalog.render(msg("missing.key"), "zh-Hans"))
    }

    @Test fun nestedMessagesRetranslateAndArgumentsStayLiteral() {
        val value = msg("sync.connected", "device" to msg("transport.tcp", "host" to "{device} $1 中文🙂"))
        assertEquals("已连接 网络 · {device} $1 中文🙂", catalog.render(value, "zh-Hans"))
        assertEquals("sync.connected", catalog.render(value, "qps-keys"))
    }

    @Test fun systemAndExplicitSelectionResolveIndependently() {
        assertEquals("zh-Hans", catalog.resolve("system", Locale.forLanguageTag("zh-SG")))
        assertEquals("qps-keys", catalog.resolve("qps-keys", Locale.CHINA))
        for (tag in listOf("en-US", "en-GB", "en-SG", "en")) {
            assertEquals("en-US", catalog.resolve("system", Locale.forLanguageTag(tag)))
        }
        assertEquals("zh-Hans", catalog.resolve("system", Locale.FRANCE))
        assertEquals("zh-Hans", catalog.resolve("zh-Hans", Locale.US))
    }

    @Test fun languageChangesInvalidateTranslatedComposableScopes() {
        I18n.render(msg("nav.sync")) // Initialize state before observing, as the first composition does.
        Snapshot.notifyObjectsInitialized()
        Snapshot.sendApplyNotifications()
        val observer = SnapshotStateObserver { it() }
        var invalidated = false
        observer.start()
        try {
            observer.observeReads(Any(), { invalidated = true }) { I18n.render(msg("nav.sync")) }
            Snapshot.withMutableSnapshot { I18n.invalidate() }
            assertTrue("Skipped dialog scopes must observe language changes", invalidated)
        } finally { observer.stop(); observer.clear() }
    }
}
