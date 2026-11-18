package tw.wannabe.phonekb

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

@SuppressLint("MissingPermission")
class MainActivity : Activity(), HidController.Listener {

    private lateinit var hid: HidController
    private lateinit var keyboard: KeyboardView
    private lateinit var touchpad: TouchpadView
    private lateinit var airMouse: AirMouseView
    private val useAirMouse get() = prefs.getString("mouse_mode", "pad") == "air"
    private val prefs by lazy { getSharedPreferences("ui", MODE_PRIVATE) }
    private var settingsDialog: AlertDialog? = null
    private var statusText: TextView? = null
    private var logView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        hid = HidController(applicationContext, this)
        hid.lowLatency = prefs.getBoolean("lowlat", true)
        hid.mouseIntervalMs = 1000L / prefs.getInt("mouse_hz", 50)

        keyboard = KeyboardView(this).apply {
            mode = runCatching { KbMode.valueOf(prefs.getString("mode", "ZHUYIN")!!) }
                .getOrDefault(KbMode.ZHUYIN)
            listener = object : KeyboardView.Listener {
                override fun onKeyboardReport(mods: Int, keys: IntArray) = hid.sendKeyboard(mods, keys)
                override fun onKeyAction(action: KeyAction) = handleAction(action)
                override fun onModeChanged(mode: KbMode) {
                    prefs.edit().putString("mode", mode.name).apply()
                }
            }
        }
        touchpad = TouchpadView(this).apply {
            sensitivity = prefs.getFloat("sens2", 1.2f)
            invertScroll = prefs.getBoolean("invert", false)
            acceleration = prefs.getBoolean("accel", false)
            visibility = View.GONE
            listener = object : TouchpadView.Listener {
                override fun onMouseReport(buttons: Int, dx: Int, dy: Int, wheel: Int, pan: Int) =
                    hid.sendMouse(buttons, dx, dy, wheel, pan)
                override fun onKeyAction(action: KeyAction) = handleAction(action)
            }
        }

