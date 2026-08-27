package pl.piny.waga

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Measurement(val grams: Double, val at: Long)

/** Ustawienia i dziennik w SharedPreferences; JSON tylko dla list. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("waga", Context.MODE_PRIVATE)

    companion object {
        private const val MAX_HISTORY = 50
    }

    // Kalibracja jest osobna dla palca i dla rysika — to inne czujniki tej samej wagi.
    private fun zeroKey(tool: Tool) = "zero_${tool.key}"
    private fun pointsKey(tool: Tool) = "points_${tool.key}"
    private fun rangeKey(tool: Tool) = "range_${tool.key}"

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
            v.take(MAX_HISTORY).forEach { arr.put(JSONObject().put("g", it.grams).put("at", it.at)) }
            prefs.edit().putString("history", arr.toString()).apply()
        }

    /**
     * Zapisany profil narzędzia albo krzywa wstępna, gdy użytkownik nic jeszcze nie
     * kalibrował. Waga nigdy nie startuje w stanie „nic nie pokażę".
     */
    fun loadCalibration(tool: Tool): Calibration {
        val saved = readPoints(tool)
        if (saved.isEmpty()) return Calibration.automatic(observedFullScale(tool))
        return Calibration(prefs.getFloat(zeroKey(tool), 0f).toDouble(), saved, auto = false)
    }

    fun saveCalibration(tool: Tool, cal: Calibration) {
        val arr = JSONArray()
        if (!cal.auto) cal.points.forEach { arr.put(JSONObject().put("raw", it.raw).put("g", it.grams)) }
        prefs.edit()
            .putFloat(zeroKey(tool), cal.zero.toFloat())
            .putString(pointsKey(tool), arr.toString())
            .apply()
        if (!cal.auto) calibratedAt = System.currentTimeMillis()
    }

    fun addMeasurement(grams: Double) {
        history = listOf(Measurement(grams, System.currentTimeMillis())) + history
    }

    fun clearHistory() { history = emptyList() }
}
