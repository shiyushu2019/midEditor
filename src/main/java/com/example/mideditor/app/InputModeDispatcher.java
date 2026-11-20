package com.example.mideditor.app;

import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

import com.example.mideditor.command.TextFocusGuard;
import com.example.mideditor.document.MidiDocumentView;

/**
 * 输入模式的按键接管。
 *
 * <p>
 * 输入模式打开时（编辑 → 输入模式 / Ctrl+I），画布上有一个红色圆点光标：
 * </p>
 * <ul>
 * <li><b>W / S</b>：光标上移 / 下移半音；</li>
 * <li><b>Ctrl+U / Ctrl+L</b>：光标上移 / 下移一个八度；</li>
 * <li><b>A / D</b>：光标左移 / 右移一格（当前网格分辨率）；</li>
 * <li><b>Q</b>：用当前音轨音色试听光标处的音高；</li>
 * <li><b>空格</b>：按下开始计时、松手落成一条平行线 —— <b>按住多久，音符就有多长</b>
 * （按当前速度配置换算成 tick，走的就是这段真实时间）；</li>
 * <li><b>小键盘 0</b>：休止符（简谱的 0）—— 按住多久就留多长的空；</li>
 * <li><b>小键盘 1~7</b>：简谱的 do/re/mi/fa/sol/la/si —— 按下就把光标切到那个音高并
 * 立刻开始输入（不用先按空格），松手同样落成平行线；八度取光标的当前八度；</li>
 * <li><b>小键盘 + / -</b>：八度偏移 ±1，只作用于接下来按下的那个数字，输完一个音就归零；</li>
 * <li><b>按住大键盘 +/=</b>（或 <b>-/_</b>）：紧接着按下的那个数字高（低）一个八度，
 * 用完自动回到原八度（不用按反方向键扳回来）；同时按着 <b>Ctrl</b> 则高（低）两个八度；</li>
 * <li><b>Esc</b>：退出输入模式。</li>
 * </ul>
 *
 * <p>
 * 之所以用全局 {@link KeyEventDispatcher} 而不是画布上的键绑定：焦点可能停在工具栏 / 状态栏，
 * 那时 Swing 的键绑定根本不触发；另外输入模式下空格必须优先于「播放/暂停」，
 * 这里消费掉事件就不会再落到命令系统里。
 * </p>
 */
public final class InputModeDispatcher implements KeyEventDispatcher {

    private final AppContext app;

