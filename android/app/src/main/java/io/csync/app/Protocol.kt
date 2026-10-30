package io.csync.app

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.UUID

object Protocol {
    const val VERSION = 1
    const val MAX_TEXT = 256 * 1024
    const val MAX_FRAME = MAX_TEXT * 6 + 8192
    const val BT_UUID = "b5d37a72-0b98-4bca-9690-f1f709d093d8"
    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun hash(text: String): String = hash(text.toByteArray(Charsets.UTF_8))
    fun message(type: String, vararg fields: Pair<String, Any>): JSONObject = JSONObject().put("type", type).also { obj -> fields.forEach { obj.put(it.first, it.second) } }
    fun read(input: DataInputStream): JSONObject {
        val length = input.readInt()
        require(length in 1..MAX_FRAME) { "消息长度超限" }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        // Reject malformed UTF-8 rather than silently replacing bytes.
        val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        return JSONObject(text)
    }
    fun write(output: DataOutputStream, message: JSONObject) {
        val bytes = message.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_FRAME)
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }
}

data class Clip(val id: String, val origin: String, val sequence: Long, val text: String, val hash: String) {
    fun json(): JSONObject = JSONObject().put("id", id).put("origin", origin).put("sequence", sequence).put("text", text).put("hash", hash)
    fun validate() {
        require(text.isNotEmpty() && text.toByteArray(Charsets.UTF_8).size <= Protocol.MAX_TEXT)
        require(UUID.fromString(id).toString() == id)
        require(origin.matches(Regex("[a-f0-9]{64}")) && sequence > 0)
        require(hash == Protocol.hash(text))
    }
    companion object {
        fun create(origin: String, sequence: Long, text: String): Clip = Clip(UUID.randomUUID().toString(), origin, sequence, text, Protocol.hash(text)).also { it.validate() }
        fun parse(obj: JSONObject): Clip = Clip(obj.getString("id"), obj.getString("origin"), obj.getLong("sequence"), obj.getString("text"), obj.getString("hash")).also { it.validate() }
    }
}
