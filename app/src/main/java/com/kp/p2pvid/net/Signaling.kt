package com.kp.p2pvid.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class AuthException(msg: String) : IOException(msg)

/**
 * Authenticated JSON message pipe over one TCP socket on the LAN.
 * Used only to exchange SDP + ICE candidates; media and control go over WebRTC.
 *
 * Frames: u32 length + UTF-8 JSON. After the handshake every frame is
 * {"p": payload, "m": HMAC(key, seq|payload)} with a per-direction sequence number,
 * so a device without the invite secret can neither join nor inject messages.
 */
class SignalChannel internal constructor(private val socket: Socket, private val key: ByteArray) {
    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output = DataOutputStream(socket.getOutputStream().buffered())
    private var sendSeq = 0L
    private var recvSeq = 0L
    private val writeLock = Any()

    val remoteAddress: String? get() = (socket.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress

    suspend fun send(msg: JSONObject) = withContext(Dispatchers.IO) {
        synchronized(writeLock) {
            val payload = msg.toString()
            val frame = JSONObject()
                .put("p", payload)
                .put("m", hex(hmac(key, "${sendSeq++}|$payload".toByteArray())))
            writeFrame(output, frame)
        }
    }

    suspend fun receive(): JSONObject = withContext(Dispatchers.IO) {
        val frame = readFrame(input)
        val payload = frame.getString("p")
        val expected = hmac(key, "${recvSeq++}|$payload".toByteArray())
        if (!MessageDigest.isEqual(expected, unhex(frame.getString("m")))) throw AuthException("bad mac")
        JSONObject(payload)
    }

    fun close() = runCatching { socket.close() }.let { }

    companion object {
        private const val MAX_FRAME = 256 * 1024
        private const val HANDSHAKE_TIMEOUT_MS = 5_000

        fun deriveKey(secret: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest("p2pvid-v1".toByteArray() + secret)

        /**
         * Host side: accept connections until one proves knowledge of the secret.
         * Connections with the wrong code are dropped and waiting continues.
         */
        suspend fun accept(server: ServerSocket, secret: ByteArray): SignalChannel = withContext(Dispatchers.IO) {
            val key = deriveKey(secret)
            while (isActive) {
                val socket = server.accept()
                try {
                    socket.tcpNoDelay = true
                    socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                    val inp = DataInputStream(socket.getInputStream().buffered())
                    val out = DataOutputStream(socket.getOutputStream().buffered())
                    val hostNonce = nonce()
                    writeFrame(out, JSONObject().put("t", "hello").put("v", 1).put("n", hex(hostNonce)))
                    val auth = readFrame(inp)
                    val joinNonce = unhex(auth.getString("n"))
                    val mac = unhex(auth.getString("m"))
                    if (!MessageDigest.isEqual(mac, hmac(key, "J".toByteArray() + hostNonce + joinNonce))) {
                        throw AuthException("wrong code")
                    }
                    writeFrame(out, JSONObject().put("t", "ok").put("m", hex(hmac(key, "H".toByteArray() + joinNonce + hostNonce))))
                    socket.soTimeout = 0
                    return@withContext SignalChannel(socket, key)
                } catch (e: IOException) {
                    runCatching { socket.close() }
                } catch (e: org.json.JSONException) {
                    runCatching { socket.close() }
                }
            }
            throw IOException("cancelled")
        }

        /** Joiner side: connect to the address inside the invite and run the mutual handshake. */
        suspend fun connect(invite: InviteCode, timeoutMs: Int = 6_000): SignalChannel = withContext(Dispatchers.IO) {
            val key = deriveKey(invite.secret)
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(invite.host, invite.port), timeoutMs)
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                val inp = DataInputStream(socket.getInputStream().buffered())
                val out = DataOutputStream(socket.getOutputStream().buffered())
                val hello = readFrame(inp)
                if (hello.optString("t") != "hello") throw IOException("not a P2Pvid host")
                val hostNonce = unhex(hello.getString("n"))
                val joinNonce = nonce()
                writeFrame(out, JSONObject().put("t", "auth").put("n", hex(joinNonce))
                    .put("m", hex(hmac(key, "J".toByteArray() + hostNonce + joinNonce))))
                val ok = try {
                    readFrame(inp)
                } catch (e: IOException) {
                    throw AuthException("wrong code") // host hangs up on bad MAC
                }
                if (!MessageDigest.isEqual(unhex(ok.getString("m")), hmac(key, "H".toByteArray() + joinNonce + hostNonce))) {
                    throw AuthException("host failed authentication")
                }
                socket.soTimeout = 0
                SignalChannel(socket, key)
            } catch (e: SocketTimeoutException) {
                runCatching { socket.close() }
                throw IOException("peer unreachable", e)
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw e
            }
        }

        internal fun writeFrame(out: DataOutputStream, msg: JSONObject) {
            val bytes = msg.toString().toByteArray(Charsets.UTF_8)
            out.writeInt(bytes.size)
            out.write(bytes)
            out.flush()
        }

        internal fun readFrame(inp: DataInputStream): JSONObject {
            val len = inp.readInt()
            if (len <= 0 || len > MAX_FRAME) throw IOException("bad frame length $len")
            val bytes = ByteArray(len)
            inp.readFully(bytes)
            return JSONObject(String(bytes, Charsets.UTF_8))
        }

        private fun nonce() = ByteArray(16).also(SecureRandom()::nextBytes)

        internal fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        private fun unhex(s: String): ByteArray {
            if (s.length % 2 != 0) throw IOException("bad hex")
            return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
    }
}