    public InputModeDispatcher(AppContext app) {
        this.app = app;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        TextFocusGuard.noteKeyboardEvent(e);
        if (TextFocusGuard.isTextEditing()) {
            return false;
        }
        MidiDocumentView view = app.documents().activeView();
        if (view == null || !view.inputMode()) {
            return false;
        }
        if (!isMainWindowActive()) {
            return false;
        }
        if (e.getID() == KeyEvent.KEY_RELEASED) {
            // 松手一律收尾（哪怕期间按下了 Ctrl），否则会一直停在“按住”状态
            int code = e.getKeyCode();
            if (code == KeyEvent.VK_SPACE
                    || (code >= KeyEvent.VK_NUMPAD0 && code <= KeyEvent.VK_NUMPAD9)) {
                // 空格 / 小键盘数字都是“按住输入”，只收自己按下的那个
                view.endInputNoteFromKey(code);
                e.consume();
                return true;
            }
            if (code == KeyEvent.VK_EQUALS || code == KeyEvent.VK_MINUS) {
                // 松开大键盘 +/= 或 -/_：撤掉还没用掉的临时八度偏移
                view.releaseInputOctaveHold(code);
                e.consume();
                return true;
            }
            if (code == KeyEvent.VK_Q) {
                view.endInputAudition();
                e.consume();
                return true;
            }
            return false;
        }
        if (e.getID() != KeyEvent.KEY_PRESSED) {
            return false;
        }
        int code = e.getKeyCode();
        boolean ctrl = (e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0;
        boolean shift = (e.getModifiersEx() & InputEvent.SHIFT_DOWN_MASK) != 0;
        // Alt/Meta 组合键一律留给命令系统（Alt+方向键等）
        if ((e.getModifiersEx() & (InputEvent.ALT_DOWN_MASK | InputEvent.META_DOWN_MASK)) != 0) {
            return false;
        }
        boolean numpadDegree = code >= KeyEvent.VK_NUMPAD0 && code <= KeyEvent.VK_NUMPAD7;
        boolean octaveHoldKey = code == KeyEvent.VK_EQUALS || code == KeyEvent.VK_MINUS;
        // 只认「纯 Ctrl+U/L」（Ctrl+Shift+L 仍是“循环域＝选区”，不抢）
        boolean octaveCursorKey = !shift && (code == KeyEvent.VK_U || code == KeyEvent.VK_L);
        // Ctrl 只放行「小键盘 1~7」「大键盘 +/-」（Ctrl+数字 = 两个八度）和 Ctrl+U/L（光标整体唱八度），
        // 其余留给命令系统（Ctrl+Space 停止等）
        if (ctrl && !numpadDegree && !octaveHoldKey && !octaveCursorKey) {
            return false;
        }
        // 按住大键盘 +/- 时数字再整体翻一个八度，带 Ctrl 则两个八度
        int octaveScale = ctrl ? 2 : 1;
        switch (code) {
            case KeyEvent.VK_W -> view.moveInputCursor(0, 1);
            case KeyEvent.VK_S -> view.moveInputCursor(0, -1);
            case KeyEvent.VK_A -> view.moveInputCursor(-1, 0);
            case KeyEvent.VK_D -> view.moveInputCursor(1, 0);
            // Ctrl+U / Ctrl+L：光标整体上 / 下移一个八度（只是挪光标，不出音符）
            case KeyEvent.VK_U -> {
                if (!ctrl) {
                    return false;
                }
                view.moveInputCursor(0, 12);
            }
            case KeyEvent.VK_L -> {
                if (!ctrl) {
                    return false;
                }
                view.moveInputCursor(0, -12);
            }
            case KeyEvent.VK_Q -> view.beginInputAudition();
            case KeyEvent.VK_SPACE -> view.beginInputNote();
            // 小键盘 +/-：调整下一个数字的八度；1~7 就是简谱的 do~si，按下即输入
            case KeyEvent.VK_ADD -> view.shiftInputOctaveJump(1);
            case KeyEvent.VK_SUBTRACT -> view.shiftInputOctaveJump(-1);
            // 大键盘 +/= 与 -/_：给紧接着的那一个数字挂上 ±1 八度（用完自动撤）
            case KeyEvent.VK_EQUALS -> view.pressInputOctaveHold(1, code);
            case KeyEvent.VK_MINUS -> view.pressInputOctaveHold(-1, code);
            case KeyEvent.VK_NUMPAD0 -> view.beginInputRestNote();
            case KeyEvent.VK_NUMPAD1 -> view.beginInputDegreeNote(1, octaveScale);
            case KeyEvent.VK_NUMPAD2 -> view.beginInputDegreeNote(2, octaveScale);
            case KeyEvent.VK_NUMPAD3 -> view.beginInputDegreeNote(3, octaveScale);
            case KeyEvent.VK_NUMPAD4 -> view.beginInputDegreeNote(4, octaveScale);
            case KeyEvent.VK_NUMPAD5 -> view.beginInputDegreeNote(5, octaveScale);
            case KeyEvent.VK_NUMPAD6 -> view.beginInputDegreeNote(6, octaveScale);
            case KeyEvent.VK_NUMPAD7 -> view.beginInputDegreeNote(7, octaveScale);
            case KeyEvent.VK_BACK_SPACE -> view.deleteInputStep(-1);
            case KeyEvent.VK_DELETE -> view.deleteInputStep(1);
            case KeyEvent.VK_ESCAPE -> view.setInputMode(false);
            default -> {
                return false;
            }
        }
        e.consume();
        return true;
    }

    private boolean isMainWindowActive() {
        Window focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
        // 无焦点窗口 / 已 dispose 的窗口都按「没有别的窗口抢焦点」处理
        return focused == null || !focused.isDisplayable() || focused == app.mainFrame();
    }
}
