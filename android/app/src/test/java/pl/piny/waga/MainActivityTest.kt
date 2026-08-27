package pl.piny.waga

import android.os.Looper
import android.view.MotionEvent
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.math.exp

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MainActivityTest {

    private fun rawFor(mass: Double) = 1 - exp(-mass / 10.0)

    private fun touch(pan: PanView, action: Int, pressure: Float, size: Float = 12f) {
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER
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

    private fun launch(): MainActivity =
        Robolectric.buildActivity(MainActivity::class.java).setup().get()

    @Test
    fun `aplikacja startuje i pokazuje komplet elementow`() {
        val a = launch()
        assertNotNull(a.findViewById<PanView>(R.id.pan))
        assertNotNull(a.findViewById<MeterView>(R.id.meter))
        assertEquals("pięć narzędzi na dole", 5,
            a.findViewById<android.widget.LinearLayout>(R.id.tools).childCount)
        assertEquals("––,–", a.findViewById<TextView>(R.id.value).text.toString())
    }

    @Test
    fun `bez kalibracji nie pokazuje zadnej masy`() {
        val a = launch()
        val pan = a.findViewById<PanView>(R.id.pan)
        touch(pan, MotionEvent.ACTION_DOWN, 0.4f)
        idle(1500)
        assertEquals("––,–", a.findViewById<TextView>(R.id.value).text.toString())
        assertTrue(a.findViewById<TextView>(R.id.sub).text.toString().contains("kalibracj"))
    }

    @Test
    fun `skalibrowana waga pokazuje mase i zapisuje pomiar`() {
        val store = Store(org.robolectric.RuntimeEnvironment.getApplication())
        store.saveCalibration(
            Calibration(0.0, listOf(2.0, 5.0, 10.0, 20.0).map { CalPoint(rawFor(it), it) })
        )
        store.clearHistory()

        val a = launch()
        val pan = a.findViewById<PanView>(R.id.pan)

        // najpierw narastający docisk — waga musi zobaczyć zmienność, żeby uznać
        // ekran za czujnik siły, a nie zejść na kanał powierzchniowy
        touch(pan, MotionEvent.ACTION_DOWN, 0.08f)
        listOf(0.20f, 0.35f, 0.50f).forEach { touch(pan, MotionEvent.ACTION_MOVE, it); idle(50) }
        assertTrue("ekran musi zostać uznany za czujnik siły", pan.probe.hasForceSensor)

        repeat(50) {
            touch(pan, MotionEvent.ACTION_MOVE, rawFor(10.0).toFloat() + (it % 2) * 0.0005f)
            idle(50)
        }
        val shown = a.findViewById<TextView>(R.id.value).text.toString().replace(',', '.').toDouble()
        assertEquals("odczyt w gramach", 10.0, shown, 1.0)
        assertTrue("pomiar trafia do dziennika", store.history.isNotEmpty())
        assertTrue(a.findViewById<TextView>(R.id.sub).text.toString().contains("zatrzymano"))
    }

    @Test
    fun `przelacznik jednostek zmienia wyswietlana jednostke`() {
        val store = Store(org.robolectric.RuntimeEnvironment.getApplication())
        store.displayUnit = DisplayUnit.GRAMS
        val a = launch()
        val tools = a.findViewById<android.widget.LinearLayout>(R.id.tools)
        tools.getChildAt(3).performClick()
        idle(50)
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
        listOf(0.22f, 0.35f, 0.48f, 0.61f).forEach { touch(pan, MotionEvent.ACTION_MOVE, it) }
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
}
