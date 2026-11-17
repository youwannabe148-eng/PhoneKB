package tw.wannabe.phonekb

enum class KeyAction { NONE, TOUCHPAD, SETTINGS, KEYBOARD, IME_TOGGLE }

/**
 * 鍵盤模式（按鍵位置一樣，只換顯示）：
 *  ZHUYIN 注音：按鍵大字顯示注音，角落小字顯示英文/數字
 *  ALNUM  英數：按鍵大字顯示英文字母和數字，角落小字顯示 Shift 符號
 */
enum class KbMode { ZHUYIN, ALNUM }

/**
 * @param label     英文/數字標籤
 * @param code      HID keycode（0 = 不送鍵）
 * @param shift     Shift 後的符號
 * @param zhuyin    注音（大千標準排列）
 * @param modBit    非 0 表示這是修飾鍵
 * @param fixedMods 按這顆時固定附加的修飾鍵
 * @param w         寬度（單位：一般鍵 = 1）
 */
data class Key(
    val label: String,
    val code: Int = 0,
    val shift: String = "",
    val zhuyin: String = "",
    val modBit: Int = 0,
    val fixedMods: Int = 0,
    val w: Float = 1f,
    val action: KeyAction = KeyAction.NONE,
) {
    val isModifier get() = modBit != 0
}

object KeyboardLayout {

    private fun ch(c: Char, zy: String) = Key(c.uppercase(), Hid.letter(c), "", zy)
    private fun k(label: String, code: Int, shift: String, zy: String = "") = Key(label, code, shift, zy)

    /** 每一列總寬度都是 15 單位 */
    val ROWS: List<List<Key>> = listOf(
        listOf(
            k("`", 0x35, "~"),
            k("1", 0x1E, "!", "ㄅ"), k("2", 0x1F, "@", "ㄉ"), k("3", 0x20, "#", "ˇ"),
            k("4", 0x21, "\$", "ˋ"), k("5", 0x22, "%", "ㄓ"), k("6", 0x23, "^", "ˊ"),
            k("7", 0x24, "&", "˙"), k("8", 0x25, "*", "ㄚ"), k("9", 0x26, "(", "ㄞ"),
            k("0", 0x27, ")", "ㄢ"), k("-", 0x2D, "_", "ㄦ"), k("=", 0x2E, "+"),
            Key("⌫", Hid.BACKSPACE, w = 2f),
        ),
        listOf(
            Key("Tab", Hid.TAB, w = 1.5f),
            ch('q', "ㄆ"), ch('w', "ㄊ"), ch('e', "ㄍ"), ch('r', "ㄐ"), ch('t', "ㄔ"),
            ch('y', "ㄗ"), ch('u', "ㄧ"), ch('i', "ㄛ"), ch('o', "ㄟ"), ch('p', "ㄣ"),
            k("[", 0x2F, "{"), k("]", 0x30, "}"),
            Key("\\", 0x31, "|", w = 1.5f),
        ),
        listOf(
            Key("Caps", Hid.CAPS, w = 1.75f),
            ch('a', "ㄇ"), ch('s', "ㄋ"), ch('d', "ㄎ"), ch('f', "ㄑ"), ch('g', "ㄕ"),
            ch('h', "ㄘ"), ch('j', "ㄨ"), ch('k', "ㄜ"), ch('l', "ㄠ"),
            k(";", 0x33, ":", "ㄤ"), k("'", 0x34, "\""),
            Key("Enter", Hid.ENTER, w = 2.25f),
        ),
        listOf(
            Key("Shift", modBit = Hid.L_SHIFT, w = 2.25f),
            ch('z', "ㄈ"), ch('x', "ㄌ"), ch('c', "ㄏ"), ch('v', "ㄒ"), ch('b', "ㄖ"),
            ch('n', "ㄙ"), ch('m', "ㄩ"),
            k(",", 0x36, "<", "ㄝ"), k(".", 0x37, ">", "ㄡ"), k("/", 0x38, "?", "ㄥ"),
            Key("Shift", modBit = Hid.R_SHIFT, w = 2.75f),
        ),
        listOf(
            Key("⚙", action = KeyAction.SETTINGS, w = 1f),
            Key("🖱", action = KeyAction.TOUCHPAD, w = 1.25f),
            Key("Ctrl", modBit = Hid.L_CTRL, w = 1.25f),
            Key("⌥", modBit = Hid.L_ALT, w = 1.25f),
            Key("⌘", modBit = Hid.L_GUI, w = 1.25f),
            // 點一下：手機切換 注音/英數 + 送 Ctrl+Space 讓平板一起切
            // 長按：只切手機顯示（兩邊對不齊時用）
            Key("中/英", action = KeyAction.IME_TOGGLE, w = 1.25f),
            Key("space", Hid.SPACE, w = 3.75f),
            Key("←", Hid.LEFT), Key("↑", Hid.UP), Key("↓", Hid.DOWN), Key("→", Hid.RIGHT),
        ),
    )
}
