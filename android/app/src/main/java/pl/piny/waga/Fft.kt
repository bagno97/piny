package pl.piny.waga

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.PI

/** Przekształcenie Fouriera w miejscu, podstawa 2. Bez zależności — testowalne na JVM. */
object Fft {

    /** Największa potęga dwójki nie większa niż [n]. */
    fun floorPowerOfTwo(n: Int): Int {
        if (n < 2) return 0
        var p = 1
        while (p * 2 <= n) p *= 2
        return p
    }

    /**
     * Widmo amplitudowe sygnału rzeczywistego. Zwraca [n/2] prążków,
     * gdzie prążek i odpowiada częstotliwości i * sampleRate / n.
     */
    fun magnitudes(samples: DoubleArray): DoubleArray {
        val n = floorPowerOfTwo(samples.size)
        require(n >= 8) { "za mało próbek na przekształcenie: ${samples.size}" }

        val re = DoubleArray(n) { samples[it] }
        val im = DoubleArray(n)
        transform(re, im)

        return DoubleArray(n / 2) { hypot(re[it], im[it]) }
    }

    private fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size

        // przestawienie bitowo-odwrotne
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }

        var len = 2
        while (len <= n) {
            val angle = -2 * PI / len
            val wRe = cos(angle)
            val wIm = sin(angle)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe
                    im[i + k + len / 2] = uIm - vIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Okno Hanna — bez niego przeciek widmowy rozmywa szczyt rezonansu. */
    fun hann(samples: DoubleArray): DoubleArray {
        val n = samples.size
        if (n < 2) return samples.copyOf()
        val mean = samples.average()
        return DoubleArray(n) { i ->
            (samples[i] - mean) * 0.5 * (1 - cos(2 * PI * i / (n - 1)))
        }
    }
}
