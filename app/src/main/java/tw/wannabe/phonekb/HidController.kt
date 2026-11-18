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

    // ---- 滑鼠：移動量先累積，最快每 MOUSE_INTERVAL_MS 送一包 ----
    // 觸控事件一秒 100+ 次，每次都送會讓藍牙佇列塞車 → 延遲越積越大。
    // 按鍵狀態改變（點擊、拖曳）則立刻送，不等。
    private var accDx = 0; private var accDy = 0; private var accWheel = 0; private var accPan = 0
    private var curButtons = 0
    private var flushScheduled = false
    private var lastMouseSend = 0L

    fun sendMouse(buttons: Int, dx: Int, dy: Int, wheel: Int = 0, pan: Int = 0) {
        sender.execute {
            if (buttons != curButtons) {
                flushMouse()                 // 先把舊按鍵狀態下的移動送完
                curButtons = buttons
                accDx += dx; accDy += dy; accWheel += wheel; accPan += pan
                flushMouse(force = true)     // 按鍵變化立刻送
            } else {
                accDx += dx; accDy += dy; accWheel += wheel; accPan += pan
                scheduleMouse()
            }
        }
    }

    private fun scheduleMouse() {
        if (flushScheduled) return
        flushScheduled = true
        val wait = (lastMouseSend + MOUSE_INTERVAL_MS - System.currentTimeMillis()).coerceAtLeast(0)
        sender.schedule({ flushScheduled = false; flushMouse() }, wait, TimeUnit.MILLISECONDS)
    }

    private fun flushMouse(force: Boolean = false) {
        if (!force && accDx == 0 && accDy == 0 && accWheel == 0 && accPan == 0) return
        val sx = accDx.coerceIn(-127, 127); val sy = accDy.coerceIn(-127, 127)
        val sw = accWheel.coerceIn(-127, 127); val sp = accPan.coerceIn(-127, 127)
        accDx -= sx; accDy -= sy; accWheel -= sw; accPan -= sp
        raw(Hid.ID_MOUSE, byteArrayOf(curButtons.toByte(), sx.toByte(), sy.toByte(), sw.toByte(), sp.toByte()))
        lastMouseSend = System.currentTimeMillis()
        if (accDx != 0 || accDy != 0 || accWheel != 0 || accPan != 0) scheduleMouse()
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
        private const val MOUSE_INTERVAL_MS = 12L   // ≈ 83 Hz，接近實體藍牙滑鼠
        private const val KEEPALIVE_MS = 80L
    }
}
