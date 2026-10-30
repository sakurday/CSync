package io.csync.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairingRegressionTest {
    @Test fun actualDesktopConfirmation() {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("host") ?: error("missing host")
        val settings = Settings(InstrumentationRegistry.getInstrumentation().targetContext)
        val (pin, name) = Transport.pair(host, 45876, DeviceIdentity(), settings.deviceName) { code ->
            InstrumentationRegistry.getInstrumentation().sendStatus(1, android.os.Bundle().apply { putString("verificationCode", code) })
            Thread.sleep(1500)
            true
        }
        settings.savePair(host, 45876, pin, name)
        Transport.tcp(host, 45876, DeviceIdentity(), pin).use { link ->
            Protocol.write(link.output, Protocol.message("hello", "version" to Protocol.VERSION, "name" to settings.deviceName))
            check(Protocol.read(link.input).getString("type") == "ready")
        }
    }
}
