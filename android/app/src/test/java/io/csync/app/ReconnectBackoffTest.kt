package io.csync.app

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class ReconnectBackoffTest {
    @Test fun repeatedFailuresBecomeInfrequentAndStayCapped() {
        val backoff = ReconnectBackoff { 0 }
        assertEquals(listOf(2_000L, 5_000L, 15_000L, 30_000L, 60_000L, 120_000L, 300_000L),
            List(7) { backoff.nextDelayMs() })
        repeat(100) { assertEquals(300_000L, backoff.nextDelayMs()) }
    }

    @Test fun flappingSessionsDoNotResetBackoffButStableSessionsDo() {
        val backoff = ReconnectBackoff { 0 }
        backoff.nextDelayMs()
        backoff.sessionEnded(59_999)
        assertEquals(5_000L, backoff.nextDelayMs())
        backoff.sessionEnded(60_000)
        assertEquals(2_000L, backoff.nextDelayMs())
        backoff.reset()
        assertEquals(2_000L, backoff.nextDelayMs())
    }

    @Test fun jitterAddsAtMostTenPercent() {
        val backoff = ReconnectBackoff { it }
        assertEquals(2_200L, backoff.nextDelayMs())
        repeat(6) { backoff.nextDelayMs() }
        assertEquals(330_000L, backoff.nextDelayMs())
    }

    @Test fun networkChangeInterruptsLongBackoffAndOfflineWait(): Unit = runBlocking {
        val events = Channel<Unit>(Channel.CONFLATED)
        for (delayMs in listOf(300_000L, null)) {
            withTimeout(2_000) {
                val waiting = async { awaitReconnect(events, delayMs) }
                yield()
                assertFalse(waiting.isCompleted)
                events.send(Unit)
                assertTrue(waiting.await())
            }
        }
        events.close()
    }

    @Test fun timeoutRetriesAndNetworkBurstsAreConflated(): Unit = runBlocking {
        val events = Channel<Unit>(Channel.CONFLATED)
        assertFalse(awaitReconnect(events, 1))
        repeat(20) { events.trySend(Unit) }
        assertTrue(awaitReconnect(events, 300_000))
        assertTrue(events.tryReceive().isFailure)
        events.close()
    }

    @Test fun stoppingCancelsOfflineWait(): Unit = runBlocking {
        val events = Channel<Unit>(Channel.CONFLATED)
        val waiting = async { awaitReconnect(events, null) }
        yield()
        waiting.cancelAndJoin()
        assertTrue(waiting.isCancelled)
        events.close()
    }

    @Test fun closingNetworkMonitorCancelsTimedAndOfflineWaits(): Unit = runBlocking {
        for (delayMs in listOf(300_000L, null)) {
            val events = Channel<Unit>(Channel.CONFLATED)
            val waiting = async { awaitReconnect(events, delayMs) }
            yield()
            events.close()
            try {
                waiting.await()
                fail("A closed network monitor must cancel reconnecting")
            } catch (_: CancellationException) {
                assertTrue(waiting.isCancelled)
            }
        }
    }
}
