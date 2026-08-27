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
import pl.piny.waga.databinding.ActivityResonanceBinding
import java.util.Locale
import kotlin.math.sqrt

/**
 * Waga rezonansowa: mierzy masę przedmiotu leżącego na telefonie.
 *
 * Telefon oparty na miękkim podłożu to układ masa–sprężyna. Po impulsie wibracji
 * drga z częstotliwością f = (1/2π)·√(k/M); położony przedmiot zwiększa M, więc f
 * spada. W odróżnieniu od ekranu metoda nie wymaga, żeby przedmiot cokolwiek
 * dotykał ani przewodził — kamień leżący biernie jest tu w pełni mierzalny.
 */
class ResonanceActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        const val RECORD_MS = 2600L
        const val MIN_SAMPLES = 512
        private const val NANOS = 1_000_000_000.0
    }

    private lateinit var b: ActivityResonanceBinding
    private lateinit var store: Store
    private lateinit var sensors: SensorManager
    private var accelerometer: Sensor? = null

    private val ui = Handler(Looper.getMainLooper())

    private val values = ArrayList<Double>(4096)
    private val stamps = ArrayList<Long>(4096)
    private var recording = false
    private var onMeasured: ((Double) -> Unit)? = null

    private var scale: ResonanceScale? = null
    private var emptyHz: Double = 0.0
    private var pendingReferenceGrams: Double = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityResonanceBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        store = Store(this)
        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        emptyHz = store.resonanceEmptyHz
        scale = store.loadResonanceScale()

        b.back.setOnClickListener { finish() }
        b.stepEmpty.setOnClickListener { measureEmpty() }
        b.stepReference.setOnClickListener { measureReference() }
        b.stepWeigh.setOnClickListener { weigh() }

        if (accelerometer == null) {
            b.status.text = getString(R.string.res_no_accelerometer)
            listOf(b.stepEmpty, b.stepReference, b.stepWeigh).forEach { it.isEnabled = false }
        }
        paintState()
    }

    override fun onPause() {
        super.onPause()
        stopRecording()
    }

    // ── pomiar ──────────────────────────────────────────────────────────────

    private fun record(prompt: String, onDone: (Double) -> Unit) {
        val sensor = accelerometer ?: return
        if (recording) return
        recording = true
        values.clear(); stamps.clear()
        onMeasured = onDone
        b.status.text = prompt
        setButtonsEnabled(false)

        sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST)
        buzz(35)                                   // impuls pobudzający układ
        ui.postDelayed({ finishRecording() }, RECORD_MS)
    }

    private fun finishRecording() {
        stopRecording()
        setButtonsEnabled(true)

        if (values.size < MIN_SAMPLES) {
            b.status.text = getString(R.string.res_too_few_samples, values.size)
            return
        }
        val span = (stamps.last() - stamps.first()) / NANOS
        val rate = if (span > 0) (values.size - 1) / span else 0.0
        val hz = ResonanceAnalyzer.dominantFrequency(values.toDoubleArray(), rate)

        if (hz == null) {
            b.status.text = getString(R.string.res_no_peak)
            return
        }
        b.frequency.text = String.format(Locale.US, "%.3f Hz", hz)
        onMeasured?.invoke(hz)
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false
        sensors.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!recording) return
        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
        values.add(sqrt((x * x + y * y + z * z).toDouble()))
        stamps.add(event.timestamp)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ── kroki ───────────────────────────────────────────────────────────────

    private fun measureEmpty() = record(getString(R.string.res_recording_empty)) { hz ->
        emptyHz = hz
        store.resonanceEmptyHz = hz
        scale = null
        store.clearResonanceScale()
        b.status.text = getString(R.string.res_empty_done)
        paintState()
    }

    private fun measureReference() {
        if (emptyHz <= 0) { toast(getString(R.string.res_need_empty)); return }
        val grams = Fmt.parse(b.referenceMass.text.toString())
        if (grams == null || grams <= 0) { toast(getString(R.string.res_need_mass)); return }
        pendingReferenceGrams = grams
        record(getString(R.string.res_recording_reference)) { hz ->
            val built = ResonanceScale.calibrate(emptyHz, hz, pendingReferenceGrams)
            if (built == null) {
                b.status.text = getString(R.string.res_bad_reference)
                return@record
            }
            scale = built
            store.saveResonanceScale(built)
            b.status.text = getString(
                R.string.res_reference_done,
                Fmt.pl(built.systemMass, 0),
                Fmt.pl(built.resolution(0.05), 2)
            )
            paintState()
        }
    }

    private fun weigh() {
        val current = scale
        if (current == null) { toast(getString(R.string.res_need_calibration)); return }
        record(getString(R.string.res_recording_object)) { hz ->
            val grams = current.mass(hz)
            b.mass.text = Fmt.pl(grams, 1)
            b.status.text = getString(R.string.res_weigh_done, Fmt.pl(grams / 0.2, 2))
            if (grams > 0.05) store.addMeasurement(grams)
        }
    }

    // ── pomocnicze ──────────────────────────────────────────────────────────

    private fun paintState() {
        b.stateEmpty.text = if (emptyHz > 0)
            String.format(Locale.US, "%.3f Hz", emptyHz) else getString(R.string.res_not_measured)
        val current = scale
        b.stateCalibration.text = if (current == null) getString(R.string.res_not_calibrated)
        else getString(
            R.string.res_calibrated,
            Fmt.pl(current.systemMass, 0),
            Fmt.pl(current.resolution(0.05), 2)
        )
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        listOf(b.stepEmpty, b.stepReference, b.stepWeigh).forEach { it.isEnabled = enabled }
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
