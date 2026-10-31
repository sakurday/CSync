package io.csync.app

import android.content.Context
import android.os.Build

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    init {
        if (!prefs.contains("hostProfiles")) {
            val pin = prefs.getString("pin", "")!!
            val oldHosts = prefs.getString("hosts", "")!!.split(',').map(String::trim).filter(String::isNotBlank)
            val profiles = if (pin.matches(Regex("[a-f0-9]{64}"))) oldHosts.mapIndexed { index, host ->
                HostProfile(host, prefs.getInt("port", 45876), pin, prefs.getString("serverName", "电脑")!!,
                    if (index == 0) prefs.getString("bluetoothAddress", "")!! else "")
            } else emptyList()
            check(prefs.edit().putString("hostProfiles", HostProfile.encode(profiles)).commit()) { "无法迁移主机列表" }
        }
    }
    var profiles: List<HostProfile>
        get() = HostProfile.decode(prefs.getString("hostProfiles", "[]")!!)
        private set(value) { check(prefs.edit().putString("hostProfiles", HostProfile.encode(value)).commit()) { "无法保存主机列表" } }
    val port: Int get() = profiles.firstOrNull()?.port ?: 45876
    val serverName: String get() = profiles.firstOrNull { it.id == activeHostId }?.name ?: profiles.firstOrNull()?.name ?: "未配对"
    var activeHostId: String get() = prefs.getString("activeHostId", "")!!; set(v) { prefs.edit().putString("activeHostId", v).apply() }
    fun updateProfiles(value: List<HostProfile>) {
        profiles = value
    }
    var bluetoothEnabled: Boolean get() = prefs.getBoolean("bluetoothEnabled", false); set(v) { prefs.edit().putBoolean("bluetoothEnabled", v).apply() }
    var enabled: Boolean get() = prefs.getBoolean("enabled", false); set(v) { prefs.edit().putBoolean("enabled", v).apply() }
    var paused: Boolean get() = prefs.getBoolean("paused", false); set(v) { prefs.edit().putBoolean("paused", v).apply() }
    var status: String get() = prefs.getString("status", "尚未启动")!!; set(v) { prefs.edit().putString("status", v).apply() }
    var shizukuStatus: String get() = prefs.getString("shizukuStatus", "尚未连接")!!; set(v) { prefs.edit().putString("shizukuStatus", v).apply() }
    val deviceName: String get() = "${Build.MANUFACTURER} ${Build.MODEL}".filterNot(Char::isISOControl).take(64)
    val paired: Boolean get() = profiles.isNotEmpty()
    fun savePair(host: String, port: Int, pin: String, name: String) {
        val profile = HostProfile(host, port, pin, name.take(64))
        val existing = profiles
        val index = existing.indexOfFirst { it.id == profile.id }
        profiles = existing.toMutableList().apply {
            if (index < 0) add(profile) else set(index, profile.copy(bluetoothAddress = if (existing[index].pin == pin) existing[index].bluetoothAddress else ""))
        }
    }
}
