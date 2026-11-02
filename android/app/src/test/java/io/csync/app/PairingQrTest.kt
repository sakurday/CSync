package io.csync.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter

class PairingQrTest {
    private fun payload() = JSONObject().put("v", 1).put("hosts", org.json.JSONArray(listOf("192.168.1.2", "10.0.0.2")))
        .put("port", 45876).put("pin", "a".repeat(64)).put("token", "b".repeat(64))
    private fun parse(value: JSONObject) = PairingQr.parse("CSYNC:$value")

    @Test fun decodesOfflineQrAndPreservesIdentityAndEndpoints() {
        val raw = "CSYNC:${payload()}"
        val matrix = QRCodeWriter().encode(raw, BarcodeFormat.QR_CODE, 400, 400)
        val pixels = IntArray(400 * 400) { i -> if (matrix[i % 400, i / 400]) 0xff000000.toInt() else 0xffffffff.toInt() }
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(400, 400, pixels)))).text
        val invitation = PairingQr.parse(decoded)
        assertEquals(listOf("192.168.1.2", "10.0.0.2"), invitation.hosts)
        assertEquals(45876, invitation.port)
        assertEquals("a".repeat(64), invitation.pin)
        assertEquals("b".repeat(64), invitation.token)
        assertFalse(invitation.toString().contains(invitation.token))
    }

    @Test fun rejectsUntrustedDestinationsAndMalformedInvitations() {
        for (host in listOf("127.0.0.1", "8.8.8.8", "example.com", "192.168.1.2/evil", "192.168.1.999", "192.168.01.2")) {
            assertThrows(IllegalArgumentException::class.java) { parse(payload().put("hosts", org.json.JSONArray(listOf(host)))) }
        }
        for ((key, value) in listOf("v" to 2, "v" to 1.5, "port" to 0, "port" to 65536, "port" to 1.5, "pin" to "wrong", "token" to "")) {
            assertThrows(IllegalArgumentException::class.java) { parse(payload().put(key, value)) }
        }
        assertThrows(IllegalArgumentException::class.java) { parse(payload().put("hosts", org.json.JSONArray())) }
        assertThrows(IllegalArgumentException::class.java) { PairingQr.parse("https://example.com") }
        assertThrows(IllegalArgumentException::class.java) { PairingQr.parse("CSYNC:" + "x".repeat(2048)) }
    }
}
