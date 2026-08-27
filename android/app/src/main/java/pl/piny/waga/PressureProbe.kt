package pl.piny.waga

/**
 * Rozstrzyga, czy ekran naprawdę mierzy siłę.
 *
 * Dowodem jest ZAKRES odczytów, nie pojedyncza wartość: ekran bez czujnika zwraca
 * stałe 1,0 (albo 0,5), które potrafi drgać na ostatnim bicie i wygląda wtedy jak
 * pomiar.
 *
 * Zakres liczymy WZGLĘDNIE, bo sterowniki nie trzymają się skali 0–1: bywa, że
 * mocny docisk to 0,0006, a lekki 0,0002. Bezwzględny próg uznałby taki ekran za
 * pozbawiony czujnika, choć rozróżnia nacisk trzykrotnie.
 */
class PressureProbe {
    companion object {
        const val MIN_SAMPLES = 6

        /** Próg bezwzględny — dla ekranów trzymających się skali 0–1. */
        const val MIN_SPAN = 0.06

        /** Próg względny — dla ekranów o dowolnej skali własnej. */
        const val MIN_RELATIVE_SPAN = 0.25

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

    /** Rozpiętość odniesiona do największego odczytu — niezależna od skali sterownika. */
    val relativeSpan: Double get() = if (samples == 0 || max <= 0.0) 0.0 else span / max

    val levelCount: Int get() = levels.size

    val hasForceSensor: Boolean
        get() = samples >= MIN_SAMPLES &&
            (span >= MIN_SPAN || relativeSpan >= MIN_RELATIVE_SPAN || levels.size >= MIN_LEVELS)

    fun note(pressure: Double) {
        if (pressure <= 0.0 || !pressure.isFinite()) return
        samples++
        if (pressure < min) min = pressure
        if (pressure > max) max = pressure
        // poziomy też liczymy względem maksimum, inaczej mała skala daje same zera
        val level = if (max > 0.0) Math.round(pressure / max * 100.0).toInt() else 0
        if (level != 0 && level != 50 && level != 100 && levels.size < LEVEL_CAP) levels.add(level)
    }

    fun reset() {
        levels.clear(); samples = 0; min = Double.MAX_VALUE; max = -Double.MAX_VALUE
    }
}
