package io.csync.app

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import org.junit.Assert.*
import org.junit.Test

class SyncRuntimeTest {
    @Test fun connectionChangesInvalidateUiWithoutPreferenceOrMessageChanges() {
        val original = SyncRuntime.connected
        SyncRuntime.connected = false
        Snapshot.notifyObjectsInitialized()
        Snapshot.sendApplyNotifications()
        val observer = SnapshotStateObserver { it() }
        var invalidated = false
        val scope = Any()
        observer.start()
        try {
            for (connected in listOf(true, false)) {
                invalidated = false
                observer.observeReads(scope, { invalidated = true }) { SyncRuntime.connected }
                // The network worker publishes a successful handshake/disconnect without
                // changing preferences: the same host/transport/message may already be saved.
                Thread { Snapshot.withMutableSnapshot { SyncRuntime.connected = connected } }.apply { start(); join() }
                assertTrue("Connection transition to $connected must invalidate the UI", invalidated)
                assertEquals(connected, SyncRuntime.connected)
            }
        } finally {
            observer.stop()
            observer.clear()
            SyncRuntime.connected = original
        }
    }
}
