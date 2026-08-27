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
    fun `maly zakres wlasny ekranu tez jest czujnikiem sily`() {
        // sterownik oddaje 0,0002–0,0006: bezwzględny próg by to odrzucił,
        // choć ekran rozróżnia nacisk trzykrotnie
        val probe = PressureProbe()
        listOf(0.0002, 0.00025, 0.00033, 0.00041, 0.00052, 0.0006).forEach { probe.note(it) }
        assertTrue("ekran o własnej skali musi być rozpoznany", probe.hasForceSensor)
        assertTrue(probe.relativeSpan > 0.5)
    }

    @Test
    fun `staly nacisk w malej skali nadal nie jest czujnikiem`() {
        val probe = PressureProbe()
        repeat(50) { probe.note(0.0004) }
        assertFalse(probe.hasForceSensor)
        assertEquals(0.0, probe.relativeSpan, 1e-12)
    }

    @Test
    fun `zbior poziomow nie rosnie w nieskonczonosc`() {
        val probe = PressureProbe()
        repeat(100_000) { probe.note(0.001 + it % 997 * 0.001) }
        assertTrue("poziomów: ${probe.levelCount}", probe.levelCount <= 64)
    }
}
