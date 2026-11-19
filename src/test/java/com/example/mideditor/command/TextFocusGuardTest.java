package com.example.mideditor.command;

import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * “焦点在文本框里就把命令让位”只该对**键盘触发**生效。
 *
 * <p>
 * 以前不看触发方式，于是：改完 BPM（焦点停在那只 spinner 的输入框里）点工具栏的播放按钮，
 * 播放命令会被当成“正在打字”直接 return —— 表现就是“点播放没反应，得先点一下频谱”。
 * </p>
 */
class TextFocusGuardTest {

    @Test
    void keyboardTriggerInATextFieldBlocksTheCommand() {
        assertTrue(TextFocusGuard.shouldBlock(true, new JTextField()), "打字时不该触发单键命令");
        assertTrue(TextFocusGuard.shouldBlock(true, new JSpinner()), "BPM 输入框也算");
        assertTrue(TextFocusGuard.shouldBlock(true, new JComboBox<String>()), "下拉框也算");
    }

    @Test
    void mouseTriggerNeverBlocksEvenIfFocusIsStillInATextField() {
        assertFalse(TextFocusGuard.shouldBlock(false, new JTextField()),
                "点按钮 / 点菜单时焦点还停在输入框里，也必须照常执行");
        assertFalse(TextFocusGuard.shouldBlock(false, new JSpinner()), "BPM 输入框同理");
    }

    @Test
    void keyboardTriggerOutsideTextFieldsRunsNormally() {
        assertFalse(TextFocusGuard.shouldBlock(true, new JPanel()), "焦点不在文本控件就不该挡");
        assertFalse(TextFocusGuard.shouldBlock(true, null), "没有焦点也不该挡");
    }
}
