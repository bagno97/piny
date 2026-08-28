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
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import pl.piny.waga.databinding.ActivityTiltScaleBinding
import java.util.Locale

/**
 * Waga przechyłowa — waży przedmiot LEŻĄCY na telefonie.
 *
 * Jeden kanał, jedna kalibracja, jedna liczba. Poprzednie podejścia dokładały
 * kanał za kanałem (rezonans, pole styku, czujnik zbliżeniowy, żyroskop) i żaden
 * poza tym jednym nie odezwał się na prawdziwym urządzeniu.
 *
 * Zasada: telefon na miękkim podłożu ugina je nierównomiernie, gdy położy się na
 * nim przedmiot poza środkiem. Kierunek grawitacji obraca się o kąt
 * proporcjonalny do masy. Kąt czytamy jako uśredniony wektor z akcelerometru —
 * przy kilkuset próbkach szum maleje z pierwiastkiem ich liczby.
 *
 * Kąt to jeszcze nie gramy: przelicznik zależy od sztywności podłoża i miejsca
 * położenia, więc raz trzeba pokazać wadze przedmiot o znanej masie. Potem jest
 * pamiętany.
 */
class TiltScaleActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        /** Okno uśredniania — ok. 3 s przy 50 Hz. */
        const val WINDOW = 160
        const val MIN_SAMPLES = 48
        const val REFRESH_MS = 200L

        /** Poniżej tego rozrzutu telefon uznajemy za nieruchomy. */
        const val STILL_SPREAD_DEG = 0.15

        /** Tyle spokoju wystarczy, żeby waga wyzerowała się sama. */
        const val AUTO_ZERO_MS = 2000L

        /** Tyle musi trwać nowy poziom, zanim uznamy go za ustalony. */
        const val SETTLE_MS = 1500L

        /** Zmiana przechyłu, od której uznajemy, że coś położono lub zdjęto. */
        const val CHANGE_DEG = 0.008

        /** Po tylu sekundach oczekiwania na wzorzec waga mówi, że nic nie zobaczyła. */
        const val ARM_TIMEOUT_MS = 6000L
    }

    private lateinit var b: ActivityTiltScaleBinding
    private lateinit var store: Store
    private lateinit var sensors: SensorManager
    private var accelerometer: Sensor? = null

    private val ui = Handler(Looper.getMainLooper())
    private val window_ = ArrayDeque<Direction>()

    private var baseline: Direction? = null
    private var gramsPerDegree = 0.0

    private var tiltDeg = 0.0
    private var stillSince = 0L
    private var settleSince = 0L
    private var settleValue = 0.0
    private var latched: Double? = null

    /** Masa wzorca, na którego położenie waga czeka. */
    private var awaiting: Double? = null
    private var awaitingSince = 0L

    /** Widoczne w module, żeby testy mogły sprawdzić stan bez zaglądania w widoki. */
    internal val currentTiltDeg: Double get() = tiltDeg
    internal val currentGrams: Double get() = if (gramsPerDegree > 0) tiltDeg * gramsPerDegree else 0.0
    internal val isCalibrated: Boolean get() = gramsPerDegree > 0
    internal val hasZero: Boolean get() = baseline != null

    private val ticker = object : Runnable {
        override fun run() {
            update()
            ui.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTiltScaleBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        store = Store(this)
        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        baseline = store.baselineTilt
        gramsPerDegree = store.gramsPerDegree

        b.back.setOnClickListener { finish() }
        b.zero.setOnClickListener { zeroNow() }
        b.calibrate.setOnClickListener { armCalibration() }
        b.forget.setOnClickListener { forgetCalibration() }

        if (accelerometer == null) {
            b.status.setText(R.string.tilt_no_accelerometer)
            listOf(b.zero, b.calibrate, b.forget).forEach { it.isEnabled = false }
        }
        paintState()
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        ui.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(ticker)
        sensors.unregisterListener(this)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        window_.addLast(
            Direction(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble())
        )
        while (window_.size > WINDOW) window_.removeFirst()
    }

    // ── serce wagi ──────────────────────────────────────────────────────────

    private fun update() {
        if (window_.size < MIN_SAMPLES) return
        val samples = window_.toList()
        val spread = TiltAnalyzer.spreadDeg(samples)
        val still = spread < STILL_SPREAD_DEG
        val now = SystemClock.uptimeMillis()

        b.stability.text = getString(
            if (still) R.string.tilt_still else R.string.tilt_moving,
            String.format(Locale.US, "%.3f", spread)
        )
        b.stability.setTextColor(
            androidx.core.content.ContextCompat.getColor(this, if (still) R.color.ok else R.color.warn)
        )

        if (!still) {
            stillSince = 0L
            settleSince = 0L
            return
        }
        if (stillSince == 0L) stillSince = now

        val mean = TiltAnalyzer.meanDirection(samples, maxSpreadDeg = STILL_SPREAD_DEG * 2) ?: return
        val base = baseline
        if (base == null) {
            if (now - stillSince > AUTO_ZERO_MS) {
                baseline = mean
                store.baselineTilt = mean
                buzz(20)
                b.status.setText(R.string.tilt_auto_zero)
                paintState()
            }
            return
        }

        tiltDeg = base.angleTo(mean)
        paintReading()

        // wzorzec, który niczego nie ugiął, też wymaga odpowiedzi — bez tego
        // waga milczałaby w nieskończoność
        awaiting?.let { grams ->
            if (now - awaitingSince > ARM_TIMEOUT_MS && tiltDeg < CHANGE_DEG) {
                captureCalibration(grams)
                return
            }
        }

        // ustalenie się nowego poziomu
        if (kotlin.math.abs(tiltDeg - settleValue) > CHANGE_DEG) {
            settleValue = tiltDeg
            settleSince = now
            return
        }
        if (settleSince == 0L || now - settleSince <= SETTLE_MS || latched == tiltDeg) return

        latched = tiltDeg
        awaiting?.let { grams -> captureCalibration(grams); return }
        if (tiltDeg > CHANGE_DEG) {
            buzz(30)
            b.status.text = if (isCalibrated)
                getString(R.string.tilt_settled, Fmt.pl(currentGrams, 2))
            else
                getString(R.string.tilt_settled_raw, String.format(Locale.US, "%.4f", tiltDeg))
        }
    }

    private fun paintReading() {
        if (isCalibrated) {
            b.value.text = Fmt.pl(currentGrams, 2)
            b.unit.setText(R.string.unit_g)
            b.detail.text = getString(R.string.tilt_detail_calibrated,
                String.format(Locale.US, "%.4f", tiltDeg), Fmt.pl(currentGrams / 0.2, 2))
        } else {
            b.value.text = String.format(Locale.US, "%.4f", tiltDeg)
            b.unit.setText(R.string.tilt_unit_deg)
            b.detail.setText(R.string.tilt_detail_raw)
        }
    }

    // ── obsługa ─────────────────────────────────────────────────────────────

    private fun zeroNow() {
        val mean = TiltAnalyzer.meanDirection(window_.toList(), maxSpreadDeg = STILL_SPREAD_DEG * 2)
        if (mean == null) { toast(getString(R.string.tilt_unstable)); return }
        baseline = mean
        store.baselineTilt = mean
        tiltDeg = 0.0
        settleSince = 0L
        latched = null
        paintReading()
        paintState()
        toast(getString(R.string.tilt_zeroed))
    }

    /** Uzbraja oczekiwanie na wzorzec — naciskanie w trakcie pomiaru psuło odczyt. */
    private fun armCalibration() {
        if (baseline == null) { toast(getString(R.string.tilt_need_zero)); return }
        val grams = Fmt.parse(b.referenceMass.text.toString())
        if (grams == null || grams <= 0) { toast(getString(R.string.tilt_need_mass)); return }
        awaiting = grams
        awaitingSince = SystemClock.uptimeMillis()
        settleSince = 0L
        latched = null
        b.status.text = getString(R.string.tilt_awaiting, Fmt.pl(grams, 2))
        paintState()
    }

    private fun captureCalibration(grams: Double) {
        awaiting = null
        if (tiltDeg < CHANGE_DEG) {
            b.status.text = getString(R.string.tilt_too_small, String.format(Locale.US, "%.4f", tiltDeg))
            paintState()
            return
        }
        gramsPerDegree = grams / tiltDeg
        store.gramsPerDegree = gramsPerDegree
        buzz(40)
        b.status.text = getString(R.string.tilt_calibrated,
            Fmt.pl(grams, 2), String.format(Locale.US, "%.4f", tiltDeg), Fmt.pl(gramsPerDegree, 1))
        paintReading()
        paintState()
    }

    private fun forgetCalibration() {
        gramsPerDegree = 0.0
        awaiting = null
        store.gramsPerDegree = 0.0
        paintReading()
        paintState()
        toast(getString(R.string.tilt_forgotten))
    }

    private fun paintState() {
        b.calibrationState.text = when {
            awaiting != null -> getString(R.string.tilt_state_awaiting)
            isCalibrated -> getString(R.string.tilt_state_ready, Fmt.pl(gramsPerDegree, 1))
            else -> getString(R.string.tilt_state_none)
        }
        b.forget.visibility = if (isCalibrated) View.VISIBLE else View.GONE
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
