package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioTrack
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AudioTrackAttributesCompatTest {
    @Test
    @Config(sdk = [28])
    fun androidNineKeepsConstructionAttributesWithoutCallingTheMissingGetter() {
        val track = mock(AudioTrack::class.java)
        val configured = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        assertSame(configured, AudioTrackAttributesCompat.resolve(track, configured))
        verifyNoInteractions(track)
    }

    @Test
    @Config(sdk = [29])
    fun androidTenReadsActualAttributesFromTheTrack() {
        val track = mock(AudioTrack::class.java)
        val configured = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        val actual = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).build()
        `when`(track.audioAttributes).thenReturn(actual)
        assertSame(actual, AudioTrackAttributesCompat.resolve(track, configured))
        verify(track).audioAttributes
    }
}
