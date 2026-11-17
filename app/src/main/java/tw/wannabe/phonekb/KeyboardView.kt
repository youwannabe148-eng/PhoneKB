package tw.wannabe.phonekb

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.SparseArray
import android.util.SparseLongArray
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * 全螢幕鍵盤。支援多指同時按（例如按住 Shift 再按字母）。
 *
 * 兩種模式（按鍵位置相同，只換顯示）：
 *  - 注音：大字注音，左上小字英文/數字
 *  - 英數：大字英文/數字，左上小字 Shift 符號
 * 【中/英】點一下 → 手機換模式，同時送 Ctrl+Space 讓平板輸入法跟著切；
 *          長按   → 只換手機顯示，不送鍵（兩邊對不齊時校正用）
 *
 * 修飾鍵：按住 + 其他鍵 = 組合鍵；單獨點一下 = 黏住到下一個鍵，再點一次取消。
 * 長按一般鍵 → 平板端自己連發（跟實體鍵盤一樣）。
 */
class KeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onKeyboardReport(mods: Int, keys: IntArray)
        fun onKeyAction(action: KeyAction)
        fun onModeChanged(mode: KbMode)
    }

    var listener: Listener? = null

    var mode = KbMode.ZHUYIN
        set(v) { field = v; invalidate() }
    var capsOn = false
        set(v) { field = v; invalidate() }
    var statusColor = COLOR_RED
        set(v) { field = v; invalidate() }

    private class Slot(val key: Key, val rect: RectF)
    private val slots = ArrayList<Slot>()

    private val pointerKeys = SparseArray<Key>()      // pointerId → 按下的鍵
    private val pointerDownAt = SparseLongArray()      // pointerId → 按下時間
    private val pressedKeys = LinkedHashSet<Key>()     // 一般鍵（非修飾鍵）
    private val pressedVisual = HashSet<Key>()
    private var heldMods = 0
    private var latchedMods = 0
    private var dirtyMods = 0  // 按住期間有沒有搭配其他鍵

    private val density = resources.displayMetrics.density
    private val gap = 3f * density
    private val radius = 6f * density
    private val statusH = 3f * density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9AA0A6"); textAlign = Paint.Align.LEFT
    }
    private val modePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val statusPaint = Paint()

    init {
        isHapticFeedbackEnabled = true
        setBackgroundColor(Color.parseColor("#111214"))
    }

    // ---------------------------------------------------------------- layout

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        slots.clear()
        val rows = KeyboardLayout.ROWS
        val rowH = (h - statusH) / rows.size
        rows.forEachIndexed { r, row ->
            val unit = w / row.sumOf { it.w.toDouble() }.toFloat()
            var x = 0f
            val y = statusH + r * rowH
            for (key in row) {
                val kw = key.w * unit
                slots += Slot(key, RectF(x + gap / 2, y + gap / 2, x + kw - gap / 2, y + rowH - gap / 2))
                x += kw
            }
        }
        subPaint.textSize = (rowH - gap) * 0.20f
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        statusPaint.color = statusColor
        canvas.drawRect(0f, 0f, width.toFloat(), statusH, statusPaint)
        val zy = mode == KbMode.ZHUYIN

        for (s in slots) {
            val k = s.key
            val r = s.rect
            bgPaint.color = when {
                k in pressedVisual -> COLOR_PRESSED
                k.isModifier && (latchedMods and k.modBit) != 0 -> COLOR_LATCHED
                k.code == Hid.CAPS && capsOn -> COLOR_LATCHED
                k.action == KeyAction.IME_TOGGLE -> if (zy) COLOR_ZHUYIN_KEY else COLOR_ALNUM_KEY
                k.label == "space" -> COLOR_KEY
                k.isModifier || k.action != KeyAction.NONE || k.label.length > 1 -> COLOR_FUNC
                else -> COLOR_KEY
            }
            canvas.drawRoundRect(r, radius, radius, bgPaint)
            val pad = r.height() * 0.10f

            // 切換鍵：顯示目前模式
            if (k.action == KeyAction.IME_TOGGLE) {
                modePaint.color = Color.WHITE
                modePaint.textSize = r.height() * 0.32f
                canvas.drawText(if (zy) "注音" else "英數", r.centerX(), r.centerY() + r.height() * 0.06f, modePaint)
                modePaint.color = Color.parseColor("#C8CCD2")
                modePaint.textSize = r.height() * 0.17f
                canvas.drawText("點:切換", r.centerX(), r.bottom - pad, modePaint)
                continue
            }

            val big: String
            val corner: String
            val bigColor: Int
            if (zy && k.zhuyin.isNotEmpty()) {
                big = k.zhuyin; corner = k.label; bigColor = COLOR_ZHUYIN
            } else {
                big = k.label; corner = k.shift; bigColor = Color.WHITE
            }
            val single = big.length == 1 || big == "⌫"
            labelPaint.color = bigColor
            labelPaint.textSize = r.height() * if (single) 0.40f else 0.24f
            val cy = if (corner.isNotEmpty()) r.centerY() + r.height() * 0.20f
                     else r.centerY() + labelPaint.textSize * 0.35f
            canvas.drawText(big, r.centerX(), cy, labelPaint)
            if (corner.isNotEmpty()) {
                canvas.drawText(corner, r.left + pad, r.top + pad + subPaint.textSize, subPaint)
            }
        }
    }

    // ---------------------------------------------------------------- touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val key = keyAt(e.getX(i), e.getY(i)) ?: return true
                val id = e.getPointerId(i)
                pointerKeys.put(id, key)
                pointerDownAt.put(id, e.eventTime)
                press(key)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                val key = pointerKeys.get(id)
                val held = e.eventTime - pointerDownAt.get(id, e.eventTime)
                pointerKeys.remove(id)
                pointerDownAt.delete(id)
                if (key != null) release(key, held)
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        return true
    }

    private fun keyAt(x: Float, y: Float): Key? {
        slots.firstOrNull { it.rect.contains(x, y) }?.let { return it.key }
        return slots.minByOrNull {
            val dx = x - it.rect.centerX(); val dy = y - it.rect.centerY(); dx * dx + dy * dy
        }?.key
    }

    private fun press(k: Key) {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        pressedVisual += k
        when {
            k.action != KeyAction.NONE -> { invalidate(); return }
            k.isModifier -> heldMods = heldMods or k.modBit
            else -> {
                dirtyMods = dirtyMods or heldMods
                if (pressedKeys.size < 6) pressedKeys += k
            }
        }
        sendReport()
        invalidate()
    }

    private fun release(k: Key, heldMs: Long) {
        pressedVisual -= k
        when {
            k.action == KeyAction.IME_TOGGLE -> {
                if (heldMs < LONG_PRESS_MS) tapCtrlSpace()   // 長按則只切手機顯示
                mode = if (mode == KbMode.ZHUYIN) KbMode.ALNUM else KbMode.ZHUYIN
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                listener?.onModeChanged(mode)
                return
            }
            k.action != KeyAction.NONE -> {
                invalidate()
                listener?.onKeyAction(k.action)
                return
            }
            k.isModifier -> {
                if ((dirtyMods and k.modBit) == 0) latchedMods = latchedMods xor k.modBit
                dirtyMods = dirtyMods and k.modBit.inv()
                heldMods = heldMods and k.modBit.inv()
            }
            else -> {
                pressedKeys -= k
                if (pressedKeys.isEmpty()) latchedMods = 0
            }
        }
        sendReport()
        invalidate()
    }

    /** 平板的實體鍵盤切換輸入法快速鍵：Ctrl + Space */
    private fun tapCtrlSpace() {
        val l = listener ?: return
        l.onKeyboardReport(Hid.L_CTRL, intArrayOf(Hid.SPACE))
        l.onKeyboardReport(Hid.L_CTRL, IntArray(0))
        sendReport() // 回到目前實際按著的狀態
    }

    private fun sendReport() {
        var mods = heldMods or latchedMods
        for (k in pressedKeys) mods = mods or k.fixedMods
        val codes = pressedKeys.map { it.code }.distinct().toIntArray()
        listener?.onKeyboardReport(mods, codes)
    }

    /** 切換畫面 / App 進背景時呼叫，避免卡鍵 */
    fun releaseAll() {
        pointerKeys.clear()
        pointerDownAt.clear()
        pressedKeys.clear()
        pressedVisual.clear()
        heldMods = 0
        latchedMods = 0
        dirtyMods = 0
        listener?.onKeyboardReport(0, IntArray(0))
        invalidate()
    }

    companion object {
        private const val LONG_PRESS_MS = 450L
        val COLOR_KEY = Color.parseColor("#2B2D31")
        val COLOR_FUNC = Color.parseColor("#1E1F22")
        val COLOR_PRESSED = Color.parseColor("#5865F2")
        val COLOR_LATCHED = Color.parseColor("#3C4A9E")
        val COLOR_ZHUYIN = Color.parseColor("#F2B866")
        val COLOR_ZHUYIN_KEY = Color.parseColor("#8A5A1E")
        val COLOR_ALNUM_KEY = Color.parseColor("#2F6B44")
        val COLOR_GREEN = Color.parseColor("#3BA55C")
        val COLOR_YELLOW = Color.parseColor("#FAA61A")
        val COLOR_RED = Color.parseColor("#ED4245")
    }
}
