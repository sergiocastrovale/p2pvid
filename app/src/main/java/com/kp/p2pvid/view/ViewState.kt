package com.kp.p2pvid.view

/** Absolute identity of a call participant, the same on both devices. */
enum class Party { HOST, JOINER;
    val other get() = if (this == HOST) JOINER else HOST
}

enum class ViewMode { DYNAMIC, LOCKED, DUAL }

/** What is shown on screen (and in recordings). Shared verbatim between devices in director mode. */
data class ViewState(
    val mode: ViewMode = ViewMode.DYNAMIC,
    val lockTarget: Party = Party.JOINER,
    val speaker: Party = Party.JOINER,
) {
    /** The party filling the screen in single-stream modes. */
    val main: Party get() = if (mode == ViewMode.LOCKED) lockTarget else speaker
}

/**
 * Director ownership as a last-writer-wins register ordered by (epoch, writer==HOST).
 * Claiming bumps the epoch, so a claim always takes over; two claims sent at the same
 * time carry the same epoch and both sides pick the host's, so they stay consistent.
 */
class DirectorState(private val self: Party) {
    var director: Party? = null
        private set
    var epoch: Long = 0
        private set
    private var lastWriter: Party? = null

    val isDirector get() = director == self
    val isFollower get() = director != null && director != self

    /** Returns the epoch to send to the peer. */
    fun claim(): Long = localWrite(self)

    fun release(): Long = localWrite(null)

    private fun localWrite(value: Party?): Long {
        epoch += 1
        director = value
        lastWriter = self
        return epoch
    }

    /** Apply a claim (value=writer) or release (value=null) from the peer. Returns true if it took effect. */
    fun onRemote(value: Party?, remoteEpoch: Long): Boolean {
        val writer = self.other
        val wins = remoteEpoch > epoch || (remoteEpoch == epoch && writer == Party.HOST && lastWriter != Party.HOST)
        if (!wins) return false
        epoch = remoteEpoch
        director = value
        lastWriter = writer
        return true
    }
}

/**
 * Picks who is talking from two audio levels (0..1), with hysteresis so the view does not
 * flicker: a new speaker must be clearly louder for [switchAfterMs], and a switch is never
 * made sooner than [minHoldMs] after the previous one.
 */
class SpeakerDetector(
    initial: Party,
    private val threshold: Double = 0.02,
    private val switchAfterMs: Long = 500,
    private val minHoldMs: Long = 1_200,
) {
    var current: Party = initial
        private set
    private var candidate: Party? = null
    private var candidateSince = 0L
    private var lastSwitch = Long.MIN_VALUE / 2

    fun update(levels: Map<Party, Double>, nowMs: Long): Party {
        val loudest = levels.maxByOrNull { it.value }
        val challenger = loudest?.takeIf { (p, lvl) ->
            p != current && lvl >= threshold && lvl > (levels[current] ?: 0.0) * 1.5
        }?.key

        if (challenger == null) {
            candidate = null
            return current
        }
        if (candidate != challenger) {
            candidate = challenger
            candidateSince = nowMs
        }
        if (nowMs - candidateSince >= switchAfterMs && nowMs - lastSwitch >= minHoldMs) {
            current = challenger
            lastSwitch = nowMs
            candidate = null
        }
        return current
    }

    fun reset(p: Party) {
        current = p
        candidate = null
    }
}
