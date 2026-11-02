package io.csync.app

import org.json.JSONObject

/** A short-lived invitation, never stored in preferences or included in errors. */
data class PairingQr(val hosts: List<String>, val port: Int, val pin: String, val token: String) {
    companion object {
        fun parse(raw: String): PairingQr {
            require(raw.length <= 2048 && raw.startsWith("CSYNC:"))
            val json = JSONObject(raw.substring(6))
            require(json.get("v") == 1)
            val port = json.getInt("port")
            require(port in 1..65535 && json.get("port") is Int)
            val pin = json.getString("pin")
            val token = json.getString("token")
            require(pin.matches(Regex("[0-9a-f]{64}")) && token.matches(Regex("[0-9a-f]{64}")))
            val values = json.getJSONArray("hosts")
            require(values.length() in 1..4)
            val hosts = (0 until values.length()).map { index ->
                val host = values.getString(index)
                val parts = host.split('.')
                require(parts.size == 4 && parts.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() in 0..255 })
                val bytes = parts.map(String::toInt)
                require(bytes[0] == 10 || (bytes[0] == 172 && bytes[1] in 16..31) || (bytes[0] == 192 && bytes[1] == 168))
                host
            }.distinct()
            return PairingQr(hosts, port, pin, token)
        }
    }
    // Do not accidentally expose the invitation token in diagnostic output.
    override fun toString() = "PairingQr(<redacted>)"
}
