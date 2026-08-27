package pl.piny.waga

import kotlin.math.sqrt

/**
 * Mediana z okna tłumi pojedyncze skoki, filtr wykładniczy wygładza resztę,
 * a odchylenie standardowe z bieżącego kontaktu mówi, czy odczyt się ustabilizował.
 */
class SignalFilter(
    private val medianWindow: Int = 7,
    private val alpha: Double = 0.28,
    private val historySize: Int = 45
) {
    private val window = ArrayDeque<Double>()
    private val history = ArrayDeque<Double>()

    var value: Double = 0.0
        private set
    var peak: Double = 0.0
        private set

    fun push(raw: Double): Double {
        window.addLast(raw)
        if (window.size > medianWindow) window.removeFirst()
        val median = window.sorted()[window.size / 2]
        value += (median - value) * alpha
        if (value > peak) peak = value
        history.addLast(value)
        if (history.size > historySize) history.removeFirst()
        return value
    }

    /** Duża wartość dopóki nie ma dość próbek — wtedy nic nie uchodzi za stabilne. */
    val standardDeviation: Double
        get() {
            if (history.size < 12) return Double.MAX_VALUE
            val mean = history.average()
            return sqrt(history.sumOf { (it - mean) * (it - mean) } / history.size)
        }

    /** Wołane przy zmianie kontaktu: stabilność liczymy tylko z bieżącego dotknięcia. */
    fun resetHistory() = history.clear()

    fun resetPeak() { peak = 0.0 }

    fun reset() {
        window.clear(); history.clear(); value = 0.0; peak = 0.0
    }
}
