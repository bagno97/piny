package pl.piny.waga

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import pl.piny.waga.databinding.ActivitySensorScaleBinding
import java.util.Locale
import kotlin.math.sqrt

/**
 * Waga czujnikowa: mierzy masę przedmiotu LEŻĄCEGO na telefonie, bez dotyku.
 *
 * Ekran pojemnościowy nie widzi przedmiotu biernego, więc masa jest odczytywana
 * dwoma niezależnymi kanałami:
 *
 *  - przechył — przedmiot położony poza środkiem ugina podłoże nierównomiernie
 *    i obraca telefon o kąt proporcjonalny do momentu siły. Sygnał jest
 *    STATYCZNY: trwa, dopóki przedmiot leży, więc odczyt nie znika.
 *  - rezonans — telefon na miękkim podłożu drga jak masa na sprężynie; dołożona
 *    masa obniża częstotliwość drgań własnych.
 *
 * Czujnik zbliżeniowy służy do potwierdzenia, że coś na telefonie leży.
 * Oba kanały łączy model uczony na wzorcach o znanej masie.
 */
class SensorScaleActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        const val STATIC_MS = 1600L
        const val RINGDOWN_MS = 2600L
        const val MIN_STATIC_SAMPLES = 64
        const val MIN_RINGDOWN_SAMPLES = 512

        /** Okno uśredniania odczytu na żywo — kompromis między spokojem a reakcją. */
        const val LIVE_WINDOW = 160
        const val LIVE_REFRESH_MS = 200L
        const val LIVE_MIN_SAMPLES = 48
        private const val NANOS = 1_000_000_000.0
    }

    private lateinit var b: ActivitySensorScaleBinding
    private lateinit var store: Store
    private lateinit var sensors: SensorManager

    private var accelerometer: Sensor? = null
    private var proximity: Sensor? = null

    private val ui = Handler(Looper.getMainLooper())

    private enum class Phase { IDLE, STATIC, RINGDOWN }

    private var phase = Phase.IDLE
    private val directions = ArrayList<Direction>(1024)
    private val magnitudes = ArrayList<Double>(4096)
    private val stamps = ArrayList<Long>(4096)
    private var proximityNear = false
    private var onCaptured: ((Direction?, Double?, Double) -> Unit)? = null

    /** Okno ostatnich sekund — z niego liczony jest odczyt na żywo. */
    private val liveWindow = ArrayDeque<Direction>()
    private var liveTiltDeg = 0.0
    private var liveMass = 0.0

    private val liveTicker = object : Runnable {
        override fun run() {
            updateLive()
            ui.postDelayed(this, LIVE_REFRESH_MS)
        }
    }

    private var baseline: Direction? = null
    private var baselineHz = 0.0
    private var samples = listOf<SensorSample>()
    private var model: SensorScaleModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySensorScaleBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        store = Store(this)
        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        proximity = sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        baseline = store.baselineTilt
        baselineHz = store.baselineHz
        samples = store.sensorSamples
        model = SensorScaleModel.build(samples)

        b.back.setOnClickListener { finish() }
        b.stepZero.setOnClickListener { measureZero() }
        b.tare.setOnClickListener { tareHere() }
        b.stepReference.setOnClickListener { addReference() }
        b.stepWeigh.setOnClickListener { weigh() }
        b.reset.setOnClickListener {
            store.clearSensorScale()
            baseline = null; baselineHz = 0.0; samples = emptyList(); model = null
            b.status.setText(R.string.sen_status_start)
            paintState()
            toast(getString(R.string.sen_reset_done))
        }

        if (accelerometer == null) {
            b.status.setText(R.string.sen_no_accelerometer)
            setButtonsEnabled(false)
        }
        paintState()
    }

    override fun onResume() {
        super.onResume()
        // czujnik chodzi bez przerwy: waga ma pokazywać masę leżącego przedmiotu
        // od razu i trzymać ją, a nie mierzyć w czterosekundowych zrywach
        accelerometer?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        proximity?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        ui.post(liveTicker)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(liveTicker)
        stopCapture()
        sensors.unregisterListener(this)
    }

    /** Przelicza okno na kąt i masę; wołane co [LIVE_REFRESH_MS]. */
    private fun updateLive() {
        if (phase != Phase.IDLE) return
        val base = baseline ?: return
        if (liveWindow.size < LIVE_MIN_SAMPLES) return

        val samplesNow = liveWindow.toList()
        val mean = TiltAnalyzer.meanDirection(samplesNow, maxSpreadDeg = 2.0) ?: return
        liveTiltDeg = base.angleTo(mean)
        liveMass = model?.mass(liveTiltDeg, 0.0) ?: 0.0

        b.channelTilt.text = getString(R.string.sen_tilt_value, String.format(Locale.US, "%.4f", liveTiltDeg))
        if (model != null) b.mass.text = Fmt.pl(liveMass, 1)
        b.live.text = getString(
            if (TiltAnalyzer.spreadDeg(samplesNow) > 0.4) R.string.sen_live_unstable else R.string.sen_live_ok
        )
    }

    // ── zbieranie danych z czujników ────────────────────────────────────────

    /**
     * Jeden pomiar: najpierw cisza (przechył), potem impuls i wybrzmiewanie
     * (rezonans). Oddaje kierunek grawitacji, częstotliwość i rozrzut zapisu.
     */
    private fun capture(prompt: String, onDone: (Direction?, Double?, Double) -> Unit) {
        val sensor = accelerometer ?: return
        if (phase != Phase.IDLE) return

        directions.clear(); magnitudes.clear(); stamps.clear()
        proximityNear = false
        onCaptured = onDone
        b.status.text = prompt
        setButtonsEnabled(false)

        sensors.unregisterListener(this)
        sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST)
        proximity?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }

        phase = Phase.STATIC
        ui.postDelayed({
            phase = Phase.RINGDOWN
            buzz(35)                                    // impuls pobudzający układ
            ui.postDelayed({ finishCapture() }, RINGDOWN_MS)
        }, STATIC_MS)
    }

    private fun finishCapture() {
        stopCapture()
        setButtonsEnabled(true)

        val spread = if (directions.isEmpty()) 0.0 else TiltAnalyzer.spreadDeg(directions)
        val tilt = if (directions.size >= MIN_STATIC_SAMPLES) TiltAnalyzer.meanDirection(directions) else null

        val hz = if (magnitudes.size >= MIN_RINGDOWN_SAMPLES) {
            val span = (stamps.last() - stamps.first()) / NANOS
            val rate = if (span > 0) (magnitudes.size - 1) / span else 0.0
            ResonanceAnalyzer.dominantFrequency(magnitudes.toDoubleArray(), rate)
        } else null

        b.channelTilt.text = if (tilt == null) getString(R.string.sen_tilt_none)
        else getString(R.string.sen_tilt_value,
            String.format(Locale.US, "%.4f", baseline?.angleTo(tilt) ?: 0.0))
        b.channelResonance.text = if (hz == null) getString(R.string.sen_res_none)
        else getString(R.string.sen_res_value, String.format(Locale.US, "%.3f", hz))
        b.presence.text = getString(
            if (proximityNear) R.string.sen_presence_yes else R.string.sen_presence_no
        )

        onCaptured?.invoke(tilt, hz, spread)
    }

    private fun stopCapture() {
        if (phase == Phase.IDLE) return
        phase = Phase.IDLE
        sensors.unregisterListener(this)
        liveWindow.clear()
        accelerometer?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        proximity?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor?.type) {
            Sensor.TYPE_PROXIMITY -> {
                val range = event.sensor.maximumRange.takeIf { it > 0f } ?: 5f
                if (event.values[0] < range) proximityNear = true
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0].toDouble()
                val y = event.values[1].toDouble()
                val z = event.values[2].toDouble()
                when (phase) {
                    Phase.IDLE -> {
                        liveWindow.addLast(Direction(x, y, z))
                        while (liveWindow.size > LIVE_WINDOW) liveWindow.removeFirst()
                    }
                    Phase.STATIC -> directions.add(Direction(x, y, z))
                    Phase.RINGDOWN -> {
                        magnitudes.add(sqrt(x * x + y * y + z * z))
                        stamps.add(event.timestamp)
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ── kroki ───────────────────────────────────────────────────────────────

    private fun measureZero() = capture(getString(R.string.sen_capturing_zero)) { tilt, hz, spread ->
        if (tilt == null) {
            b.status.text = getString(R.string.sen_unstable, String.format(Locale.US, "%.3f", spread))
            return@capture
        }
        baseline = tilt
        baselineHz = hz ?: 0.0
        store.baselineTilt = tilt
        store.baselineHz = baselineHz
        store.sensorSamples = emptyList()
        samples = emptyList()
        model = null
        b.status.text = getString(R.string.sen_zero_done,
            if (baselineHz > 0) String.format(Locale.US, "%.2f Hz", baselineHz)
            else getString(R.string.sen_res_missing))
        paintState()
    }

    /** Zeruje wskazanie w bieżącym położeniu — bez ceremonii, jak tara w wadze. */
    private fun tareHere() {
        val samplesNow = liveWindow.toList()
        val mean = TiltAnalyzer.meanDirection(samplesNow, maxSpreadDeg = 2.0)
        if (mean == null) {
            toast(getString(R.string.sen_tare_unstable))
            return
        }
        baseline = mean
        store.baselineTilt = mean
        liveTiltDeg = 0.0
        liveMass = 0.0
        b.mass.text = "0,0"
        toast(getString(R.string.sen_tare_done))
        paintState()
    }

    private fun addReference() {
        val base = baseline
        if (base == null) { toast(getString(R.string.sen_need_zero)); return }
        val grams = Fmt.parse(b.referenceMass.text.toString())
        if (grams == null || grams <= 0) { toast(getString(R.string.sen_need_mass)); return }

        capture(getString(R.string.sen_capturing_reference)) { tilt, hz, spread ->
            if (tilt == null) {
                b.status.text = getString(R.string.sen_unstable, String.format(Locale.US, "%.3f", spread))
                return@capture
            }
            val tiltDeg = base.angleTo(tilt)
            val drop = if (baselineHz > 0 && hz != null) (baselineHz - hz).coerceAtLeast(0.0) else 0.0

            if (tiltDeg < SensorScaleModel.MIN_TILT_DEG && drop < SensorScaleModel.MIN_FREQ_DROP) {
                b.status.text = getString(R.string.sen_no_signal)
                return@capture
            }

            samples = samples + SensorSample(tiltDeg, drop, grams)
            store.sensorSamples = samples
            model = SensorScaleModel.build(samples)
            b.status.text = getString(R.string.sen_reference_added,
                Fmt.pl(grams, 1), String.format(Locale.US, "%.4f", tiltDeg),
                String.format(Locale.US, "%.3f", drop))
            paintState()
        }
    }

    private fun weigh() {
        val base = baseline
        val current = model
        if (base == null || current == null) { toast(getString(R.string.sen_need_calibration)); return }

        capture(getString(R.string.sen_capturing_object)) { tilt, hz, spread ->
            if (tilt == null) {
                b.status.text = getString(R.string.sen_unstable, String.format(Locale.US, "%.3f", spread))
                return@capture
            }
            val tiltDeg = base.angleTo(tilt)
            val drop = if (baselineHz > 0 && hz != null) (baselineHz - hz).coerceAtLeast(0.0) else 0.0
            val grams = current.mass(tiltDeg, drop)

            b.mass.text = Fmt.pl(grams, 1)
            b.status.text = getString(R.string.sen_weigh_done, Fmt.pl(grams / 0.2, 2))
            if (grams > 0.05) store.addMeasurement(grams)
        }
    }

    // ── stan ────────────────────────────────────────────────────────────────

    private fun paintState() {
        b.stateZero.text = if (baseline == null) getString(R.string.sen_not_measured)
        else getString(R.string.sen_zero_ready,
            if (baselineHz > 0) String.format(Locale.US, "%.2f Hz", baselineHz)
            else getString(R.string.sen_res_missing))

        val current = model
        b.stateModel.text = when {
            samples.isEmpty() -> getString(R.string.sen_no_references)
            current == null -> getString(R.string.sen_no_references)
            current.isTrained -> getString(R.string.sen_model_trained, samples.size)
            else -> getString(R.string.sen_model_single, samples.size, current.singleChannelName ?: "—")
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        listOf(b.stepZero, b.stepReference, b.stepWeigh, b.reset).forEach { it.isEnabled = enabled }
    }

    private fun buzz(ms: Long) {
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        runCatching { vib?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
