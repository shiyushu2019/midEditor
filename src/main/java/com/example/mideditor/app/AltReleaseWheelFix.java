package com.example.mideditor.app;

import java.awt.KeyboardFocusManager;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.swing.MenuSelectionManager;

import com.example.mideditor.util.Log;

/**
 * Windows / AWT 兼容修复。
 *
 * <p>
 * 现象：单独按一下 Alt（没有配合其它键）后，窗口会进入系统的「菜单模式」，
 * 之后 <b>滚轮消息被操作系统直接丢弃</b>（键盘、鼠标点击仍正常），
 * 必须再按一个键或点击一次才恢复 —— 表现出来就是「滚轮有时完全没反应」。
 * </p>
 *
 * <p>
 * 修复：监听裸 Alt 的抬起，在 EDT 之外补发一个 ESC（本应用不使用 ESC），
 * 让窗口立刻退出菜单模式，滚轮随即恢复。为避免副作用，只在下列条件都满足时补发：
 * 主窗口是活动窗口（对话框打开时不动作）、没有弹出菜单正在选择。
 * </p>
 */
final class AltReleaseWheelFix {

    private static final ExecutorService SENDER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "alt-wheel-fix");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile Robot robot;

    private AltReleaseWheelFix() {
    }

    static void install(MainFrame frame) {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(event -> {
            if (event.getID() == KeyEvent.KEY_RELEASED
                    && event.getKeyCode() == KeyEvent.VK_ALT
                    && !event.isAltDown() && !event.isControlDown() && !event.isShiftDown()) {
                SENDER.submit(() -> sendEscape(frame));
            }
            return false;
        });
    }

    private static void sendEscape(MainFrame frame) {
        try {
            Thread.sleep(40);
            if (!frame.isActive()) {
                return;
            }
            Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
            if (active != frame) {
                return; // 对话框 / 浮动窗口在用 ESC，不打扰
            }
            if (MenuSelectionManager.defaultManager().getSelectedPath().length > 0) {
                return; // 弹出菜单正在处理 ESC
            }
            Robot sender = robot;
            if (sender == null) {
                sender = new Robot();
                robot = sender;
            }
            sender.keyPress(KeyEvent.VK_ESCAPE);
            sender.keyRelease(KeyEvent.VK_ESCAPE);
        } catch (InterruptedException | java.awt.AWTException e) {
            Log.error("Alt 抬起后的滚轮兼容修复失败：" + e);
        }
    }
}
