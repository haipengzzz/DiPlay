package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class VideoRecoveryPolicyTest {
    @Test fun startupHasFiniteGraceWithoutChangingSteadyStateLatency() {
        val policy = VideoRecoveryPolicy()
        assertEquals(1_500_000_000L, policy.frameAgeLimitNs(false))
        assertEquals(250_000_000L, policy.frameAgeLimitNs(true))
    }

    @Test fun persistentStartupFailureEscalatesAndStaysAtSoftwareTier() {
        val policy = VideoRecoveryPolicy()
        assertFalse(policy.onRecovery(false))
        assertTrue(policy.onRecovery(false))
        assertEquals(1, policy.tier)
        assertFalse(policy.onRecovery(false))
        assertTrue(policy.onRecovery(false))
        assertEquals(2, policy.tier)
        repeat(10) { assertFalse(policy.onRecovery(false)) }
        assertEquals(2, policy.tier)
    }

    @Test fun SteadyStateNetworkGapsDoNotForceSoftwareDecode() {
        val policy = VideoRecoveryPolicy()
        repeat(10) { assertFalse(policy.onRecovery(true)) }
        assertEquals(0, policy.tier)
        policy.onRecovery(false)
        policy.reset()
        assertFalse(policy.onRecovery(false))
        assertEquals(0, policy.tier)
    }

    @Test fun android9SoftwareDetectionDoesNotMistakeVendorCodecsForSoftware() {
        assertTrue(isLegacySoftwareDecoder("OMX.google.h264.decoder"))
        assertTrue(isLegacySoftwareDecoder("c2.android.avc.decoder"))
        assertFalse(isLegacySoftwareDecoder("OMX.MTK.VIDEO.DECODER.AVC"))
        assertFalse(isLegacySoftwareDecoder("c2.mtk.avc.decoder"))
    }
}
