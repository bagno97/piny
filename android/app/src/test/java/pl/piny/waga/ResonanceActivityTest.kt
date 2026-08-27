package pl.piny.waga

import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Looper
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager
import java.time.Duration
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ResonanceActivityTest {

    private lateinit var sensorManager: SensorManager
    private lateinit var accelerometer: Sensor

    @Before
    fun prepare() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        // Robolectric nie ma domyślnie akcelerometru — podstawiamy własny
        sensorManager = RuntimeEnvironment.getApplication()
            .getSystemService(android.content.Context.SENSOR_SERVICE) as SensorManager
        accelerometer = ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER)
        shadowOf(sensorManager).addSensor(Sensor.TYPE_ACCELEROMETER, accelerometer)
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun launch(): ResonanceActivity =
        Robolectric.buildActivity(ResonanceActivity::class.java).setup().get()

    /** Podaje aktywności zanikające drganie o zadanej częstotliwości. */
    private fun feed(activity: ResonanceActivity, hz: Double, seconds: Double = 2.6, rate: Double = 400.0) {
        val n = (seconds * rate).toInt()
        for (i in 0 until n) {
            val t = i / rate
            val value = 9.81f + (exp(-4.0 * t) * sin(2 * PI * hz * t)).toFloat()
            activity.onSensorChanged(
                ShadowSensorManager.createSensorEvent(3, Sensor.TYPE_ACCELEROMETER).apply {
                    values[0] = value; values[1] = 0f; values[2] = 0f
                    timestamp = (t * 1_000_000_000L).toLong()
                }
            )
        }
    }

    @Test
    fun `ekran rezonansowy startuje i prowadzi od kroku pierwszego`() {
        val a = launch()
        assertEquals("nie zmierzono", a.findViewById<TextView>(R.id.stateEmpty).text.toString())
        assertEquals("brak kalibracji", a.findViewById<TextView>(R.id.stateCalibration).text.toString())
        assertTrue(a.findViewById<TextView>(R.id.status).text.toString().contains("kroku 1"))
    }

    @Test
    fun `pelna sciezka  pusty telefon  wzorzec  wazenie`() {
        val a = launch()
        val systemMass = 200.0
        val emptyHz = 40.0
        val c = systemMass * emptyHz * emptyHz          // C = M·f²

        // krok 1: telefon bez obciążenia
        a.findViewById<TextView>(R.id.stepEmpty).performClick()
        feed(a, emptyHz)
        idle(3000)
        assertTrue("częstotliwość własna zmierzona",
            a.findViewById<TextView>(R.id.stateEmpty).text.toString().contains("Hz"))

        // krok 2: wzorzec 50 g
        a.findViewById<android.widget.EditText>(R.id.referenceMass).setText("50,0")
        a.findViewById<TextView>(R.id.stepReference).performClick()
        feed(a, sqrt(c / (systemMass + 50.0)))
        idle(3000)
        val calibration = a.findViewById<TextView>(R.id.stateCalibration).text.toString()
        assertFalse("kalibracja nie powstała: $calibration", calibration.contains("brak"))
        val measured = Regex("""układ (\d+) g""").find(calibration)!!.groupValues[1].toDouble()
        assertEquals("odtworzona masa układu", systemMass, measured, 5.0)

        // krok 3: ważenie 20 g
        a.findViewById<TextView>(R.id.stepWeigh).performClick()
        feed(a, sqrt(c / (systemMass + 20.0)))
        idle(3000)
        val shown = a.findViewById<TextView>(R.id.mass).text.toString().replace(',', '.').toDouble()
        assertEquals("zważona masa", 20.0, shown, 1.5)
    }

    @Test
    fun `czestotliwosc rosnaca po dolozeniu masy jest odrzucana`() {
        val a = launch()
        a.findViewById<TextView>(R.id.stepEmpty).performClick()
        feed(a, 30.0)
        idle(3000)

        a.findViewById<android.widget.EditText>(R.id.referenceMass).setText("50,0")
        a.findViewById<TextView>(R.id.stepReference).performClick()
        feed(a, 35.0)                                   // wyżej zamiast niżej
        idle(3000)

        assertTrue("aplikacja musi odrzucić pomiar sprzeczny z fizyką",
            a.findViewById<TextView>(R.id.status).text.toString().contains("przeczy fizyce"))
        assertEquals("brak kalibracji",
            a.findViewById<TextView>(R.id.stateCalibration).text.toString())
    }
}
