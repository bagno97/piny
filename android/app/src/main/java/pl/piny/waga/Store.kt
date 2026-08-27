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

    var zero: Double
        get() = prefs.getFloat("zero", 0f).toDouble()
        set(v) = prefs.edit().putFloat("zero", v.toFloat()).apply()

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

    var points: List<CalPoint>
        get() {
            val raw = prefs.getString("points", "[]") ?: "[]"
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    CalPoint(o.getDouble("raw"), o.getDouble("g"))
                }
            }.getOrDefault(emptyList())
        }
        set(v) {
            val arr = JSONArray()
            v.forEach { arr.put(JSONObject().put("raw", it.raw).put("g", it.grams)) }
            prefs.edit().putString("points", arr.toString()).apply()
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

    fun loadCalibration() = Calibration(zero, points)

    fun saveCalibration(cal: Calibration) {
        zero = cal.zero
        points = cal.points
        calibratedAt = System.currentTimeMillis()
    }

    fun addMeasurement(grams: Double) {
        history = listOf(Measurement(grams, System.currentTimeMillis())) + history
    }

    fun clearHistory() { history = emptyList() }
}
