package tw.wannabe.phonekb

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 把手機註冊成藍牙 HID 裝置（鍵盤 + 滑鼠），負責連線與送 report。
 * 所有 Listener 回呼都在主執行緒。
 */
@SuppressLint("MissingPermission") // 權限在 MainActivity 先檢查過
class HidController(private val context: Context, private val listener: Listener) {

    enum class State { OFF, REGISTERING, READY, CONNECTING, CONNECTED, ERROR }

    interface Listener {
        fun onHidState(state: State, device: BluetoothDevice?)
        fun onLeds(leds: Int)
        fun onLog() {}
    }

    val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    var state = State.OFF
        private set
    var connectedDevice: BluetoothDevice? = null
        private set
    var errorText: String = ""
        private set

    // ---- 連線紀錄（顯示在 ⚙ 裡，debug 用）----
    private val logLines = ArrayDeque<String>()
    fun logText(): String = synchronized(logLines) { logLines.joinToString("\n") }
    private fun log(msg: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
        synchronized(logLines) {
            logLines.addLast("$t  $msg")
            while (logLines.size > 25) logLines.removeFirst()
        }
        main.post { listener.onLog() }
    }
    private fun nameOf(d: BluetoothDevice?) = d?.let { it.name ?: it.address } ?: "null"
    private var sendFails = 0

    private var hid: BluetoothHidDevice? = null
    private var registered = false
    private val main = Handler(Looper.getMainLooper())
    private val callbackExecutor = Executors.newSingleThreadExecutor()
    // 所有送出都在這條執行緒，保證順序；滑鼠移動在這裡合併、限速
    private val sender = Executors.newSingleThreadScheduledExecutor()
    private val prefs = context.getSharedPreferences("hid", Context.MODE_PRIVATE)

