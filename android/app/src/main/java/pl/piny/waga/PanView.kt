package pl.piny.waga

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/** Który kanał ekranu niesie informację o nacisku. */
enum class Channel { AUTO, PRESSURE, AREA }

/** Czym dotykamy ekranu — rysik i palec mają zupełnie inne charakterystyki nacisku. */
enum class Tool(val label: String, val key: String) {
    FINGER("palec", "finger"),
    STYLUS("rysik", "stylus");
}

data class Sample(
    val pressureSum: Double,
    val areaSumMm2: Double,
    val contacts: Int,
    val maxPressure: Double
)

/**
 * Pole pomiarowe. Czyta MotionEvent bez pośrednictwa przeglądarki, więc dostaje
 * surowe wartości z ekranu — łącznie z próbkami historycznymi z jednej paczki
 * zdarzeń, które podnoszą realną częstotliwość próbkowania.
 */
class PanView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        /** Pole styku odpowiadające pełnemu wychyleniu kanału powierzchniowego. */
        const val AREA_FULL_SCALE_MM2 = 150.0
        const val SATURATION = 0.995
    }

    val probe = PressureProbe()
    var channel: Channel = Channel.AUTO

    private val pressures = HashMap<Int, Double>()
    private val areas = HashMap<Int, Double>()
    private val tools = HashMap<Int, Tool>()

    /** Narzędzie ostatniego kontaktu; rysik ma pierwszeństwo, gdy leży też dłoń. */
    var tool: Tool = Tool.FINGER
        private set

    /** Wołane, gdy zmienia się narzędzie — waga przełącza wtedy profil kalibracji. */
    var onToolChanged: ((Tool) -> Unit)? = null

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    var lineColor = 0
    var accentColor = 0

    /** 0..1 — wypełnienie poświaty pod odczytem. */
    var glow = 0f

    private val dp = resources.displayMetrics.density
    private val xdpi = resources.displayMetrics.xdpi.takeIf { it > 1f } ?: 160f
    private val ydpi = resources.displayMetrics.ydpi.takeIf { it > 1f } ?: 160f

    private fun pxToMmX(px: Float) = px / xdpi * 25.4
    private fun pxToMmY(px: Float) = px / ydpi * 25.4

    /**
     * Zakres deklarowany przez sterownik, wyznaczany raz. Sterowniki bywają
     * niezgodne ze skalą 0–1 — jeśli deklarują własną, sprowadzamy do niej odczyty.
     */
    private val declaredRange: InputDevice.MotionRange? by lazy { declaredPressureRange() }

    private fun normalize(raw: Double): Double {
        val r = declaredRange ?: return raw
        val span = (r.max - r.min).toDouble()
        // skala 0–1 albo bezsensowna deklaracja — zostawiamy wartość surową
        if (span <= 0.0 || (r.min == 0f && r.max == 1f)) return raw
        return ((raw - r.min) / span).coerceIn(0.0, 1.0)
    }

    /** Zakres osi nacisku zadeklarowany przez sterownik ekranu — null gdy go nie podaje. */
    fun declaredPressureRange(): InputDevice.MotionRange? {
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id) ?: continue
            if (device.sources and InputDevice.SOURCE_TOUCHSCREEN != InputDevice.SOURCE_TOUCHSCREEN) continue
            device.getMotionRange(MotionEvent.AXIS_PRESSURE, InputDevice.SOURCE_TOUCHSCREEN)?.let { return it }
        }
        return null
    }

    fun sample(): Sample {
        val pressureSum = pressures.values.sum()
        val areaSum = areas.values.sum()
        return Sample(pressureSum, areaSum, pressures.size, pressures.values.maxOrNull() ?: 0.0)
    }

    /** Sygnał podawany wadze — po wyborze kanału. */
    fun signal(): Double {
        val s = sample()
        val useArea = channel == Channel.AREA || (channel == Channel.AUTO && !probe.hasForceSensor)
        return if (useArea) s.areaSumMm2 / AREA_FULL_SCALE_MM2 else s.pressureSum
    }

    fun usingArea(): Boolean =
        channel == Channel.AREA || (channel == Channel.AUTO && !probe.hasForceSensor)

    fun saturated(): Boolean =
        !usingArea() && (pressures.values.maxOrNull() ?: 0.0) >= SATURATION

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_MOVE -> {
                // próbki historyczne z tej samej paczki — więcej danych dla filtru
                for (h in 0 until event.historySize) {
                    for (i in 0 until event.pointerCount) {
                        probe.note(normalize(event.getHistoricalPressure(i, h).toDouble()))
                    }
                }
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val p = normalize(event.getPressure(i).toDouble())
                    probe.note(p)
                    pressures[id] = p
                    areas[id] = contactAreaMm2(event, i)
                    tools[id] = toolOf(event.getToolType(i))
                }
                updateTool()
                if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(event.actionIndex)
                pressures.remove(id); areas.remove(id); tools.remove(id)
                updateTool()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressures.clear(); areas.clear(); tools.clear()
            }
        }
        return true
    }

    private fun toolOf(toolType: Int): Tool = when (toolType) {
        MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> Tool.STYLUS
        else -> Tool.FINGER
    }

    private fun updateTool() {
        val next = if (tools.values.any { it == Tool.STYLUS }) Tool.STYLUS else Tool.FINGER
        if (next != tool) {
            tool = next
            onToolChanged?.invoke(next)
        }
    }

    /** Elipsa styku w mm²; gdy sterownik jej nie podaje, sięga po znormalizowany rozmiar. */
    private fun contactAreaMm2(event: MotionEvent, index: Int): Double {
        val major = event.getTouchMajor(index)
        val minor = event.getTouchMinor(index)
        if (major > 1f && minor > 0f) {
            return pxToMmX(major) * pxToMmY(minor)
        }
        val size = event.getSize(index).toDouble()
        return if (size > 0) size * AREA_FULL_SCALE_MM2 else 0.0
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f

        if (glow > 0.001f) {
            val r = (60f + glow * 190f) * dp
            pulsePaint.shader = RadialGradient(
                cx, cy, r,
                intArrayOf((accentColor and 0x00FFFFFF) or (((40 + glow * 90).toInt()) shl 24), accentColor and 0x00FFFFFF),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, r, pulsePaint)
        }

        crossPaint.color = lineColor
        crossPaint.alpha = 110
        val arm = 135f * dp
        canvas.drawLine(cx - arm, cy, cx + arm, cy, crossPaint)
        canvas.drawLine(cx, cy - arm, cx, cy + arm, crossPaint)

        ringPaint.color = lineColor
        val maxR = min(width, height) / 2f - 8 * dp
        for ((i, radius) in listOf(75f * dp, 115f * dp, 155f * dp).withIndex()) {
            if (radius > maxR) continue
            ringPaint.alpha = if (i == 2) 120 else 190
            canvas.drawCircle(cx, cy, radius, ringPaint)
        }
    }
}
