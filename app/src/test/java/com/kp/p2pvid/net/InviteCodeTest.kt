package com.kp.p2pvid.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class InviteCodeTest {

    @Test fun roundTrip() {
        val rnd = SecureRandom()
        repeat(500) {
            val ip = "${rnd.nextInt(256)}.${rnd.nextInt(256)}.${rnd.nextInt(256)}.${rnd.nextInt(256)}"
            val code = InviteCode.generate(ip, 1 + rnd.nextInt(65535), rnd)
            val text = code.encode()
            assertTrue(text, Regex("[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}").matches(text))
            assertEquals(code, InviteCode.parse(text))
        }
    }

    @Test fun parseIsForgiving() {
        val code = InviteCode("192.168.1.42", 45678, byteArrayOf(1, 2, 3, 4))
        val text = code.encode()
        assertEquals(code, InviteCode.parse(text.lowercase()))
        assertEquals(code, InviteCode.parse(text.replace("-", "")))
        assertEquals(code, InviteCode.parse("  " + text.replace("-", " ") + " "))
        assertEquals(code, InviteCode.parse(InviteCode.QR_PREFIX + text))
    }

    @Test fun lookAlikesMapToDigits() {
        val code = InviteCode("10.0.0.1", 40000, byteArrayOf(0, 0, 0, 1))
        val text = code.encode()
        val confused = text.replace('0', 'O').replace('1', 'I')
        assertEquals(code, InviteCode.parse(confused))
    }

    @Test fun rejectsBadInput() {
        assertNull(InviteCode.parse(""))
        assertNull(InviteCode.parse("ABCD-EFGH"))
        assertNull(InviteCode.parse("ABCD-EFGH-JKMN-PQRST")) // 17 chars
        // port 0 is invalid
        assertNull(InviteCode.parse("0000-0000-0000-0000"))
    }

    @Test fun normalizeAndFormatForInputField() {
        assertEquals("ABCD-EF", InviteCode.format(InviteCode.normalize("ab-cd ef")))
        assertNotNull(InviteCode.parse(InviteCode("172.20.10.2", 1234, byteArrayOf(9, 8, 7, 6)).encode()))
    }
}
