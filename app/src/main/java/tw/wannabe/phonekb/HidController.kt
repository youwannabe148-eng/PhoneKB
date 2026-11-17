package tw.wannabe.phonekb

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

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
    }

    val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    var state = State.OFF
        private set
    var connectedDevice: BluetoothDevice? = null
        private set
    var errorText: String = ""
        private set

    private var hid: BluetoothHidDevice? = null
    private var registered = false
    private val main = Handler(Looper.getMainLooper())
    private val callbackExecutor = Executors.newSingleThreadExecutor()
    private val sendExecutor = Executors.newSingleThreadExecutor() // 保證 report 順序
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
            main.post {
                connectedDevice = null
                setState(if (registered) State.READY else State.OFF, null)
            }
        }
    }

    private fun postLeds(v: Int) = main.post { listener.onLeds(v and 0xFF) }

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        val a = adapter
        if (a == null) { fail("這支手機沒有藍牙"); return }
        if (!a.isEnabled) { setState(State.OFF, null); return }
        if (hid != null) { register(); return }
        setState(State.REGISTERING, null)
        val ok = a.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.HID_DEVICE) return
                hid = proxy as BluetoothHidDevice
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
        if (!ok) fail("系統不支援 HID Device（可能被廠商關閉）")
    }

    private fun register() {
        if (registered) return
        val h = hid ?: return
        val ok = h.registerApp(sdp, null, null, callbackExecutor, callback)
        if (!ok) fail("註冊 HID 失敗：可能有其他鍵盤/滑鼠 App 正在占用，請先關掉它")
    }

    fun stop() {
        releaseAll()
        hid?.let {
            if (registered) it.unregisterApp()
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it)
        }
        hid = null
        registered = false
        connectedDevice = null
        state = State.OFF
    }

    fun connect(device: BluetoothDevice) {
        val h = hid ?: return
        setState(State.CONNECTING, device)
        if (!h.connect(device)) setState(State.READY, null)
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
        send(Hid.ID_KEYBOARD, r)
    }

    fun sendMouse(buttons: Int, dx: Int, dy: Int, wheel: Int = 0, pan: Int = 0) {
        send(
            Hid.ID_MOUSE, byteArrayOf(
                buttons.toByte(), clamp(dx), clamp(dy), clamp(wheel), clamp(pan)
            )
        )
    }

    fun releaseAll() {
        if (connectedDevice == null) return
        sendKeyboard(0, IntArray(0))
        sendMouse(0, 0, 0)
    }

    private fun send(id: Int, data: ByteArray) {
        val h = hid ?: return
        val d = connectedDevice ?: return
        sendExecutor.execute {
            try { h.sendReport(d, id, data) } catch (_: Exception) { }
        }
    }

    private fun clamp(v: Int): Byte = v.coerceIn(-127, 127).toByte()

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
    }
}
