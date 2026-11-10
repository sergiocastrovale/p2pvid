package com.kp.p2pvid.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class SignalingTest {

    private fun server() = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
    private fun invite(port: Int, secret: ByteArray) = InviteCode("127.0.0.1", port, secret)

    @Test fun handshakeAndMessagesBothWays() = runBlocking {
        val secret = byteArrayOf(1, 2, 3, 4)
        server().use { srv ->
            val host = async(Dispatchers.IO) { SignalChannel.accept(srv, secret) }
            val joiner = SignalChannel.connect(invite(srv.localPort, secret))
            val h = withTimeout(5_000) { host.await() }

            joiner.send(JSONObject().put("t", "offer").put("sdp", "v=0"))
            assertEquals("v=0", h.receive().getString("sdp"))
            h.send(JSONObject().put("t", "answer").put("sdp", "v=1"))
            assertEquals("v=1", joiner.receive().getString("sdp"))
            repeat(20) { h.send(JSONObject().put("i", it)) }
            repeat(20) { assertEquals(it, joiner.receive().getInt("i")) }
            h.close(); joiner.close()
        }
    }

    @Test fun wrongCodeIsRejectedAndHostKeepsWaiting() = runBlocking {
        val secret = byteArrayOf(1, 2, 3, 4)
        server().use { srv ->
            val host = async(Dispatchers.IO) { SignalChannel.accept(srv, secret) }
            try {
                SignalChannel.connect(invite(srv.localPort, byteArrayOf(9, 9, 9, 9)))
                fail("expected AuthException")
            } catch (e: AuthException) {
                // expected
            }
            // The right code still gets in afterwards.
            val joiner = SignalChannel.connect(invite(srv.localPort, secret))
            val h = withTimeout(5_000) { host.await() }
            joiner.send(JSONObject().put("ok", true))
            assertEquals(true, h.receive().getBoolean("ok"))
            h.close(); joiner.close()
        }
    }

    @Test fun tamperedFrameIsRejected() = runBlocking {
        val secret = byteArrayOf(5, 6, 7, 8)
        server().use { srv ->
            val host = async(Dispatchers.IO) { SignalChannel.accept(srv, secret) }
            // Hand-rolled joiner that authenticates correctly, then sends a frame with a bad MAC.
            val sock = Socket()
            sock.connect(InetSocketAddress("127.0.0.1", srv.localPort))
            val inp = DataInputStream(sock.getInputStream())
            val out = DataOutputStream(sock.getOutputStream())
            val key = SignalChannel.deriveKey(secret)
            val hello = SignalChannel.readFrame(inp)
            val hostNonce = hex(hello.getString("n"))
            val joinNonce = ByteArray(16) { 7 }
            SignalChannel.writeFrame(out, JSONObject().put("t", "auth").put("n", unhex(joinNonce))
                .put("m", unhex(SignalChannel.hmac(key, "J".toByteArray() + hostNonce + joinNonce))))
            SignalChannel.readFrame(inp) // ok
            val h = withTimeout(5_000) { host.await() }
            SignalChannel.writeFrame(out, JSONObject().put("p", "{\"t\":\"bye\"}").put("m", "00".repeat(32)))
            try {
                h.receive()
                fail("expected AuthException")
            } catch (e: AuthException) {
                // expected
            }
            h.close(); sock.close()
        }
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun unhex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
