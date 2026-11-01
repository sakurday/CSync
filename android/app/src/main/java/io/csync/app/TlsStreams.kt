package io.csync.app

import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException

/** A bounded TLS 1.3 byte-stream adapter for the network connection.
 * One reader and one writer are supported, as required by SSLEngine's contract.
 * No raw application bytes are ever written to the transport.
 */
class TlsStreams(private val rawIn: InputStream, private val rawOut: OutputStream,
                 private val transport: Closeable, private val engine: SSLEngine) : Closeable {
    private val netIn = ByteBuffer.allocate(65536).apply { limit(0) }
    private val appIn = ByteBuffer.allocate(65536).apply { limit(0) }
    private val netOut = ByteBuffer.allocate(65536)
    private val writeLock = Any()
    private val empty = ByteBuffer.allocate(0)
    val peerPin: String get() = Protocol.hash(engine.session.peerCertificates.single().encoded)
    @Volatile private var closed = false

    init {
        engine.useClientMode = true
        engine.enabledProtocols = arrayOf("TLSv1.3")
        engine.beginHandshake()
        handshake()
    }
    private fun tasks() { while (true) { val task = engine.delegatedTask ?: break; task.run() } }
    private fun networkRead() {
        netIn.compact()
        if (!netIn.hasRemaining()) throw SSLException("TLS record exceeds buffer")
        val n = rawIn.read(netIn.array(), netIn.position(), netIn.remaining())
        if (n < 0) throw EOFException(msg("protocol.connection_closed"))
        netIn.position(netIn.position() + n)
        netIn.flip()
    }
    private fun wrap(source: ByteBuffer): SSLEngineResult = synchronized(writeLock) {
        netOut.clear()
        val result = engine.wrap(source, netOut)
        if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) throw SSLException("TLS output overflow")
        if (result.status == SSLEngineResult.Status.CLOSED) throw EOFException("TLS closed")
        netOut.flip()
        if (netOut.hasRemaining()) { rawOut.write(netOut.array(), netOut.position(), netOut.remaining()); rawOut.flush() }
        if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) tasks()
        result
    }
    private fun handshake() {
        val scratch = ByteBuffer.allocate(65536)
        var steps = 0
        while (true) {
            if (++steps > 256) throw SSLException("TLS handshake exceeded limit")
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_TASK -> tasks()
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> wrap(empty)
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    // Feed network bytes before invoking the platform engine.
                    if (!netIn.hasRemaining()) networkRead()
                    scratch.clear()
                    val result = engine.unwrap(netIn, scratch)
                    when (result.status) {
                        SSLEngineResult.Status.BUFFER_UNDERFLOW -> networkRead()
                        SSLEngineResult.Status.OK -> if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) tasks()
                        else -> throw SSLException("TLS handshake failed")
                    }
                }
                SSLEngineResult.HandshakeStatus.FINISHED, SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING -> return
                else -> throw SSLException("unsupported TLS state")
            }
        }
    }
    val input: InputStream = object : InputStream() {
        override fun read(): Int { val one = ByteArray(1); return if (read(one) < 0) -1 else one[0].toInt() and 255 }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
            if (length == 0) return 0
            while (!appIn.hasRemaining()) {
                if (closed) return -1
                if (!netIn.hasRemaining()) networkRead()
                appIn.clear()
                val result = engine.unwrap(netIn, appIn)
                appIn.flip()
                when (result.status) {
                    SSLEngineResult.Status.BUFFER_UNDERFLOW -> networkRead()
                    SSLEngineResult.Status.CLOSED -> return -1
                    SSLEngineResult.Status.BUFFER_OVERFLOW -> throw SSLException("TLS plaintext overflow")
                    SSLEngineResult.Status.OK -> {
                        if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) tasks()
                        if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) wrap(ByteBuffer.allocate(0))
                    }
                    else -> throw SSLException("invalid TLS state")
                }
            }
            val n = minOf(length, appIn.remaining())
            appIn.get(bytes, offset, n)
            return n
        }
    }
    val output: OutputStream = object : OutputStream() {
        override fun write(value: Int) { write(byteArrayOf(value.toByte())) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            val source = ByteBuffer.wrap(bytes, offset, length)
            while (source.hasRemaining()) {
                if (closed) throw EOFException(msg("protocol.connection_closed"))
                val result = wrap(source)
                if (result.bytesConsumed() == 0 && result.bytesProduced() == 0) throw SSLException("TLS made no progress")
            }
        }
        override fun flush() { synchronized(writeLock) { rawOut.flush() } }
    }
    override fun close() { closed = true; runCatching { transport.close() } }
}
