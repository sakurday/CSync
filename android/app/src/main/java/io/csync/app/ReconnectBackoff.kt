package io.csync.app

import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun awaitReconnect(changes: ReceiveChannel<Unit>, delayMs: Long?): Boolean {
    suspend fun receiveChange(): Boolean {
        if (changes.receiveCatching().isClosed) throw CancellationException("Network monitoring stopped")
        return true
    }
    if (delayMs == null) return receiveChange()
    return withTimeoutOrNull(delayMs) { receiveChange() } == true
}

// Coroutine delays do not wake a sleeping device. No alarms, polling or wake locks.
internal class ReconnectBackoff(private val jitter: (Long) -> Long = { Random.nextLong(it + 1) }) {
    private var failures = 0

    fun reset() { failures = 0 }

    fun sessionEnded(durationMs: Long) {
        // A host repeatedly accepting and dropping connections must still back off.
        if (durationMs >= 60_000L) reset()
    }

    fun nextDelayMs(): Long {
        val base = intervals[failures.coerceAtMost(intervals.lastIndex)]
        failures = (failures + 1).coerceAtMost(intervals.lastIndex)
        return base + jitter(base / 10)
    }

    companion object {
        private val intervals = longArrayOf(2_000, 5_000, 15_000, 30_000, 60_000, 120_000, 300_000)
    }
}
