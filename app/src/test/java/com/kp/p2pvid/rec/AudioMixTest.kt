package com.kp.p2pvid.rec

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioMixTest {
    @Test fun monoDownmixAverages() {
        assertArrayEquals(shortArrayOf(150, -50), AudioMix.toMono(shortArrayOf(100, 200, 0, -100), 2))
    }

    @Test fun resampleChangesLength() {
        assertEquals(480, AudioMix.resample(ShortArray(160), 16_000).size)
        assertEquals(480, AudioMix.resample(ShortArray(441), 44_100).size)
    }

    @Test fun mixClipsAndPads() {
        val out = AudioMix.mix(shortArrayOf(30_000, 10, 5), shortArrayOf(10_000, 10))
        assertArrayEquals(shortArrayOf(Short.MAX_VALUE, 20, 5), out)
    }

    @Test fun fifoZeroPadsOnUnderrunAndTrimsBacklog() {
        val f = PcmFifo(1000)
        f.write(shortArrayOf(1, 2, 3))
        assertArrayEquals(shortArrayOf(1, 2, 3, 0), f.read(4))
        f.write(ShortArray(900) { it.toShort() })
        val out = f.read(10, maxLatency = 100)
        assertEquals(100, f.available())
        assertEquals(790.toShort(), out[0])
    }
}
