package pl.piny.waga

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Wyznacza częstotliwość drgań własnych z zapisu akcelerometru.
 *
 * Telefon oparty na miękkim podłożu zachowuje się jak układ masa–sprężyna.
 * Po krótkim pobudzeniu (impuls wibracji albo stuknięcie) drga z częstotliwością
 * f = (1/2π)·√(k/M). Położenie przedmiotu zwiększa M, więc f spada — i to właśnie
 * ten spadek niesie informację o masie. W przeciwieństwie do ekranu metoda działa
 * dla przedmiotów biernych: kamień nie musi niczego dotykać ani przewodzić.
 */
object ResonanceAnalyzer {

    /** Poza tym pasmem szukanie nie ma sensu: niżej dryf, wyżej szum czujnika. */
    /**
     * Dolna granica pasma. Nie schodzimy niżej celowo: zanik drgań ma potężny
     * ogon przy najniższych częstotliwościach i przy szerszym paśmie to on
     * wygrywałby z rzeczywistym szczytem rezonansu.
     */
    const val MIN_HZ = 4.0
    const val MAX_HZ = 150.0

    /**
     * Ile razy szczyt musi przewyższać tło. Obniżenie tego progu sprawia, że
     * czysty szum zaczyna uchodzić za rezonans — fałszywy odczyt jest gorszy
     * niż brak odczytu, więc zamiast luzować próg wzmacniamy pobudzenie.
     */
    const val PEAK_OVER_BACKGROUND = 3.0

    /**
     * Szczyt widma w paśmie [MIN_HZ]–[MAX_HZ] z interpolacją paraboliczną,
     * która daje rozdzielczość lepszą niż odstęp prążków. Zwraca null,
     * gdy w zapisie nie ma wyraźnego szczytu.
     */
    fun dominantFrequency(samples: DoubleArray, sampleRateHz: Double): Double? {
        val n = Fft.floorPowerOfTwo(samples.size)
        if (n < 256 || sampleRateHz <= 0) return null

        val windowed = Fft.hann(samples.copyOf(n))
        val spectrum = Fft.magnitudes(windowed)
        val binHz = sampleRateHz / n

        val from = (MIN_HZ / binHz).toInt().coerceAtLeast(1)
        val to = (MAX_HZ / binHz).toInt().coerceAtMost(spectrum.size - 2)
        if (to <= from) return null

        var peak = from
        for (i in from..to) if (spectrum[i] > spectrum[peak]) peak = i

        // szczyt musi wystawać ponad tło, inaczej to szum
        val background = (from..to).filter { abs(it - peak) > 2 }
            .map { spectrum[it] }.average()
        if (background <= 0 || spectrum[peak] < background * PEAK_OVER_BACKGROUND) return null

        return (peak + parabolicOffset(spectrum, peak)) * binHz
    }

    /** Wierzchołek paraboli przez trzy prążki wokół szczytu. */
    private fun parabolicOffset(spectrum: DoubleArray, peak: Int): Double {
        val a = spectrum[peak - 1]
        val b = spectrum[peak]
        val c = spectrum[peak + 1]
        val denominator = a - 2 * b + c
        if (abs(denominator) < 1e-12) return 0.0
        return (0.5 * (a - c) / denominator).coerceIn(-0.5, 0.5)
    }
}

/**
 * Przelicza częstotliwość drgań na masę.
 *
 * Z f = (1/2π)·√(k/M) wynika M = C/f², gdzie C = k/4π². Stałej C nie znamy,
 * więc wyznaczamy ją z jednego wzorca o znanej masie — dokładnie tak, jak
 * kalibruje się każdą wagę sprężynową.
 */
class ResonanceScale(
    /** Częstotliwość samego telefonu, bez obciążenia. */
    val emptyHz: Double,
    /** C = k/4π², w gramach·Hz². */
    val constant: Double
) {
    companion object {
        /**
         * Kalibracja bez wzorca: odważnikiem jest sam telefon.
         *
         * Z f₀ = (1/2π)·√(k/M₀) przy znanej masie telefonu wynika C = M₀·f₀²,
         * a stąd każda dołożona masa liczy się bezwzględnie. Dokładność ogranicza
         * to, że podłoże też wnosi masę zastępczą — dlatego wynik jest przybliżony,
         * a wzorzec pozostaje sposobem na jego uściślenie.
         */
        fun fromPhoneMass(emptyHz: Double, phoneGrams: Double): ResonanceScale? {
            if (emptyHz <= 0 || phoneGrams <= 0) return null
            return ResonanceScale(emptyHz, phoneGrams * emptyHz * emptyHz)
        }

        /**
         * Kalibracja wzorcem. Zwraca null, gdy pomiar przeczy fizyce —
         * dołożenie masy musi obniżyć częstotliwość.
         */
        fun calibrate(emptyHz: Double, referenceHz: Double, referenceGrams: Double): ResonanceScale? {
            if (emptyHz <= 0 || referenceHz <= 0 || referenceGrams <= 0) return null
            if (referenceHz >= emptyHz) return null
            val denominator = 1 / (referenceHz * referenceHz) - 1 / (emptyHz * emptyHz)
            if (denominator <= 0) return null
            return ResonanceScale(emptyHz, referenceGrams / denominator)
        }
    }

    /** Masa przedmiotu leżącego na telefonie, w gramach. */
    fun mass(hz: Double): Double {
        if (hz <= 0) return 0.0
        return (constant * (1 / (hz * hz) - 1 / (emptyHz * emptyHz))).coerceAtLeast(0.0)
    }

    /** Masa własna układu (telefon plus podłoże), w gramach. */
    val systemMass: Double get() = constant / (emptyHz * emptyHz)

    /**
     * Najmniejsza masa, jaką da się odróżnić przy danej niepewności odczytu
     * częstotliwości — pozwala uczciwie powiedzieć, czy metoda wystarcza.
     */
    fun resolution(frequencyUncertaintyHz: Double): Double =
        abs(mass(emptyHz - frequencyUncertaintyHz) - mass(emptyHz))
}
