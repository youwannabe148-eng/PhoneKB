package tw.wannabe.phonekb

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.SparseArray
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * 體感滑鼠：把整支手機當空中滑鼠（手機直拿，像遙控器一樣指著螢幕）。
 *
 * 原理：陀螺儀量「轉動角速度」，用重力方向分解成
 *   - 水平轉動（yaw，繞著「上」軸）→ 游標左右
 *   - 上下抬壓（pitch，繞著「右」軸）→ 游標上下
 * 不論手機平放、斜拿、直立都能用，因為軸是跟著重力算的，不是固定手機座標。
 *
 * 為什麼不用「平移」：加速度計要積分兩次才變位移，誤差會累積，幾秒內就漂走。
 *
 * 版面（直向）：
 *   ┌─────────┬───┬─────────┐
 *   │  左鍵    │滾 │  右鍵    │  上 42%
 *   ├─────────┴───┴─────────┤
 *   │  按住 = 暫停移動        │  中 43%（像把滑鼠拿起來重新擺）
 *   ├────────────┬───────────┤
 *   │  ⌨ 鍵盤    │ 移動 開/關 │  下 15%
 *   └────────────┴───────────┘
 */
class AirMouseView(context: Context) : View(context), SensorEventListener {

    var listener: TouchpadView.Listener? = null
    var sensitivity = 1.0f
    var invertScroll = false
    var statusColor = KeyboardView.COLOR_RED
        set(v) { field = v; invalidate() }

