package com.kp.p2pvid.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectorStateTest {
    /** Two replicas connected by an in-order message queue, like the data channel. */
    private class Pair2 {
        val host = DirectorState(Party.HOST)
        val joiner = DirectorState(Party.JOINER)
        val toJoiner = ArrayDeque<kotlin.Pair<Party?, Long>>()
        val toHost = ArrayDeque<kotlin.Pair<Party?, Long>>()
        fun hostClaim() = toJoiner.addLast(Party.HOST to host.claim())
        fun joinerClaim() = toHost.addLast(Party.JOINER to joiner.claim())
        fun hostRelease() = toJoiner.addLast(null to host.release())
        fun deliver() {
            while (toJoiner.isNotEmpty()) toJoiner.removeFirst().let { joiner.onRemote(it.first, it.second) }
            while (toHost.isNotEmpty()) toHost.removeFirst().let { host.onRemote(it.first, it.second) }
        }
    }

    @Test fun claimIsMirrored() {
        val p = Pair2()
        p.hostClaim(); p.deliver()
        assertTrue(p.host.isDirector)
        assertTrue(p.joiner.isFollower)
    }

    @Test fun laterClaimTakesOver() {
        val p = Pair2()
        p.hostClaim(); p.deliver()
        p.joinerClaim(); p.deliver()
        assertEquals(Party.JOINER, p.host.director)
        assertEquals(Party.JOINER, p.joiner.director)
        assertTrue(p.joiner.isDirector)
    }

    @Test fun simultaneousClaimsConvergeOnHost() {
        val p = Pair2()
        p.hostClaim(); p.joinerClaim() // both in flight
        p.deliver()
        assertEquals(Party.HOST, p.host.director)
        assertEquals(Party.HOST, p.joiner.director)
    }

    @Test fun releaseClearsBothSides() {
        val p = Pair2()
        p.hostClaim(); p.deliver()
        p.hostRelease(); p.deliver()
        assertNull(p.host.director)
        assertNull(p.joiner.director)
        assertFalse(p.joiner.isFollower)
    }
}

class SpeakerDetectorTest {
    @Test fun switchesAfterSustainedSpeechAndHolds() {
        val d = SpeakerDetector(Party.JOINER, switchAfterMs = 500, minHoldMs = 1_000)
        val hostTalking = mapOf(Party.HOST to 0.3, Party.JOINER to 0.0)
        assertEquals(Party.JOINER, d.update(hostTalking, 0))
        assertEquals(Party.JOINER, d.update(hostTalking, 400))
        assertEquals(Party.HOST, d.update(hostTalking, 600))
        // A blip from the other side right after does not flip it back.
        val joinerTalking = mapOf(Party.HOST to 0.0, Party.JOINER to 0.3)
        assertEquals(Party.HOST, d.update(joinerTalking, 700))
        assertEquals(Party.HOST, d.update(joinerTalking, 1_300))
        assertEquals(Party.JOINER, d.update(joinerTalking, 1_700))
    }

    @Test fun silenceKeepsCurrent() {
        val d = SpeakerDetector(Party.HOST)
        repeat(50) { assertEquals(Party.HOST, d.update(mapOf(Party.HOST to 0.0, Party.JOINER to 0.005), it * 200L)) }
    }
}

class LayoutTest {
    @Test fun dualLandscapeSplitsCanvas() {
        val t = Layout.tiles(ViewState(mode = ViewMode.DUAL), 1280, 720)
        assertEquals(listOf(Party.HOST, Party.JOINER), t.map { it.party })
        assertEquals(Box(0, 0, 640, 720), t[0].box)
        assertEquals(Box(640, 0, 640, 720), t[1].box)
    }

    @Test fun dualPortraitIsLetterboxed() {
        val t = Layout.tiles(ViewState(mode = ViewMode.DUAL), 720, 1280)
        assertEquals(405, t[0].box.h)
        assertEquals((1280 - 405) / 2, t[0].box.y)
    }

    @Test fun lockedShowsTargetWithPip() {
        val t = Layout.tiles(ViewState(mode = ViewMode.LOCKED, lockTarget = Party.HOST, speaker = Party.JOINER), 720, 1280)
        assertEquals(Party.HOST, t[0].party)
        assertEquals(Box(0, 0, 720, 1280), t[0].box)
        assertTrue(t[1].isPip)
        assertEquals(Party.JOINER, t[1].party)
        assertTrue(t[1].box.x + t[1].box.w <= 720 && t[1].box.y + t[1].box.h <= 1280)
    }

    @Test fun dynamicFollowsSpeaker() {
        val t = Layout.tiles(ViewState(mode = ViewMode.DYNAMIC, lockTarget = Party.HOST, speaker = Party.JOINER), 1280, 720)
        assertEquals(Party.JOINER, t[0].party)
    }

    @Test fun cropScaleCentersCrop() {
        // 16:9 frame into a 9:16 box: keep full height, show 9/16 / 16/9 of the width.
        val (sx, sy) = Layout.cropScale(1280, 720, 720, 1280)
        assertEquals(1f, sy)
        assertEquals((9f / 16f) / (16f / 9f), sx, 1e-4f)
        // Same aspect: no crop.
        assertEquals(1f to 1f, Layout.cropScale(640, 360, 1280, 720))
    }
}

class ControlTest {
    @Test fun roundTrip() {
        val all = listOf(
            Control.Director(true, 3), Control.Director(false, 4),
            Control.View(ViewMode.DUAL, Party.HOST), Control.Speaker(Party.JOINER),
            Control.Recording(true), Control.Bye,
        )
        all.forEach { assertEquals(it, Control.parse(it.toJson())) }
        assertNull(Control.parse("garbage"))
        assertNull(Control.parse("{\"t\":\"unknown\"}"))
    }
}
