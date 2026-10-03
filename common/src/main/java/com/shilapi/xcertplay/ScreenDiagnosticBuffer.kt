package com.shilapi.xcertplay

/** Main-thread bounded, redacted screen history. File history is retained separately. */
internal class ScreenDiagnosticBuffer(private val maxLines: Int = 200) {
    private data class Entry(val timestamp: Long, val text: String)
    private val entries = ArrayDeque<Entry>()
    init { require(maxLines > 0) }
    fun append(message: String, now: Long, format: (String) -> String): Boolean {
        val safe = DiagnosticRedactor.redact(message) ?: return false
        entries.addLast(Entry(now, format(safe)))
        while (entries.size > maxLines) entries.removeFirst()
        return true
    }
    fun clear() = entries.clear()
    fun expire(cutoff: Long) {
        while (entries.firstOrNull()?.timestamp?.let { it <= cutoff } == true) entries.removeFirst()
    }
    fun oldestTimestamp(): Long? = entries.firstOrNull()?.timestamp
    fun text(): String = entries.joinToString("\n") { it.text }
}
