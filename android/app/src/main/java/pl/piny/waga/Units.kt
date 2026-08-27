package pl.piny.waga

import java.util.Locale
import kotlin.math.abs

/** Jednostki masy. [grams] mówi, ile gramów waży jedna jednostka. */
enum class MassUnit(
    val symbol: String,
    val label: String,
    val grams: Double,
    val decimals: Int
) {
    MG("mg", "miligram", 0.001, 0),
    G("g", "gram", 1.0, 2),
    CT("ct", "karat metryczny", 0.2, 2),
    GR("gr", "gran", 0.06479891, 2),
    DWT("dwt", "pennyweight", 1.55517384, 3),
    OZT("ozt", "uncja trojańska", 31.1034768, 4),
    OZ("oz", "uncja handlowa", 28.349523125, 4);

    fun fromGrams(g: Double): Double = g / grams
    fun toGrams(value: Double): Double = value * grams
}

/** Jednostki dostępne na głównym wyświetlaczu, w kolejności przełączania. */
enum class DisplayUnit(val unit: MassUnit, val label: String, val decimals: Int) {
    GRAMS(MassUnit.G, "gramy", 1),
    CARATS(MassUnit.CT, "karaty", 2),
    OUNCES(MassUnit.OZ, "uncje", 3),
    TROY(MassUnit.OZT, "troy", 3);

    fun next(): DisplayUnit = entries[(ordinal + 1) % entries.size]
}

object Fmt {
    /**
     * Formatuje po polsku, przecinkiem. Wartość mieszcząca się w połowie ostatniej
     * cyfry jest sprowadzana do zera — inaczej lekko ujemny szum dawałby "-0,0".
     */
    fun pl(value: Double, decimals: Int): String {
        val half = 0.5 / Math.pow(10.0, decimals.toDouble())
        val v = if (abs(value) < half) 0.0 else value
        return String.format(Locale.US, "%.${decimals}f", v).replace('.', ',')
    }

    /** Wczytuje liczbę wpisaną z przecinkiem albo kropką. */
    fun parse(text: String): Double? =
        text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
}
