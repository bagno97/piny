package pl.piny.waga

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

class TiltTest {

    /** Telefon leżący płasko: grawitacja wzdłuż osi Z. */
    private val flat = Direction(0.0, 0.0, 9.81)

    /** Przechyla wektor grawitacji o zadany kąt i dokłada szum czujnika. */
    private fun tilted(deg: Double, noise: Double = 0.0, seed: Long = 1, n: Int = 600): List<Direction> {
        val random = Random(seed)
        val rad = Math.toRadians(deg)
        return List(n) {
            Direction(
                9.81 * sin(rad) + random.nextGaussian() * noise,
                random.nextGaussian() * noise,
                9.81 * cos(rad) + random.nextGaussian() * noise
            )
        }
    }

    @Test
    fun `usredniony kierunek odtwarza przechyl`() {
        val mean = TiltAnalyzer.meanDirection(tilted(0.5, noise = 0.02))
        assertNotNull(mean)
        assertEquals("przechył 0,5°", 0.5, flat.angleTo(mean!!), 0.02)
    }

    @Test
    fun `usrednianie wyciaga przechyl mniejszy niz szum pojedynczej probki`() {
        // szum 0,02 m/s² to ok. 0,12° na próbkę — a szukamy 0,05°
        val mean = TiltAnalyzer.meanDirection(tilted(0.05, noise = 0.02, n = 600))
        assertNotNull(mean)
        assertEquals(0.05, flat.angleTo(mean!!), 0.02)
    }

    @Test
    fun `poruszony telefon jest odrzucany`() {
        val shaken = tilted(0.2, noise = 0.6)
        assertNull("zapis z drganiami nie może uchodzić za pomiar",
            TiltAnalyzer.meanDirection(shaken))
    }

    @Test
    fun `za malo probek to brak pomiaru`() {
        assertNull(TiltAnalyzer.meanDirection(tilted(0.3, n = 10)))
    }

    @Test
    fun `kalibracja przechylem odtwarza mase`() {
        // wzorzec 20 g daje przechył 0,4° → 50 g na stopień
        val reference = TiltAnalyzer.meanDirection(tilted(0.4, noise = 0.01))!!
        val scale = TiltScale.calibrate(flat, reference, 20.0)
        assertNotNull(scale)
        assertEquals(50.0, scale!!.gramsPerDegree, 2.0)

        for ((deg, grams) in listOf(0.1 to 5.0, 0.2 to 10.0, 0.8 to 40.0, 2.0 to 100.0)) {
            val measured = TiltAnalyzer.meanDirection(tilted(deg, noise = 0.01))!!
            assertEquals("przechył $deg°", grams, scale.mass(measured), grams * 0.08 + 0.3)
        }
    }

    @Test
    fun `zerowy przechyl nie pozwala na kalibracje`() {
        assertNull(TiltScale.calibrate(flat, flat, 20.0))
        assertNull(TiltScale.calibrate(flat, tilted(0.4, noise = 0.0)[0], -5.0))
    }

    @Test
    fun `rozdzielczosc wynika ze stalej kalibracji`() {
        val scale = TiltScale(flat, gramsPerDegree = 50.0)
        assertEquals(0.5, scale.resolution(0.01), 1e-9)
    }
}
