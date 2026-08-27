package pl.piny.waga

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/** Uśredniony kierunek wektora grawitacji — trzy składowe, długość 1. */
data class Direction(val x: Double, val y: Double, val z: Double) {
    val length: Double get() = sqrt(x * x + y * y + z * z)

    fun normalized(): Direction {
        val l = length
        return if (l <= 0) this else Direction(x / l, y / l, z / l)
    }

    /** Kąt między kierunkami w stopniach. */
    fun angleTo(other: Direction): Double {
        val a = normalized()
        val b = other.normalized()
        val dot = (a.x * b.x + a.y * b.y + a.z * b.z).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(dot))
    }
}

/**
 * Wyznacza przechył telefonu z uśrednionego wektora grawitacji.
 *
 * Telefon leżący na miękkim podłożu ugina je nierównomiernie, gdy położy się na
 * nim przedmiot poza środkiem. Powstaje przechył o kąt proporcjonalny do momentu
 * siły, czyli do masy — przy stałym miejscu położenia. W odróżnieniu od rezonansu
 * sygnał jest STATYCZNY: trwa tak długo, jak długo przedmiot leży, więc odczyt
 * nie znika po odjęciu ręki.
 */
object TiltAnalyzer {

    /** Uśrednia próbki i odrzuca zapis, w którym telefon był poruszany. */
    fun meanDirection(samples: List<Direction>, maxSpreadDeg: Double = 0.6): Direction? {
        if (samples.size < 32) return null
        val mean = Direction(
            samples.sumOf { it.x } / samples.size,
            samples.sumOf { it.y } / samples.size,
            samples.sumOf { it.z } / samples.size
        ).normalized()
        // rozrzut wokół średniej — duży oznacza, że ktoś trzymał albo stukał telefon
        val spread = samples.maxOf { it.angleTo(mean) }
        return if (spread > maxSpreadDeg) null else mean
    }

    /** Miara niepokoju zapisu, w stopniach — do pokazania użytkownikowi. */
    fun spreadDeg(samples: List<Direction>): Double {
        val mean = Direction(
            samples.sumOf { it.x } / samples.size,
            samples.sumOf { it.y } / samples.size,
            samples.sumOf { it.z } / samples.size
        ).normalized()
        return if (samples.isEmpty()) 0.0 else samples.maxOf { it.angleTo(mean) }
    }
}

/**
 * Przelicza przechył na masę. Zależność jest liniowa dla małych kątów
 * (moment siły = m·g·d), więc wystarczy jeden wzorzec położony w tym samym miejscu.
 */
class TiltScale(
    /** Kierunek grawitacji przy pustym telefonie. */
    val baseline: Direction,
    /** Ile gramów przypada na stopień przechyłu. */
    val gramsPerDegree: Double
) {
    companion object {
        /** Poniżej tego kąta pomiar tonie w szumie czujnika. */
        const val MIN_USEFUL_DEG = 0.01

        fun calibrate(baseline: Direction, withReference: Direction, referenceGrams: Double): TiltScale? {
            if (referenceGrams <= 0) return null
            val angle = baseline.angleTo(withReference)
            if (angle < MIN_USEFUL_DEG) return null
            return TiltScale(baseline, referenceGrams / angle)
        }
    }

    fun mass(current: Direction): Double = baseline.angleTo(current) * gramsPerDegree

    /** Najmniejsza masa odróżnialna przy danym szumie kątowym. */
    fun resolution(angleNoiseDeg: Double): Double = abs(angleNoiseDeg * gramsPerDegree)
}
