package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class StartupConnectionPreferenceTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        app.getSharedPreferences("diplay_startup_connection", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun firstLaunchFallsBackToSelectedMode() {
        assertTrue(StartupConnectionPreference.wirelessForAutoConnect(app, true))
        assertFalse(StartupConnectionPreference.wirelessForAutoConnect(app, false))
    }
    @Test fun unsuccessfulWirelessSelectionDoesNotReplaceSuccessfulUsbMode() {
        StartupConnectionPreference.rememberSuccessfulTransport(app, false)
        AirPlayPersistence.saveWirelessEnabled(app, true)
        assertFalse(StartupConnectionPreference.wirelessForAutoConnect(app,
            AirPlayPersistence.loadWirelessEnabled(app)))
        assertTrue(AirPlayPersistence.loadWirelessEnabled(app)) // Explicit manual selection remains intact.
        assertTrue(StartupConnectionPreference.report(app).contains("Transport=USB"))
    }
    @Test fun successfulWirelessSessionReplacesRememberedUsbMode() {
        StartupConnectionPreference.rememberSuccessfulTransport(app, false)
        StartupConnectionPreference.rememberSuccessfulTransport(app, true)
        assertTrue(StartupConnectionPreference.wirelessForAutoConnect(app, false))
        assertTrue(StartupConnectionPreference.report(app).contains("Transport=wireless"))
    }
    @Test fun bootRequestIsConsumedOnceAndRequiresEnabledBootSetting() {
        val intent = Intent().putExtra(StartupConnectionPreference.EXTRA_BOOT_AUTO_CONNECT, true)
        assertTrue(StartupConnectionPreference.consumeBootAutoConnect(intent, true))
        assertFalse(StartupConnectionPreference.consumeBootAutoConnect(intent, true))
        intent.putExtra(StartupConnectionPreference.EXTRA_BOOT_AUTO_CONNECT, true)
        assertFalse(StartupConnectionPreference.consumeBootAutoConnect(intent, false))
        assertFalse(intent.hasExtra(StartupConnectionPreference.EXTRA_BOOT_AUTO_CONNECT))
    }
    @Test fun manualLaunchDoesNotPretendToBeBoot() {
        assertFalse(StartupConnectionPreference.consumeBootAutoConnect(Intent(), true))
        assertTrue(StartupConnectionPreference.report(app).contains("Transport=unset"))
    }
}
