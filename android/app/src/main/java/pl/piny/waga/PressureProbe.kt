package pl.piny.waga

/**
 * Rozstrzyga, czy ekran naprawdę mierzy siłę.
 *
 * Dowodem jest ZAKRES odczytów, nie pojedyncza wartość: ekran bez czujnika zwraca
 * stałe 1,0 (albo 0,5), które potrafi drgać na ostatnim bicie i wygląda wtedy jak
 * pomiar. Zbiór poziomów jest ograniczony, żeby długa sesja go nie rozdymała.
 */
class PressureProbe {
    companion object {
        const val MIN_SAMPLES = 6
        const val MIN_SPAN = 0.06
        const val MIN_LEVELS = 4
        private const val LEVEL_CAP = 64
    }

    private val levels = HashSet<Int>()

    var samples = 0
        private set
    var min = Double.MAX_VALUE
        private set
    var max = -Double.MAX_VALUE
        private set

    val span: Double get() = if (samples == 0) 0.0 else max - min
    val levelCount: Int get() = levels.size

    val hasForceSensor: Boolean
        get() = (samples >= MIN_SAMPLES && span >= MIN_SPAN) || levels.size >= MIN_LEVELS

    fun note(pressure: Double) {
        if (pressure <= 0.0 || !pressure.isFinite()) return
        samples++
        if (pressure < min) min = pressure
        if (pressure > max) max = pressure
        val level = Math.round(pressure * 100.0).toInt()
        if (level != 0 && level != 50 && level != 100 && levels.size < LEVEL_CAP) levels.add(level)
    }

    fun reset() {
        levels.clear(); samples = 0; min = Double.MAX_VALUE; max = -Double.MAX_VALUE
    }
}
