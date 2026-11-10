package com.kp.p2pvid.net

import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

/**
 * 16-char invite code: Crockford Base32 of 10 bytes = IPv4(4) + port(2) + secret(4).
 * Displayed as XXXX-XXXX-XXXX-XXXX. Everything needed to reach the host is inside the
 * code, so joining works with no internet and no discovery.
 */
data class InviteCode(val host: String, val port: Int, val secret: ByteArray) {

    init {
        require(port in 1..65535) { "bad port" }
        require(secret.size == SECRET_BYTES) { "bad secret" }
        require(ipBytes(host) != null) { "bad ipv4" }
    }

    fun encode(): String {
        val ip = ipBytes(host)!!
        val bytes = ip + byteArrayOf((port shr 8).toByte(), port.toByte()) + secret
        return format(base32(bytes))
    }

    override fun equals(other: Any?) =
        other is InviteCode && host == other.host && port == other.port && secret.contentEquals(other.secret)

    override fun hashCode() = (host.hashCode() * 31 + port) * 31 + secret.contentHashCode()

    companion object {
        const val SECRET_BYTES = 4
        const val LENGTH = 16
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        const val QR_PREFIX = "p2pvid:"

        fun generate(host: String, port: Int, random: SecureRandom = SecureRandom()): InviteCode =
            InviteCode(host, port, ByteArray(SECRET_BYTES).also(random::nextBytes))

        /** Accepts any case, dashes/spaces, the QR prefix, and common look-alike characters. */
        fun parse(input: String): InviteCode? {
            val chars = normalize(input)
            if (chars.length != LENGTH) return null
            val bytes = unbase32(chars) ?: return null
            val ip = bytes.copyOfRange(0, 4).joinToString(".") { (it.toInt() and 0xFF).toString() }
            val port = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)
            if (port == 0) return null
            return InviteCode(ip, port, bytes.copyOfRange(6, 10))
        }

        /** Strip to code characters only (used both for parsing and for live input formatting). */
        fun normalize(input: String): String =
            input.trim().removePrefix(QR_PREFIX).uppercase()
                .map {
                    when (it) {
                        'O' -> '0'
                        'I', 'L' -> '1'
                        else -> it
                    }
                }
                .filter { it in ALPHABET }
                .joinToString("")

        fun format(chars: String): String = chars.chunked(4).joinToString("-")

        private fun ipBytes(host: String): ByteArray? {
            val parts = host.split(".")
            if (parts.size != 4) return null
            val out = ByteArray(4)
            for (i in 0..3) {
                val n = parts[i].toIntOrNull() ?: return null
                if (n !in 0..255) return null
                out[i] = n.toByte()
            }
            return out
        }

        // 10 bytes = 80 bits = exactly 16 five-bit groups, so no padding needed.
        private fun base32(bytes: ByteArray): String {
            val sb = StringBuilder()
            var buffer = 0
            var bits = 0
            for (b in bytes) {
                buffer = (buffer shl 8) or (b.toInt() and 0xFF)
                bits += 8
                while (bits >= 5) {
                    bits -= 5
                    sb.append(ALPHABET[(buffer shr bits) and 31])
                }
            }
            return sb.toString()
        }

        private fun unbase32(chars: String): ByteArray? {
            val out = ByteArray(chars.length * 5 / 8)
            var buffer = 0
            var bits = 0
            var i = 0
            for (c in chars) {
                val v = ALPHABET.indexOf(c)
                if (v < 0) return null
                buffer = (buffer shl 5) or v
                bits += 5
                if (bits >= 8) {
                    bits -= 8
                    out[i++] = (buffer shr bits).toByte()
                }
            }
            return out
        }
    }
}

object LanAddress {
    /**
     * Best LAN IPv4 of this device. Prefers Wi-Fi client and hotspot interfaces; falls back
     * to any site-local address. Mobile-data interfaces (rmnet, ccmni...) are never chosen
     * because they are not site-local.
     */
    fun find(): String? = runCatching {
        val candidates = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif ->
                nif.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filter { it.isSiteLocalAddress }
                    .map { nif.name to it.hostAddress!! }
            }
        val preferred = listOf("wlan", "swlan", "ap", "softap", "eth", "rndis", "bt-pan")
        candidates.minByOrNull { (name, _) ->
            preferred.indexOfFirst { name.startsWith(it) }.let { if (it < 0) preferred.size else it }
        }?.second
    }.getOrNull()
}
