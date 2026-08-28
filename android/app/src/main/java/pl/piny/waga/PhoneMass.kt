package pl.piny.waga

import android.os.Build

/**
 * Masa własna telefonu — wbudowany odważnik wagi.
 *
 * Dzięki niej kalibracja wzorcem przestaje być konieczna: znając masę układu M₀
 * i jego częstotliwość drgań własnych f₀, wyliczamy stałą sprężystości podłoża
 * (C = M₀·f₀²), a potem każdą dołożoną masę liczymy bezwzględnie z m = C/f² − M₀.
 */
object PhoneMass {

    /** Masy katalogowe popularnych modeli, w gramach. */
    private val KNOWN = mapOf(
        "SM-A356" to 209.0,   // Galaxy A35 5G
        "SM-A546" to 202.0,   // Galaxy A54 5G
        "SM-A536" to 189.0,   // Galaxy A53 5G
        "SM-A566" to 194.0,   // Galaxy A56 5G
        "SM-S911" to 168.0,   // Galaxy S23
        "SM-S916" to 195.0,   // Galaxy S23+
        "SM-S918" to 234.0,   // Galaxy S23 Ultra
        "SM-S921" to 167.0,   // Galaxy S24
        "SM-S928" to 232.0,   // Galaxy S24 Ultra
        "SM-A155" to 200.0,   // Galaxy A15
        "SM-A256" to 197.0,   // Galaxy A25 5G
        "SM-G991" to 169.0,   // Galaxy S21
        "SM-N986" to 208.0    // Galaxy Note 20 Ultra
    )

    /** Gdy modelu nie ma w tabeli — typowa masa dzisiejszego telefonu. */
    const val DEFAULT_GRAMS = 195.0

    /** Czy masę udało się rozpoznać, czy jest to wartość zastępcza. */
    fun isKnown(model: String = Build.MODEL): Boolean = lookup(model) != null

    fun forModel(model: String = Build.MODEL): Double = lookup(model) ?: DEFAULT_GRAMS

    private fun lookup(model: String): Double? {
        val normalized = model.trim().uppercase()
        KNOWN[normalized]?.let { return it }
        // numery handlowe bywają z przyrostkiem kraju: SM-A356B, SM-A356E…
        return KNOWN.entries.firstOrNull { normalized.startsWith(it.key) }?.value
    }
}
