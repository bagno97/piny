package pl.piny.waga

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp

private fun rawFor(mass: Double) = 1 - exp(-mass / 10.0)

class ScaleEngineTest {

    private fun engine() = ScaleEngine(
        Calibration(0.0, listOf(2.0, 5.0, 10.0, 20.0).map { CalPoint(rawFor(it), it) })
    )

    /** Podaje sygnał przez [ms] milisekund w krokach po 16 ms, jak przy 60 Hz. */
    private fun ScaleEngine.hold(raw: Double, ms: Long, startAt: Long, contacts: Int = 1,
                                saturated: Boolean = false): Pair<Reading, Long> {
        var t = startAt
        var last: Reading? = null
        while (t < startAt + ms) {
            last = update(raw, contacts, saturated, t)
            t += 16
        }
        return last!! to t
    }

    @Test
    fun `bez kalibracji nie zmyśla masy`() {
        val e = ScaleEngine(Calibration())
        val (r, _) = e.hold(0.5, 500, 0)
        assertEquals(ScaleState.UNCALIBRATED, r.state)
        assertNull(r.grams)
    }

    @Test
    fun `po ustabilizowaniu zatrzymuje odczyt i zapisuje go raz`() {
        val e = engine()
        var t = 0L
        var captures = 0
        var last: Reading? = null
        while (t < 3000) {
            val r = e.update(rawFor(10.0), 1, false, t)
            if (r.captured != null) captures++
            last = r; t += 16
        }
        assertEquals(ScaleState.HOLD, last!!.state)
        assertEquals(10.0, last.grams!!, 0.6)
        assertEquals("odczyt zapisuje się dokładnie raz", 1, captures)
    }

    @Test
    fun `zdjecie palca konczy pomiar`() {
        val e = engine()
        var (r, t) = e.hold(rawFor(10.0), 2000, 0)
        assertEquals(ScaleState.HOLD, r.state)
        val (idle, _) = e.hold(0.0, 800, t, contacts = 0)
        assertEquals(ScaleState.IDLE, idle.state)
        assertEquals(0.0, idle.grams!!, 1e-9)
    }

    @Test
    fun `tara liczy netto i nie rusza kalibracji`() {
        val e = engine()
        val before = e.calibration.referenceCount
        var (_, t) = e.hold(rawFor(10.0), 2000, 0)

        val taken = e.tareNow()
        assertEquals(10.0, taken, 0.6)
        assertEquals("tara nie może kasować wzorców", before, e.calibration.referenceCount)
        assertEquals(0.0, e.calibration.zero, 1e-12)

        val (tared, t2) = e.hold(rawFor(10.0), 800, t)
        assertEquals(0.0, tared.grams!!, 0.3)

        val (loaded, _) = e.hold(rawFor(15.0), 2500, t2)
        assertEquals("po tarze doważanie liczy netto", 5.0, loaded.grams!!, 1.5)
    }

    @Test
    fun `tara znika po zdjeciu obciazenia`() {
        val e = engine()
        var (_, t) = e.hold(rawFor(10.0), 1500, 0)
        e.tareNow()
        assertTrue(e.tare > 0)
        val (r, _) = e.hold(0.0, 500, t, contacts = 0)
        assertEquals(0.0, e.tare, 1e-12)
        assertEquals(0.0, r.tare, 1e-12)
    }

    @Test
    fun `zatrzymany odczyt zwalnia sie po zmianie obciazenia`() {
        val e = engine()
        val (held, t) = e.hold(rawFor(5.0), 2000, 0)
        assertEquals(ScaleState.HOLD, held.state)
        assertEquals(5.0, held.grams!!, 0.5)

        val (changed, _) = e.hold(rawFor(12.0), 2500, t)
        assertEquals("waga musi zauważyć dołożony ciężar", 12.0, changed.grams!!, 1.5)
    }

    @Test
    fun `drobny szum nie zwalnia zatrzymanego odczytu`() {
        val e = engine()
        val (held, t) = e.hold(rawFor(10.0), 2000, 0)
        val value = held.grams!!
        var tt = t
        var last: Reading? = null
        repeat(60) {
            last = e.update(rawFor(10.0) + (if (it % 2 == 0) 0.0008 else -0.0008), 1, false, tt)
            tt += 16
        }
        assertEquals(ScaleState.HOLD, last!!.state)
        assertEquals(value, last!!.grams!!, 1e-9)
    }

    @Test
    fun `nasycenie czujnika to przeciazenie a nie pomiar`() {
        val e = engine()
        val (r, _) = e.hold(1.0, 1500, 0, saturated = true)
        assertEquals(ScaleState.OVERLOAD, r.state)
        assertNull("przy przeciążeniu nic nie trafia do dziennika", r.captured)
    }

    @Test
    fun `sygnal poza zakresem wzorcow jest oznaczony`() {
        val e = engine()
        val (r, _) = e.hold(2.5, 1200, 0)
        assertTrue(r.beyondRange)
        assertTrue("odczyt musi być ograniczony", r.grams!! <= 20.0 * Calibration.EXTRAPOLATION_LIMIT)
    }

    @Test
    fun `ustabilizowane wskazanie nie migocze`() {
        val e = engine()
        var t = 0L
        val settled = mutableSetOf<Double>()
        while (t < 3000) {
            val r = e.update(rawFor(7.0) + (t % 3 - 1) * 0.0004, 1, false, t)
            // pierwsza sekunda to narastanie filtra — oceniamy dopiero ustaloną wartość
            if (t > 1500) r.grams?.let { settled.add(Math.round(it * 10.0) / 10.0) }
            t += 16
        }
        assertEquals("ustabilizowany odczyt musi być jedną liczbą, było: $settled", 1, settled.size)
    }

    @Test
    fun `narastajacy docisk a potem spokoj konczy sie zatrzymaniem odczytu`() {
        val e = engine()
        var t = 0L
        // docisk narasta, jak przy prawdziwym kładzeniu przedmiotu
        for (p in listOf(0.08, 0.15, 0.23, 0.31, 0.39, 0.47, 0.56)) {
            repeat(3) { e.update(p, 1, false, t); t += 16 }
        }
        var captures = 0
        var last: Reading? = null
        repeat(150) {
            val r = e.update(rawFor(10.0) + (it % 2) * 0.0005, 1, false, t)
            if (r.captured != null) captures++
            last = r; t += 16
        }
        assertEquals("po uspokojeniu odczyt musi zostać zatrzymany", ScaleState.HOLD, last!!.state)
        assertEquals(1, captures)
        assertTrue("odchylenie po uspokojeniu: ${last!!.stability}", last!!.stability < ScaleEngine.STABLE_SD)
    }

    @Test
    fun `bardzo lekki dotyk nie jest zapisywany jako pomiar`() {
        val e = engine()
        var t = 0L
        var captures = 0
        while (t < 3000) {
            if (e.update(0.001, 1, false, t).captured != null) captures++
            t += 16
        }
        assertEquals(0, captures)
    }
}
