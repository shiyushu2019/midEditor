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
 * <li><b>A / D</b>：光标左移 / 右移一格（当前网格分辨率）；</li>
 * <li><b>Q</b>：用当前音轨音色试听光标处的音高；</li>
 * <li><b>空格</b>：按下开始计时、松手落成一条平行线 —— <b>按住多久，音符就有多长</b>
 * （按当前速度配置换算成 tick，走的就是这段真实时间）；</li>
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
        TextFocusGuard.noteKeyboardEvent();
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
            if (e.getKeyCode() == KeyEvent.VK_SPACE) {
                view.endInputNote();
                e.consume();
                return true;
            }
            if (e.getKeyCode() == KeyEvent.VK_Q) {
                view.endInputAudition();
                e.consume();
                return true;
            }
            return false;
        }
        if (e.getID() != KeyEvent.KEY_PRESSED) {
            return false;
        }
        // Ctrl/Alt/Meta 组合键留给命令系统（Ctrl+Space 停止、Alt+方向键等）
        if ((e.getModifiersEx() & (InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK
                | InputEvent.META_DOWN_MASK)) != 0) {
            return false;
        }
        switch (e.getKeyCode()) {
            case KeyEvent.VK_W -> view.moveInputCursor(0, 1);
            case KeyEvent.VK_S -> view.moveInputCursor(0, -1);
            case KeyEvent.VK_A -> view.moveInputCursor(-1, 0);
            case KeyEvent.VK_D -> view.moveInputCursor(1, 0);
            case KeyEvent.VK_Q -> view.beginInputAudition();
            case KeyEvent.VK_SPACE -> view.beginInputNote();
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
