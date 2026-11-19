package com.example.mideditor.command;

import javax.swing.JComboBox;
import javax.swing.JSpinner;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;

/** 判断焦点是否在文本编辑控件上，避免空格 / Delete 等单键快捷键误触发命令。 */
public final class TextFocusGuard {

    /**
     * 最近一次输入是键盘还是鼠标。
     *
     * <p>
     * 只有键盘触发的动作才应该被“正在打字”挡住：点工具栏按钮 / 菜单项时焦点可能还停在
     * BPM 输入框里（按钮是不可聚焦的），以前会被误判成在打字，表现就是
     * “改完 BPM 后点播放没反应，得先点一下别处”——那是把点击当成打字了。
     * </p>
     */
    private static volatile boolean keyboardDriven = true;

    /** 最近一次按下的键：F1–F12 这类功能键不是文字输入，焦点在文本框里也不该被挡。 */
    private static volatile int lastKeyCode = KeyEvent.VK_UNDEFINED;

    private TextFocusGuard() {
    }

    /** 键盘事件到达时调用（全局 KeyEventDispatcher 负责）。 */
    public static void noteKeyboardEvent() {
        noteKeyboardEvent(null);
    }

    /** 键盘事件到达时调用；带上键码，用来把 F1–F12 从“正在打字”里排除。 */
    public static void noteKeyboardEvent(KeyEvent event) {
        keyboardDriven = true;
        lastKeyCode = event == null ? KeyEvent.VK_UNDEFINED : event.getKeyCode();
    }

    /** 鼠标事件到达时调用（全局 AWT 监听负责）。 */
    public static void noteMouseEvent() {
        keyboardDriven = false;
    }

    public static boolean isTextEditing() {
        return shouldBlock(keyboardDriven, lastKeyCode,
                KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner());
    }

    /** 是否应该“让位给文本框”：只有键盘触发 + 焦点在文本控件上才算。 */
    static boolean shouldBlock(boolean keyboardDriven, Component focusOwner) {
        return shouldBlock(keyboardDriven, KeyEvent.VK_UNDEFINED, focusOwner);
    }

    /**
     * 同上，但知道是按了哪个键：F1–F12 不可能往文本框里插字符，
     * 所以即使用户的光标还停在 BPM 输入框里，F5 也要能直接播放（否则改完速度按 F5 毫无反应）。
     */
    static boolean shouldBlock(boolean keyboardDriven, int keyCode, Component focusOwner) {
        if (!keyboardDriven) {
            return false;
        }
        if (keyCode >= KeyEvent.VK_F1 && keyCode <= KeyEvent.VK_F12) {
            return false;
        }
        return focusOwner instanceof JTextComponent || focusOwner instanceof JSpinner
                || focusOwner instanceof JComboBox;
    }
}
