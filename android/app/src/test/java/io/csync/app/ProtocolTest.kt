package io.csync.app

import org.junit.Assert.*
import org.junit.Test
import java.io.*

class ProtocolTest {
    @Test fun unicodeAndEscapesRoundTrip() {
        val clip = Clip.create("a".repeat(64), 1, "你好\n\"手机\"\u0001🙂")
        val bytes = ByteArrayOutputStream()
        Protocol.write(DataOutputStream(bytes), Protocol.message("clip", "clip" to clip.json()))
        val received = Protocol.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
        assertEquals(clip, Clip.parse(received.getJSONObject("clip")))
    }
    @Test fun oversizedHeaderRejectedBeforeAllocation() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).writeInt(Int.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) { Protocol.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))) }
    }
    @Test fun invalidHashAndUtf8SizeRejected() {
        val clip = Clip.create("a".repeat(64), 1, "hello")
        assertThrows(IllegalArgumentException::class.java) { clip.copy(text = "changed").validate() }
        assertThrows(IllegalArgumentException::class.java) { Clip.create("a".repeat(64), 1, "你".repeat(Protocol.MAX_TEXT / 3 + 1)) }
    }
    @Test fun contentHashMatchesRustFixture() {
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", Protocol.hash("hello"))
    }
}
