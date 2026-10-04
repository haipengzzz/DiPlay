package com.shilapi.xcertplay

import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InlineNoticeTest {
    private lateinit var activity: DiPlayActivity
    private lateinit var panel: LinearLayout
    @Before fun setup() {
        activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        panel = LinearLayout(activity)
        activity.javaClass.getDeclaredField("noticeContainer").apply { isAccessible = true }.set(activity, panel)
    }
    @After fun drainLogs() { assertTrue(AsyncDiagnosticLog.awaitIdle(2_000)) }
    private fun show(message: String, actions: List<Pair<String, () -> Unit>> = emptyList()) {
        activity.javaClass.getDeclaredMethod("showNotice", String::class.java, List::class.java)
            .apply { isAccessible = true }.invoke(activity, message, actions)
    }
    @Test fun informationIsInlineWithoutDialogOrToast() {
        show("Saved for the next connection")
        assertEquals(View.VISIBLE, panel.visibility)
        assertEquals("Saved for the next connection", (panel.getChildAt(0) as TextView).text.toString())
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        assertNull(ShadowToast.getLatestToast())
    }
    @Test fun actionsWorkAndNewNoticesRemoveOldActions() {
        var selected = false
        show("Report saved", listOf("Share" to { selected = true }))
        (panel.getChildAt(1) as Button).performClick()
        assertTrue(selected)
        show("Bluetooth is off")
        assertEquals(2, panel.childCount) // Message and dismiss, not the old Share action.
        (panel.getChildAt(1) as Button).performClick()
        assertEquals(View.GONE, panel.visibility)
        assertNull(activity.javaClass.getDeclaredField("noticeMessage").apply { isAccessible = true }.get(activity))
    }
    @Test fun redactedEvidenceIsWrittenToSeparateDiagnostics() {
        show("UI notice token=private-token")
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        val log = File(activity.filesDir, "logs/ui-notices.log").readText()
        assertTrue(log.contains("UI notice"))
        assertFalse(log.contains("private-token"))
    }
    @Test fun replacingThePanelRestoresTheCurrentNoticeAndActions() {
        show("Permission needed", listOf("Settings" to {}))
        val replacement = LinearLayout(activity)
        activity.javaClass.getDeclaredField("noticeContainer").apply { isAccessible = true }.set(activity, replacement)
        activity.javaClass.getDeclaredMethod("updateNotice").apply { isAccessible = true }.invoke(activity)
        assertEquals("Permission needed", (replacement.getChildAt(0) as TextView).text.toString())
        assertEquals("Settings", (replacement.getChildAt(1) as Button).text.toString())
    }
}
