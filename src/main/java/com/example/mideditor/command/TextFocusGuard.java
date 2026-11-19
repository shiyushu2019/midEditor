package com.example.mideditor.command;

import javax.swing.JComboBox;
import javax.swing.JSpinner;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.KeyboardFocusManager;

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

    private TextFocusGuard() {
    }

    /** 键盘事件到达时调用（全局 KeyEventDispatcher 负责）。 */
    public static void noteKeyboardEvent() {
        keyboardDriven = true;
    }

    /** 鼠标事件到达时调用（全局 AWT 监听负责）。 */
    public static void noteMouseEvent() {
        keyboardDriven = false;
    }

    public static boolean isTextEditing() {
        return shouldBlock(keyboardDriven,
                KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner());
    }

    /** 是否应该“让位给文本框”：只有键盘触发 + 焦点在文本控件上才算。 */
    static boolean shouldBlock(boolean keyboardDriven, Component focusOwner) {
        if (!keyboardDriven) {
            return false;
        }
        return focusOwner instanceof JTextComponent || focusOwner instanceof JSpinner
                || focusOwner instanceof JComboBox;
    }
}