    private val sm = context.getSystemService(SensorManager::class.java)
    private val gyro: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val grav: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY)
    val hasGyro get() = gyro != null
    private var running = false

    // 感測器狀態
    private val up = floatArrayOf(0f, 0f, 1f)          // 重力反方向（手機座標）
    private var wx = 0f; private var wy = 0f; private var wz = 0f   // 平滑後角速度 rad/s
    private var lastTs = 0L
    private var accX = 0f; private var accY = 0f

    // 操作狀態
    private var moving = true      // 底部開關
    private var clutch = 0         // 按住暫停區的手指數
    private var buttons = 0
    private val pointerZone = SparseArray<Zone>()
    private var wheelLastY = 0f
    private var wheelAcc = 0f
    private var wheelMoved = 0f
    private var wheelOffset = 0f   // 畫滾輪紋路用

    private enum class Zone { LEFT, RIGHT, WHEEL, CLUTCH, KEYBOARD, TOGGLE }

    private val density = resources.displayMetrics.density
    private val wheelStep = 16f * density
    private val statusH = 3f * density

    private val rL = RectF(); private val rR = RectF(); private val rW = RectF()
    private val rC = RectF(); private val rK = RectF(); private val rT = RectF()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    init { setBackgroundColor(Color.parseColor("#111214")) }

    // ---------------------------------------------------------------- sensors

    fun start() {
        if (running || gyro == null) return
        // 不能用 SENSOR_DELAY_FASTEST：Android 12+ 沒宣告 HIGH_SAMPLING_RATE_SENSORS 權限會直接閃退。
        // 5000µs = 200Hz，是不需要額外權限的上限，對滑鼠已經很夠。
        runCatching { sm?.registerListener(this, gyro, GYRO_PERIOD_US) }
        grav?.let { g -> runCatching { sm?.registerListener(this, g, SensorManager.SENSOR_DELAY_GAME) } }
        running = true
        lastTs = 0L; wx = 0f; wy = 0f; wz = 0f; accX = 0f; accY = 0f
    }

    fun stop() {
        if (!running) return
        sm?.unregisterListener(this)
        running = false
        releaseAll()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                val n = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                if (n > 1f) { up[0] = e.values[0] / n; up[1] = e.values[1] / n; up[2] = e.values[2] / n }
            }
            Sensor.TYPE_GYROSCOPE -> onGyro(e)
        }
    }

    private fun onGyro(e: SensorEvent) {
        if (lastTs == 0L) { lastTs = e.timestamp; return }
        val dt = ((e.timestamp - lastTs) * 1e-9f).coerceIn(0f, 0.05f)
        lastTs = e.timestamp
        // 一階低通，濾掉手抖的高頻
        wx += SMOOTH * (e.values[0] - wx)
        wy += SMOOTH * (e.values[1] - wy)
        wz += SMOOTH * (e.values[2] - wz)
        if (!moving || clutch > 0) return

        // 指向方向 p：手機斜拿/平拿時是機頂 (+Y)，直立對著螢幕時是背面 (-Z)
        var px: Float; var py: Float; var pz: Float
        if (abs(up[1]) < 0.7f) { px = 0f; py = 1f; pz = 0f } else { px = 0f; py = 0f; pz = -1f }
        val d = px * up[0] + py * up[1] + pz * up[2]          // 去掉垂直分量 → 水平指向
        px -= d * up[0]; py -= d * up[1]; pz -= d * up[2]
        // 右軸 r = p × up
        var rx = py * up[2] - pz * up[1]
        var ry = pz * up[0] - px * up[2]
        var rz = px * up[1] - py * up[0]
        val rn = sqrt(rx * rx + ry * ry + rz * rz)
        if (rn < 1e-3f) return
        rx /= rn; ry /= rn; rz /= rn

        val yaw = wx * up[0] + wy * up[1] + wz * up[2]       // 正 = 往左轉
        val pitch = wx * rx + wy * ry + wz * rz              // 正 = 往上抬
        val mag = hypot(yaw, pitch)
        if (mag < DEADBAND) return

        // 速度曲線：慢轉精準（抑制手抖）、快轉跨得遠
        val curve = 0.3f + 0.7f * (mag / 1.0f).coerceIn(0f, 1f) + 0.5f * ((mag - 1f) / 2f).coerceIn(0f, 1f)
        val g = COUNTS_PER_RAD * sensitivity * curve
        accX += -yaw * dt * g
        accY += -pitch * dt * g
        val ix = accX.toInt(); val iy = accY.toInt()
        if (ix == 0 && iy == 0) return
        accX -= ix; accY -= iy
        listener?.onMouseReport(buttons, ix, iy, 0, 0)
    }

    // ---------------------------------------------------------------- layout / draw

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val g = 6f * density
        val top = statusH + g
        val btnBottom = top + (h - top) * 0.42f
        val clutchBottom = top + (h - top) * 0.85f
        val ww = w * 0.20f
        rL.set(g, top, (w - ww) / 2f - g / 2, btnBottom)
        rW.set((w - ww) / 2f + g / 2, top, (w + ww) / 2f - g / 2, btnBottom)
        rR.set((w + ww) / 2f + g / 2, top, w - g, btnBottom)
        rC.set(g, btnBottom + g, w - g, clutchBottom)
        rK.set(g, clutchBottom + g, w / 2f - g / 2, h - g)
        rT.set(w / 2f + g / 2, clutchBottom + g, w - g, h - g)
    }

    override fun onDraw(canvas: Canvas) {
        paint.color = statusColor
        canvas.drawRect(0f, 0f, width.toFloat(), statusH, paint)
        val r = 18f * density

        fun block(rect: RectF, on: Boolean, base: Int = KeyboardView.COLOR_KEY) {
            paint.color = if (on) KeyboardView.COLOR_PRESSED else base
            canvas.drawRoundRect(rect, r, r, paint)
        }
        fun label(rect: RectF, s: String, size: Float, color: Int = Color.WHITE, dy: Float = 0f) {
            text.color = color; text.textSize = size
            canvas.drawText(s, rect.centerX(), rect.centerY() + size * 0.35f + dy, text)
        }

        block(rL, buttons and 1 != 0); label(rL, "左鍵", 22f * density)
        block(rR, buttons and 2 != 0); label(rR, "右鍵", 22f * density)

        // 滾輪：畫會跟著捲動的橫紋
        block(rW, buttons and 4 != 0, Color.parseColor("#1E1F22"))
        paint.color = Color.parseColor("#3A3D44")
        val gap = 14f * density
        var y = rW.top + ((wheelOffset % gap) + gap) % gap
        while (y < rW.bottom - 4f * density) {
            if (y > rW.top + 4f * density)
                canvas.drawRect(rW.left + 10f * density, y, rW.right - 10f * density, y + 3f * density, paint)
            y += gap
        }

        block(rC, clutch > 0, Color.parseColor("#1A1B1E"))
        val hint = when {
            !hasGyro -> "這支手機沒有陀螺儀，無法使用體感滑鼠"
            !moving -> "移動已關閉"
            clutch > 0 -> "暫停中…放開繼續"
            else -> "轉動手機移動游標"
        }
        label(rC, hint, 18f * density, Color.WHITE, -14f * density)
        label(rC, "按住這裡＝暫停（像把滑鼠拿起來）", 13f * density, Color.parseColor("#9AA0A6"), 18f * density)

        block(rK, false, Color.parseColor("#1E1F22")); label(rK, "⌨ 鍵盤", 18f * density)
        block(rT, false, if (moving) Color.parseColor("#2F6B44") else Color.parseColor("#5A2A2C"))
        label(rT, if (moving) "移動：開" else "移動：關", 18f * density)
    }

    // ---------------------------------------------------------------- touch

    private fun zoneAt(x: Float, y: Float): Zone? = when {
        rL.contains(x, y) -> Zone.LEFT
        rR.contains(x, y) -> Zone.RIGHT
        rW.contains(x, y) -> Zone.WHEEL
        rC.contains(x, y) -> Zone.CLUTCH
        rK.contains(x, y) -> Zone.KEYBOARD
        rT.contains(x, y) -> Zone.TOGGLE
        else -> null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val z = zoneAt(e.getX(i), e.getY(i)) ?: return true
                pointerZone.put(e.getPointerId(i), z)
                down(z, e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    if (pointerZone.get(e.getPointerId(i)) == Zone.WHEEL) wheelMove(e.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                val z = pointerZone.get(id)
                pointerZone.remove(id)
                if (z != null) up(z)
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        return true
    }

    private fun down(z: Zone, y: Float) {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        when (z) {
            Zone.LEFT -> setButton(1, true)
            Zone.RIGHT -> setButton(2, true)
            Zone.WHEEL -> { wheelLastY = y; wheelAcc = 0f; wheelMoved = 0f }
            Zone.CLUTCH -> { clutch++; accX = 0f; accY = 0f }
            else -> {}
        }
        invalidate()
    }

    private fun up(z: Zone) {
        when (z) {
            Zone.LEFT -> setButton(1, false)
            Zone.RIGHT -> setButton(2, false)
            Zone.WHEEL -> if (wheelMoved < 8f * density) {   // 點一下滾輪 = 中鍵
                setButton(4, true); setButton(4, false)
            }
            Zone.CLUTCH -> { clutch = (clutch - 1).coerceAtLeast(0) }
            Zone.TOGGLE -> { moving = !moving; accX = 0f; accY = 0f }
            Zone.KEYBOARD -> { releaseAll(); listener?.onKeyAction(KeyAction.KEYBOARD); return }
        }
        invalidate()
    }

    private fun wheelMove(y: Float) {
        val dy = y - wheelLastY
        wheelLastY = y
        wheelMoved += abs(dy)
        wheelOffset += dy
        wheelAcc += dy
        val steps = (wheelAcc / wheelStep).toInt()
        if (steps != 0) {
            wheelAcc -= steps * wheelStep
            // 手指往下撥 = 像實體滾輪往自己撥 = 往下捲（wheel 負）
            val w = if (invertScroll) steps else -steps
            listener?.onMouseReport(buttons, 0, 0, w, 0)
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        invalidate()
    }

    private fun setButton(bit: Int, on: Boolean) {
        buttons = if (on) buttons or bit else buttons and bit.inv()
        listener?.onMouseReport(buttons, 0, 0, 0, 0)
    }

    fun releaseAll() {
        pointerZone.clear()
        clutch = 0
        if (buttons != 0) { buttons = 0; listener?.onMouseReport(0, 0, 0, 0, 0) }
        invalidate()
    }

    companion object {
        private const val SMOOTH = 0.5f
        private const val GYRO_PERIOD_US = 5000
        private const val DEADBAND = 0.03f        // rad/s ≈ 1.7°/s，低於這個當作手抖
        private const val COUNTS_PER_RAD = 800f   // 靈敏度 1.0 時，每轉 1 弧度（57°）約 800 counts × 曲線
    }
}
