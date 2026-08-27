package pl.piny.waga

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class FusionTest {

    @Test
    fun `uczy sie masy z dwoch kanalow czujnikow`() {
        // masa = 40·przechył + 12·spadek częstotliwości
        val rows = listOf(
            doubleArrayOf(0.10, 0.30), doubleArrayOf(0.25, 0.80), doubleArrayOf(0.50, 1.60),
            doubleArrayOf(0.90, 2.90), doubleArrayOf(1.50, 4.80)
        )
        val targets = rows.map { 40 * it[0] + 12 * it[1] }
        val model = LinearModel.fit(rows, targets)
        assertNotNull(model)

        val predicted = model!!.predict(doubleArrayOf(0.70, 2.20))
        assertEquals(40 * 0.70 + 12 * 2.20, predicted, 0.5)
    }

    @Test
    fun `radzi sobie z szumem w danych uczacych`() {
        val random = Random(11)
        val rows = (1..20).map { doubleArrayOf(it * 0.1, it * 0.32) }
        val targets = rows.map { 40 * it[0] + 12 * it[1] + random.nextGaussian() * 0.5 }
        val model = LinearModel.fit(rows, targets)!!
        assertEquals(40 * 1.0 + 12 * 3.2, model.predict(doubleArrayOf(1.0, 3.2)), 2.0)
    }

    @Test
    fun `zbyt malo pomiarow nie daje modelu`() {
        // dwie cechy wymagają co najmniej trzech pomiarów
        val rows = listOf(doubleArrayOf(0.1, 0.3), doubleArrayOf(0.2, 0.6))
        assertNull(LinearModel.fit(rows, listOf(10.0, 20.0)))
    }

    @Test
    fun `niespojne dane sa odrzucane`() {
        assertNull(LinearModel.fit(emptyList(), emptyList()))
        assertNull(LinearModel.fit(listOf(doubleArrayOf(1.0)), listOf(1.0, 2.0)))
        assertNull(LinearModel.fit(
            listOf(doubleArrayOf(1.0, 2.0), doubleArrayOf(3.0)), listOf(1.0, 2.0)))
    }

    @Test
    fun `jeden kanal tez wystarczy`() {
        val rows = listOf(doubleArrayOf(0.2), doubleArrayOf(0.5), doubleArrayOf(1.1))
        val model = LinearModel.fit(rows, rows.map { it[0] * 55.0 })!!
        assertEquals(44.0, model.predict(doubleArrayOf(0.8)), 0.5)
    }
}
