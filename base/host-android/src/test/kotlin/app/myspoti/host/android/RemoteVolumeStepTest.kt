package app.myspoti.host.android

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteVolumeStepTest {
    @Test fun stepsUpDownAndClampsToRange() {
        assertEquals(55, RemoteVolume.step(50, 1))
        assertEquals(45, RemoteVolume.step(50, -1))
        assertEquals(50, RemoteVolume.step(50, 0))
        assertEquals(100, RemoteVolume.step(98, 1))
        assertEquals(0, RemoteVolume.step(2, -1))
    }
}
