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
import kotlin.math.cos
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TiltScaleActivityTest {

    private val random = Random(3)

    @Before
    fun prepare() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val sm = RuntimeEnvironment.getApplication()
            .getSystemService(android.content.Context.SENSOR_SERVICE) as SensorManager
        shadowOf(sm).addSensor(Sensor.TYPE_ACCELEROMETER, ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun launch(): TiltScaleActivity =
        Robolectric.buildActivity(TiltScaleActivity::class.java).setup().get()

    /** Telefon leży nieruchomo, przechylony o zadany kąt. */
    private fun rest(a: TiltScaleActivity, tiltDeg: Double, noise: Double = 0.003, n: Int = 80) {
        val rad = Math.toRadians(tiltDeg)
        repeat(n) {
            a.onSensorChanged(
                ShadowSensorManager.createSensorEvent(3, Sensor.TYPE_ACCELEROMETER).apply {
                    values[0] = (9.81 * sin(rad) + random.nextGaussian() * noise).toFloat()
                    values[1] = (random.nextGaussian() * noise).toFloat()
                    values[2] = (9.81 * cos(rad) + random.nextGaussian() * noise).toFloat()
                }
            )
        }
        idle(400)
    }

    /** Odstawia telefon i czeka, aż waga wyzeruje się sama. */
    private fun autoZero(a: TiltScaleActivity) {
        for (i in 0 until 20) {
            rest(a, 0.0)
            if (a.hasZero) return
        }
        fail("waga nie wyzerowała się sama")
    }

    private fun status(a: TiltScaleActivity) = a.findViewById<TextView>(R.id.status).text.toString()

    @Test
    fun `startuje bez kalibracji i mowi co robic`() {
        val a = launch()
        assertFalse(a.isCalibrated)
        assertTrue(status(a).contains("ODSTAW TELEFON"))
        assertEquals("brak przelicznika — waga pokazuje stopnie",
            a.findViewById<TextView>(R.id.calibrationState).text.toString())
    }

    @Test
    fun `odstawiony telefon zeruje sie sam`() {
        val a = launch()
        autoZero(a)
        assertTrue(a.hasZero)
        assertTrue(status(a).contains("samoczynnie"))
    }

    @Test
    fun `telefon w rece nie zeruje sie i jest oznaczony jako ruchomy`() {
        val a = launch()
        repeat(10) { rest(a, 0.0, noise = 0.5) }
        assertFalse("drgający telefon nie może uchodzić za odstawiony", a.hasZero)
        assertTrue(a.findViewById<TextView>(R.id.stability).text.toString().contains("RUSZA"))
    }

    @Test
    fun `lezacy przedmiot podnosi wskazanie i je utrzymuje`() {
        val a = launch()
        autoZero(a)

        repeat(6) { rest(a, 0.32) }
        assertEquals("przechył od leżącego przedmiotu", 0.32, a.currentTiltDeg, 0.03)

        // przedmiot dalej leży — wskazanie ma zostać
        repeat(6) { rest(a, 0.32) }
        assertEquals(0.32, a.currentTiltDeg, 0.03)

        // zdjęty — wraca do zera
        repeat(6) { rest(a, 0.0) }
        assertTrue("po zdjęciu wskazanie wraca do zera", a.currentTiltDeg < 0.03)
    }

    @Test
    fun `jeden wzorzec zamienia stopnie na gramy i zostaje zapamietany`() {
        val a = launch()
        autoZero(a)

        a.findViewById<EditText>(R.id.referenceMass).setText("6,54")
        a.findViewById<TextView>(R.id.calibrate).performClick()
        assertTrue("waga ma czekać na wzorzec", status(a).contains("Czekam"))

        // kładziemy monetę i cofamy ręce — reszta dzieje się sama
        repeat(10) { rest(a, 0.40) }
        assertTrue("wzorzec ma zostać zapisany bez dotykania telefonu: ${status(a)}", a.isCalibrated)
        assertEquals("6,54 g na 0,40° to ok. 16 g na stopień", 16.35, 6.54 / 0.40, 0.01)
        assertEquals(6.54, a.currentGrams, 0.6)

        // kolejny przedmiot: gramy od razu, bez powtarzania kalibracji
        repeat(8) { rest(a, 0.80) }
        assertEquals("dwa razy większy przechył to dwa razy większa masa",
            13.08, a.currentGrams, 1.2)

        // przelicznik przeżywa ponowne wejście na ekran
        assertTrue(Store(a).gramsPerDegree > 0)
    }

    @Test
    fun `za maly przechyl wzorca jest zglaszany zamiast zapisany`() {
        val a = launch()
        autoZero(a)
        a.findViewById<EditText>(R.id.referenceMass).setText("6,54")
        a.findViewById<TextView>(R.id.calibrate).performClick()
        repeat(20) { rest(a, 0.0) }          // wzorzec nic nie ugiął
        assertFalse(a.isCalibrated)
        assertTrue(status(a).contains("za mały"))
    }

    @Test
    fun `zapomnienie przelicznika wraca do stopni`() {
        val a = launch()
        autoZero(a)
        a.findViewById<EditText>(R.id.referenceMass).setText("6,54")
        a.findViewById<TextView>(R.id.calibrate).performClick()
        repeat(10) { rest(a, 0.40) }
        assertTrue(a.isCalibrated)

        a.findViewById<TextView>(R.id.forget).performClick()
        assertFalse(a.isCalibrated)
        assertEquals(0.0, Store(a).gramsPerDegree, 0.0)
    }
}
