package io.csync.app

import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CancellationException

data class HostProfile(val host: String, val port: Int, val pin: String, val name: String) {
    val id: String get() = "$host:$port"
    fun json() = JSONObject().put("host", host).put("port", port).put("pin", pin).put("name", name)
    companion object {
        fun decode(value: String): List<HostProfile> {
            val array = JSONArray(value)
            return (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                HostProfile(item.getString("host"), item.getInt("port"), item.getString("pin"), item.getString("name")).also {
                    require(it.host.isNotBlank() && it.port in 1..65535 && it.pin.matches(Regex("[a-f0-9]{64}")))
                }
            }.distinctBy { it.id }
        }
        fun encode(hosts: List<HostProfile>) = JSONArray().apply { hosts.forEach { put(it.json()) } }.toString()
    }
}

// A failed TLS or application handshake must advance to the next profile.
internal suspend fun <T : Any> firstAvailable(hosts: List<HostProfile>, attempt: suspend (HostProfile) -> T): Pair<HostProfile, T>? {
    for (host in hosts) {
        try { return host to attempt(host) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { /* Try the next host in the saved order. */ }
    }
    return null
}
