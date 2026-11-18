package tw.wannabe.phonekb

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.util.SparseArray
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 觸控板模式
 *  - 單指滑動：移動游標（有加速度）
 *  - 單指輕點：左鍵
 *  - 雙指輕點：右鍵
 *  - 雙指滑動：捲動（上下 + 左右）
 *  - 輕點後馬上再按住拖曳：拖移（按住左鍵）
 *  - 底部：左鍵 / 右鍵（可按住）、切回鍵盤
 */
class TouchpadView(context: Context) : View(context) {

    interface Listener {
        fun onMouseReport(buttons: Int, dx: Int, dy: Int, wheel: Int, pan: Int)
        fun onKeyAction(action: KeyAction)
    }

    var listener: Listener? = null
    var sensitivity = 1.5f
    var invertScroll = false
    /** iPad / Windows 自己就有游標加速，預設關掉避免加速兩次 */
    var acceleration = false
    var statusColor = KeyboardView.COLOR_RED
        set(v) { field = v; invalidate() }

    private val density = resources.displayMetrics.density
    private val slop = 8f * density
    private val scrollStep = 14f * density   // 雙指移動多少 px 算一格滾輪
    private val statusH = 3f * density

    // 底部按鈕
    private val btnL = RectF(); private val btnR = RectF(); private val btnKb = RectF()
    private var barTop = 0f
    private val barPointers = SparseArray<Int>() // pointerId → button bit（0 = 鍵盤鍵）
    private var barButtons = 0

    // 觸控板手勢狀態
    private val padPointers = SparseArray<PointF>()
    private var gestureStart = 0L
    private var maxPointers = 0
    private var moved = 0f
    private var lastTapUp = 0L
    private val lastTapPos = PointF()
    private var dragging = false
    private var multiTouchSeen = false
    private var accX = 0f; private var accY = 0f
    private var lastMoveTime = 0L
    private var startMoved = false  // 手指要先移動超過 START_SLOP 才開始動游標（防抖、防點擊時偏移）
    private var slopX = 0f; private var slopY = 0f
    private var scrollAccX = 0f; private var scrollAccY = 0f

