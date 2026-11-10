package com.kp.p2pvid.rec

/** Pure PCM helpers for the recorder: everything is converted to 16-bit mono at [RATE]. */
object AudioMix {
    const val RATE = 48_000

    /** Interleaved 16-bit PCM (any channel count) → mono. */
    fun toMono(src: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return src
        val frames = src.size / channels
        return ShortArray(frames) { f ->
            var sum = 0
            for (c in 0 until channels) sum += src[f * channels + c]
            (sum / channels).toShort()
        }
    }

    /** Linear-interpolation resample of mono PCM. Adequate for voice recording. */
    fun resample(src: ShortArray, fromRate: Int, toRate: Int = RATE): ShortArray {
        if (fromRate == toRate || src.isEmpty()) return src
        val outLen = (src.size.toLong() * toRate / fromRate).toInt()
        val ratio = fromRate.toDouble() / toRate
        return ShortArray(outLen) { i ->
            val pos = i * ratio
            val i0 = pos.toInt().coerceAtMost(src.size - 1)
            val i1 = (i0 + 1).coerceAtMost(src.size - 1)
            val t = pos - i0
            (src[i0] * (1 - t) + src[i1] * t).toInt().toShort()
        }
    }

    /** a + b with hard clipping. b may be shorter (treated as silence past its end). */
    fun mix(a: ShortArray, b: ShortArray): ShortArray = ShortArray(a.size) { i ->
        val s = a[i] + (if (i < b.size) b[i].toInt() else 0)
        s.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}

/** Single-producer/single-consumer-ish PCM FIFO (synchronized) holding remote audio until the mic clock pulls it. */
class PcmFifo(private val capacity: Int = AudioMix.RATE) {
    private val buf = ShortArray(capacity)
    private var head = 0
    private var size = 0

    @Synchronized fun write(samples: ShortArray) {
        for (s in samples) {
            if (size == capacity) { head = (head + 1) % capacity; size-- } // drop oldest
            buf[(head + size) % capacity] = s
            size++
        }
    }

    /** Reads up to n samples; returns exactly n, zero-padded on underrun. Trims backlog beyond [maxLatency]. */
    @Synchronized fun read(n: Int, maxLatency: Int = AudioMix.RATE / 5): ShortArray {
        if (size - n > maxLatency) { val drop = size - n - maxLatency; head = (head + drop) % capacity; size -= drop }
        val out = ShortArray(n)
        val take = minOf(n, size)
        for (i in 0 until take) out[i] = buf[(head + i) % capacity]
        head = (head + take) % capacity
        size -= take
        return out
    }

    @Synchronized fun available() = size
}
