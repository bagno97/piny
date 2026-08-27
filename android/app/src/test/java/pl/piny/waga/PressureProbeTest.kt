package pl.piny.waga

import org.junit.Assert.*
import org.junit.Test

class PressureProbeTest {

    @Test
    fun `staly nacisk z drgajacym ostatnim bitem to nie czujnik sily`() {
        val probe = PressureProbe()
        repeat(200) { probe.note(1.0 + (it % 3 - 1) * 0.0005) }
        assertFalse(probe.hasForceSensor)
    }

    @Test
    fun `zmienny nacisk rozpoznaje czujnik sily`() {
        val probe = PressureProbe()
        listOf(0.12, 0.21, 0.35, 0.48, 0.60, 0.44).forEach { probe.note(it) }
        assertTrue(probe.hasForceSensor)
        assertEquals(0.48, probe.span, 1e-9)
    }

    @Test
    fun `zera nie licza sie jako probki`() {
        val probe = PressureProbe()
        repeat(50) { probe.note(0.0) }
        assertEquals(0, probe.samples)
        assertFalse(probe.hasForceSensor)
    }

    @Test
    fun `zbior poziomow nie rosnie w nieskonczonosc`() {
        val probe = PressureProbe()
        repeat(100_000) { probe.note(0.001 + it % 997 * 0.001) }
        assertTrue("poziomów: ${probe.levelCount}", probe.levelCount <= 64)
    }
}
