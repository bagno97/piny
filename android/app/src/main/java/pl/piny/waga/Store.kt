package pl.piny.waga

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Measurement(val grams: Double, val at: Long)

/** Ustawienia obu wag i dziennik pomiarów. JSON tylko tam, gdzie trzeba listy. */
class Store(context: Context) {

    private val prefs = context.getSharedPreferences("waga", Context.MODE_PRIVATE)

    companion object {
        private const val MAX_HISTORY = 50
    }

    // ── waga ekranowa ──────────────────────────────────────────────────────
    // Kalibracja jest osobna dla palca i dla rysika — to inne charakterystyki
    // tego samego czujnika.

    private fun zeroKey(tool: Tool) = "zero_${tool.key}"
    private fun pointsKey(tool: Tool) = "points_${tool.key}"
    private fun rangeKey(tool: Tool) = "range_${tool.key}"

    var displayUnit: DisplayUnit
        get() = runCatching { DisplayUnit.valueOf(prefs.getString("unit", "GRAMS")!!) }
            .getOrDefault(DisplayUnit.GRAMS)
        set(v) = prefs.edit().putString("unit", v.name).apply()

    var channel: Channel
        get() = runCatching { Channel.valueOf(prefs.getString("channel", "AUTO")!!) }
            .getOrDefault(Channel.AUTO)
        set(v) = prefs.edit().putString("channel", v.name).apply()

    var calibratedAt: Long
        get() = prefs.getLong("calAt", 0L)
        set(v) = prefs.edit().putLong("calAt", v).apply()

    /**
     * Największy sygnał, jaki ekran oddał dla tego narzędzia. Na nim opiera się
     * kalibracja wstępna, żeby korzystać z całej czułości, a nie z jej promila.
     */
    fun observedFullScale(tool: Tool): Double =
        prefs.getFloat(rangeKey(tool), Calibration.INITIAL_FULL_SCALE_SIGNAL.toFloat()).toDouble()

    fun saveObservedFullScale(tool: Tool, signal: Double) {
        prefs.edit().putFloat(rangeKey(tool), signal.toFloat()).apply()
    }

    fun resetObservedFullScale(tool: Tool) {
        prefs.edit().remove(rangeKey(tool)).apply()
    }

    private fun readPoints(tool: Tool): List<CalPoint> {
        val raw = prefs.getString(pointsKey(tool), "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                CalPoint(o.getDouble("raw"), o.getDouble("g"))
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Zapisany profil narzędzia albo krzywa wstępna, gdy użytkownik nic jeszcze
     * nie kalibrował. Waga nigdy nie startuje w stanie „nic nie pokażę".
     */
    fun loadCalibration(tool: Tool): Calibration {
        val saved = readPoints(tool)
        if (saved.isEmpty()) return Calibration.automatic(observedFullScale(tool))
        return Calibration(prefs.getFloat(zeroKey(tool), 0f).toDouble(), saved, auto = false)
    }

    fun saveCalibration(tool: Tool, cal: Calibration) {
        val arr = JSONArray()
        if (!cal.auto) cal.points.forEach {
            arr.put(JSONObject().put("raw", it.raw).put("g", it.grams))
        }
        prefs.edit()
            .putFloat(zeroKey(tool), cal.zero.toFloat())
            .putString(pointsKey(tool), arr.toString())
            .apply()
        if (!cal.auto) calibratedAt = System.currentTimeMillis()
    }

    // ── waga przechyłowa ───────────────────────────────────────────────────

    /** Kierunek grawitacji przy pustym telefonie. */
    var baselineTilt: Direction?
        get() {
            val x = prefs.getFloat("base_x", Float.NaN)
            val y = prefs.getFloat("base_y", Float.NaN)
            val z = prefs.getFloat("base_z", Float.NaN)
            return if (x.isNaN() || y.isNaN() || z.isNaN()) null
            else Direction(x.toDouble(), y.toDouble(), z.toDouble())
        }
        set(v) {
            val e = prefs.edit()
            if (v == null) e.remove("base_x").remove("base_y").remove("base_z")
            else e.putFloat("base_x", v.x.toFloat())
                .putFloat("base_y", v.y.toFloat())
                .putFloat("base_z", v.z.toFloat())
            e.apply()
        }

    /** Ile gramów przypada na stopień przechyłu; zero oznacza brak przelicznika. */
    var gramsPerDegree: Double
        get() = prefs.getFloat("g_per_deg", 0f).toDouble()
        set(v) = prefs.edit().putFloat("g_per_deg", v.toFloat()).apply()

    // ── dziennik ───────────────────────────────────────────────────────────

    var history: List<Measurement>
        get() {
            val raw = prefs.getString("history", "[]") ?: "[]"
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    Measurement(o.getDouble("g"), o.getLong("at"))
                }
            }.getOrDefault(emptyList())
        }
        set(v) {
            val arr = JSONArray()
            v.take(MAX_HISTORY).forEach {
                arr.put(JSONObject().put("g", it.grams).put("at", it.at))
            }
            prefs.edit().putString("history", arr.toString()).apply()
        }

    fun addMeasurement(grams: Double) {
        history = listOf(Measurement(grams, System.currentTimeMillis())) + history
    }

    fun clearHistory() { history = emptyList() }
}
