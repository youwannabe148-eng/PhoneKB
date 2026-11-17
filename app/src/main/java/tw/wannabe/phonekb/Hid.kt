package tw.wannabe.phonekb

/**
 * HID 報告格式（複合裝置：鍵盤 + 滑鼠）
 *
 * Report ID 1 鍵盤，8 bytes：
 *   [0] modifier bitmask (LCtrl LShift LAlt LGUI RCtrl RShift RAlt RGUI)
 *   [1] reserved
 *   [2..7] 最多 6 個同時按下的 keycode
 *   Output report（主機 → 手機）：1 byte LED（bit0 NumLock, bit1 CapsLock ...）
 *
 * Report ID 2 滑鼠，5 bytes：
 *   [0] buttons (bit0 左, bit1 右, bit2 中)
 *   [1] dx  int8 相對
 *   [2] dy  int8 相對
 *   [3] wheel int8（正 = 往上捲）
 *   [4] AC Pan int8（水平捲動，正 = 往右）
 */
object Hid {
    const val ID_KEYBOARD = 1
    const val ID_MOUSE = 2

    val DESCRIPTOR: ByteArray = bytes(
        // ---------- Keyboard ----------
        0x05, 0x01,       // Usage Page (Generic Desktop)
        0x09, 0x06,       // Usage (Keyboard)
        0xA1, 0x01,       // Collection (Application)
        0x85, ID_KEYBOARD, //   Report ID (1)
        0x05, 0x07,       //   Usage Page (Keyboard/Keypad)
        0x19, 0xE0,       //   Usage Minimum (Left Control)
        0x29, 0xE7,       //   Usage Maximum (Right GUI)
        0x15, 0x00,       //   Logical Minimum (0)
        0x25, 0x01,       //   Logical Maximum (1)
        0x75, 0x01,       //   Report Size (1)
        0x95, 0x08,       //   Report Count (8)
        0x81, 0x02,       //   Input (Data,Var,Abs)    modifiers
        0x95, 0x01,       //   Report Count (1)
        0x75, 0x08,       //   Report Size (8)
        0x81, 0x01,       //   Input (Const)           reserved
        0x95, 0x05,       //   Report Count (5)
        0x75, 0x01,       //   Report Size (1)
        0x05, 0x08,       //   Usage Page (LEDs)
        0x19, 0x01,       //   Usage Minimum (Num Lock)
        0x29, 0x05,       //   Usage Maximum (Kana)
        0x91, 0x02,       //   Output (Data,Var,Abs)   LEDs
        0x95, 0x01,       //   Report Count (1)
        0x75, 0x03,       //   Report Size (3)
        0x91, 0x01,       //   Output (Const)          padding
        0x95, 0x06,       //   Report Count (6)
        0x75, 0x08,       //   Report Size (8)
        0x15, 0x00,       //   Logical Minimum (0)
        0x26, 0xFF, 0x00, //   Logical Maximum (255)
        0x05, 0x07,       //   Usage Page (Keyboard/Keypad)
        0x19, 0x00,       //   Usage Minimum (0)
        0x2A, 0xFF, 0x00, //   Usage Maximum (255)
        0x81, 0x00,       //   Input (Data,Array,Abs)  6 keys
        0xC0,             // End Collection

        // ---------- Mouse ----------
        0x05, 0x01,       // Usage Page (Generic Desktop)
        0x09, 0x02,       // Usage (Mouse)
        0xA1, 0x01,       // Collection (Application)
        0x85, ID_MOUSE,   //   Report ID (2)
        0x09, 0x01,       //   Usage (Pointer)
        0xA1, 0x00,       //   Collection (Physical)
        0x05, 0x09,       //     Usage Page (Button)
        0x19, 0x01,       //     Usage Minimum (1)
        0x29, 0x03,       //     Usage Maximum (3)
        0x15, 0x00,       //     Logical Minimum (0)
        0x25, 0x01,       //     Logical Maximum (1)
        0x95, 0x03,       //     Report Count (3)
        0x75, 0x01,       //     Report Size (1)
        0x81, 0x02,       //     Input (Data,Var,Abs)  buttons
        0x95, 0x01,       //     Report Count (1)
        0x75, 0x05,       //     Report Size (5)
        0x81, 0x01,       //     Input (Const)         padding
        0x05, 0x01,       //     Usage Page (Generic Desktop)
        0x09, 0x30,       //     Usage (X)
        0x09, 0x31,       //     Usage (Y)
        0x09, 0x38,       //     Usage (Wheel)
        0x15, 0x81,       //     Logical Minimum (-127)
        0x25, 0x7F,       //     Logical Maximum (127)
        0x75, 0x08,       //     Report Size (8)
        0x95, 0x03,       //     Report Count (3)
        0x81, 0x06,       //     Input (Data,Var,Rel)  X, Y, Wheel
        0x05, 0x0C,       //     Usage Page (Consumer)
        0x0A, 0x38, 0x02, //     Usage (AC Pan)
        0x15, 0x81,       //     Logical Minimum (-127)
        0x25, 0x7F,       //     Logical Maximum (127)
        0x75, 0x08,       //     Report Size (8)
        0x95, 0x01,       //     Report Count (1)
        0x81, 0x06,       //     Input (Data,Var,Rel)  Pan
        0xC0,             //   End Collection
        0xC0              // End Collection
    )

    // modifier bits
    const val L_CTRL = 0x01
    const val L_SHIFT = 0x02
    const val L_ALT = 0x04
    const val L_GUI = 0x08
    const val R_SHIFT = 0x20

    // 常用 keycode（HID Usage Table, page 0x07）
    const val ENTER = 0x28
    const val ESC = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val SPACE = 0x2C
    const val CAPS = 0x39
    const val RIGHT = 0x4F
    const val LEFT = 0x50
    const val DOWN = 0x51
    const val UP = 0x52

    fun letter(c: Char): Int = 0x04 + (c.lowercaseChar() - 'a')

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}
