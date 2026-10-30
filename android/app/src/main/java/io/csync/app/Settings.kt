package io.csync.app

import android.content.Context
import android.os.Build

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var hosts: List<String>
        get() = prefs.getString("hosts", "")!!.split(',').map(String::trim).filter(String::isNotEmpty)
        set(value) { prefs.edit().putString("hosts", value.joinToString(",")).apply() }
    var port: Int get() = prefs.getInt("port", 45876); set(v) { prefs.edit().putInt("port", v).apply() }
    var pin: String get() = prefs.getString("pin", "")!!; set(v) { prefs.edit().putString("pin", v).apply() }
    var serverName: String get() = prefs.getString("serverName", "未配对")!!; set(v) { prefs.edit().putString("serverName", v).apply() }
    var bluetoothAddress: String get() = prefs.getString("bluetoothAddress", "")!!; set(v) { prefs.edit().putString("bluetoothAddress", v).apply() }
    var bluetoothEnabled: Boolean get() = prefs.getBoolean("bluetoothEnabled", false); set(v) { prefs.edit().putBoolean("bluetoothEnabled", v).apply() }
    var enabled: Boolean get() = prefs.getBoolean("enabled", false); set(v) { prefs.edit().putBoolean("enabled", v).apply() }
    var paused: Boolean get() = prefs.getBoolean("paused", false); set(v) { prefs.edit().putBoolean("paused", v).apply() }
    var status: String get() = prefs.getString("status", "尚未启动")!!; set(v) { prefs.edit().putString("status", v).apply() }
    var shizukuStatus: String get() = prefs.getString("shizukuStatus", "尚未连接")!!; set(v) { prefs.edit().putString("shizukuStatus", v).apply() }
    val deviceName: String get() = "${Build.MANUFACTURER} ${Build.MODEL}".filterNot(Char::isISOControl).take(64)
    val paired: Boolean get() = pin.matches(Regex("[a-f0-9]{64}")) && hosts.isNotEmpty()
    fun savePair(host: String, port: Int, pin: String, name: String) {
        check(prefs.edit().putString("hosts", host).putInt("port", port)
            .putString("pin", pin).putString("serverName", name.take(64)).commit()) { "无法保存配对" }
    }
    fun forget() {
        enabled = false
        prefs.edit().remove("pin").remove("hosts").remove("serverName").remove("bluetoothAddress").apply()
    }
}
