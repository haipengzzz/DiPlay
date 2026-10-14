package com.shilapi.xcertplay

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class NoticeLogRotationTest {
    @Test fun firstNoticeCreatesItsLogDirectoryWithoutResettingHistory() {
        val dir = Files.createTempDirectory("diplay-first-notice").toFile()
        try {
            val current = dir.resolve("logs/ui-notices.log")
            SessionLogFile(current, listOf("ui-notices-previous.log")).use { it.append("First notice") }
            assertTrue(current.readText().contains("First notice"))
        } finally { dir.deleteRecursively() }
    }
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
