package pl.piny.waga

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var store: Store
    private lateinit var engine: ScaleEngine

    private var displayUnit = DisplayUnit.GRAMS
    private var lastMass: Double? = null
    private var running = false

    private lateinit var panBackground: GradientDrawable
    private lateinit var unitButtonLabel: TextView

    private var colInk = 0; private var colMuted = 0; private var colLine = 0
    private var colAccent = 0; private var colOk = 0; private var colBad = 0
    private var colPanel = 0; private var colPanel2 = 0; private var colWarn = 0

    private val ui = Handler(Looper.getMainLooper())

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
        engine = ScaleEngine(store.loadCalibration())
        displayUnit = store.displayUnit

        resolveColors()
        stylePan()
        b.pan.channel = store.channel
        buildTools()
        paintCalibrationStamp()
        updateSensorBadge()

        b.badge.setOnClickListener { showDiagnostics() }

        if (store.calibratedAt == 0L && store.history.isEmpty()) {
            ui.postDelayed({ if (!isFinishing) showDiagnostics() }, 700)
        }
    }

    override fun onResume() {
        super.onResume()
        running = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    override fun onPause() {
        super.onPause()
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
    }

    private fun color(id: Int) = ContextCompat.getColor(this, id)
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
        val unitTool = tool("⇄", displayUnit.label) { cycleUnit() }
        unitButtonLabel = unitTool.getChildAt(1) as TextView
        b.tools.addView(unitTool)
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
        unitButtonLabel.text = displayUnit.label
    }

    private fun doTare() {
        buzz(12)
        if (!engine.calibration.isCalibrated) {
            engine.zeroSignal()
            store.saveCalibration(engine.calibration)
            paintCalibrationStamp()
            toast("Punkt zerowy sygnału ustawiony")
            return
        }
        val taken = engine.tareNow()
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
            now = System.currentTimeMillis()
        )
        render(reading)
        reading.captured?.let {
            store.addMeasurement(it)
            lastMass = it
            buzz(18)
        }
    }

    private fun render(r: Reading) {
        val u = displayUnit.unit
        b.unit.text = u.symbol

        val shown = r.grams
        if (shown == null) {
            b.value.text = if (engine.calibration.isCalibrated) "0,0" else getString(R.string.value_placeholder)
            b.value.setTextColor(colMuted)
            b.alt.text = ""
        } else {
            b.value.text = Fmt.pl(u.fromGrams(shown), displayUnit.decimals)
            if (shown > 0) lastMass = shown
            val dim = abs(shown) < ScaleEngine.DIVISION_G / 2
            b.value.setTextColor(
                when {
                    r.state == ScaleState.OVERLOAD -> colBad
                    r.state == ScaleState.HOLD -> colOk
                    dim -> colMuted
                    else -> colInk
                }
            )
            val alt = if (u == MassUnit.G) MassUnit.CT else MassUnit.G
            b.alt.text = if (shown > 0) {
                val base = "${Fmt.pl(alt.fromGrams(shown), 2)} ${alt.symbol}"
                if (r.tare > 0) "$base  ·  netto" else base
            } else ""
        }

        b.sub.text = when {
            r.state == ScaleState.OVERLOAD -> getString(R.string.state_overload)
            r.state == ScaleState.UNCALIBRATED -> getString(R.string.state_uncalibrated)
            r.beyondRange && r.contacts > 0 -> getString(R.string.state_beyond)
            r.state == ScaleState.HOLD -> getString(R.string.state_hold)
            r.state == ScaleState.SETTLING -> getString(R.string.state_settling)
            r.state == ScaleState.MEASURING -> getString(R.string.state_measuring)
            else -> getString(R.string.state_idle)
        }
        b.sub.setTextColor(
            when (r.state) {
                ScaleState.OVERLOAD -> colBad
                ScaleState.HOLD -> colOk
                else -> colMuted
            }
        )

        panBackground.setStroke(
            dp(1f),
            when {
                r.state == ScaleState.OVERLOAD -> colBad
                r.state == ScaleState.HOLD -> colOk
                r.contacts > 0 -> colAccent
                else -> colLine
            }
        )

        val norm = r.raw.coerceIn(0.0, 1.0).toFloat()
        b.pan.glow = norm
        b.pan.invalidate()
        b.meter.value = norm
        b.meter.peak = r.peak.coerceIn(0.0, 1.0).toFloat()
        b.rawOut.text = String.format(Locale.US, "%.3f", r.raw)
        b.contactsOut.text = if (r.contacts == 1) "1 punkt styku" else "${r.contacts} punktów styku"
        b.stateOut.text = when {
            r.state == ScaleState.OVERLOAD -> "przeciążenie"
            r.state == ScaleState.HOLD -> "hold"
            r.state == ScaleState.SETTLING -> "stabilny"
            r.contacts > 0 -> "ruch"
            else -> getString(R.string.division)
        }
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
        b.badge.text = text
        b.badge.setTextColor(tint)
        b.modeStamp.text = mode
    }

    private fun paintCalibrationStamp() {
        val cal = engine.calibration
        b.calStamp.text = when {
            engine.tare > 0 -> "netto · tara ${Fmt.pl(engine.tare, 1)} g"
            !cal.isCalibrated -> getString(R.string.cal_none)
            cal.referenceCount == 1 -> "1 wzorzec"
            else -> "${cal.referenceCount} wzorce"
        }
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

    private fun sheet(build: (LinearLayout, BottomSheetDialog) -> Unit): BottomSheetDialog {
        val dialog = BottomSheetDialog(this)
        val binding = SheetBaseBinding.inflate(layoutInflater)
        dialog.setContentView(binding.root)
        build(binding.content, dialog)
        dialog.show()
        return dialog
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

    private fun showCalibration() = sheet { content, dialog ->
        fun refresh() {
            content.removeAllViews()
            val cal = engine.calibration

            content.eyebrow("Procedura")
            content.title("Kalibracja wzorcami")
            content.body(
                "Ekran zwraca bezwymiarowy nacisk od 0 do 1, a jego odpowiedź nie jest liniowa. " +
                "Jeden wzorzec daje prostą, trzy i więcej — krzywą, która trzyma dokładność w całym zakresie."
            )

            content.sectionLabel("01 · Zero")
            content.body("Nic nie dotyka ekranu, telefon leży nieruchomo.")
            content.buttonRow(button("Ustaw zero") {
                if (b.pan.sample().contacts > 0) { toast("Zdejmij wszystko z ekranu"); return@button }
                engine.calibration = engine.calibration.withZero(engine.signal)
                store.saveCalibration(engine.calibration)
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
            content.buttonRow(button("Zmierz wzorzec", solid = true) { btn ->
                val mass = Fmt.parse(massField.text.toString())
                if (mass == null || mass <= 0) { toast("Podaj masę wzorca w gramach"); return@button }
                measureReference(mass, btn) { refresh() }
            })

            content.sectionLabel("03 · Zapisane wzorce")
            val refs = cal.curve.drop(1)
            if (refs.isEmpty()) {
                content.body("Brak wzorców — waga nie poda jeszcze masy.")
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
                            store.saveCalibration(engine.calibration)
                            paintCalibrationStamp(); refresh(); toast("Punkt usunięty")
                        }
                    })
                    content.addView(row)
                }
            }

            content.sectionLabel("Stan przyrządu")
            content.mono(
                "zero    ${String.format(Locale.US, "%.4f", cal.zero)}\n" +
                "wzorce  ${cal.referenceCount} (${if (cal.isCurved) "krzywa" else "prosta"})\n" +
                "zakres  0 – ${Fmt.pl(cal.maxMass, 1)} g\n" +
                "data    ${if (store.calibratedAt > 0)
                    SimpleDateFormat("dd.MM.yyyy", Locale("pl")).format(Date(store.calibratedAt)) else "—"}"
            )

            content.buttonRow(
                button("Skasuj wszystko") {
                    engine.calibration = engine.calibration.cleared()
                    store.saveCalibration(engine.calibration)
                    store.calibratedAt = 0L
                    paintCalibrationStamp(); refresh(); toast("Kalibracja skasowana")
                },
                button("Zamknij") { dialog.dismiss() }
            )
        }
        refresh()
    }

    /** Zbiera sygnał przez 3 s i zapisuje medianę jako wzorzec o podanej masie. */
    private fun measureReference(mass: Double, btn: TextView, done: () -> Unit) {
        val samples = mutableListOf<Double>()
        btn.isEnabled = false
        var step = 0
        val label = btn.text
        val task = object : Runnable {
            override fun run() {
                if (b.pan.sample().contacts > 0) samples.add(engine.signal)
                step++
                btn.text = "Dociskaj… ${3 - step / 10} s"
                if (step < 30) { ui.postDelayed(this, 100); return }

                btn.isEnabled = true
                btn.text = label
                if (samples.size < 8) { toast("Za mało kontaktu — trzymaj wzorzec przez całe 3 s"); return }
                val raw = samples.sorted()[samples.size / 2]
                if (raw - engine.calibration.zero <= 0.01) { toast("Czujnik nie zarejestrował nacisku"); return }
                engine.calibration = engine.calibration.withPoint(CalPoint(raw, mass))
                store.saveCalibration(engine.calibration)
                paintCalibrationStamp()
                toast("Zapisano wzorzec ${Fmt.pl(mass, 2)} g")
                done()
            }
        }
        ui.postDelayed(task, 100)
    }

    // ── przelicznik ─────────────────────────────────────────────────────────

    private fun showConverter() = sheet { content, dialog ->
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

    private fun showHistory() = sheet { content, dialog ->
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

    private fun showDiagnostics() = sheet { content, dialog ->
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
        val report = content.mono("Naciśnij „Test czujnika” i przez 6 s naciskaj raz mocniej, raz słabiej.")
        content.buttonRow(button("Test czujnika · 6 s", solid = true) { btn -> selfTest(btn, report) })
        content.buttonRow(button("Zamknij") { dialog.dismiss() })
    }

    private fun selfTest(btn: TextView, report: TextView) {
        b.pan.probe.reset()
        val pressures = mutableListOf<Double>()
        val areas = mutableListOf<Double>()
        btn.isEnabled = false
        var step = 0
        val label = btn.text
        val task = object : Runnable {
            override fun run() {
                val s = b.pan.sample()
                if (s.contacts > 0) { pressures.add(s.pressureSum); areas.add(s.areaSumMm2) }
                step++
                btn.text = "Naciskaj… ${6 - step / 10} s"
                report.text = "próbek: ${pressures.size}"
                if (step < 60) { ui.postDelayed(this, 100); return }

                btn.isEnabled = true
                btn.text = label
                if (pressures.size < 10) {
                    report.text = "Test przerwany — ekran nie był dotykany.\n" +
                        "Trzymaj palec na polu pomiarowym przez całe 6 s."
                    return
                }
                val pSpan = pressures.max() - pressures.min()
                val aSpan = if (areas.isEmpty()) 0.0 else areas.max() - areas.min()
                val forceOk = b.pan.probe.hasForceSensor
                val areaOk = areas.isNotEmpty() && areas.max() > 0 && aSpan / areas.max() > 0.15

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
                report.text = verdict + "\n\n" +
                    "nacisk  min ${String.format(Locale.US, "%.3f", pressures.min())}" +
                    "  max ${String.format(Locale.US, "%.3f", pressures.max())}" +
                    "  zakres ${String.format(Locale.US, "%.3f", pSpan)}\n" +
                    "poziomy ${b.pan.probe.levelCount}\n" +
                    "pole    min ${String.format(Locale.US, "%.1f", areas.minOrNull() ?: 0.0)} mm²" +
                    "  max ${String.format(Locale.US, "%.1f", areas.maxOrNull() ?: 0.0)} mm²\n" +
                    "próbek  ${pressures.size}"
            }
        }
        ui.postDelayed(task, 100)
    }
}
