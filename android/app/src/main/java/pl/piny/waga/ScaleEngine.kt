package pl.piny.waga

import kotlin.math.abs
import kotlin.math.max

enum class ScaleState { IDLE, UNCALIBRATED, MEASURING, SETTLING, HOLD, OVERLOAD }

data class Reading(
    val state: ScaleState,
    /** Wskazanie netto zaokrąglone do działki; null gdy brak kalibracji. */
    val grams: Double?,
    val gross: Double?,
    val raw: Double,
    val peak: Double,
    val contacts: Int,
    val tare: Double,
    val beyondRange: Boolean,
    /** true, gdy odczyt opiera się na krzywej wstępnej, a nie na wzorcach użytkownika. */
    val approximate: Boolean,
    /** Odchylenie standardowe bieżącego kontaktu — miara spokoju odczytu. */
    val stability: Double,
    val samples: Int,
    /** Masa dopisana do dziennika w tym kroku — niepusta tylko w chwili zatrzymania odczytu. */
    val captured: Double?
)

/**
 * Maszyna stanu wagi: przyjmuje surowy sygnał z ekranu, oddaje gotowe wskazanie.
 *
 * Tara jest liczona w gramach (netto = brutto − tara), a nie przez przesuwanie punktu
 * zerowego sygnału — przesuwanie kasowałoby punkty kalibracji leżące poniżej.
 */
class ScaleEngine(var calibration: Calibration) {
    companion object {
        const val DIVISION_G = 0.1
        const val STABLE_SD = 0.012
        const val HOLD_DELAY_MS = 900L
        const val CAPTURE_GAP_MS = 1500L
        const val MIN_HOLD_G = 0.2
        const val LOAD_THRESHOLD = 0.02

        /** Zmiana obciążenia, która zwalnia zatrzymany odczyt. */
        const val RELEASE_DIVISIONS = 3
        const val RELEASE_FRACTION = 0.03
    }

    private val filter = SignalFilter()

    var tare: Double = 0.0
        private set

    private var held: Double? = null
    private var shown: Double? = null
    private var stableSince = 0L
    private var lastCapture = 0L
    private var wasActive = false

    val signal: Double get() = filter.value

    fun update(rawSum: Double, contacts: Int, saturated: Boolean, now: Long): Reading {
        val raw = filter.push(rawSum)
        val active = contacts > 0

        if (active != wasActive) {
            filter.resetHistory()
            if (!active) { tare = 0.0; held = null }
            wasActive = active
        }

        val over = active && saturated
        val gross = calibration.massFor(raw)
        val net = gross?.minus(tare)
        val loaded = active && raw > calibration.zero + LOAD_THRESHOLD
        val steady = loaded && !over && filter.standardDeviation < STABLE_SD

        // zatrzymany odczyt puszczamy, gdy obciążenie realnie się zmieniło — inaczej
        // waga trzymałaby starą wartość mimo dokładania na ekran
        held?.let { h ->
            if (net != null && abs(net - h) > max(RELEASE_DIVISIONS * DIVISION_G, abs(h) * RELEASE_FRACTION)) {
                held = null
                stableSince = 0L
            }
        }

        var captured: Double? = null
        if (steady) {
            if (stableSince == 0L) stableSince = now
            if (now - stableSince > HOLD_DELAY_MS && held == null && net != null && net >= MIN_HOLD_G) {
                held = net
                if (now - lastCapture > CAPTURE_GAP_MS) {
                    captured = net
                    lastCapture = now
                }
            }
        } else {
            stableSince = 0L
            if (!active) held = null
        }

        shown = quantize(held ?: net)

        val state = when {
            over -> ScaleState.OVERLOAD
            !calibration.isCalibrated -> ScaleState.UNCALIBRATED
            held != null -> ScaleState.HOLD
            steady -> ScaleState.SETTLING
            active -> ScaleState.MEASURING
            else -> ScaleState.IDLE
        }

        return Reading(
            state = state,
            grams = shown,
            gross = gross,
            raw = raw,
            peak = filter.peak,
            contacts = contacts,
            tare = tare,
            beyondRange = calibration.beyondRange(raw),
            approximate = calibration.auto,
            stability = filter.standardDeviation,
            samples = filter.historyCount,
            captured = captured
        )
    }

    /** Zaokrąglenie do działki z histerezą — bez niej ostatnia cyfra migocze. */
    private fun quantize(target: Double?): Double? {
        if (target == null) return null
        val q = Math.round(target / DIVISION_G) * DIVISION_G
        val prev = shown
        return if (prev == null || abs(q - prev) >= DIVISION_G * 0.75 || held != null) q else prev
    }

    /** Tara z bieżącego obciążenia. Zwraca masę wziętą jako tara. */
    fun tareNow(): Double {
        val gross = calibration.massFor(filter.value) ?: 0.0
        tare = if (gross > 0) gross else 0.0
        held = null
        shown = null
        filter.resetHistory()
        filter.resetPeak()
        return tare
    }

    fun clearTare() {
        tare = 0.0
        held = null
        shown = null
        filter.resetHistory()
        filter.resetPeak()
    }

    /** Dla wagi bez kalibracji: bieżący sygnał staje się punktem zerowym. */
    fun zeroSignal() {
        calibration = calibration.withZero(filter.value)
        held = null
        shown = null
        filter.resetHistory()
        filter.resetPeak()
    }

    fun reset() {
        filter.reset(); tare = 0.0; held = null; shown = null
        stableSince = 0L; lastCapture = 0L; wasActive = false
    }
}
