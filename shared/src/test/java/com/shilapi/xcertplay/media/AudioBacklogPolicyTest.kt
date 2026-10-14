package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class AudioBacklogPolicyTest {
    @Test fun staleMusicIsCaughtUpButNormalJitterIsRetained() {
        val policy = AudioBacklogPolicy(300)
        assertFalse(policy.shouldCatchUp(500_000_000L, true, true, true))
        assertTrue(policy.shouldCatchUp(501_000_000L, true, true, true))
        assertTrue(policy.shouldDiscard(151_000_000L))
        assertFalse(policy.shouldDiscard(150_000_000L))
    }
    @Test fun callsNavigationCompressedAudioAndPrebufferAreNeverSkipped() {
        val policy = AudioBacklogPolicy(300)
        assertFalse(policy.shouldCatchUp(5_000_000_000L, false, true, true))
        assertFalse(policy.shouldCatchUp(5_000_000_000L, true, false, true))
        assertFalse(policy.shouldCatchUp(5_000_000_000L, true, true, false))
    }
    @Test fun deliberateLargeJitterBufferIsRespected() {
        val policy = AudioBacklogPolicy(1000)
        assertFalse(policy.shouldCatchUp(900_000_000L, true, true, true))
        assertTrue(policy.shouldCatchUp(1_001_000_000L, true, true, true))
    }
    @Test fun oldBurstDropsToFreshTailNotToAnEmptyBuffer() {
        val policy = AudioBacklogPolicy(300)
        val ages = listOf(900L, 800L, 400L, 200L, 150L, 100L, 0L).map { it * 1_000_000 }
        assertEquals(listOf(150_000_000L, 100_000_000L, 0L), ages.dropWhile(policy::shouldDiscard))
    }
}
