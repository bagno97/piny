package pl.piny.waga

/** Punkt kalibracji: surowy sygnał ekranu i odpowiadająca mu znana masa. */
data class CalPoint(val raw: Double, val grams: Double)

/**
 * Zamienia bezwymiarowy nacisk ekranu na gramy.
 *
 * Odpowiedź ekranu nie jest liniowa, więc punkty wzorcowe tworzą krzywą odcinkami
 * liniową. Jeden wzorzec daje zwykłą prostą przez zero.
 */
class Calibration(
    val zero: Double = 0.0,
    val points: List<CalPoint> = emptyList(),
    /** true dla krzywej wstępnej — odczyt jest wtedy szacunkiem, nie pomiarem. */
    val auto: Boolean = false
) {
    companion object {
        /**
         * Dwa wzorce leżące bliżej niż 1 % zakresu krzywej to w praktyce ten sam punkt.
         * Próg jest WZGLĘDNY: ekran oddający najwyżej 0,0006 miałby przy progu
         * bezwzględnym wszystkie wzorce „identyczne" i krzywa nigdy by nie powstała.
         */
        const val MIN_GAP_FRACTION = 0.01
        const val MIN_GAP_FLOOR = 1e-7

        /** Powyżej najcięższego wzorca ufamy nachyleniu najwyżej do tej krotności jego masy. */
        const val EXTRAPOLATION_LIMIT = 1.5

        /**
         * Masa odpowiadająca pełnemu wychyleniu czujnika przy kalibracji wstępnej.
         * Mocny docisk palcem to około 3–5 N, rysik S Pen ma zakres rzędu 500 gf —
         * stąd ta wartość. Jest przybliżeniem: pozwala wadze pokazać sensowną liczbę
         * od pierwszego dotknięcia, zamiast blokować się do czasu kalibracji wzorcem.
         */
        const val DEFAULT_FULL_SCALE_G = 500.0

        /** Sygnał, przy którym zaczynamy — dopóki ekran nie pokaże, ile naprawdę daje. */
        const val INITIAL_FULL_SCALE_SIGNAL = 1.0

        /**
         * Kalibracja wstępna: prosta od zera do [signalFullScale].
         *
         * [signalFullScale] to największy sygnał, jaki ekran realnie oddał. Sterowniki
         * nie trzymają się skali 0–1 — jeśli mocny docisk daje 0,0006, to właśnie ta
         * wartość jest pełnym wychyleniem i cała czułość ekranu musi zmieścić się
         * między zerem a nią. Inaczej używalibyśmy promila dostępnego zakresu.
         */
        fun automatic(
            signalFullScale: Double = INITIAL_FULL_SCALE_SIGNAL,
            fullScale: Double = DEFAULT_FULL_SCALE_G
        ) = Calibration(
            0.0,
            listOf(CalPoint(signalFullScale.coerceAtLeast(1e-6), fullScale)),
            auto = true
        )
    }

    /** Krzywa: zawsze zaczyna się od punktu zerowego, dalej rosnące wzorce. */
    val curve: List<CalPoint> = build()

    val isCalibrated: Boolean get() = curve.size >= 2
    val referenceCount: Int get() = (curve.size - 1).coerceAtLeast(0)
    val isCurved: Boolean get() = curve.size > 2
    val maxMass: Double get() = curve.last().grams

    private fun build(): List<CalPoint> {
        val extra = points
            .filter { it.raw.isFinite() && it.grams.isFinite() && it.raw > zero && it.grams > 0 }
            .sortedBy { it.raw }
        val minGap = minGapFor(extra)
        val out = mutableListOf(CalPoint(zero, 0.0))
        for (p in extra) {
            val gap = p.raw - out.last().raw
            if (gap >= minGap) out.add(p)
            else if (out.size > 1) out[out.size - 1] = p   // za blisko poprzedniego → zastąp
        }
        return out
    }

    private fun minGapFor(sorted: List<CalPoint>): Double {
        val span = (sorted.lastOrNull()?.raw ?: zero) - zero
        return maxOf(MIN_GAP_FLOOR, span * MIN_GAP_FRACTION)
    }

    /** Odstęp uznawany za „ten sam punkt" dla bieżącej krzywej. */
    val minGap: Double get() = minGapFor(points.sortedBy { it.raw })

    /**
     * Nachylenia w węzłach wg Fritscha–Carlsona. Metoda zachowuje monotoniczność,
     * więc masa nigdy nie maleje przy rosnącym nacisku — dla wagi to warunek konieczny.
     */
    private val slopes: DoubleArray = computeSlopes()

    private fun computeSlopes(): DoubleArray {
        val n = curve.size
        if (n < 2) return DoubleArray(n)
        val h = DoubleArray(n - 1) { curve[it + 1].raw - curve[it].raw }
        val d = DoubleArray(n - 1) { (curve[it + 1].grams - curve[it].grams) / h[it] }
        val m = DoubleArray(n)
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) {
            if (d[i - 1] * d[i] <= 0.0) {
                m[i] = 0.0
            } else {
                val w1 = 2 * h[i] + h[i - 1]
                val w2 = h[i] + 2 * h[i - 1]
                m[i] = (w1 + w2) / (w1 / d[i - 1] + w2 / d[i])
            }
        }
        return m
    }

    /**
     * Masa brutto dla surowego sygnału, albo null gdy waga nie jest skalibrowana.
     *
     * Wewnątrz zakresu wzorców interpoluje sześciennie (Hermite), bo odpowiedź ekranu
     * jest krzywą — łamana przez te same punkty myli się kilkukrotnie bardziej na
     * szerokich odcinkach. Powyżej ostatniego wzorca przechodzi w prostą o nachyleniu
     * końcowym, z twardym ograniczeniem: bez niego trzy palce na ekranie „ważyłyby"
     * setki gramów.
     */
    fun massFor(raw: Double): Double? {
        if (!isCalibrated) return null
        if (raw <= curve[0].raw) return 0.0

        for (i in 1 until curve.size) {
            val a = curve[i - 1]
            val b = curve[i]
            if (raw <= b.raw) {
                val h = b.raw - a.raw
                val t = (raw - a.raw) / h
                val t2 = t * t
                val t3 = t2 * t
                val h00 = 2 * t3 - 3 * t2 + 1
                val h10 = t3 - 2 * t2 + t
                val h01 = -2 * t3 + 3 * t2
                val h11 = t3 - t2
                val v = h00 * a.grams + h10 * h * slopes[i - 1] + h01 * b.grams + h11 * h * slopes[i]
                return v.coerceAtLeast(0.0)
            }
        }

        val last = curve.last()
        val v = last.grams + (raw - last.raw) * slopes.last()
        return v.coerceIn(0.0, last.grams * EXTRAPOLATION_LIMIT)
    }

    /** Czy sygnał wyszedł poza zakres pokryty wzorcami — wtedy odczyt jest ekstrapolacją. */
    fun beyondRange(raw: Double): Boolean = isCalibrated && raw > curve.last().raw

    /** Dodanie własnego wzorca zawsze porzuca krzywą wstępną. */
    fun withPoint(point: CalPoint): Calibration =
        Calibration(
            zero,
            if (auto) listOf(point)
            else points.filter { kotlin.math.abs(it.raw - point.raw) >= minGap } + point,
            auto = false
        )

    fun withoutPoint(point: CalPoint): Calibration {
        val rest = points.filter { it != point }
        return if (rest.isEmpty()) automatic() else Calibration(zero, rest, auto = false)
    }

    fun withZero(newZero: Double): Calibration = Calibration(newZero, points, auto)

    /** Kasowanie wzorców wraca do krzywej wstępnej, a nie do wagi, która nic nie pokazuje. */
    fun cleared(): Calibration = automatic()
}
