package com.shilapi.xcertplay.media

/** Only independent media LPCM packets may be skipped to catch up to live audio. */
internal class AudioBacklogPolicy(bufferMillis: Int) {
    private val limitNs = maxOf(500, MediaAudioBuffer.sanitize(bufferMillis)) * 1_000_000L
    private val targetNs = 150_000_000L
    fun shouldCatchUp(oldestAgeNs: Long, isMedia: Boolean, isLpcm: Boolean, playing: Boolean): Boolean =
        isMedia && isLpcm && playing && oldestAgeNs > limitNs
    fun shouldDiscard(ageNs: Long): Boolean = ageNs > targetNs
}