    private val sdp = BluetoothHidDeviceAppSdpSettings(
        "Phone Keyboard",
        "Phone keyboard and touchpad",
        "PhoneKB",
        BluetoothHidDevice.SUBCLASS1_COMBO,
        Hid.DESCRIPTOR
    )

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            log("HID 註冊狀態：${if (registered) "已註冊" else "已取消"}，plugged=${nameOf(pluggedDevice)}")
            main.post {
                this@HidController.registered = registered
                if (registered) {
                    setState(State.READY, null)
                    val target = pluggedDevice ?: lastDevice()
                    if (target != null) connect(target)
                } else {
                    connectedDevice = null
                    setState(State.OFF, null)
                }
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            val st = when (state) {
                BluetoothProfile.STATE_CONNECTED -> "已連線"; BluetoothProfile.STATE_CONNECTING -> "連線中"
                BluetoothProfile.STATE_DISCONNECTING -> "斷線中"; else -> "已斷線"
            }
            log("HID 通道 ${nameOf(device)}：$st")
            main.post {
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        connectedDevice = device
                        prefs.edit().putString(KEY_LAST, device.address).apply()
                        setState(State.CONNECTED, device)
                    }
                    BluetoothProfile.STATE_CONNECTING -> setState(State.CONNECTING, device)
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (connectedDevice == null || connectedDevice?.address == device.address) {
                            connectedDevice = null
                            setState(if (registered) State.READY else State.OFF, null)
                        }
                    }
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            log("對方要求 report id=$id")
            val h = hid ?: return
            when (id.toInt()) {
                Hid.ID_KEYBOARD -> h.replyReport(device, type, id, ByteArray(8))
                Hid.ID_MOUSE -> h.replyReport(device, type, id, ByteArray(5))
                else -> h.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
            }
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
            if (id.toInt() == Hid.ID_KEYBOARD && data.isNotEmpty()) postLeds(data.last().toInt())
        }

        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) {
            if (reportId.toInt() == Hid.ID_KEYBOARD && data.isNotEmpty()) postLeds(data.last().toInt())
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            log("對方拔除虛擬連線：${nameOf(device)}")
            main.post {
                connectedDevice = null
                setState(if (registered) State.READY else State.OFF, null)
            }
        }
    }

    /**
     * 平板從自己的藍牙設定配對 / 連上手機時，很多主機（iPad 尤其）不會主動開 HID 通道，
     * 要由「鍵盤」這端去連。所以監聽配對完成與 ACL 連線，自動呼叫 hid.connect()。
     */
    private var receiverOn = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val dev = deviceOf(i) ?: return
            when (i.action) {
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> log("藍牙底層斷線：${nameOf(dev)}")
                else -> {}
            }
            when (i.action) {
                BluetoothDevice.ACTION_BOND_STATE_CHANGED ->
                    if (i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1) == BluetoothDevice.BOND_BONDED) {
                        log("配對完成：${nameOf(dev)}"); autoConnect(dev)
                    }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    log("藍牙底層連上：${nameOf(dev)}（類別 0x${Integer.toHexString(dev.bluetoothClass?.majorDeviceClass ?: 0)}）")
                    autoConnect(dev)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun deviceOf(i: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)

    private fun isHost(d: BluetoothDevice): Boolean {
        val major = d.bluetoothClass?.majorDeviceClass ?: return true
        return major == BluetoothClass.Device.Major.COMPUTER || major == BluetoothClass.Device.Major.PHONE
    }

    private fun autoConnect(d: BluetoothDevice) {
        if (!registered || connectedDevice != null) return
        if (!isHost(d)) { log("略過自動連線（不是電腦/平板）：${nameOf(d)}"); return }
        if (d.bondState != BluetoothDevice.BOND_BONDED) return
        main.postDelayed({ if (registered && connectedDevice == null) connect(d) }, 1200)
    }

    private fun postLeds(v: Int) = main.post { listener.onLeds(v and 0xFF) }

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        val a = adapter
        if (a == null) { fail("這支手機沒有藍牙"); return }
        if (!a.isEnabled) { setState(State.OFF, null); return }
        if (!receiverOn) {
            val f = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
            else context.registerReceiver(receiver, f)
            receiverOn = true
        }
        if (hid != null) { register(); return }
        setState(State.REGISTERING, null)
        val ok = a.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.HID_DEVICE) return
                hid = proxy as BluetoothHidDevice
                log("HID profile 已取得")
                register()
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile != BluetoothProfile.HID_DEVICE) return
                hid = null
                registered = false
                connectedDevice = null
                main.post { setState(State.OFF, null) }
            }
        }, BluetoothProfile.HID_DEVICE)
        log("取得 HID profile：${if (ok) "要求中" else "失敗"}")
        if (!ok) fail("系統不支援 HID Device（可能被廠商關閉）")
    }

    private var regAttempts = 0

    /**
     * 系統同時只允許一個 HID 裝置 App。App 關掉後很快又打開時，上一次的註冊常常還沒釋放，
     * registerApp 會回 false。這時先 unregisterApp()（同一個 App 可以清掉自己殘留的註冊），
     * 再延遲重試，最多 5 次才判定失敗。
     */
    private fun register() {
        if (registered) return
        val h = hid ?: return
        if (h.registerApp(sdp, null, null, callbackExecutor, callback)) { log("registerApp 送出"); regAttempts = 0; return }
        regAttempts++
        log("registerApp 被拒（第 $regAttempts 次）")
        if (regAttempts == 1) runCatching { h.unregisterApp() }
        if (regAttempts <= 5) {
            setState(State.REGISTERING, null)
            main.postDelayed({ register() }, 400L * regAttempts)
        } else {
            regAttempts = 0
            fail("註冊 HID 失敗：可能有其他鍵盤/滑鼠 App 占用。關掉它，或把手機藍牙關掉再打開")
        }
    }

    fun stop() {
        releaseAll()
        if (receiverOn) { runCatching { context.unregisterReceiver(receiver) }; receiverOn = false }
        hid?.let {
            if (registered) it.unregisterApp()
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it)
        }
        hid = null
        registered = false
        regAttempts = 0
        main.removeCallbacksAndMessages(null)
        connectedDevice = null
        state = State.OFF
    }

    fun connect(device: BluetoothDevice) {
        val h = hid ?: run { log("connect：HID 尚未就緒"); return }
        if (!registered) { log("connect：尚未註冊完成"); return }
        setState(State.CONNECTING, device)
        val ok = h.connect(device)
        log("主動連線 ${nameOf(device)}：${if (ok) "已送出" else "被系統拒絕"}")
        if (!ok) { setState(State.READY, null); return }
        main.postDelayed({
            if (state == State.CONNECTING) {
                log("連線逾時：${nameOf(device)} 10 秒沒有回應")
                setState(State.READY, null)
            }
        }, 10_000)
    }

    fun disconnect() {
        val d = connectedDevice ?: return
        releaseAll()
        hid?.disconnect(d)
    }

    fun bondedDevices(): List<BluetoothDevice> =
        adapter?.bondedDevices?.sortedBy { it.name ?: it.address } ?: emptyList()

    fun deviceName(d: BluetoothDevice?): String = d?.name ?: d?.address ?: ""

    private fun lastDevice(): BluetoothDevice? {
        val addr = prefs.getString(KEY_LAST, null) ?: return null
        return adapter?.bondedDevices?.firstOrNull { it.address == addr }
    }

    // ---------------------------------------------------------------- reports

    fun sendKeyboard(mods: Int, keys: IntArray) {
        val r = ByteArray(8)
        r[0] = mods.toByte()
        for (i in 0 until minOf(6, keys.size)) r[2 + i] = keys[i].toByte()
        sender.execute { lastKb = r; raw(Hid.ID_KEYBOARD, r) }
    }

    // ---- 低延遲模式：連線閒置時藍牙會進入省電（sniff）模式，第一個按鍵要等它醒來 ----
    // App 在前景時，每 KEEPALIVE_MS 重送一次「目前的鍵盤狀態」（內容不變，平板不會有反應），
    // 讓連線保持清醒。代價是比較耗電，App 進背景就停。
    @Volatile var lowLatency = true
    private var lastKb = ByteArray(8)
    private var lastAnySend = 0L
    private var keepAliveTask: java.util.concurrent.ScheduledFuture<*>? = null

    fun setForeground(fg: Boolean) {
        sender.execute {
            keepAliveTask?.cancel(false); keepAliveTask = null
            if (fg) keepAliveTask = sender.scheduleWithFixedDelay({
                if (lowLatency && connectedDevice != null &&
                    System.currentTimeMillis() - lastAnySend >= KEEPALIVE_MS) raw(Hid.ID_KEYBOARD, lastKb)
            }, KEEPALIVE_MS, KEEPALIVE_MS, TimeUnit.MILLISECONDS)
        }
    }

    // ---- 滑鼠移動：重新取樣（resampling）----
    /**
     * 為什麼不能「收到就送」或「累積到時間就送」：
     *  1. 觸控（約 120Hz）、陀螺儀（約 50Hz）的事件間隔本身會抖，跟送出的節拍對不齊，
     *     每包的移動量就會忽大忽小 → 游標跳。
     *  2. 送得比 iPad 螢幕的 60Hz 還慢（例如 50Hz）→ 有些畫面格完全沒收到移動 → 一頓一頓。
     * 做法：把輸入記成「(時間, 累積位置)」的軌跡，固定每 mouseIntervalMs 一拍，
     *       取「RESAMPLE_DELAY 之前」那一刻的內插位置，送出和上一拍的差。
     *       每包移動量 ≈ 速度 × 間隔，非常平均；代價是多約 30ms 延遲。
     * 按鍵（點擊、拖曳開始/結束）不等節拍：先把軌跡全部補送完，再立刻送按鍵狀態。
     * 滾輪也立刻送。
     */
    @Volatile var mouseIntervalMs = 12L
    private class Sample(val t: Long, val x: Long, val y: Long)
    private val samples = ArrayDeque<Sample>()
    private var cumX = 0L; private var cumY = 0L   // 輸入的累積位置
    private var outX = 0L; private var outY = 0L   // 已送出的累積位置
    private var lastInputNs = 0L
    private var ticker: java.util.concurrent.ScheduledFuture<*>? = null
    private var curButtons = 0

    fun sendMouse(buttons: Int, dx: Int, dy: Int, wheel: Int = 0, pan: Int = 0) {
        sender.execute {
            val now = System.nanoTime()
            if (buttons != curButtons) {
                addMotion(now, dx, dy)
                catchUp()                    // 舊按鍵狀態下的移動全部送完
                curButtons = buttons
                rawMouse(0, 0, wheel, pan)   // 立刻送新的按鍵狀態
                return@execute
            }
            if (wheel != 0 || pan != 0) rawMouse(0, 0, wheel, pan)
            if (dx != 0 || dy != 0) {
                addMotion(now, dx, dy)
                ensureTicker()
            }
        }
    }

    private fun addMotion(now: Long, dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        if (samples.isEmpty() || now - lastInputNs > IDLE_GAP_NS) {
            // 停了一陣子才又開始動：補一個起點，讓第一段移動也平均分散送出
            samples.clear()
            samples.addLast(Sample(now - START_SPREAD_NS, cumX, cumY))
        }
        cumX += dx; cumY += dy
        samples.addLast(Sample(now, cumX, cumY))
        lastInputNs = now
    }

    /** 軌跡在時間 t 的位置（線性內插） */
    private fun posAt(t: Long): DoubleArray {
        if (samples.isEmpty()) return doubleArrayOf(cumX.toDouble(), cumY.toDouble())
        val first = samples.first()
        val last = samples.last()
        if (t <= first.t) return doubleArrayOf(first.x.toDouble(), first.y.toDouble())
        if (t >= last.t) return doubleArrayOf(last.x.toDouble(), last.y.toDouble())
        for (i in 0 until samples.size - 1) {
            val a = samples[i]
            val b = samples[i + 1]
            if (t < b.t) {
                val k = (t - a.t).toDouble() / (b.t - a.t).coerceAtLeast(1L).toDouble()
                return doubleArrayOf(a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k)
            }
        }
        return doubleArrayOf(last.x.toDouble(), last.y.toDouble())
    }

    private fun ensureTicker() {
        if (ticker != null) return
        val period = mouseIntervalMs.coerceIn(5L, 50L)
        ticker = sender.scheduleAtFixedRate({
            try { tick() } catch (_: Exception) { }   // 例外會讓 fixedRate 永久停止，一定要接住
        }, 0L, period, TimeUnit.MILLISECONDS)
    }

    private fun tick() {
        val now = System.nanoTime()
        val t = now - RESAMPLE_DELAY_NS
        val p = posAt(t)
        val dx = (Math.round(p[0]) - outX).coerceIn(-127L, 127L)
        val dy = (Math.round(p[1]) - outY).coerceIn(-127L, 127L)
        if (dx != 0L || dy != 0L) {
            outX += dx; outY += dy
            rawMouse(dx.toInt(), dy.toInt(), 0, 0)
        }
        // 丟掉用不到的舊樣本（保留 t 之前的最後一筆供內插）
        while (samples.size > 2 && samples[1].t <= t) samples.removeFirst()
        // 輸入停了、也全部送完 → 停止節拍（省電，也讓低延遲模式的 keepalive 接手）
        if (now - lastInputNs > RESAMPLE_DELAY_NS + IDLE_GAP_NS && outX == cumX && outY == cumY) {
            ticker?.cancel(false)
            ticker = null
        }
    }

    /** 把還沒送的移動立刻全部送完（按鍵狀態改變前用） */
    private fun catchUp() {
        while (outX != cumX || outY != cumY) {
            val dx = (cumX - outX).coerceIn(-127L, 127L)
            val dy = (cumY - outY).coerceIn(-127L, 127L)
            outX += dx; outY += dy
            rawMouse(dx.toInt(), dy.toInt(), 0, 0)
        }
        samples.clear()
    }

    private fun rawMouse(dx: Int, dy: Int, wheel: Int, pan: Int) {
        raw(Hid.ID_MOUSE, byteArrayOf(
            curButtons.toByte(), dx.toByte(), dy.toByte(),
            wheel.coerceIn(-127, 127).toByte(), pan.coerceIn(-127, 127).toByte()
        ))
    }

    fun releaseAll() {
        if (connectedDevice == null) return
        sendKeyboard(0, IntArray(0))
        sendMouse(0, 0, 0)
    }

    /** 只在 sender 執行緒呼叫 */
    private fun raw(id: Int, data: ByteArray) {
        val h = hid ?: return
        val d = connectedDevice ?: return
        val ok = try { h.sendReport(d, id, data) } catch (_: Exception) { false }
        if (!ok && sendFails++ % 50 == 0) log("sendReport 失敗（累計 $sendFails 次）")
        lastAnySend = System.currentTimeMillis()
    }

    private fun fail(msg: String) {
        errorText = msg
        setState(State.ERROR, null)
    }

    private fun setState(s: State, d: BluetoothDevice?) {
        state = s
        if (s != State.ERROR) errorText = ""
        listener.onHidState(s, d)
    }

    companion object {
        private const val KEY_LAST = "last_device"
        private const val KEEPALIVE_MS = 80L
        private const val RESAMPLE_DELAY_NS = 30_000_000L   // 30ms：要大於最慢輸入（陀螺儀約 20ms）的間隔
        private const val IDLE_GAP_NS = 100_000_000L        // 停 100ms 以上算新的一段移動
        private const val START_SPREAD_NS = 16_000_000L
    }
}
