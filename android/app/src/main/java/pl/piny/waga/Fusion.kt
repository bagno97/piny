package pl.piny.waga

/**
 * Łączy odczyty z kilku czujników w jedną masę.
 *
 * Zamiast nieprzejrzystej sieci neuronowej używamy regresji liniowej z
 * regularyzacją grzbietową: przy kilku wzorcach to jedyna metoda, która się nie
 * przeucza, a jej współczynniki da się obejrzeć i sprawdzić. Model uczy się z
 * par (cechy czujników, znana masa) — dokładnie tak, jak opisuje to podejście
 * „zbieraj dane, ucz na znanych ciężarach".
 */
class LinearModel(val weights: DoubleArray, val intercept: Double) {

    fun predict(features: DoubleArray): Double {
        var sum = intercept
        for (i in weights.indices) sum += weights[i] * features.getOrElse(i) { 0.0 }
        return sum
    }

    companion object {
        /** Siła regularyzacji — trzyma współczynniki w ryzach przy garstce próbek. */
        const val RIDGE = 1e-6

        /**
         * Dopasowanie metodą najmniejszych kwadratów. Zwraca null, gdy danych jest
         * mniej niż cech plus jeden — wtedy model opisywałby szum, nie zjawisko.
         */
        fun fit(rows: List<DoubleArray>, targets: List<Double>): LinearModel? {
            if (rows.isEmpty() || rows.size != targets.size) return null
            val featureCount = rows[0].size
            if (rows.any { it.size != featureCount }) return null
            if (rows.size < featureCount + 1) return null

            val n = featureCount + 1                       // + wyraz wolny
            val design = Array(rows.size) { r ->
                DoubleArray(n) { c -> if (c == featureCount) 1.0 else rows[r][c] }
            }

            // równania normalne: (XᵀX + λI)·w = Xᵀy
            val ata = Array(n) { DoubleArray(n) }
            val atb = DoubleArray(n)
            for (r in design.indices) {
                for (i in 0 until n) {
                    atb[i] += design[r][i] * targets[r]
                    for (j in 0 until n) ata[i][j] += design[r][i] * design[r][j]
                }
            }
            for (i in 0 until n) ata[i][i] += RIDGE

            val solution = solve(ata, atb) ?: return null
            return LinearModel(solution.copyOf(featureCount), solution[featureCount])
        }

        /** Eliminacja Gaussa z wyborem elementu głównego. */
        private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
            val n = b.size
            val m = Array(n) { r -> DoubleArray(n + 1) { c -> if (c == n) b[r] else a[r][c] } }

            for (col in 0 until n) {
                var pivot = col
                for (r in col until n) if (kotlin.math.abs(m[r][col]) > kotlin.math.abs(m[pivot][col])) pivot = r
                if (kotlin.math.abs(m[pivot][col]) < 1e-12) return null
                val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp

                for (r in 0 until n) {
                    if (r == col) continue
                    val factor = m[r][col] / m[col][col]
                    for (c in col..n) m[r][c] -= factor * m[col][c]
                }
            }
            return DoubleArray(n) { m[it][n] / m[it][it] }
        }
    }
}

/** Jeden wzorzec: co pokazały czujniki i jaka to była naprawdę masa. */
data class SensorSample(
    /** Przechył względem pustego telefonu, w stopniach. */
    val tiltDeg: Double,
    /** Spadek częstotliwości drgań własnych, w hercach. */
    val freqDrop: Double,
    val grams: Double
) {
    fun features() = doubleArrayOf(tiltDeg, freqDrop)
}

/**
 * Model wagi czujnikowej złożony z dostępnych kanałów.
 *
 * Przy trzech i więcej wzorcach uczy się obu kanałów naraz. Przy mniejszej
 * liczbie schodzi do prostej proporcji na tym kanale, który dał wyraźniejszy
 * sygnał — lepsze to niż model dopasowany do szumu.
 */
class SensorScaleModel private constructor(
    private val model: LinearModel?,
    private val singleChannel: Pair<Int, Double>?,
    val sampleCount: Int
) {
    companion object {
        /** Poniżej tych progów kanał nie niesie informacji. */
        const val MIN_TILT_DEG = 0.01
        const val MIN_FREQ_DROP = 0.02

        fun build(samples: List<SensorSample>): SensorScaleModel? {
            if (samples.isEmpty()) return null

            LinearModel.fit(samples.map { it.features() }, samples.map { it.grams })
                ?.let { return SensorScaleModel(it, null, samples.size) }

            // za mało wzorców na dwa kanały — bierzemy mocniejszy pojedynczo
            val tiltStrength = samples.maxOf { it.tiltDeg }
            val freqStrength = samples.maxOf { it.freqDrop }
            val index = if (tiltStrength >= MIN_TILT_DEG && tiltStrength / MIN_TILT_DEG >=
                freqStrength / MIN_FREQ_DROP) 0 else 1
            val usable = samples.filter { it.features()[index] > 0 }
            if (usable.isEmpty()) return null
            val ratio = usable.sumOf { it.grams } / usable.sumOf { it.features()[index] }
            return SensorScaleModel(null, index to ratio, samples.size)
        }
    }

    val isTrained: Boolean get() = model != null

    /** Nazwa kanału użytego samodzielnie, gdy wzorców było za mało. */
    val singleChannelName: String?
        get() = when (singleChannel?.first) {
            0 -> "przechył"
            1 -> "rezonans"
            else -> null
        }

    fun mass(tiltDeg: Double, freqDrop: Double): Double {
        model?.let { return it.predict(doubleArrayOf(tiltDeg, freqDrop)).coerceAtLeast(0.0) }
        val (index, ratio) = singleChannel ?: return 0.0
        val feature = if (index == 0) tiltDeg else freqDrop
        return (feature * ratio).coerceAtLeast(0.0)
    }
}
