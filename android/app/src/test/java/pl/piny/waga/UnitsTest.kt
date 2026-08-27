package pl.piny.waga

import org.junit.Assert.*
import org.junit.Test

class UnitsTest {

    @Test
    fun `karat metryczny to dokladnie jedna piata grama`() {
        assertEquals(0.2, MassUnit.CT.grams, 0.0)
        assertEquals(5.0, MassUnit.CT.fromGrams(1.0), 1e-12)
        assertEquals(1.05, MassUnit.CT.toGrams(5.25), 1e-12)
    }

    @Test
    fun `przeliczniki jubilerskie zgadzaja sie z definicjami`() {
        assertEquals(1000.0, MassUnit.MG.fromGrams(1.0), 1e-9)
        assertEquals(1.0, MassUnit.OZT.fromGrams(31.1034768), 1e-12)
        assertEquals(1.0, MassUnit.OZ.fromGrams(28.349523125), 1e-12)
        assertEquals(20.0, MassUnit.DWT.fromGrams(31.1034768), 1e-9)   // 20 dwt = 1 uncja trojańska
        assertEquals(480.0, MassUnit.GR.fromGrams(31.1034768), 1e-6)   // 480 granów = 1 uncja trojańska
    }

    @Test
    fun `przelicznik jest odwracalny`() {
        for (u in MassUnit.entries) {
            assertEquals(7.77, u.fromGrams(u.toGrams(7.77)), 1e-9)
        }
    }

    @Test
    fun `formatowanie nie pokazuje ujemnego zera`() {
        assertEquals("0,0", Fmt.pl(-0.04, 1))
        assertEquals("0,0", Fmt.pl(-0.0, 1))
        assertEquals("-0,2", Fmt.pl(-0.2, 1))
        assertEquals("6,5", Fmt.pl(6.54, 1))
        assertEquals("32,70", Fmt.pl(32.7, 2))
    }

    @Test
    fun `wczytywanie liczby przyjmuje przecinek i kropke`() {
        assertEquals(6.54, Fmt.parse("6,54")!!, 1e-9)
        assertEquals(6.54, Fmt.parse(" 6.54 ")!!, 1e-9)
        assertNull(Fmt.parse("abc"))
        assertNull(Fmt.parse(""))
    }

    @Test
    fun `przelacznik jednostek wraca do gramow`() {
        var u = DisplayUnit.GRAMS
        repeat(DisplayUnit.entries.size) { u = u.next() }
        assertEquals(DisplayUnit.GRAMS, u)
    }
}
