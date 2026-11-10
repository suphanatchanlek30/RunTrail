package com.example.runtrail.domain

import com.example.runtrail.data.Pause
import org.junit.Assert.assertEquals
import org.junit.Test

class CodecTest {

    @Test fun trackRoundTrip() {
        val pts = TestTracks.north(10, 3.0)
        assertEquals(pts, Codec.decodeTrack(Codec.encodeTrack(pts)))
        assertEquals(emptyList<Any>(), Codec.decodeTrack(null))
        assertEquals(1, Codec.decodeTrack("1,2,3,4,5;bad;1,2").size)
    }

    @Test fun pausesRoundTrip() {
        val p = listOf(Pause(1, 5), Pause(10, 20))
        assertEquals(p, Codec.decodePauses(Codec.encodePauses(p)))
        assertEquals(emptyList<Pause>(), Codec.decodePauses("9-1"))   // end before start is dropped
    }

    @Test fun downsampleKeepsEnds() {
        val pts = TestTracks.north(1000, 3.0)
        val d = Codec.downsample(pts, 120)
        assertEquals(120, d.size)
        assertEquals(pts.first(), d.first())
        assertEquals(pts.last(), d.last())
        assertEquals(pts.take(50), Codec.downsample(pts.take(50), 120))
    }
}
