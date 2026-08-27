package pl.piny.waga

import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Looper
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager
import java.time.Duration
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SensorScaleActivityTest {

    private val random = Random(5)

    @Before
    fun prepare() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val sm = RuntimeEnvironment.getApplication()
            .getSystemService(android.content.Context.SENSOR_SERVICE) as SensorManager
        shadowOf(sm).addSensor(Sensor.TYPE_ACCELEROMETER, ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun launch(): SensorScaleActivity =
        Robolectric.buildActivity(SensorScaleActivity::class.java).setup().get()

    private fun send(a: SensorScaleActivity, x: Double, y: Double, z: Double, t: Double) {
        a.onSensorChanged(
            ShadowSensorManager.createSensorEvent(3, Sensor.TYPE_ACCELEROMETER).apply {
                values[0] = x.toFloat(); values[1] = y.toFloat(); values[2] = z.toFloat()
                timestamp = (t * 1_000_000_000L).toLong()
            }
        )
    }

    /** Odgrywa pełny pomiar: cisza z przechyłem, potem wybrzmiewanie o zadanej częstotliwości. */
    private fun runCapture(a: SensorScaleActivity, tiltDeg: Double, hz: Double, noise: Double = 0.01) {
        val rad = Math.toRadians(tiltDeg)
        repeat(400) {
            send(a,
                9.81 * sin(rad) + random.nextGaussian() * noise,
                random.nextGaussian() * noise,
                9.81 * cos(rad) + random.nextGaussian() * noise,
                it / 250.0)
        }
        idle(SensorScaleActivity.STATIC_MS + 50)

        val rate = 400.0
        repeat(1100) {
            val t = it / rate
            send(a, 9.81 + exp(-4.0 * t) * sin(2 * PI * hz * t), 0.0, 0.0, t)
        }
        idle(SensorScaleActivity.RINGDOWN_MS + 200)
    }

    @Test
    fun `ekran startuje i prowadzi od kroku pierwszego`() {
        val a = launch()
        assertEquals("nie zmierzono", a.findViewById<TextView>(R.id.stateZero).text.toString())
        assertTrue(a.findViewById<TextView>(R.id.status).text.toString().contains("kroku 1"))
    }

    @Test
    fun `mierzy przedmiot lezacy  bez zadnego dotkniecia ekranu`() {
        val a = launch()
        val systemMass = 200.0
        val emptyHz = 40.0
        val c = systemMass * emptyHz * emptyHz
        // przechył 0,02° na gram — przedmiot kładziony zawsze w tym samym miejscu
        fun tiltFor(g: Double) = g * 0.02
        fun hzFor(g: Double) = sqrt(c / (systemMass + g))

        // krok 1 — pusty telefon
        a.findViewById<TextView>(R.id.stepZero).performClick()
        runCapture(a, 0.0, emptyHz)
        assertTrue("stan pusty zmierzony",
            a.findViewById<TextView>(R.id.stateZero).text.toString().contains("zmierzony"))

        // krok 2 — trzy wzorce, żeby model objął oba kanały
        for (g in listOf(10.0, 30.0, 60.0)) {
            a.findViewById<EditText>(R.id.referenceMass).setText(Fmt.pl(g, 1))
            a.findViewById<TextView>(R.id.stepReference).performClick()
            runCapture(a, tiltFor(g), hzFor(g))
        }
        val modelState = a.findViewById<TextView>(R.id.stateModel).text.toString()
        assertTrue("model powinien objąć oba kanały: $modelState", modelState.contains("oba kanały"))

        // krok 3 — ważenie przedmiotu, którego nie było wśród wzorców
        a.findViewById<TextView>(R.id.stepWeigh).performClick()
        runCapture(a, tiltFor(45.0), hzFor(45.0))
        val shown = a.findViewById<TextView>(R.id.mass).text.toString().replace(',', '.').toDouble()
        assertEquals("masa leżącego przedmiotu", 45.0, shown, 4.0)

        assertTrue("pomiar trafia do dziennika", Store(a).history.isNotEmpty())
    }

    @Test
    fun `sam przechyl wystarczy gdy rezonansu nie ma`() {
        val a = launch()
        a.findViewById<TextView>(R.id.stepZero).performClick()
        runCapture(a, 0.0, 40.0)

        a.findViewById<EditText>(R.id.referenceMass).setText("20,0")
        a.findViewById<TextView>(R.id.stepReference).performClick()
        runCapture(a, 0.40, 40.0)              // częstotliwość bez zmian, sam przechył

        val state = a.findViewById<TextView>(R.id.stateModel).text.toString()
        assertTrue("model musi zejść na jeden kanał: $state", state.contains("przechył"))

        a.findViewById<TextView>(R.id.stepWeigh).performClick()
        runCapture(a, 0.20, 40.0)
        val shown = a.findViewById<TextView>(R.id.mass).text.toString().replace(',', '.').toDouble()
        assertEquals("połowa przechyłu to połowa masy", 10.0, shown, 2.0)
    }

    @Test
    fun `poruszony telefon nie daje pomiaru`() {
        val a = launch()
        a.findViewById<TextView>(R.id.stepZero).performClick()
        runCapture(a, 0.0, 40.0, noise = 0.9)   // ktoś trzyma telefon w ręce
        assertTrue("aplikacja musi odrzucić niespokojny zapis",
            a.findViewById<TextView>(R.id.status).text.toString().contains("poruszał"))
        assertEquals("nie zmierzono", a.findViewById<TextView>(R.id.stateZero).text.toString())
    }

    @Test
    fun `brak sygnalu w obu kanalach jest zglaszany`() {
        val a = launch()
        a.findViewById<TextView>(R.id.stepZero).performClick()
        runCapture(a, 0.0, 40.0)

        a.findViewById<EditText>(R.id.referenceMass).setText("20,0")
        a.findViewById<TextView>(R.id.stepReference).performClick()
        runCapture(a, 0.0, 40.0)                // nic się nie zmieniło
        assertTrue(a.findViewById<TextView>(R.id.status).text.toString().contains("Żaden kanał"))
    }
}
