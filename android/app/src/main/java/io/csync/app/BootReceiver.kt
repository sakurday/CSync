package io.csync.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Settings(context).let { it.enabled && it.paired }) {
            runCatching { context.startForegroundService(Intent(context, SyncService::class.java)) }
                .onFailure { Settings(context).status = msg("background.boot_denied") }
        }
    }
}
