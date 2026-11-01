package io.csync.app

import android.content.Context
import android.os.Build

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    init {
        I18n.initialize(context)
        if (!prefs.contains("hostProfiles")) {
            val pin = prefs.getString("pin", "")!!
            val oldHosts = prefs.getString("hosts", "")!!.split(',').map(String::trim).filter(String::isNotBlank)
            val profiles = if (pin.matches(Regex("[a-f0-9]{64}"))) oldHosts.map { host ->
                HostProfile(host, prefs.getInt("port", 45876), pin, prefs.getString("serverName", tr("hosts.default_name"))!!)
            } else emptyList()
            check(prefs.edit().putString("hostProfiles", HostProfile.encode(profiles)).commit()) { msg("hosts.migration_failed") }
        }
        // Discard retired transport settings while preserving endpoint order and paired identities.
        val stored = prefs.getString("hostProfiles", "[]")!!
        val profiles = HostProfile.decode(stored)
        val normalized = HostProfile.encode(profiles)
        if (prefs.contains("bluetoothEnabled") || prefs.contains("bluetoothAddress") || stored != normalized) {
            check(prefs.edit().remove("bluetoothEnabled").remove("bluetoothAddress")
                .putString("hostProfiles", normalized).commit()) { msg("hosts.migration_failed") }
        }
    }
    var profiles: List<HostProfile>
        get() = HostProfile.decode(prefs.getString("hostProfiles", "[]")!!)
        private set(value) { check(prefs.edit().putString("hostProfiles", HostProfile.encode(value)).commit()) { msg("hosts.save_failed") } }
    val port: Int get() = profiles.firstOrNull()?.port ?: 45876
    val serverName: String get() = profiles.firstOrNull { it.id == activeHostId }?.name ?: profiles.firstOrNull()?.name ?: tr("hosts.unpaired")
    var activeHostId: String get() = prefs.getString("activeHostId", "")!!; set(v) { prefs.edit().putString("activeHostId", v).apply() }
    fun updateProfiles(value: List<HostProfile>) {
        profiles = value
    }
    var enabled: Boolean get() = prefs.getBoolean("enabled", false); set(v) { prefs.edit().putBoolean("enabled", v).apply() }
    var paused: Boolean get() = prefs.getBoolean("paused", false); set(v) { prefs.edit().putBoolean("paused", v).apply() }
    // Old display-only status strings are transient; retain all host and identity settings.
    val statusMessage: String get() = prefs.getString("statusMessage", msg("sync.not_started"))!!
    var status: String get() = I18n.render(statusMessage); set(v) { prefs.edit().putString("statusMessage", v).apply() }
    var shizukuStatus: String get() = I18n.render(prefs.getString("shizukuMessage", msg("sync.not_connected"))!!); set(v) { prefs.edit().putString("shizukuMessage", v).apply() }
    var connected: Boolean get() = SyncRuntime.connected; set(v) { SyncRuntime.connected = v }
    var transport: String get() = I18n.render(prefs.getString("transportMessage", "")!!); set(v) { prefs.edit().putString("transportMessage", v).apply() }
    val deviceName: String get() = "${Build.MANUFACTURER} ${Build.MODEL}".filterNot(Char::isISOControl).take(64)
    val paired: Boolean get() = profiles.isNotEmpty()
    fun savePair(host: String, port: Int, pin: String, name: String) {
        val profile = HostProfile(host, port, pin, name.take(64))
        val existing = profiles
        val index = existing.indexOfFirst { it.id == profile.id }
        profiles = existing.toMutableList().apply {
            if (index < 0) add(profile) else set(index, profile)
        }
    }
}
