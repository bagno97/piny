package pl.piny.waga

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

class ResonanceTest {

    /** Zanikające drganie, jakie zostaje po impulsie wibracji. */
    private fun ringdown(hz: Double, sampleRate: Double, n: Int, decay: Double = 6.0) =
        DoubleArray(n) { i ->
            val t = i / sampleRate
            exp(-decay * t) * sin(2 * PI * hz * t) + 0.01 * sin(2 * PI * 3.1 * t)
        }

    @Test
    fun `odczytuje czestotliwosc drgan z zapisu akcelerometru`() {
        val rate = 400.0
        for (hz in listOf(12.0, 27.5, 48.0, 63.25)) {
            val found = ResonanceAnalyzer.dominantFrequency(ringdown(hz, rate, 2048), rate)
            assertNotNull("nie znalazł szczytu dla $hz Hz", found)
            assertEquals("szczyt $hz Hz", hz, found!!, 0.15)
        }
    }

    @Test
    fun `interpolacja daje dokladnosc lepsza niz odstep prazkow`() {
        val rate = 400.0
        val n = 2048
        val binHz = rate / n                        // 0,195 Hz
        // częstotliwość celowo pomiędzy prążkami
        val hz = 30.0 + binHz / 2
        val found = ResonanceAnalyzer.dominantFrequency(ringdown(hz, rate, n), rate)!!
        assertTrue("błąd ${kotlin.math.abs(found - hz)} Hz przy prążku $binHz Hz",
            kotlin.math.abs(found - hz) < binHz / 2)
    }

    @Test
    fun `sam szum nie jest uznawany za rezonans`() {
        val random = java.util.Random(7)
        val noise = DoubleArray(2048) { random.nextGaussian() }
        assertNull(ResonanceAnalyzer.dominantFrequency(noise, 400.0))
    }

    @Test
    fun `za krotki zapis nie daje wyniku`() {
        assertNull(ResonanceAnalyzer.dominantFrequency(DoubleArray(64), 400.0))
    }

    @Test
    fun `kalibracja wzorcem odtwarza mase`() {
        // układ o masie własnej 200 g; dołożenie 50 g obniża częstotliwość
        val c = 200.0 * 40.0 * 40.0                 // C = M·f²
        val empty = 40.0
        val withRef = kotlin.math.sqrt(c / 250.0)   // 200 g + 50 g

        val scale = ResonanceScale.calibrate(empty, withRef, 50.0)
        assertNotNull(scale)
        assertEquals("masa własna układu", 200.0, scale!!.systemMass, 0.5)

        for (m in listOf(5.0, 20.0, 75.0, 150.0)) {
            val hz = kotlin.math.sqrt(c / (200.0 + m))
            assertEquals("masa $m g", m, scale.mass(hz), m * 0.02 + 0.05)
        }
    }

    @Test
    fun `kalibracja odrzuca pomiar sprzeczny z fizyka`() {
        // dołożenie masy nie może podnieść częstotliwości
        assertNull(ResonanceScale.calibrate(emptyHz = 40.0, referenceHz = 42.0, referenceGrams = 50.0))
        assertNull(ResonanceScale.calibrate(emptyHz = 40.0, referenceHz = 40.0, referenceGrams = 50.0))
        assertNull(ResonanceScale.calibrate(emptyHz = 0.0, referenceHz = 30.0, referenceGrams = 50.0))
    }

    @Test
    fun `rozdzielczosc mowi wprost ile metoda wykrywa`() {
        val c = 200.0 * 40.0 * 40.0
        val scale = ResonanceScale.calibrate(40.0, kotlin.math.sqrt(c / 250.0), 50.0)!!
        // przy niepewności 0,05 Hz na układzie 200 g / 40 Hz
        val r = scale.resolution(0.05)
        assertTrue("rozdzielczość wyszła $r g", r > 0.1 && r < 2.0)
    }
}
