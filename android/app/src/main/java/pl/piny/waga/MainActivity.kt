package pl.piny.waga

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import pl.piny.waga.databinding.ActivityMainBinding
import pl.piny.waga.databinding.SheetBaseBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        /** Okno uśredniania przechyłu — ok. 3 s przy 50 Hz. */
        const val TILT_WINDOW = 160
    }

    private lateinit var b: ActivityMainBinding
    private lateinit var store: Store
    /** Widoczne w module, żeby testy mogły zajrzeć w stan pomiaru. */
    internal lateinit var engine: ScaleEngine

    private var displayUnit = DisplayUnit.GRAMS
    private var lastMass: Double? = null
    internal var lastReading: Reading? = null
    private var lastSelfTest: String? = null
    private var running = false

    private lateinit var panBackground: GradientDrawable

    private var colInk = 0; private var colMuted = 0; private var colLine = 0
    private var colAccent = 0; private var colOk = 0; private var colBad = 0
    private var colPanel = 0; private var colPanel2 = 0; private var colWarn = 0

    private val ui = Handler(Looper.getMainLooper())

    // ── podgląd przechyłu wprost na głównym ekranie ─────────────────────────
    // Bez tego jedyny kanał widzący LEŻĄCY przedmiot był schowany na osobnym
    // ekranie i można go było w ogóle nie znaleźć.
    private var sensors: SensorManager? = null
    private var accelerometer: Sensor? = null
    private val tiltWindow = ArrayDeque<Direction>()
    private var tiltBaseline: Direction? = null
    private var tiltDeg = 0.0

    /**
     * Ustawia tekst tylko przy realnej zmianie. Bez tego każde odświeżenie napisu
     * wymusza przeliczenie układu 60 razy na sekundę, choć treść stoi w miejscu.
     */
    private fun TextView.setTextIfChanged(value: CharSequence) {
        if (text?.toString() != value.toString()) text = value
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            tick()
            if (running) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // ── cykl życia ──────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        store = Store(this)
        sensors = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        engine = ScaleEngine(store.loadCalibration(Tool.FINGER))
        displayUnit = store.displayUnit

        resolveColors()
        stylePan()
        b.pan.channel = store.channel
        buildTools()
        paintCalibrationStamp()
        updateSensorBadge()

        b.badge.setOnClickListener { showDiagnostics() }
        // jednostkę przełącza się dotknięciem symbolu przy liczbie — to zwalnia
        // miejsce w pasku narzędzi na tryb rezonansowy
        b.unit.setOnClickListener { cycleUnit() }

        // przełączenie palec/rysik wczytuje profil tego narzędzia w locie
        b.pan.onToolChanged = { tool ->
            engine.calibration = store.loadCalibration(tool)
            paintCalibrationStamp()
        }
    }

    override fun onResume() {
        super.onResume()
        running = true
        Choreographer.getInstance().postFrameCallback(frame)
        accelerometer?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        super.onPause()
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
        sensors?.unregisterListener(this)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        tiltWindow.addLast(
            Direction(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble())
        )
        while (tiltWindow.size > TILT_WINDOW) tiltWindow.removeFirst()

        val mean = TiltAnalyzer.meanDirection(tiltWindow.toList(), maxSpreadDeg = 2.0) ?: return
        val base = tiltBaseline
        if (base == null) {
            tiltBaseline = mean          // pierwszy spokojny odczyt staje się odniesieniem
            return
        }
        tiltDeg = base.angleTo(mean)
    }

    /** Ustawia bieżące położenie jako zero przechyłu. */
    internal fun zeroTilt() {
        tiltBaseline = TiltAnalyzer.meanDirection(tiltWindow.toList(), maxSpreadDeg = 2.0)
        tiltDeg = 0.0
    }

    internal val liveTiltDeg: Double get() = tiltDeg

    private fun color(id: Int) = ContextCompat.getColor(this, id)

    private fun tiltLine() = getString(
        R.string.hint_tilt, String.format(Locale.US, "%.4f", tiltDeg)
    )
    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    private fun resolveColors() {
        colInk = color(R.color.ink); colMuted = color(R.color.muted); colLine = color(R.color.line)
        colAccent = color(R.color.accent); colOk = color(R.color.ok); colBad = color(R.color.bad)
        colPanel = color(R.color.panel); colPanel2 = color(R.color.panel2); colWarn = color(R.color.warn)
    }

    private fun stylePan() {
        panBackground = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(26f).toFloat()
            setColor(colPanel)
            setStroke(dp(1f), colLine)
        }
        b.panFrame.background = panBackground
        b.pan.lineColor = colLine
        b.pan.accentColor = colAccent
        b.meter.trackColor = colPanel2
        b.meter.startColor = colAccent
        b.meter.endColor = colOk
        b.meter.markColor = colInk
    }

    // ── narzędzia ───────────────────────────────────────────────────────────

    private fun buildTools() {
        b.tools.removeAllViews()
        b.tools.addView(tool("◎", getString(R.string.tool_tare), primary = true) { doTare() })
        b.tools.addView(tool("◆", getString(R.string.tool_calibration)) { showCalibration() })
        b.tools.addView(tool("◇", getString(R.string.tool_converter)) { showConverter() })
        b.tools.addView(tool("∿", getString(R.string.tilt_open)) {
            startActivity(android.content.Intent(this, TiltScaleActivity::class.java))
        })
        b.tools.addView(tool("≡", getString(R.string.tool_history)) { showHistory() })
    }

    private fun tool(icon: String, label: String, primary: Boolean = false, onClick: () -> Unit): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(
                this@MainActivity,
                if (primary) R.drawable.bg_tool_primary else R.drawable.bg_tool
            )
            setPadding(dp(3f), dp(10f), dp(3f), dp(8f))
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(3f); marginEnd = dp(3f) }
        }
        box.addView(TextView(this).apply {
            text = icon
            textSize = 16f
            setTextColor(if (primary) colAccent else colInk)
            gravity = Gravity.CENTER
        })
        box.addView(TextView(this).apply {
            text = label
            textSize = 9.5f
            isAllCaps = true
            letterSpacing = 0.07f
            setTextColor(if (primary) colAccent else colMuted)
            gravity = Gravity.CENTER
            setPadding(0, dp(4f), 0, 0)
        })
        return box
    }

    private fun cycleUnit() {
        displayUnit = displayUnit.next()
        store.displayUnit = displayUnit
        toast("Jednostka: ${displayUnit.label}")
    }

    private fun doTare() {
        buzz(12)
        zeroTilt()
        val taken = if (b.pan.sample().contacts > 0) engine.tareNow() else 0.0
        if (taken > 0) toast("Tara ${Fmt.pl(taken, 1)} g — waga liczy netto")
        else { engine.clearTare(); toast("Wyzerowano") }
        paintCalibrationStamp()
    }

    // ── pętla pomiarowa ─────────────────────────────────────────────────────

    private fun tick() {
        val reading = engine.update(
            rawSum = b.pan.signal(),
            contacts = b.pan.sample().contacts,
            saturated = b.pan.saturated(),
            // zegar monotoniczny: odstępy nie mogą zależeć od korekty czasu w systemie
            now = SystemClock.uptimeMillis()
        )
        lastReading = reading
        autoRange(reading)
        render(reading)
        reading.captured?.let {
            store.addMeasurement(it)
            lastMass = it
            buzz(18)
        }
    }

    /**
     * Dostraja kalibrację wstępną do zakresu, jaki ekran naprawdę oddaje.
     *
     * Sterowniki nie trzymają się skali 0–1: bywa, że najmocniejszy docisk to 0,0006.
     * Bez tego cała czułość ekranu mieściłaby się w promilu wyświetlanego zakresu
     * i mocne przyciśnięcie pokazywałoby ułamek grama.
     */
    private fun autoRange(r: Reading) {
        if (!engine.calibration.auto) return
        val fullScale = engine.calibration.curve.last().raw
        if (r.peak > fullScale * 1.05 && r.peak > 1e-6) {
            engine.calibration = Calibration.automatic(r.peak)
            store.saveObservedFullScale(b.pan.tool, r.peak)
        }
    }

    private fun render(r: Reading) {
        val u = displayUnit.unit
        b.unit.setTextIfChanged(u.symbol)

        val shown = r.grams
        if (shown == null) {
            b.value.setTextIfChanged("0,0")
            b.value.setTextColor(colMuted)
            b.alt.setTextIfChanged(tiltLine())
        } else {
            b.value.setTextIfChanged(Fmt.pl(u.fromGrams(shown), displayUnit.decimals))
            if (shown > 0) lastMass = shown
            val dim = abs(shown) < ScaleEngine.DIVISION_G / 2
            b.value.setTextColor(
                when {
                    r.state == ScaleState.OVERLOAD -> colBad
                    r.state == ScaleState.HOLD || r.state == ScaleState.RETAINED -> colOk
                    dim -> colMuted
                    else -> colInk
                }
            )
            val alt = if (u == MassUnit.G) MassUnit.CT else MassUnit.G
            b.alt.setTextIfChanged(if (shown > 0) {
                val base = "${Fmt.pl(alt.fromGrams(shown), 2)} ${alt.symbol}"
                if (r.tare > 0) "$base  ·  netto" else base
            } else tiltLine())
        }

        b.sub.setTextIfChanged(when {
            r.state == ScaleState.OVERLOAD -> getString(R.string.state_overload)
            r.beyondRange && r.contacts > 0 -> getString(R.string.state_beyond)
            r.state == ScaleState.HOLD -> getString(R.string.state_hold)
            r.state == ScaleState.RETAINED -> getString(R.string.state_retained)
            r.state == ScaleState.SETTLING -> getString(R.string.state_settling)
            r.state == ScaleState.MEASURING ->
                if (r.approximate) getString(R.string.state_estimate) else getString(R.string.state_measuring)
            else -> getString(R.string.state_idle)
        })
        b.sub.setTextColor(
            when (r.state) {
                ScaleState.OVERLOAD -> colBad
                ScaleState.HOLD, ScaleState.RETAINED -> colOk
                else -> colMuted
            }
        )

        panBackground.setStroke(
            dp(1f),
            when {
                r.state == ScaleState.OVERLOAD -> colBad
                r.state == ScaleState.HOLD || r.state == ScaleState.RETAINED -> colOk
                r.contacts > 0 -> colAccent
                else -> colLine
            }
        )

        val norm = r.raw.coerceIn(0.0, 1.0).toFloat()
        b.pan.glow = norm
        b.pan.invalidate()
        b.meter.value = norm
        b.meter.peak = r.peak.coerceIn(0.0, 1.0).toFloat()
        b.rawOut.setTextIfChanged(String.format(Locale.US, "%.3f", r.raw))
        b.contactsOut.setTextIfChanged(if (r.contacts == 1) "1 punkt styku" else "${r.contacts} punktów styku")
        b.stateOut.setTextIfChanged(when {
            r.state == ScaleState.OVERLOAD -> "przeciążenie"
            r.state == ScaleState.HOLD -> "hold"
            r.state == ScaleState.RETAINED -> "wynik zatrzymany"
            r.state == ScaleState.SETTLING -> "stabilny"
            r.contacts > 0 -> "ruch"
            else -> getString(R.string.division)
        })
        if (r.tare > 0) paintCalibrationStamp()
        updateSensorBadge()
    }

    private fun updateSensorBadge() {
        val force = b.pan.channel == Channel.PRESSURE ||
            (b.pan.channel == Channel.AUTO && b.pan.probe.hasForceSensor)
        val (text, mode, tint) = when {
            force -> Triple(getString(R.string.sensor_force), getString(R.string.mode_force), colOk)
            b.pan.probe.samples > 0 || b.pan.channel == Channel.AREA ->
                Triple(getString(R.string.sensor_approx), getString(R.string.mode_area), colWarn)
            else -> Triple(getString(R.string.sensor_detecting), getString(R.string.mode_unknown), colMuted)
        }
        b.badge.setTextIfChanged(text)
        b.badge.setTextColor(tint)
        b.modeStamp.setTextIfChanged(mode)
    }

    private fun paintCalibrationStamp() {
        val cal = engine.calibration
        b.calStamp.setTextIfChanged(when {
            engine.tare > 0 -> "netto · tara ${Fmt.pl(engine.tare, 1)} g"
            cal.auto -> "${b.pan.tool.label} · wstępna"
            cal.referenceCount == 1 -> "${b.pan.tool.label} · 1 wzorzec"
            else -> "${b.pan.tool.label} · ${cal.referenceCount} wzorce"
        })
    }

    // ── budulec arkuszy ─────────────────────────────────────────────────────

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

    private fun sheet(build: (LinearLayout, BottomSheetDialog) -> Unit) {
        val dialog = BottomSheetDialog(this)
        val binding = SheetBaseBinding.inflate(layoutInflater)
        dialog.setContentView(binding.root)
        build(binding.content, dialog)
        dialog.show()
    }

    private fun LinearLayout.eyebrow(text: String) = addView(TextView(context).apply {
        this.text = text; textSize = 10.5f; isAllCaps = true; letterSpacing = 0.18f
        setTextColor(colAccent); typeface = Typeface.MONOSPACE
    })

    private fun LinearLayout.title(text: String) = addView(TextView(context).apply {
        this.text = text; textSize = 19f; setTextColor(colInk)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4f), 0, dp(6f))
    })

    private fun LinearLayout.body(text: CharSequence) = addView(TextView(context).apply {
        this.text = text; textSize = 13.5f; setTextColor(colMuted); setLineSpacing(0f, 1.35f)
        setPadding(0, 0, 0, dp(12f))
    })

    private fun LinearLayout.sectionLabel(text: String) = addView(TextView(context).apply {
        this.text = text; textSize = 11f; isAllCaps = true; letterSpacing = 0.1f
        setTextColor(colMuted); setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(18f), 0, dp(8f))
    })

    private fun LinearLayout.mono(text: String): TextView {
        val tv = TextView(context).apply {
            this.text = text; textSize = 12f; setTextColor(colInk)
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.5f)
            background = ContextCompat.getDrawable(context, R.drawable.bg_chip)
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10f); bottomMargin = dp(4f) }
        }
        addView(tv)
        return tv
    }

    private fun button(text: String, solid: Boolean = false, onClick: (TextView) -> Unit): TextView {
        val tv = TextView(this).apply {
            this.text = text; textSize = 12.5f; isAllCaps = true; letterSpacing = 0.06f
            gravity = Gravity.CENTER
            setTextColor(if (solid) Color.WHITE else colInk)
            setTypeface(typeface, Typeface.BOLD)
            background = ContextCompat.getDrawable(
                this@MainActivity, if (solid) R.drawable.bg_button_solid else R.drawable.bg_button
            )
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            isClickable = true
        }
        tv.setOnClickListener { onClick(tv) }
        return tv
    }

    private fun LinearLayout.buttonRow(vararg buttons: TextView) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12f) }
        }
        buttons.forEach {
            it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(3f); marginEnd = dp(3f) }
            row.addView(it)
        }
        addView(row)
    }

    private fun numberInput(value: String): EditText = EditText(this).apply {
        setText(value)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        typeface = Typeface.MONOSPACE
        textSize = 15f
        setTextColor(colInk)
        background = ContextCompat.getDrawable(context, R.drawable.bg_input)
        setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
    }

    // ── kalibracja ──────────────────────────────────────────────────────────

    private fun showCalibration(): Unit = sheet { content, dialog ->
        fun refresh() {
            content.removeAllViews()
            val cal = engine.calibration

            content.eyebrow(if (cal.auto) "Nieobowiązkowe" else "Profil: ${b.pan.tool.label}")
            content.title(if (cal.auto) "Ustaw dokładność" else "Kalibracja wzorcami")
            content.body(
                if (cal.auto)
                    "Waga już działa — pokazuje szacunek oparty na typowym zakresie nacisku ekranu. " +
                    "Żeby zamienić szacunek na pomiar, zmierz jeden przedmiot o znanej masie. " +
                    "Trzy wzorce i więcej układają krzywą, która trzyma dokładność w całym zakresie.\n\n" +
                    "Profil zapisuje się osobno dla palca i dla rysika, bo to zupełnie inne naciski."
                else
                    "Ekran zwraca bezwymiarowy nacisk od 0 do 1, a jego odpowiedź nie jest liniowa. " +
                    "Jeden wzorzec daje prostą, trzy i więcej — krzywą, która trzyma dokładność w całym " +
                    "zakresie. Ten profil dotyczy narzędzia: ${b.pan.tool.label}."
            )

            content.sectionLabel("01 · Zero (nieobowiązkowe)")
            content.body("Nic nie dotyka ekranu, telefon leży nieruchomo. Przydatne tylko wtedy, " +
                "gdy waga pokazuje coś przy pustym ekranie.")
            content.buttonRow(button("Ustaw zero") {
                if (b.pan.sample().contacts > 0) { toast("Zdejmij wszystko z ekranu"); return@button }
                engine.calibration = engine.calibration.withZero(engine.signal)
                store.saveCalibration(b.pan.tool, engine.calibration)
                paintCalibrationStamp(); refresh(); toast("Zero ustawione")
            })

            content.sectionLabel("02 · Wzorzec o znanej masie")
            content.body(
                "Monety obiegowe: 5 zł = 6,54 g · 2 zł = 5,21 g · 1 zł = 5,00 g · " +
                "50 gr = 3,94 g · 20 gr = 3,22 g · 10 gr = 2,51 g."
            )
            val massField = numberInput("6,54")
            content.addView(massField, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            content.buttonRow(button("Zmierz wzorzec", solid = true) {
                val mass = Fmt.parse(massField.text.toString())
                if (mass == null || mass <= 0) { toast("Podaj masę wzorca w gramach"); return@button }
                dialog.dismiss()                       // oddajemy ekran pod docisk
                measureReference(mass) { showCalibration() }
            })

            content.sectionLabel("03 · Zapisane wzorce")
            val refs = cal.curve.drop(1)
            if (refs.isEmpty()) {
                content.body("Brak własnych wzorców — waga liczy z krzywej wstępnej.")
            } else {
                refs.forEach { point ->
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = ContextCompat.getDrawable(context, R.drawable.bg_chip)
                        setPadding(dp(13f), dp(9f), dp(9f), dp(9f))
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(6f) }
                    }
                    row.addView(TextView(this).apply {
                        text = "${Fmt.pl(point.grams, 2)} g"
                        textSize = 12.5f; typeface = Typeface.MONOSPACE; setTextColor(colInk)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    row.addView(TextView(this).apply {
                        text = "sygnał ${String.format(Locale.US, "%.3f", point.raw)}"
                        textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(colMuted)
                    })
                    row.addView(TextView(this).apply {
                        text = "  ✕  "
                        textSize = 15f; setTextColor(colMuted); isClickable = true
                        setOnClickListener {
                            engine.calibration = engine.calibration.withoutPoint(point)
                            store.saveCalibration(b.pan.tool, engine.calibration)
                            paintCalibrationStamp(); refresh(); toast("Punkt usunięty")
                        }
                    })
                    content.addView(row)
                }
            }

            content.sectionLabel("Stan przyrządu")
            content.mono(
                "zero    ${String.format(Locale.US, "%.4f", cal.zero)}\n" +
                "narzędzie ${b.pan.tool.label}\n" +
                "wzorce  ${if (cal.auto) "wstępna" else "${cal.referenceCount} (${if (cal.isCurved) "krzywa" else "prosta"})"}\n" +
                "zakres  0 – ${Fmt.pl(cal.maxMass, 1)} g\n" +
                "data    ${if (store.calibratedAt > 0)
                    SimpleDateFormat("dd.MM.yyyy", Locale("pl")).format(Date(store.calibratedAt)) else "—"}"
            )

            content.buttonRow(
                button("Wróć do wstępnej") {
                    store.resetObservedFullScale(b.pan.tool)
                    engine.calibration = engine.calibration.cleared()
                    store.saveCalibration(b.pan.tool, engine.calibration)
                    store.calibratedAt = 0L
                    paintCalibrationStamp(); refresh(); toast("Wrócono do kalibracji wstępnej")
                },
                button("Zamknij") { dialog.dismiss() }
            )
        }
        refresh()
    }

    /**
     * Zbiera próbki na głównym ekranie, z arkuszem zamkniętym.
     *
     * Arkusz zasłania pole pomiarowe i przechwytuje dotknięcia, więc pomiar
     * uruchamiany „zza" niego nie miał prawa zebrać ani jednej próbki. Każdy
     * pomiar wymagający nacisku musi więc najpierw oddać ekran użytkownikowi.
     */
    private fun captureOnPan(
        seconds: Int,
        prompt: String,
        onDone: (samples: List<Sample>, signals: List<Double>) -> Unit
    ) {
        val samples = mutableListOf<Sample>()
        val signals = mutableListOf<Double>()
        val steps = seconds * 10
        var step = 0
        b.captureBanner.visibility = View.VISIBLE
        b.captureBanner.text = "$prompt · $seconds s"

        val task = object : Runnable {
            override fun run() {
                val sample = b.pan.sample()
                if (sample.contacts > 0) {
                    samples.add(sample)
                    signals.add(engine.signal)
                }
                step++
                val left = seconds - step / 10
                b.captureBanner.text =
                    if (samples.isEmpty()) "Dotknij pola pomiarowego · $left s"
                    else "$prompt · $left s"
                if (step < steps) { ui.postDelayed(this, 100); return }
                b.captureBanner.visibility = View.GONE
                buzz(18)
                onDone(samples, signals)
            }
        }
        buzz(12)
        ui.postDelayed(task, 100)
    }

    /** Mierzy wzorzec o znanej masie i dopisuje go do krzywej. */
    private fun measureReference(mass: Double, done: () -> Unit) {
        captureOnPan(3, "Dociskaj wzorzec") { _, signals ->
            if (signals.size < 8) {
                toast("Za mało kontaktu — trzymaj wzorzec przez całe 3 s")
            } else {
                val raw = signals.sorted()[signals.size / 2]
                if (raw - engine.calibration.zero <= 1e-6) {
                    toast("Czujnik nie zarejestrował nacisku")
                } else {
                    engine.calibration = engine.calibration.withPoint(CalPoint(raw, mass))
                    store.saveCalibration(b.pan.tool, engine.calibration)
                    paintCalibrationStamp()
                    toast("Zapisano wzorzec ${Fmt.pl(mass, 2)} g")
                }
            }
            done()
        }
    }

    // ── przelicznik ─────────────────────────────────────────────────────────

    private fun showConverter(): Unit = sheet { content, dialog ->
        content.eyebrow("Przelicznik")
        content.title("Karaty i pozostałe jednostki")
        content.body(
            "Wpisz wartość w dowolnym polu — reszta przeliczy się od razu. " +
            "Karat metryczny to dokładnie 0,2 g; taki karat opisuje masę kamienia."
        )

        val fields = LinkedHashMap<MassUnit, EditText>()
        var guard = false
        val goldNote = TextView(this).apply {
            textSize = 13f; setTextColor(colMuted); setLineSpacing(0f, 1.35f)
            setPadding(0, dp(10f), 0, 0)
        }

        fun spread(grams: Double?, source: MassUnit?) {
            guard = true
            fields.forEach { (unit, field) ->
                if (unit != source) field.setText(if (grams == null) "" else Fmt.pl(unit.fromGrams(grams), unit.decimals))
            }
            guard = false
            goldNote.text = if (grams != null && grams > 0)
                "Przy tej masie: próba 585 (14 kt) → ${Fmt.pl(grams * 0.585, 2)} g czystego złota, " +
                "próba 750 (18 kt) → ${Fmt.pl(grams * 0.750, 2)} g."
            else "Zmierz coś albo wpisz masę, a policzę zawartość czystego kruszcu."
        }

        MassUnit.entries.forEach { unit ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(context, R.drawable.bg_chip)
                setPadding(dp(13f), dp(7f), dp(8f), dp(7f))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(7f) }
            }
            val names = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            names.addView(TextView(this).apply {
                text = unit.label; textSize = 12.5f; setTextColor(colInk)
                setTypeface(typeface, Typeface.BOLD)
            })
            names.addView(TextView(this).apply {
                text = unit.symbol.uppercase(); textSize = 10.5f; setTextColor(colMuted)
                typeface = Typeface.MONOSPACE; letterSpacing = 0.06f
            })
            row.addView(names)

            val field = numberInput("").apply {
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(dp(112f), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (guard) return
                    val text = s?.toString().orEmpty()
                    if (text.isBlank()) { spread(null, unit); return }
                    Fmt.parse(text)?.let { spread(unit.toGrams(it), unit) }
                }
            })
            fields[unit] = field
            row.addView(field)
            content.addView(row)
        }

        content.buttonRow(
            button("Wstaw ostatni pomiar") {
                val m = lastMass ?: store.history.firstOrNull()?.grams
                if (m == null || m <= 0) toast("Brak pomiaru do wstawienia")
                else { spread(m, null); toast("Wstawiono ${Fmt.pl(m, 1)} g") }
            },
            button("Wyczyść") { spread(null, null) }
        )

        content.sectionLabel("Karat złota to co innego")
        content.body(
            "Karat przy złocie oznacza próbę, czyli zawartość czystego kruszcu, a nie masę. " +
            "8 kt = próba 333 · 9 kt = 375 · 14 kt = 585 · 18 kt = 750 · 22 kt = 916 · 24 kt = 999."
        )
        content.addView(goldNote)
        content.buttonRow(button("Zamknij") { dialog.dismiss() })

        spread(lastMass ?: store.history.firstOrNull()?.grams, null)
    }

    // ── dziennik ────────────────────────────────────────────────────────────

    private fun showHistory(): Unit = sheet { content, dialog ->
        fun refresh() {
            content.removeAllViews()
            content.eyebrow("Dziennik")
            content.title("Zapisane pomiary")
            content.body("Waga zapisuje odczyt automatycznie, gdy ustabilizuje się na dłużej niż sekundę.")

            val entries = store.history
            if (entries.isEmpty()) {
                content.body("Brak pomiarów. Ustabilizuj odczyt, a zapisze się sam.")
            } else {
                val format = SimpleDateFormat("dd.MM, HH:mm", Locale("pl"))
                entries.forEach { m ->
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(2f), dp(12f), dp(2f), dp(12f))
                    }
                    row.addView(TextView(this).apply {
                        text = "${Fmt.pl(displayUnit.unit.fromGrams(m.grams), displayUnit.decimals)} ${displayUnit.unit.symbol}"
                        textSize = 17f; typeface = Typeface.MONOSPACE; setTextColor(colInk)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    row.addView(TextView(this).apply {
                        text = format.format(Date(m.at))
                        textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(colMuted)
                    })
                    content.addView(row)
                    content.addView(View(this).apply {
                        setBackgroundColor(colLine)
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                    })
                }
            }
            content.buttonRow(
                button("Wyczyść") { store.clearHistory(); refresh(); toast("Dziennik wyczyszczony") },
                button("Zamknij") { dialog.dismiss() }
            )
        }
        refresh()
    }

    // ── diagnostyka ─────────────────────────────────────────────────────────

    private fun showDiagnostics(): Unit = sheet { content, dialog ->
        val force = b.pan.channel == Channel.PRESSURE ||
            (b.pan.channel == Channel.AUTO && b.pan.probe.hasForceSensor)

        content.eyebrow("Diagnostyka")
        content.title(
            when {
                force -> "Czujnik nacisku aktywny"
                b.pan.probe.samples > 0 -> "Tryb przybliżony"
                else -> "Sprawdź, co potrafi Twój ekran"
            }
        )
        content.body(
            when {
                force ->
                    "Ekran zwraca rzeczywistą siłę nacisku. Sygnał przechodzi przez medianę i filtr " +
                    "wykładniczy, kalibracja układa z punktów krzywą, a po 0,9 s bez ruchu odczyt " +
                    "zostaje zatrzymany i zapisany.\n\nEkran mierzy nacisk, nie masę: przedmiot " +
                    "położony luźno prawie nic nie naciska — dociśnij go palcem albo oprzyj o ekran " +
                    "trzymany pionowo."
                b.pan.probe.samples > 0 ->
                    "Ten ekran nie różnicuje siły, więc nacisk jest szacowany z powierzchni styku: " +
                    "im mocniej dociskasz palec, tym bardziej się spłaszcza. Działa dla palca i tylko " +
                    "orientacyjnie — twardych przedmiotów tą metodą nie zważysz."
                else ->
                    "Dotknij pola pomiarowego albo uruchom test poniżej. Realny czujnik siły mają " +
                    "iPhone 6s – XS, Apple Watch, Huawei Mate S i część Androidów; wiele ekranów " +
                    "zwraca stałą wartość i wtedy pomiar ilościowy jest niemożliwy."
            }
        )

        val range = b.pan.declaredPressureRange()
        content.sectionLabel("Co deklaruje sterownik ekranu")
        content.mono(
            if (range == null) "brak osi nacisku w opisie urządzenia"
            else "min  ${String.format(Locale.US, "%.4f", range.min)}\n" +
                 "max  ${String.format(Locale.US, "%.4f", range.max)}\n" +
                 "rozdz. ${String.format(Locale.US, "%.5f", range.resolution)}\n" +
                 "szum ${String.format(Locale.US, "%.5f", range.fuzz)}"
        )

        content.sectionLabel("Pomiar na żywo")
        content.mono(
            lastSelfTest ?: "Naciśnij „Test czujnika”. Arkusz się zamknie i odda Ci ekran — " +
                "przez 6 s naciskaj środek pola pomiarowego, raz mocniej, raz słabiej. " +
                "Wynik wróci tutaj."
        )
        content.buttonRow(button("Test czujnika · 6 s", solid = true) {
            dialog.dismiss()                      // arkusz zasłania pole pomiarowe
            selfTest()
        })
        content.buttonRow(
            button("Ucz zakresu od nowa") {
                store.resetObservedFullScale(b.pan.tool)
                if (engine.calibration.auto) engine.calibration = Calibration.automatic()
                toast("Zakres wyzerowany — naciśnij mocno, żeby go pokazać wadze")
            },
            button("Zamknij") { dialog.dismiss() }
        )
    }

    private fun selfTest() {
        b.pan.probe.reset()
        captureOnPan(6, "Naciskaj raz mocniej, raz słabiej") { samples, _ ->
            if (samples.size < 10) {
                lastSelfTest = "Test przerwany — ekran nie był dotykany.\n" +
                    "Trzymaj palec na polu pomiarowym przez całe 6 s."
                showDiagnostics()
                return@captureOnPan
            }

            val pressures = samples.map { it.pressureSum }
            val areas = samples.map { it.areaSumMm2 }
            val pMax = pressures.max()
            val pSpan = pMax - pressures.min()
            val pRelative = if (pMax > 0) pSpan / pMax else 0.0
            val aMax = areas.maxOrNull() ?: 0.0
            val aSpan = aMax - (areas.minOrNull() ?: 0.0)

            val forceOk = b.pan.probe.hasForceSensor
            val areaOk = aMax > 0 && aSpan / aMax > 0.15

            b.pan.channel = when {
                forceOk -> Channel.PRESSURE
                areaOk -> Channel.AREA
                else -> Channel.AUTO
            }
            store.channel = b.pan.channel
            updateSensorBadge()

            val verdict = when {
                forceOk -> "Czujnik siły działa. Po kalibracji odczyt jest ilościowy."
                areaOk -> "Brak czujnika siły, jest pole styku. Włączam tryb przybliżony."
                else -> "Ekran nie różnicuje nacisku — pomiar ilościowy jest niemożliwy."
            }

            lastSelfTest = verdict + "\n\n" +
                "nacisk min  ${String.format(Locale.US, "%.6f", pressures.min())}\n" +
                "nacisk max  ${String.format(Locale.US, "%.6f", pMax)}\n" +
                "rozpiętość  ${String.format(Locale.US, "%.6f", pSpan)}" +
                "  (${String.format(Locale.US, "%.0f", pRelative * 100)}%)\n" +
                "poziomy     ${b.pan.probe.levelCount}\n" +
                "pole styku  ${String.format(Locale.US, "%.1f", areas.minOrNull() ?: 0.0)}" +
                " – ${String.format(Locale.US, "%.1f", aMax)} mm²\n" +
                "pełna skala ${String.format(Locale.US, "%.6f", engine.calibration.curve.last().raw)}\n" +
                "próbek      ${samples.size}"
            showDiagnostics()
        }
    }
}
