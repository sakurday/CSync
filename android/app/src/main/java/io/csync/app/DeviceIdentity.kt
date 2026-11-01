package io.csync.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLContext
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

class DeviceIdentity {
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    init {
        synchronized(lock) {
            if (!store.containsAlias(ALIAS)) {
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                    initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        // Platform Conscrypt delegates prehashed TLS signatures
                        // through NONEwithECDSA to Android Keystore.
                        .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                        .setCertificateSubject(X500Principal("CN=CSync Android"))
                        .setCertificateSerialNumber(BigInteger(128, SecureRandom()))
                        .setCertificateNotBefore(Date(1577836800000L))
                        .setCertificateNotAfter(Date(2524608000000L))
                        .setUserAuthenticationRequired(false).build())
                }.generateKeyPair()
            }
        }
    }
    private val cert: X509Certificate get() = store.getCertificate(ALIAS) as X509Certificate
    val id: String get() = Protocol.hash(cert.encoded)

    fun context(pin: String?): SSLContext {
        require(pin == null || pin.matches(Regex("[a-f0-9]{64}")))
        val keys = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf(ALIAS)
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String = ALIAS
            override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String = ALIAS
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
            override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(cert)
            override fun getPrivateKey(alias: String?): PrivateKey = store.getKey(ALIAS, null) as PrivateKey
        }
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) { throw java.security.cert.CertificateException("client-only trust manager") }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain == null || chain.size != 1 || (pin != null && Protocol.hash(chain[0].encoded) != pin)) throw java.security.cert.CertificateException(msg("pair.identity_mismatch"))
                chain[0].checkValidity()
            }
        }
        return SSLContext.getInstance("TLSv1.3").apply { init(arrayOf(keys), arrayOf(trust), SecureRandom()) }
    }
    companion object { internal const val ALIAS = "csync.device.v1.tls"; private val lock = Any() }
}
