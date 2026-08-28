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

        /** Poniżej tego rozrzutu telefon uznajemy za nieruchomy. */
        const val STILL_SPREAD_DEG = 0.15
        /** Tyle musi leżeć spokojnie, zanim waga wyzeruje się sama. */
        const val AUTO_ZERO_MS = 2000L
        /** Tyle musi trwać nowy poziom, zanim zostanie zatrzymany jako wynik. */
        const val SETTLE_MS = 1500L
        /** Zmiana przechyłu, od której uznajemy, że coś położono lub zdjęto. */
        const val CHANGE_DEG = 0.015
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

    /** Widoczne w module: testy muszą wiedzieć, kiedy waga sama zaczęła pomiar. */
    internal val busy: Boolean get() = phase != Phase.IDLE
    private val directions = ArrayList<Direction>(1024)
    private val magnitudes = ArrayList<Double>(4096)
    private val stamps = ArrayList<Long>(4096)
    private var proximityNear = false
    private var onCaptured: ((Direction?, Double?, Double) -> Unit)? = null

    /** Okno ostatnich sekund — z niego liczony jest odczyt na żywo. */
    private val liveWindow = ArrayDeque<Direction>()
    private var liveTiltDeg = 0.0
    private var liveMass = 0.0
    private var stillSince = 0L
    private var settleSince = 0L
    private var settleValue = 0.0
    private var latched: Double? = null

    /** Masa wzorca, na którego położenie waga czeka, żeby zapisać go sama. */
    private var awaitingReference: Double? = null

    /** Skala rezonansowa oparta na masie telefonu — działa bez żadnego wzorca. */
    private var resonanceScale: ResonanceScale? = null
    /** Ile gramów przypada na stopień przechyłu; wyliczane samo z rezonansu. */
    private var gramsPerDegree = 0.0
    private var autoStage = AutoStage.NONE

    private enum class AutoStage { NONE, BASELINE_DONE, OBJECT_DONE }

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
        gramsPerDegree = store.gramsPerDegree
        if (baselineHz > 0) {
            resonanceScale = ResonanceScale.fromPhoneMass(baselineHz, store.phoneGrams)
            if (resonanceScale != null) autoStage = AutoStage.BASELINE_DONE
        }

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

    /**
     * Odczyt na żywo, wołany co [LIVE_REFRESH_MS].
     *
     * Waga obsługuje się sama: gdy telefon poleży spokojnie, zeruje się bez pytania,
     * a gdy wskazanie ustali się na nowym poziomie, zatrzymuje je i sygnalizuje
     * wibracją. Dzięki temu nie trzeba dotykać telefonu w trakcie pomiaru — a każde
     * dotknięcie i tak psuło wynik.
     */
    private fun updateLive() {
        if (phase != Phase.IDLE) return
        if (liveWindow.size < LIVE_MIN_SAMPLES) return

        val samplesNow = liveWindow.toList()
        val spread = TiltAnalyzer.spreadDeg(samplesNow)
        val now = android.os.SystemClock.uptimeMillis()
        val still = spread < STILL_SPREAD_DEG

        b.live.text = getString(
            if (still) R.string.sen_live_still else R.string.sen_live_moving,
            String.format(Locale.US, "%.3f", spread)
        )

        if (!still) {
            stillSince = 0L
            settleSince = 0L
            return
        }
        if (stillSince == 0L) stillSince = now

        val mean = TiltAnalyzer.meanDirection(samplesNow, maxSpreadDeg = STILL_SPREAD_DEG * 2) ?: return

        // samoczynne zero: telefon odstawiony i nieruchomy
        val base = baseline
        if (base == null) {
            if (now - stillSince > AUTO_ZERO_MS) {
                baseline = mean
                store.baselineTilt = mean
                buzz(20)
                b.status.setText(R.string.sen_auto_zero)
                paintState()
                if (autoStage == AutoStage.NONE) ui.postDelayed({ measureBaselineResonance() }, 700)
            }
            return
        }

        liveTiltDeg = base.angleTo(mean)
        liveMass = when {
            model != null -> model!!.mass(liveTiltDeg, 0.0)
            gramsPerDegree > 0 -> liveTiltDeg * gramsPerDegree
            else -> 0.0
        }
        b.channelTilt.text = getString(R.string.sen_tilt_value, String.format(Locale.US, "%.4f", liveTiltDeg))
        if (model != null || gramsPerDegree > 0) b.mass.text = Fmt.pl(liveMass, 1)

        // zatrzymanie wyniku po ustaleniu się nowego poziomu
        if (kotlin.math.abs(liveTiltDeg - settleValue) > CHANGE_DEG) {
            settleValue = liveTiltDeg
            settleSince = now
            return
        }
        if (settleSince != 0L && now - settleSince > SETTLE_MS && latched != liveTiltDeg) {
            latched = liveTiltDeg
            if (awaitingReference != null) {
                captureReference(liveTiltDeg)
            } else if (liveTiltDeg > CHANGE_DEG && gramsPerDegree <= 0 &&
                resonanceScale != null && autoStage == AutoStage.BASELINE_DONE) {
                measureObjectResonance(liveTiltDeg)
            } else if (liveTiltDeg > CHANGE_DEG) {
                buzz(25)
                b.status.text = if (model != null) getString(
                    R.string.sen_settled,
                    String.format(Locale.US, "%.4f", liveTiltDeg), Fmt.pl(liveMass, 1)
                ) else getString(
                    R.string.sen_settled_uncalibrated, String.format(Locale.US, "%.4f", liveTiltDeg)
                )
            }
        }
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
            excite()                                    // seria impulsów pobudzających układ
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

    /**
     * Wzorzec zapisuje się sam.
     *
     * Wcześniej trzeba było nacisnąć przycisk i w cztery sekundy cofnąć ręce —
     * a samo naciśnięcie szarpało telefonem i psuło pomiar. Teraz przycisk tylko
     * uzbraja oczekiwanie: waga zapisze wzorzec, gdy sama zobaczy spokój i
     * ustalony przechył.
     */
    private fun addReference() {
        if (baseline == null) { toast(getString(R.string.sen_need_zero)); return }
        val grams = Fmt.parse(b.referenceMass.text.toString())
        if (grams == null || grams <= 0) { toast(getString(R.string.sen_need_mass)); return }

        awaitingReference = grams
        settleSince = 0L
        latched = null
        b.status.text = getString(R.string.sen_awaiting_reference, Fmt.pl(grams, 1))
    }

    /** Wołane, gdy przechył ustali się na nowym poziomie przy uzbrojonym oczekiwaniu. */
    private fun captureReference(tiltDeg: Double) {
        val grams = awaitingReference ?: return
        if (tiltDeg < SensorScaleModel.MIN_TILT_DEG) {
            b.status.text = getString(R.string.sen_reference_too_small,
                String.format(Locale.US, "%.4f", tiltDeg))
            return
        }
        awaitingReference = null
        samples = samples + SensorSample(tiltDeg, 0.0, grams)
        store.sensorSamples = samples
        model = SensorScaleModel.build(samples)
        buzz(40)
        b.status.text = getString(R.string.sen_reference_added,
            Fmt.pl(grams, 1), String.format(Locale.US, "%.4f", tiltDeg), "0.000")
        paintState()
    }

    /** Zapisuje bieżący odczyt na żywo do dziennika — nic nie trzeba mierzyć osobno. */
    private fun weigh() {
        if (baseline == null) { toast(getString(R.string.sen_need_zero)); return }
        val current = model
        if (current == null) { toast(getString(R.string.sen_need_reference)); return }
        if (liveMass <= 0.05) { toast(getString(R.string.sen_nothing_on_phone)); return }
        store.addMeasurement(liveMass)
        toast(getString(R.string.sen_saved, Fmt.pl(liveMass, 1)))
    }

    /**
     * Mierzy drgania własne pustego telefonu i buduje z nich skalę bezwzględną.
     * Odważnikiem jest masa samego telefonu, więc nie potrzeba żadnego wzorca.
     */
    private fun measureBaselineResonance() {
        if (phase != Phase.IDLE || baseline == null) return
        capture(getString(R.string.sen_auto_resonance)) { _, hz, _ ->
            if (hz == null) {
                b.status.setText(R.string.sen_resonance_failed)
                return@capture
            }
            baselineHz = hz
            store.baselineHz = hz
            resonanceScale = ResonanceScale.fromPhoneMass(hz, store.phoneGrams)
            autoStage = AutoStage.BASELINE_DONE
            b.status.text = getString(R.string.sen_ready,
                String.format(Locale.US, "%.2f", hz), Fmt.pl(store.phoneGrams, 0))
            paintState()
        }
    }

    /**
     * Mierzy rezonans z przedmiotem, przelicza go na masę i tym samym nadaje
     * skalę szybkiemu kanałowi przechyłowemu. Od tej chwili odczyt jest w gramach
     * natychmiast, bez czekania na kolejne pobudzenie.
     */
    private fun measureObjectResonance(tiltDeg: Double) {
        val scale = resonanceScale ?: return
        if (phase != Phase.IDLE) return
        capture(getString(R.string.sen_auto_weighing)) { _, hz, _ ->
            if (hz == null) {
                b.status.setText(R.string.sen_resonance_failed)
                return@capture
            }
            val grams = scale.mass(hz)
            autoStage = AutoStage.OBJECT_DONE
            if (grams <= 0.05 || tiltDeg <= 0) {
                b.status.setText(R.string.sen_object_too_light)
                return@capture
            }
            gramsPerDegree = grams / tiltDeg
            store.gramsPerDegree = gramsPerDegree
            liveMass = grams
            b.mass.text = Fmt.pl(grams, 1)
            buzz(40)
            b.status.text = getString(R.string.sen_auto_done, Fmt.pl(grams, 1))
            paintState()
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

    /**
     * Pobudza układ serią krótkich impulsów. Pojedyncze szarpnięcie bywa za słabe
     * na miękkim podłożu, a seria działa jak uderzenie szerokopasmowe — daje
     * wyraźniejszy szczyt bez luzowania progów w analizie.
     */
    private fun excite() {
        val vib = vibrator() ?: return
        runCatching {
            vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 25, 35, 25, 35, 25), -1))
        }
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private fun buzz(ms: Long) {
        runCatching {
            vibrator()?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
