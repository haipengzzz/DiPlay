package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent

/** Successful sessions are distinct from tentative manual mode selections. */
internal object StartupConnectionPreference {
    const val EXTRA_BOOT_AUTO_CONNECT = "com.shilapi.xcertplay.BOOT_AUTO_CONNECT"
    private const val PREFS = "diplay_startup_connection"
    private const val LAST_SUCCESSFUL_WIRELESS = "last_successful_wireless"

    fun rememberSuccessfulTransport(context: Context, wireless: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(LAST_SUCCESSFUL_WIRELESS, wireless).apply()
    }

    fun wirelessForAutoConnect(context: Context, selectedWireless: Boolean): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(LAST_SUCCESSFUL_WIRELESS, selectedWireless)

    fun consumeBootAutoConnect(intent: Intent, bootEnabled: Boolean): Boolean {
        val requested = intent.getBooleanExtra(EXTRA_BOOT_AUTO_CONNECT, false)
        intent.removeExtra(EXTRA_BOOT_AUTO_CONNECT)
        return bootEnabled && requested
    }

    fun report(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val mode = if (!prefs.contains(LAST_SUCCESSFUL_WIRELESS)) "unset"
            else if (prefs.getBoolean(LAST_SUCCESSFUL_WIRELESS, false)) "wireless" else "USB"
        return "Startup lastSuccessfulTransport=$mode"
    }
}
