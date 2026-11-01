package io.csync.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HostProfileTest {
    private val hosts = listOf(
        HostProfile("192.168.1.2", 45876, "a".repeat(64), "家中电脑"),
        HostProfile("example.com", 12345, "b".repeat(64), "另一台电脑"),
        HostProfile("192.168.1.3", 45876, "c".repeat(64), "备用电脑")
    )
    @Test fun orderAndIndependentIdentitiesSurviveStorage() {
        val reordered = listOf(hosts[2], hosts[0], hosts[1])
        assertEquals(reordered, HostProfile.decode(HostProfile.encode(reordered)))
        assertEquals(emptyList<HostProfile>(), HostProfile.decode(HostProfile.encode(emptyList())))
    }
    @Test fun retiredTransportFieldsDoNotLoseNetworkPairings() {
        val legacy = org.json.JSONArray().apply {
            hosts.forEach { put(it.json().put("bluetooth", "00:11:22:33:44:55")) }
        }.toString()
        val decoded = HostProfile.decode(legacy)
        assertEquals(hosts, decoded)
        assertFalse(HostProfile.encode(decoded).contains("bluetooth"))
    }
    @Test fun failureAdvancesInOrderAndStopsAtFirstReadyHost() = runBlocking {
        val attempted = mutableListOf<HostProfile>()
        val result = firstAvailable(hosts) {
            attempted.add(it)
            if (it == hosts[0]) error("TLS or ready failed")
            "ready for ${it.pin}"
        }
        assertEquals(hosts.take(2), attempted)
        assertEquals(hosts[1], result!!.first)
        assertEquals("ready for ${hosts[1].pin}", result.second)
    }
    @Test fun allFailedAndEmptyListReturnNoConnection() = runBlocking {
        val attempted = mutableListOf<HostProfile>()
        assertNull(firstAvailable<String>(hosts) { attempted.add(it); error("offline") })
        assertEquals(hosts, attempted)
        assertNull(firstAvailable<String>(emptyList()) { error("must not attempt") })
    }
    @Test fun cancellationDoesNotTryAnotherHost() {
        val attempted = mutableListOf<HostProfile>()
        assertThrows(CancellationException::class.java) {
            runBlocking { firstAvailable<String>(hosts) { attempted.add(it); throw CancellationException() } }
        }
        assertEquals(hosts.take(1), attempted)
    }
}
