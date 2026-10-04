package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioTrack
import android.os.Build

/** AudioTrack exposes its configured attributes only from API 29 onward. */
internal object AudioTrackAttributesCompat {
    fun resolve(track: AudioTrack, configured: AudioAttributes): AudioAttributes =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) track.audioAttributes else configured
}
