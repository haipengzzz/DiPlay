package com.shilapi.xcertplay.media

/** Configuration may take longer than steady-state decode on older vendor codecs. */
internal class VideoRecoveryPolicy {
    var tier = 0
        private set
    private var startupFailures = 0

    fun frameAgeLimitNs(hasOutput: Boolean): Long =
        if (hasOutput) 250_000_000L else 1_500_000_000L

    fun onRecovery(hasOutput: Boolean): Boolean {
        if (hasOutput) return false
        startupFailures++
        if (startupFailures < 2 || tier >= 2) return false
        startupFailures = 0
        tier++
        return true
    }

    fun reset() { tier = 0; startupFailures = 0 }
}

/** isSoftwareOnly is API 29; these Android platform names are safe on API 28. */
internal fun isLegacySoftwareDecoder(name: String): Boolean =
    name.startsWith("OMX.google.", ignoreCase = true) ||
        name.startsWith("c2.android.", ignoreCase = true)
