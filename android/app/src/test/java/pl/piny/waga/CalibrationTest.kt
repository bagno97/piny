package pl.piny.waga

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp

/** Ekran odpowiada nieliniowo — taki model odwzorowuje nasycenie czujnika. */
private fun rawFor(mass: Double) = 1 - exp(-mass / 10.0)

class CalibrationTest {

    private fun calibrated(vararg masses: Double) =
        Calibration(0.0, masses.map { CalPoint(rawFor(it), it) })

    @Test
    fun `bez wzorcow nie zwraca masy`() {
        assertNull(Calibration().massFor(0.5))
        assertFalse(Calibration().isCalibrated)
    }

    @Test
    fun `krzywa z czterech wzorcow trzyma blad ponizej osmiu procent`() {
        val cal = calibrated(2.0, 5.0, 10.0, 20.0)
        assertTrue(cal.isCurved)
        assertEquals(4, cal.referenceCount)
        for (m in listOf(3.0, 7.0, 15.0)) {
            val read = cal.massFor(rawFor(m))!!
            val error = abs(read - m) / m
            assertTrue("masa $m g odczytana jako $read g (błąd ${(error * 100).toInt()}%)", error < 0.08)
        }
    }

    @Test
    fun `jeden wzorzec jest wyraznie gorszy od krzywej`() {
        val line = calibrated(10.0)
        val curve = calibrated(2.0, 5.0, 10.0, 20.0)
        val lineError = abs(line.massFor(rawFor(3.0))!! - 3.0)
        val curveError = abs(curve.massFor(rawFor(3.0))!! - 3.0)
        assertTrue("prosta: $lineError, krzywa: $curveError", curveError < lineError / 3)
    }

    @Test
    fun `ekstrapolacja jest ograniczona`() {
        val cal = calibrated(2.0, 5.0, 10.0, 20.0)
        // trzy palce na ekranie dają sygnał daleko poza zakresem wzorców
        val absurd = cal.massFor(3.0)!!
        assertTrue("odczyt poza zakresem: $absurd g", absurd <= 20.0 * Calibration.EXTRAPOLATION_LIMIT)
        assertTrue(cal.beyondRange(3.0))
        assertFalse(cal.beyondRange(rawFor(15.0)))
    }

    @Test
    fun `punkt zerowy i wartosci ponizej niego daja zero`() {
        val cal = Calibration(0.05, listOf(CalPoint(0.5, 10.0)))
        assertEquals(0.0, cal.massFor(0.04)!!, 1e-9)
        assertEquals(0.0, cal.massFor(0.05)!!, 1e-9)
        assertEquals(10.0, cal.massFor(0.5)!!, 1e-9)
    }

    @Test
    fun `wzorce zbyt bliskie sobie nadpisuja sie zamiast psuc krzywa`() {
        val cal = Calibration(0.0, listOf(CalPoint(0.400, 5.0), CalPoint(0.4005, 5.2)))
        assertEquals(1, cal.referenceCount)
        assertEquals(5.2, cal.maxMass, 1e-9)
    }

    @Test
    fun `wzorce niepoprawne sa odrzucane`() {
        val cal = Calibration(0.1, listOf(
            CalPoint(0.05, 5.0),               // poniżej zera
            CalPoint(0.5, -3.0),               // ujemna masa
            CalPoint(Double.NaN, 4.0),         // śmieci
            CalPoint(0.6, 7.0)                 // jedyny poprawny
        ))
        assertEquals(1, cal.referenceCount)
        assertEquals(7.0, cal.maxMass, 1e-9)
    }

    @Test
    fun `usuniecie wzorca nie rusza pozostalych`() {
        val p = CalPoint(0.4, 5.0)
        val cal = Calibration(0.0, listOf(p, CalPoint(0.7, 12.0)))
        assertEquals(1, cal.withoutPoint(p).referenceCount)
        assertEquals(12.0, cal.withoutPoint(p).maxMass, 1e-9)
    }
}