        airMouse = AirMouseView(this).apply {
            sensitivity = prefs.getFloat("air_sens", 1.0f)
            invertScroll = prefs.getBoolean("invert", false)
            visibility = View.GONE
            listener = object : TouchpadView.Listener {
                override fun onMouseReport(buttons: Int, dx: Int, dy: Int, wheel: Int, pan: Int) =
                    hid.sendMouse(buttons, dx, dy, wheel, pan)
                override fun onKeyAction(action: KeyAction) = handleAction(action)
            }
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(keyboard, FrameLayout.LayoutParams(-1, -1))
            addView(touchpad, FrameLayout.LayoutParams(-1, -1))
            addView(airMouse, FrameLayout.LayoutParams(-1, -1))
        }
        // 避開鏡頭挖孔（橫向時在左右邊）
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val c = insets.getInsets(WindowInsets.Type.displayCutout())
                v.setPadding(c.left, c.top, c.right, c.bottom)
            } else {
                val c = insets.displayCutout
                v.setPadding(c?.safeInsetLeft ?: 0, c?.safeInsetTop ?: 0,
                    c?.safeInsetRight ?: 0, c?.safeInsetBottom ?: 0)
            }
            insets
        }
        setContentView(root)
        hideSystemBars()
        ensurePermissionsThenStart()
    }

    // ---------------------------------------------------------------- permissions / BT

    private fun neededPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        else emptyArray()

    private fun hasPermissions() = neededPermissions().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensurePermissionsThenStart() {
        val missing = neededPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_PERM)
        else startBluetooth()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (code != REQ_PERM) return
        if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) {
            startBluetooth()
        } else {
            statusText?.text = statusString()
            // 被拒絕且勾了「不再詢問」→ 系統不會再跳視窗，直接帶去 App 設定頁
            val blocked = perms.any { !shouldShowRequestPermissionRationale(it) }
            if (blocked) {
                toast("請在「權限 › 附近的裝置」選允許（不是定位）")
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)))
            } else {
                toast("需要「附近的裝置」權限才能當藍牙鍵盤（不需要定位）")
            }
        }
    }

    private fun startBluetooth() {
        val a = hid.adapter ?: run { toast("這支手機沒有藍牙"); return }
        if (!a.isEnabled) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE)
            return
        }
        hid.start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ENABLE && resultCode == RESULT_OK) hid.start()
    }

    private fun makeDiscoverable() {
        val i = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_DISCOVERABLE)
    }

    // ---------------------------------------------------------------- HID callbacks

    override fun onHidState(state: HidController.State, device: BluetoothDevice?) {
        val color = when (state) {
            HidController.State.CONNECTED -> KeyboardView.COLOR_GREEN
            HidController.State.CONNECTING, HidController.State.REGISTERING,
            HidController.State.READY -> KeyboardView.COLOR_YELLOW
            else -> KeyboardView.COLOR_RED
        }
        keyboard.statusColor = color
        touchpad.statusColor = color
        airMouse.statusColor = color
        statusText?.text = statusString()
        if (state == HidController.State.CONNECTED) toast("已連線：${hid.deviceName(device)}")
        if (state == HidController.State.ERROR) toast(hid.errorText)
    }

    override fun onLog() {
        logView?.text = hid.logText()
    }

    override fun onLeds(leds: Int) {
        keyboard.capsOn = (leds and 0x02) != 0
    }

    private fun statusString(): String = when (hid.state) {
        HidController.State.CONNECTED -> "● 已連線：${hid.deviceName(hid.connectedDevice)}"
        HidController.State.CONNECTING -> "● 連線中…"
        HidController.State.READY -> "● 等待連線（第一次請先配對）"
        HidController.State.REGISTERING -> "● 啟動中…"
        HidController.State.ERROR -> "● 錯誤：${hid.errorText}"
        HidController.State.OFF ->
            if (!hasPermissions()) "● 未啟動：缺少「附近的裝置」權限（按下方按鈕）" else "● 藍牙未啟動"
    }

    // ---------------------------------------------------------------- UI

    private fun handleAction(action: KeyAction) {
        when (action) {
            KeyAction.TOUCHPAD -> {
                keyboard.releaseAll()
                keyboard.visibility = View.GONE
                if (useAirMouse) {
                    // 體感滑鼠：手機直拿，像遙控器一樣指著螢幕
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    airMouse.visibility = View.VISIBLE
                    airMouse.start()
                    if (!airMouse.hasGyro) toast("這支手機沒有陀螺儀，請改用觸控板")
                } else {
                    touchpad.visibility = View.VISIBLE
                }
            }
            KeyAction.KEYBOARD -> {
                touchpad.releaseAll()
                touchpad.visibility = View.GONE
                airMouse.stop()
                airMouse.visibility = View.GONE
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                keyboard.visibility = View.VISIBLE
            }
            KeyAction.SETTINGS -> showSettings()
            else -> {}
        }
    }

    private fun showSettings() {
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun label(t: String, size: Float = 14f) = TextView(this).apply {
            text = t; textSize = size; setPadding(0, pad / 2, 0, pad / 4)
        }

        statusText = label(statusString(), 16f)
        box.addView(statusText)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "讓平板找到我（配對）"
            setOnClickListener { makeDiscoverable() }
        })
        row.addView(Button(this).apply {
            text = "中斷連線"
            setOnClickListener { hid.disconnect() }
        })
        row.addView(Button(this).apply {
            text = "重新啟動藍牙鍵盤"
            setOnClickListener { hid.stop(); hid.start() }
        })
        box.addView(row)
        if (!hasPermissions()) {
            box.addView(Button(this).apply {
                text = "開啟「附近的裝置」權限"
                setOnClickListener { ensurePermissionsThenStart() }
            })
        }

        box.addView(label("已配對裝置（點一下連線）："))
        val bonded = hid.bondedDevices()
        if (bonded.isEmpty()) box.addView(label("（還沒有，先按上面的配對）"))
        for (d in bonded) {
            box.addView(Button(this).apply {
                text = hid.deviceName(d)
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener { hid.connect(d) }
            })
        }

        val sensLabel = label("")
        fun updSens(v: Float) { sensLabel.text = "觸控板靈敏度：%.1f".format(v) }
        updSens(touchpad.sensitivity)
        box.addView(sensLabel)
        box.addView(SeekBar(this).apply {
            max = 35  // 0.5 ~ 4.0
            progress = ((touchpad.sensitivity - 0.5f) * 10).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    val v = 0.5f + p / 10f
                    touchpad.sensitivity = v
                    updSens(v)
                    prefs.edit().putFloat("sens2", v).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        })
        box.addView(CheckBox(this).apply {
            text = "低延遲模式（保持藍牙清醒，較耗電）"
            isChecked = hid.lowLatency
            setOnCheckedChangeListener { _, c ->
                hid.lowLatency = c; prefs.edit().putBoolean("lowlat", c).apply()
            }
        })
        box.addView(CheckBox(this).apply {
            text = "觸控板加速度（iPad 本身已有加速，通常不用開）"
            isChecked = touchpad.acceleration
            setOnCheckedChangeListener { _, c ->
                touchpad.acceleration = c; prefs.edit().putBoolean("accel", c).apply()
            }
        })
        box.addView(CheckBox(this).apply {
            text = "反轉捲動方向"
            isChecked = touchpad.invertScroll
            setOnCheckedChangeListener { _, c ->
                touchpad.invertScroll = c; airMouse.invertScroll = c
                prefs.edit().putBoolean("invert", c).apply()
            }
        })

        // ---- 滑鼠回報率 ----
        val hzLabel = label("")
        fun updHz(hz: Int) { hzLabel.text = "滑鼠回報率：$hz 次/秒（延遲大就調低，不順就調高）" }
        val hz0 = prefs.getInt("mouse_hz", 50)
        updHz(hz0)
        box.addView(hzLabel)
        box.addView(SeekBar(this).apply {
            max = 18  // 30 ~ 120，每格 5
            progress = (hz0 - 30) / 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    val hz = 30 + p * 5
                    hid.mouseIntervalMs = 1000L / hz
                    updHz(hz)
                    prefs.edit().putInt("mouse_hz", hz).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        })

        // ---- 滑鼠模式 ----
        box.addView(label("按 🖱 時使用：", 15f))
        val rg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rbPad = RadioButton(this).apply { text = "觸控板"; id = View.generateViewId() }
        val rbAir = RadioButton(this).apply { text = "體感滑鼠（轉動手機移動）"; id = View.generateViewId() }
        rg.addView(rbPad); rg.addView(rbAir)
        rg.check(if (useAirMouse) rbAir.id else rbPad.id)
        rg.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putString("mouse_mode", if (checked == rbAir.id) "air" else "pad").apply()
        }
        box.addView(rg)
        val airLabel = label("")
        fun updAir(v: Float) { airLabel.text = "體感滑鼠靈敏度：%.1f".format(v) }
        updAir(airMouse.sensitivity)
        box.addView(airLabel)
        box.addView(SeekBar(this).apply {
            max = 25  // 0.3 ~ 2.8
            progress = ((airMouse.sensitivity - 0.3f) * 10).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    val v = 0.3f + p / 10f
                    airMouse.sensitivity = v
                    updAir(v)
                    prefs.edit().putFloat("air_sens", v).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        })
        box.addView(label("連線紀錄（有問題時截圖這段）："))
        logView = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            text = hid.logText()
        }
        box.addView(logView)
        box.addView(label(
            "第一次使用：按「讓平板找到我」→ 平板 設定 › 藍牙 → 點這支手機配對。\n" +
            "之後打開 App 會自動重連上一台。\n" +
            "注音 / 英數：點【中/英】會同時切換手機顯示和平板輸入法（送 Ctrl+Space）。\n" +
            "如果兩邊對不上（手機顯示英數、平板卻打出注音），長按【中/英】只切手機，不送鍵。\n" +
            "平板「設定 › 一般 › 鍵盤 › 鍵盤」只留 English 和 注音 兩個，切換才不會跑到第三種。", 12f))

        settingsDialog = AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("關閉", null)
            .setOnDismissListener { statusText = null; logView = null; hideSystemBars() }
            .show()
    }

    // ---------------------------------------------------------------- system UI

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        if (hid.state == HidController.State.OFF && hasPermissions() && hid.adapter?.isEnabled == true) hid.start()
        hid.setForeground(true)
        if (airMouse.visibility == View.VISIBLE) airMouse.start()
    }

    override fun onPause() {
        super.onPause()
        hid.setForeground(false)
        keyboard.releaseAll()
        touchpad.releaseAll()
        airMouse.stop()
    }

    override fun onDestroy() {
        settingsDialog?.dismiss()
        hid.stop()
        super.onDestroy()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_PERM = 1
        private const val REQ_ENABLE = 2
        private const val REQ_DISCOVERABLE = 3
    }
}
