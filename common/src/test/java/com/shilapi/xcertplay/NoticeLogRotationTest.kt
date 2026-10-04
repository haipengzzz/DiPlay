package com.shilapi.xcertplay

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class NoticeLogRotationTest {
    @Test fun noticeRotationNeverOverwritesSessionHistory() {
        val dir = Files.createTempDirectory("diplay-notices").toFile()
        try {
            val sessionHistory = dir.resolve("previous.log").apply { writeText("USB session history") }
            val current = dir.resolve("ui-notices.log").apply { writeText("x".repeat(SessionLogFile.MAX_BYTES.toInt() + 1)) }
            SessionLogFile(current, listOf("ui-notices-previous.log")).use { it.append("Latest notice") }
            assertEquals("USB session history", sessionHistory.readText())
            assertTrue(dir.resolve("ui-notices-previous.log").isFile)
            assertTrue(current.readText().contains("Latest notice"))
        } finally { dir.deleteRecursively() }
    }
}
