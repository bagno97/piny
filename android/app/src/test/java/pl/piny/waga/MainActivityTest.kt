package pl.piny.waga

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowDialog
import android.view.View
import android.view.ViewGroup
import java.time.Duration
import kotlin.math.exp

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MainActivityTest {

    /**
     * Bez tego Robolectric wykonuje klatki natychmiast i pętla pomiarowa,
     * przeplanowując się w kółko, nigdy nie pozwala dokończyć startu aktywności.
     * Zatrzymany Choreographer wiąże klatki z zegarem — testy sterują nimi same.
     */
    @Before
    fun pauseFrames() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }

    private fun rawFor(mass: Double) = 1 - exp(-mass / 10.0)

    private fun touch(
        pan: PanView, action: Int, pressure: Float, size: Float = 12f,
        toolType: Int = MotionEvent.TOOL_TYPE_FINGER
    ) {
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0; this.toolType = toolType
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            x = 200f; y = 400f
            this.pressure = pressure
            this.size = size / PanView.AREA_FULL_SCALE_MM2.toFloat()
            touchMajor = size; touchMinor = size
        })
        val now = System.currentTimeMillis()
        val event = MotionEvent.obtain(now, now, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        pan.onTouchEvent(event)
        event.recycle()
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** Szuka w otwartym arkuszu przycisku o podanym początku napisu. */
    private fun sheetButton(prefix: String): TextView? {
        val root = ShadowDialog.getLatestDialog()?.window?.decorView ?: return null
        val found = mutableListOf<TextView>()
        fun walk(v: View) {
            if (v is TextView && v.text?.startsWith(prefix) == true && v.isClickable) found.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return found.firstOrNull()
    }

    private fun launch(): MainActivity =
        Robolectric.buildActivity(MainActivity::class.java).setup().get()

    @Test
    fun `symulowany zegar i petla klatek naprawde plyna`() {
        val a = launch()
        val t0 = SystemClock.uptimeMillis()
        val before = a.findViewById<TextView>(R.id.rawOut).text.toString()
        val pan = a.findViewById<PanView>(R.id.pan)
        touch(pan, MotionEvent.ACTION_DOWN, 0.4f)
        idle(1000)
        val dt = SystemClock.uptimeMillis() - t0
        assertTrue("zegar musi płynąć, minęło $dt ms", dt >= 900)
        assertTrue(
            "pętla klatek musi odświeżać sygnał: '$before' -> '${a.findViewById<TextView>(R.id.rawOut).text}'",
            a.findViewById<TextView>(R.id.rawOut).text.toString() != before
        )
    }

    @Test
    fun `aplikacja startuje i pokazuje komplet elementow`() {
        val a = launch()
        assertNotNull(a.findViewById<PanView>(R.id.pan))
        assertNotNull(a.findViewById<MeterView>(R.id.meter))
        assertEquals("pięć narzędzi na dole", 5,
            a.findViewById<android.widget.LinearLayout>(R.id.tools).childCount)
        idle(100)
        assertEquals("waga startuje gotowa do pracy", "0,0",
            a.findViewById<TextView>(R.id.value).text.toString())
    }

    @Test
    fun `bez zadnej kalibracji waga i tak wazy`() {
        val store = Store(org.robolectric.RuntimeEnvironment.getApplication())
        store.saveCalibration(Tool.FINGER, Calibration.automatic())
        val a = launch()
        val pan = a.findViewById<PanView>(R.id.pan)
        touch(pan, MotionEvent.ACTION_DOWN, 0.10f)
        listOf(0.18f, 0.26f, 0.34f, 0.42f, 0.50f, 0.58f)
            .forEach { touch(pan, MotionEvent.ACTION_MOVE, it); idle(50) }
        idle(500)

        val shown = a.findViewById<TextView>(R.id.value).text.toString().replace(',', '.').toDouble()
        assertTrue("waga musi pokazać liczbę od razu, było: $shown", shown > 0.0)
        assertTrue("odczyt ma być oznaczony jako szacunek",
            a.lastReading?.approximate == true)
        assertTrue("plakietka mówi o kalibracji wstępnej",
            a.findViewById<TextView>(R.id.calStamp).text.toString().contains("wstępna"))
    }

    @Test
    fun `rysik ma wlasny profil kalibracji`() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val store = Store(app)
        store.saveCalibration(Tool.FINGER, Calibration(0.0, listOf(CalPoint(0.5, 8.0))))
        store.saveCalibration(Tool.STYLUS, Calibration(0.0, listOf(CalPoint(0.5, 40.0))))

        val a = launch()
        val pan = a.findViewById<PanView>(R.id.pan)

        touch(pan, MotionEvent.ACTION_DOWN, 0.5f, toolType = MotionEvent.TOOL_TYPE_FINGER)
        idle(300)
        assertEquals(Tool.FINGER, pan.tool)
        assertEquals("profil palca", 8.0, a.engine.calibration.massFor(0.5)!!, 0.01)

        touch(pan, MotionEvent.ACTION_UP, 0.5f)
        touch(pan, MotionEvent.ACTION_DOWN, 0.5f, toolType = MotionEvent.TOOL_TYPE_STYLUS)
        idle(300)
        assertEquals(Tool.STYLUS, pan.tool)
        assertEquals("profil rysika", 40.0, a.engine.calibration.massFor(0.5)!!, 0.01)
        assertTrue(a.findViewById<TextView>(R.id.calStamp).text.toString().contains("rysik"))
    }

    @Test
    fun `profile palca i rysika sa niezalezne`() {
        val store = Store(org.robolectric.RuntimeEnvironment.getApplication())
        store.saveCalibration(Tool.FINGER, Calibration(0.0, listOf(CalPoint(0.4, 5.0))))
        assertTrue("rysik nie dziedziczy kalibracji palca",
            store.loadCalibration(Tool.STYLUS).auto)
        assertFalse(store.loadCalibration(Tool.FINGER).auto)
    }

    @Test
    fun `skalibrowana waga pokazuje mase i zapisuje pomiar`() {
        val store = Store(org.robolectric.RuntimeEnvironment.getApplication())
        store.saveCalibration(
            Tool.FINGER,
            Calibration(0.0, listOf(2.0, 5.0, 10.0, 20.0).map { CalPoint(rawFor(it), it) })
        )
        store.clearHistory()

        val a = launch()
        val pan = a.findViewById<PanView>(R.id.pan)

        // najpierw narastający docisk — waga musi zobaczyć zmienność, żeby uznać
        // ekran za czujnik siły, a nie zejść na kanał powierzchniowy
        touch(pan, MotionEvent.ACTION_DOWN, 0.08f)
        listOf(0.15f, 0.23f, 0.31f, 0.39f, 0.47f, 0.56f)
            .forEach { touch(pan, MotionEvent.ACTION_MOVE, it); idle(50) }
        assertTrue("ekran musi zostać uznany za czujnik siły", pan.probe.hasForceSensor)

        // trzymamy spokojny docisk aż waga sama zatrzyma odczyt — liczba klatek na
        // jedno „idle" zależy od Robolectrica, więc czekamy na stan, nie na iteracje
        var guard = 0
        while (a.lastReading?.state != ScaleState.HOLD && guard++ < 400) {
            touch(pan, MotionEvent.ACTION_MOVE, rawFor(10.0).toFloat() + (guard % 2) * 0.0005f)
            idle(50)
        }
        assertEquals(
            "waga musi zatrzymać odczyt (odchylenie ${a.lastReading?.stability})",
            ScaleState.HOLD, a.lastReading?.state
        )
        val shown = a.findViewById<TextView>(R.id.value).text.toString().replace(',', '.').toDouble()
        assertEquals("odczyt w gramach", 10.0, shown, 1.0)
        assertTrue(
            "pomiar trafia do dziennika (stan: '${a.findViewById<TextView>(R.id.sub).text}', " +
                "sygnał: '${a.findViewById<TextView>(R.id.rawOut).text}', " +
                "kontakt: '${a.findViewById<TextView>(R.id.contactsOut).text}', " +
                "odchylenie: ${a.lastReading?.stability}, próbek: ${a.lastReading?.samples}, " +
                "stan: ${a.lastReading?.state}, tara: ${a.lastReading?.tare})",
            store.history.isNotEmpty()
        )
        assertTrue(a.findViewById<TextView>(R.id.sub).text.toString().contains("zatrzymano"))
    }

    @Test
    fun `przelacznik jednostek zmienia wyswietlana jednostke`() {
        val store = Store(org.robolectric.RuntimeEnvironment.getApplication())
        store.displayUnit = DisplayUnit.GRAMS
        val a = launch()
        idle(100)
        // jednostkę przełącza się dotknięciem symbolu przy liczbie
        a.findViewById<TextView>(R.id.unit).performClick()
        idle(100)
        assertEquals(DisplayUnit.CARATS, Store(a).displayUnit)
        assertEquals("ct", a.findViewById<TextView>(R.id.unit).text.toString())
    }

    @Test
    fun `arkusze otwieraja sie bez bledu`() {
        val a = launch()
        val tools = a.findViewById<android.widget.LinearLayout>(R.id.tools)
        listOf(1, 2, 4).forEach { index ->
            tools.getChildAt(index).performClick()
            idle(100)
        }
        a.findViewById<TextView>(R.id.badge).performClick()
        idle(100)
    }

    @Test
    fun `wykrywanie czujnika sily dziala na prawdziwych zdarzeniach dotyku`() {
        val a = launch()
        val pan = a.findViewById<PanView>(R.id.pan)
        touch(pan, MotionEvent.ACTION_DOWN, 0.10f)
        listOf(0.22f, 0.35f, 0.48f, 0.61f, 0.74f, 0.86f)
            .forEach { touch(pan, MotionEvent.ACTION_MOVE, it) }
        idle(200)
        assertTrue("zmienny nacisk = czujnik siły", pan.probe.hasForceSensor)
        assertFalse(pan.usingArea())
    }

    @Test
    fun `staly nacisk przelacza na kanal powierzchniowy`() {
        val a = launch()
        val pan = a.findViewById<PanView>(R.id.pan)
        touch(pan, MotionEvent.ACTION_DOWN, 1.0f, size = 10f)
        repeat(20) { touch(pan, MotionEvent.ACTION_MOVE, 1.0f, size = 10f) }
        idle(200)
        assertFalse("stała jedynka to nie pomiar siły", pan.probe.hasForceSensor)
        assertTrue(pan.usingArea())
        val small = pan.signal()
        repeat(5) { touch(pan, MotionEvent.ACTION_MOVE, 1.0f, size = 20f) }
        assertTrue("większy styk = większy sygnał: $small -> ${pan.signal()}", pan.signal() > small)
    }

    @Test
    fun `zdjecie palca zeruje kontakt`() {
        val a = launch()
        val pan = a.findViewById<PanView>(R.id.pan)
        touch(pan, MotionEvent.ACTION_DOWN, 0.5f)
        assertEquals(1, pan.sample().contacts)
        touch(pan, MotionEvent.ACTION_UP, 0.5f)
        assertEquals(0, pan.sample().contacts)
    }

    @Test
    fun `test czujnika zbiera probki dopiero po oddaniu ekranu`() {
        val a = launch()
        idle(100)

        a.findViewById<TextView>(R.id.badge).performClick()
        idle(200)
        assertTrue("diagnostyka powinna być otwarta", ShadowDialog.getLatestDialog()!!.isShowing)

        val start = sheetButton("Test czujnika")
        assertNotNull("przycisk testu musi być w arkuszu", start)
        start!!.performClick()
        idle(200)

        // arkusz zasłaniał pole pomiarowe i przechwytywał dotknięcia —
        // pomiar bez oddania ekranu nie zebrałby ani jednej próbki
        assertFalse("arkusz musi zniknąć na czas pomiaru",
            ShadowDialog.getLatestDialog()!!.isShowing)
        assertEquals("baner musi prowadzić użytkownika", View.VISIBLE,
            a.findViewById<TextView>(R.id.captureBanner).visibility)

        val pan = a.findViewById<PanView>(R.id.pan)
        touch(pan, MotionEvent.ACTION_DOWN, 0.10f)
        repeat(70) {
            touch(pan, MotionEvent.ACTION_MOVE, 0.10f + (it % 8) * 0.06f)
            idle(100)
        }
        idle(500)

        assertEquals(View.GONE, a.findViewById<TextView>(R.id.captureBanner).visibility)
        assertTrue("po teście wraca arkusz z wynikiem",
            ShadowDialog.getLatestDialog()!!.isShowing)
        assertNotNull(sheetButton("Ucz zakresu"))
    }

    @Test
    fun `pomiar wzorca zbiera probki z pola pomiarowego`() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        Store(app).saveCalibration(Tool.FINGER, Calibration.automatic())

        val a = launch()
        idle(100)
        a.findViewById<android.widget.LinearLayout>(R.id.tools).getChildAt(1).performClick()
        idle(200)

        val measure = sheetButton("Zmierz wzorzec")
        assertNotNull("przycisk pomiaru wzorca musi być w arkuszu", measure)
        measure!!.performClick()
        idle(200)

        assertFalse("arkusz musi oddać ekran pod docisk",
            ShadowDialog.getLatestDialog()!!.isShowing)

        val pan = a.findViewById<PanView>(R.id.pan)
        touch(pan, MotionEvent.ACTION_DOWN, 0.40f)
        repeat(40) { touch(pan, MotionEvent.ACTION_MOVE, 0.40f + (it % 2) * 0.001f); idle(100) }
        idle(500)

        assertFalse("wzorzec musi trafić na krzywą", a.engine.calibration.auto)
        assertEquals(1, a.engine.calibration.referenceCount)
        assertEquals("zapisana masa wzorca", 6.54, a.engine.calibration.maxMass, 0.001)
    }
}