    private val buttons get() = barButtons or (if (dragging) 1 else 0)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9AA0A6"); textAlign = Paint.Align.CENTER
    }

    init { setBackgroundColor(Color.parseColor("#111214")) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val barH = h * 0.17f
        barTop = h - barH
        val g = 4f * density
        val kbW = w * 0.14f
        val half = (w - kbW) / 2f
        btnL.set(g, barTop + g, half - g / 2, h - g)
        btnR.set(half + g / 2, barTop + g, w - kbW - g / 2, h - g)
        btnKb.set(w - kbW + g / 2, barTop + g, w - g, h - g)
        text.textSize = barH * 0.28f
    }

    override fun onDraw(canvas: Canvas) {
        paint.color = statusColor
        canvas.drawRect(0f, 0f, width.toFloat(), statusH, paint)

        val r = 10f * density
        paint.color = Color.parseColor("#1A1B1E")
        canvas.drawRoundRect(4f * density, statusH + 4f * density, width - 4f * density,
            barTop - 2f * density, r, r, paint)
        canvas.drawText("觸控板　單指移動・輕點左鍵・雙指輕點右鍵・雙指捲動・點兩下按住拖曳",
            width / 2f, (statusH + barTop) / 2f, text)

        drawBtn(canvas, btnL, "左鍵", (barButtons and 1) != 0 || dragging)
        drawBtn(canvas, btnR, "右鍵", (barButtons and 2) != 0)
        drawBtn(canvas, btnKb, "⌨", false)
    }

    private fun drawBtn(c: Canvas, rect: RectF, label: String, down: Boolean) {
        paint.color = if (down) KeyboardView.COLOR_PRESSED else KeyboardView.COLOR_KEY
        val r = 8f * density
        c.drawRoundRect(rect, r, r, paint)
        text.color = Color.WHITE
        c.drawText(label, rect.centerX(), rect.centerY() + text.textSize * 0.35f, text)
        text.color = Color.parseColor("#9AA0A6")
    }

    // ---------------------------------------------------------------- touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val x = e.getX(i); val y = e.getY(i)
                if (y >= barTop) barDown(id, x, y) else padDown(id, x, y)
            }
            MotionEvent.ACTION_MOVE -> padMove(e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                if (barPointers.indexOfKey(id) >= 0) barUp(id) else padUp(id)
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        return true
    }

    private fun barDown(id: Int, x: Float, y: Float) {
        val bit = when {
            btnL.contains(x, y) -> 1
            btnR.contains(x, y) -> 2
            btnKb.contains(x, y) -> 0
            else -> return
        }
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        barPointers.put(id, bit)
        if (bit != 0) { barButtons = barButtons or bit; send(0, 0) }
        invalidate()
    }

    private fun barUp(id: Int) {
        val bit = barPointers.get(id)
        barPointers.remove(id)
        if (bit == 0) { releaseAll(); listener?.onKeyAction(KeyAction.KEYBOARD); return }
        barButtons = barButtons and bit.inv()
        send(0, 0)
        invalidate()
    }

    private fun padDown(id: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (padPointers.size() == 0) {
            gestureStart = now
            maxPointers = 1
            moved = 0f
            multiTouchSeen = false
            accX = 0f; accY = 0f; scrollAccX = 0f; scrollAccY = 0f
            startMoved = false; slopX = 0f; slopY = 0f
            lastMoveTime = SystemClock.uptimeMillis()
            // 點一下後很快再按住 → 拖移
            if (now - lastTapUp < DOUBLE_TAP_MS &&
                hypot(x - lastTapPos.x, y - lastTapPos.y) < 60f * density) {
                dragging = true
                send(0, 0)
                invalidate()
            }
        }
        padPointers.put(id, PointF(x, y))
        maxPointers = maxOf(maxPointers, padPointers.size())
        if (padPointers.size() >= 2) multiTouchSeen = true
    }

    private fun padMove(e: MotionEvent) {
        var sumDx = 0f; var sumDy = 0f; var n = 0
        for (i in 0 until e.pointerCount) {
            val p = padPointers.get(e.getPointerId(i)) ?: continue
            val x = e.getX(i); val y = e.getY(i)
            sumDx += x - p.x; sumDy += y - p.y
            p.set(x, y); n++
        }
        if (n == 0) return
        val dx = sumDx / n; val dy = sumDy / n
        moved += abs(dx) + abs(dy)

        if (n >= 2) {
            scroll(dx, dy)
        } else if (!multiTouchSeen) {   // 雙指放開剩一指時不要亂動游標
            val now = e.eventTime
            val dt = (now - lastMoveTime).coerceIn(1L, 50L)
            lastMoveTime = now
            if (!startMoved) {
                slopX += dx; slopY += dy
                if (hypot(slopX, slopY) / density < START_SLOP_DP) return
                startMoved = true
                move(slopX, slopY, dt)   // 超過門檻後把累積的一次補上，不會跳
            } else {
                move(dx, dy, dt)
            }
        }
    }

    /**
     * 觸控 px → dp → 滑鼠 counts。
     * 預設線性（1dp × 靈敏度），讓主機自己的加速度處理；
     * 開啟加速度時用「真實時間」算速度（dp/ms），不受觸控事件間隔忽長忽短影響。
     */
    private fun move(dxPx: Float, dyPx: Float, dtMs: Long) {
        val dxDp = dxPx / density; val dyDp = dyPx / density
        var gain = sensitivity
        if (acceleration) {
            val v = hypot(dxDp, dyDp) / dtMs           // dp/ms，慢速 ~0.1、快速 ~2+
            gain *= 1f + ((v - 0.3f) / 1.2f).coerceIn(0f, 1.5f)
        }
        accX += dxDp * gain
        accY += dyDp * gain
        val ix = accX.toInt(); val iy = accY.toInt()
        if (ix == 0 && iy == 0) return
        accX -= ix; accY -= iy
        send(ix, iy)
    }

    private fun scroll(dx: Float, dy: Float) {
        scrollAccY += dy; scrollAccX += dx
        val sy = (scrollAccY / scrollStep).toInt()
        val sx = (scrollAccX / scrollStep).toInt()
        if (sy == 0 && sx == 0) return
        scrollAccY -= sy * scrollStep; scrollAccX -= sx * scrollStep
        val dir = if (invertScroll) -1 else 1
        // 手指往上滑（dy<0）→ 內容往上跑（看下面）→ wheel 負
        listener?.onMouseReport(buttons, 0, 0, sy * dir, -sx * dir)
    }

    private fun padUp(id: Int) {
        val p = padPointers.get(id) ?: return
        padPointers.remove(id)
        if (padPointers.size() > 0) return

        val now = SystemClock.uptimeMillis()
        val isTap = now - gestureStart < TAP_MS && moved < slop * maxPointers
        if (dragging) {
            dragging = false
            send(0, 0) // 放開左鍵；若沒拖動，加上第一下就是雙擊

            lastTapUp = 0
            invalidate()
            return
        }
        if (isTap) {
            if (maxPointers >= 2) { click(2); lastTapUp = 0 }
            else { click(1); lastTapUp = now; lastTapPos.set(p.x, p.y) }
        }
    }

    private fun click(bit: Int) {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        listener?.onMouseReport(buttons or bit, 0, 0, 0, 0)
        listener?.onMouseReport(buttons, 0, 0, 0, 0)
    }

    private fun send(dx: Int, dy: Int) {
        // 一次最多 ±127，大位移拆成多包
        var rx = dx; var ry = dy
        do {
            val sx = rx.coerceIn(-127, 127); val sy = ry.coerceIn(-127, 127)
            listener?.onMouseReport(buttons, sx, sy, 0, 0)
            rx -= sx; ry -= sy
        } while (rx != 0 || ry != 0)
    }

    fun releaseAll() {
        padPointers.clear(); barPointers.clear()
        barButtons = 0; dragging = false
        listener?.onMouseReport(0, 0, 0, 0, 0)
        invalidate()
    }

    companion object {
        private const val TAP_MS = 220L
        private const val DOUBLE_TAP_MS = 280L
        private const val START_SLOP_DP = 2.5f
    }
}
