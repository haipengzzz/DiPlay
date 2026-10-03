package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class ScreenDiagnosticBufferTest {
    @Test fun keepsOnlyNewestLinesAndExpiresOldHistory() {
        val buffer = ScreenDiagnosticBuffer(3)
        repeat(5) { buffer.append("event$it", it.toLong()) { it } }
        assertEquals("event2\nevent3\nevent4", buffer.text())
        buffer.expire(3)
        assertEquals("event4", buffer.text())
        assertEquals(4L, buffer.oldestTimestamp())
        buffer.clear()
        assertEquals("", buffer.text())
        assertNull(buffer.oldestTimestamp())
    }
    @Test fun screenDoesNotExposeCredentialsOrPrivateDeviceIdentifiers() {
        val buffer = ScreenDiagnosticBuffer()
        assertFalse(buffer.append("token=private", 0) { it })
        assertFalse(buffer.append("PHONE private capture", 0) { it })
        assertTrue(buffer.append("peer=192.168.1.2 address=AA:BB:CC:DD:EE:FF", 0) { it })
        assertFalse(buffer.text().contains("192.168.1.2"))
        assertFalse(buffer.text().contains("AA:BB:CC:DD:EE:FF"))
    }
    @Test fun hostileLongLineIsBoundedAndRejectedLinesDoNotEvictEvidence() {
        val buffer = ScreenDiagnosticBuffer(1)
        buffer.append("x".repeat(10_000), 0) { it }
        assertEquals(700, buffer.text().length)
        buffer.append("password=hidden", 1) { it }
        assertEquals(700, buffer.text().length)
    }
}
