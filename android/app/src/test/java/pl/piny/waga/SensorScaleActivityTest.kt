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

    /** Odstawia telefon i czeka, aż waga wyzeruje się sama. */
    private fun autoZero(a: SensorScaleActivity) {
        for (i in 0 until 20) {
            feedResting(a, 0.0, n = 60, noise = 0.002)
            if (a.findViewById<TextView>(R.id.stateZero).text.toString().contains("zmierzony")) return
        }
        fail("waga nie wyzerowała się sama")
    }

    /**
     * Prowadzi wagę przez zadaną liczbę cykli, obsługując pomiary, które
     * rozpoczyna ona sama — to ona decyduje, kiedy pobudzić układ.
     */
    private fun drive(a: SensorScaleActivity, tiltDeg: Double, hz: Double, rounds: Int) {
        repeat(rounds) {
            if (a.busy) runCapture(a, tiltDeg, hz)
            else feedResting(a, tiltDeg, n = 60, noise = 0.002)
        }
    }

    private fun status(a: SensorScaleActivity) = a.findViewById<TextView>(R.id.status).text.toString()

    @Test
    fun `ekran startuje i prowadzi od kroku pierwszego`() {
        val a = launch()
        assertEquals("nie zmierzono", a.findViewById<TextView>(R.id.stateZero).text.toString())
        assertTrue(a.findViewById<TextView>(R.id.status).text.toString().contains("kroku 1"))
    }

    @Test
    fun `liczy w gramach bez zadnego wzorca  odwaznikiem jest sam telefon`() {
        val a = launch()
        val phone = 209.0
        Store(a).phoneGrams = phone
        val emptyHz = 40.0
        val c = phone * emptyHz * emptyHz
        fun hzFor(g: Double) = sqrt(c / (phone + g))

        // odstawiony telefon zeruje się sam, po czym sam mierzy drgania własne
        autoZero(a)
        drive(a, 0.0, emptyHz, rounds = 6)
        assertTrue("waga ma być gotowa bez wzorca, stan: ${status(a)}",
            status(a).contains("bez wzorca"))

        // kładziemy przedmiot: przechył się ustala, waga sama waży bezwzględnie
        drive(a, 0.30, hzFor(20.0), rounds = 16)

        val shown = a.findViewById<TextView>(R.id.mass).text.toString().replace(',', '.').toDouble()
        assertEquals("masa policzona bez kalibracji, stan: ${status(a)}", 20.0, shown, 2.0)
    }

    @Test
    fun `po pierwszym wazeniu odczyt przechylu jest natychmiastowy`() {
        val a = launch()
        val phone = 209.0
        Store(a).phoneGrams = phone
        val emptyHz = 40.0
        val c = phone * emptyHz * emptyHz

        autoZero(a)
        drive(a, 0.0, emptyHz, rounds = 6)
        drive(a, 0.30, sqrt(c / (phone + 20.0)), rounds = 16)

        // inny przedmiot: sam przechył wystarczy, bez ponownego pobudzania
        drive(a, 0.45, sqrt(c / (phone + 30.0)), rounds = 10)
        val shown = a.findViewById<TextView>(R.id.mass).text.toString().replace(',', '.').toDouble()
        assertEquals("półtora raza większy przechył to półtora raza większa masa",
            30.0, shown, 4.0)
    }

    @Test
    fun `wzorzec zapisuje sie sam  bez naciskania w trakcie pomiaru`() {
        val a = launch()
        autoZero(a)

        a.findViewById<EditText>(R.id.referenceMass).setText("20,0")
        a.findViewById<TextView>(R.id.stepReference).performClick()
        assertTrue("waga ma czekać na wzorzec",
            a.findViewById<TextView>(R.id.status).text.toString().contains("Czekam"))

        // kładziemy wzorzec i cofamy ręce — reszta dzieje się sama
        drive(a, 0.40, 40.0, rounds = 16)
        val state = a.findViewById<TextView>(R.id.stateModel).text.toString()
        assertFalse("wzorzec musi zostać zapisany bez dotykania telefonu: $state",
            state.contains("brak wzorców"))
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

    /** Podaje próbki w stanie spoczynku — tak, jak leżący telefon. */
    private fun feedResting(a: SensorScaleActivity, tiltDeg: Double, n: Int = 200, noise: Double = 0.004) {
        val rad = Math.toRadians(tiltDeg)
        repeat(n) {
            send(a,
                9.81 * sin(rad) + random.nextGaussian() * noise,
                random.nextGaussian() * noise,
                9.81 * cos(rad) + random.nextGaussian() * noise,
                it / 50.0)
        }
        idle(400)
    }

    @Test
    fun `odczyt lezacego przedmiotu pojawia sie sam i zostaje`() {
        val a = launch()

        // pusty telefon leży spokojnie, zerujemy w tym położeniu
        feedResting(a, 0.0)
        a.findViewById<TextView>(R.id.tare).performClick()
        idle(300)

        // kładziemy przedmiot: przechył rośnie bez naciskania czegokolwiek
        feedResting(a, 0.35)
        val shown = a.findViewById<TextView>(R.id.channelTilt).text.toString()
        val angle = Regex("""przechył ([0-9.]+)""").find(shown)!!.groupValues[1].toDouble()
        assertEquals("odczyt pojawia się sam: $shown", 0.35, angle, 0.05)

        // przedmiot dalej leży — wskazanie ma zostać, a nie wrócić do zera
        feedResting(a, 0.35)
        feedResting(a, 0.35)
        val later = Regex("""przechył ([0-9.]+)""")
            .find(a.findViewById<TextView>(R.id.channelTilt).text.toString())!!
            .groupValues[1].toDouble()
        assertEquals("wskazanie musi zostać, dopóki przedmiot leży", 0.35, later, 0.05)
    }

    @Test
    fun `zdjecie przedmiotu sprowadza wskazanie do zera`() {
        val a = launch()
        feedResting(a, 0.0)
        a.findViewById<TextView>(R.id.tare).performClick()
        idle(300)

        feedResting(a, 0.30)
        feedResting(a, 0.0)
        feedResting(a, 0.0)
        val angle = Regex("""przechył ([0-9.]+)""")
            .find(a.findViewById<TextView>(R.id.channelTilt).text.toString())!!
            .groupValues[1].toDouble()
        assertTrue("po zdjęciu przedmiotu wskazanie wraca do zera, było $angle", angle < 0.05)
    }

    @Test
    fun `zerowanie odrzuca poruszany telefon`() {
        val a = launch()
        feedResting(a, 0.0, noise = 1.2)
        a.findViewById<TextView>(R.id.tare).performClick()
        idle(200)
        assertEquals("nie zmierzono", a.findViewById<TextView>(R.id.stateZero).text.toString())
    }

    @Test
    fun `waga zeruje sie sama gdy telefon lezy nieruchomo`() {
        val a = launch()
        // odstawiony telefon: mały rozrzut przez ponad dwie sekundy
        autoZero(a)
    }

    @Test
    fun `trzymany w rece telefon nie zeruje sie sam`() {
        val a = launch()
        // rozrzut jak przy trzymaniu w dłoni — kilka stopni
        repeat(12) { feedResting(a, 0.0, n = 60, noise = 0.35) }
        val status = a.findViewById<TextView>(R.id.status).text.toString()
        assertFalse("drgający telefon nie może uchodzić za odstawiony",
            status.contains("samoczynnie"))
        assertTrue("aplikacja musi powiedzieć, że telefon się rusza",
            a.findViewById<TextView>(R.id.live).text.toString().contains("RUSZA"))
    }

    @Test
    fun `wskazanie ustala sie samo po polozeniu przedmiotu`() {
        val a = launch()
        repeat(12) { feedResting(a, 0.0, n = 60, noise = 0.002) }   // samoczynne zero

        // kładziemy przedmiot i zostawiamy
        repeat(14) { feedResting(a, 0.28, n = 60, noise = 0.002) }
        val status = a.findViewById<TextView>(R.id.status).text.toString()
        assertTrue("wskazanie ma ustalić się samo: $status", status.contains("ustalone"))
        assertTrue(status.contains("0.28") || status.contains("0.27") || status.contains("0.29"))
    }
}
