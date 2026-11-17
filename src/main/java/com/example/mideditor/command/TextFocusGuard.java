package com.example.mideditor.command;

import javax.swing.JComboBox;
import javax.swing.JSpinner;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.KeyboardFocusManager;

/** 判断焦点是否在文本编辑控件上，避免空格 / Delete 等单键快捷键误触发命令。 */
public final class TextFocusGuard {

    private TextFocusGuard() {
    }

    public static boolean isTextEditing() {
        Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        return owner instanceof JTextComponent || owner instanceof JSpinner || owner instanceof JComboBox;
    }
}
