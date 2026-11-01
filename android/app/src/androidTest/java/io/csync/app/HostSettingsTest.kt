package io.csync.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class HostSettingsTest {
    @Test fun legacyMigrationAddReorderDeleteAndReload() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val file = "host-migration-test-${System.nanoTime()}"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = base.getSharedPreferences(file, mode)
        }
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        try {
            prefs.edit().putString("hosts", "192.168.1.2, example.com").putInt("port", 12345)
                .putString("pin", "a".repeat(64)).putString("serverName", "旧电脑")
                .putString("bluetoothAddress", "00:11:22:33:44:55").putBoolean("bluetoothEnabled", true).commit()
            val settings = Settings(context)
            val migrated = settings.profiles
            assertEquals(listOf("192.168.1.2", "example.com"), migrated.map { it.host })
            assertEquals(listOf(12345, 12345), migrated.map { it.port })
            assertTrue(migrated.all { it.pin == "a".repeat(64) })
            assertFalse(prefs.contains("bluetoothAddress"))
            assertFalse(prefs.contains("bluetoothEnabled"))
            settings.savePair("new.example.com", 45876, "b".repeat(64), "新电脑")
            assertEquals(3, settings.profiles.size)
            assertEquals(migrated, settings.profiles.take(2))
            settings.updateProfiles(settings.profiles.reversed())
            assertEquals("新电脑", Settings(context).profiles.first().name)
            settings.updateProfiles(settings.profiles.dropLast(1))
            assertEquals(2, Settings(context).profiles.size)
            settings.updateProfiles(emptyList())
            assertFalse(Settings(context).paired) // Legacy keys must never resurrect deleted hosts.
        } finally { base.deleteSharedPreferences(file) }
    }

    @Test fun currentProfilesRetainOrderAndTrustWhenRetiringTransportSettings() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val file = "host-transport-migration-${System.nanoTime()}"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = base.getSharedPreferences(file, mode)
        }
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val expected = listOf(
            HostProfile("example.com", 45876, "b".repeat(64), "优先电脑"),
            HostProfile("192.0.2.1", 12345, "a".repeat(64), "备用电脑")
        )
        try {
            val legacy = org.json.JSONArray().apply {
                expected.forEach { put(it.json().put("bluetooth", "00:11:22:33:44:55")) }
            }.toString()
            prefs.edit().putString("hostProfiles", legacy).putBoolean("bluetoothEnabled", true)
                .putString("bluetoothAddress", "00:11:22:33:44:55")
                .putBoolean("enabled", true).putBoolean("paused", true).commit()
            assertEquals(expected, Settings(context).profiles)
            assertFalse(prefs.contains("bluetoothEnabled"))
            assertFalse(prefs.contains("bluetoothAddress"))
            assertFalse(prefs.getString("hostProfiles", "")!!.contains("bluetooth"))
            assertTrue(Settings(context).enabled)
            assertTrue(Settings(context).paused)
            assertEquals(expected, Settings(context).profiles)
            Settings(context).updateProfiles(emptyList())
            assertFalse(Settings(context).paired)
        } finally { base.deleteSharedPreferences(file) }
    }
}
