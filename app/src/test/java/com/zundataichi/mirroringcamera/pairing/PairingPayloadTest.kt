package com.zundataichi.mirroringcamera.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingPayloadTest {

    private val valid = """
        {"v":1,"type":"camera-pairing","server_url":"http://192.168.1.10:3001",
         "code":"K7F3A9QMX2","expires_at":"2026-08-30T06:00:00+00:00"}
    """.trimIndent()

    @Test
    fun `parses a payload produced by the server`() {
        val payload = PairingPayload.parse(valid)!!
        assertEquals("http://192.168.1.10:3001", payload.serverUrl)
        assertEquals("K7F3A9QMX2", payload.code)
        assertEquals("2026-08-30T06:00:00+00:00", payload.expiresAt)
    }

    @Test
    fun `ignores unknown fields so the server can add some later`() {
        val payload = PairingPayload.parse(
            """{"v":1,"type":"camera-pairing","server_url":"https://cam.example.com",
                "code":"AAAAABBBBB","expires_at":null,"hint":"future"}"""
        )!!
        assertEquals("https://cam.example.com", payload.serverUrl)
        assertNull(payload.expiresAt)
    }

    @Test
    fun `rejects barcodes that are not ours`() {
        assertNull(PairingPayload.parse("https://example.com"))
        assertNull(PairingPayload.parse(""))
        assertNull(PairingPayload.parse("not json at all"))
        assertNull(PairingPayload.parse("""{"v":1,"type":"something-else","server_url":"http://a","code":"B"}"""))
    }

    @Test
    fun `rejects a version it cannot speak`() {
        assertNull(
            PairingPayload.parse(
                """{"v":2,"type":"camera-pairing","server_url":"http://a","code":"B"}"""
            )
        )
    }

    @Test
    fun `rejects a payload missing what it needs to connect`() {
        assertNull(PairingPayload.parse("""{"v":1,"type":"camera-pairing","code":"B"}"""))
        assertNull(PairingPayload.parse("""{"v":1,"type":"camera-pairing","server_url":"http://a"}"""))
        assertNull(
            PairingPayload.parse("""{"v":1,"type":"camera-pairing","server_url":"","code":"B"}""")
        )
        assertNull(
            PairingPayload.parse("""{"v":1,"type":"camera-pairing","server_url":"http://a","code":"---"}""")
        )
    }

    @Test
    fun `fills in a missing scheme and drops a trailing slash`() {
        assertEquals("http://192.168.1.10:3001", PairingPayload.normalizeServerUrl("192.168.1.10:3001"))
        assertEquals("http://a.example.com", PairingPayload.normalizeServerUrl("http://a.example.com/"))
        assertEquals("https://a.example.com", PairingPayload.normalizeServerUrl(" https://a.example.com "))
        assertEquals("", PairingPayload.normalizeServerUrl("   "))
    }

    @Test
    fun `accepts a code typed in with the display grouping`() {
        assertEquals("K7F3A9QMX2", PairingPayload.normalizeCode("k7f3a-9qmx2"))
        assertEquals("K7F3A9QMX2", PairingPayload.normalizeCode(" K7F3A 9QMX2 "))
    }
}
