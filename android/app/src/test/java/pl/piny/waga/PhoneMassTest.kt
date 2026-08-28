package pl.piny.waga

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class PhoneMassTest {

    @Test
    fun `rozpoznaje model po numerze handlowym`() {
        assertEquals(209.0, PhoneMass.forModel("SM-A356B"), 0.0)
        assertEquals(209.0, PhoneMass.forModel("sm-a356e"), 0.0)
        assertTrue(PhoneMass.isKnown("SM-A356B"))
    }

    @Test
    fun `nieznany model dostaje wartosc zastepcza`() {
        assertEquals(PhoneMass.DEFAULT_GRAMS, PhoneMass.forModel("Nieznany X1"), 0.0)
        assertFalse(PhoneMass.isKnown("Nieznany X1"))
    }

    @Test
    fun `masa telefonu zastepuje wzorzec`() {
        // telefon 209 g drgający przy 40 Hz — bez żadnego wzorca
        val phone = 209.0
        val emptyHz = 40.0
        val scale = ResonanceScale.fromPhoneMass(emptyHz, phone)
        assertNotNull(scale)
        assertEquals("odtworzona masa układu", phone, scale!!.systemMass, 0.01)

        val c = phone * emptyHz * emptyHz
        for (m in listOf(5.0, 20.0, 50.0, 120.0)) {
            val hz = sqrt(c / (phone + m))
            assertEquals("masa $m g bez kalibracji", m, scale.mass(hz), m * 0.02 + 0.05)
        }
    }

    @Test
    fun `bezsensowne dane nie daja skali`() {
        assertNull(ResonanceScale.fromPhoneMass(0.0, 209.0))
        assertNull(ResonanceScale.fromPhoneMass(40.0, 0.0))
    }
}
