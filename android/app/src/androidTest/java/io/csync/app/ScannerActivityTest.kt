package io.csync.app

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerActivityTest {
    @Test fun cameraStartsAndReleasesWithoutChangingHosts() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val prefs = context.getSharedPreferences("settings", 0)
        val hosts = prefs.getString("hostProfiles", null)
        var activity: ScannerActivity? = null
        try {
            activity = instrument.startActivitySync(Intent(context, ScannerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ScannerActivity
            fun preview(view: View): PreviewView? {
                if (view is PreviewView) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) preview(view.getChildAt(i))?.let { return it }
                return null
            }
            var streaming = false
            val deadline = System.currentTimeMillis() + 15_000
            while (!streaming && System.currentTimeMillis() < deadline) {
                instrument.runOnMainSync {
                    streaming = preview(activity.window.decorView)?.previewStreamState?.value == PreviewView.StreamState.STREAMING
                }
                Thread.sleep(100)
            }
            assertTrue("CameraX preview did not start (grant CAMERA for this test)", streaming)
            assertTrue("Scanner must not alter paired hosts", hosts == prefs.getString("hostProfiles", null))
        } finally { instrument.runOnMainSync { activity?.finish() } }
    }
}
