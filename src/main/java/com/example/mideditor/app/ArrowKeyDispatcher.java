package com.example.mideditor.app;

import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.KeyEvent;

import com.example.mideditor.command.AppCommand;
import com.example.mideditor.command.AppCommandIds;
import com.example.mideditor.command.CommandContext;
import com.example.mideditor.command.TextFocusGuard;

/**
 * 方向键/修饰键的兜底分发。
 *
 * <p>
 * Swing 的键绑定只在「有焦点」时生效：焦点停在工具栏、状态栏或干脆没人持有焦点时，
 * WHEN_IN_FOCUSED_WINDOW / WHEN_FOCUSED 全部不触发，表现为“按键完全没反应”。
 * 这里在主窗口活动时直接接管方向键，语义固定为：
 * </p>
 * <ul>
 * <li>←/→ 裸按：移动平行线一格（有选区时）；</li>
 * <li>Ctrl+→ 右端向右、Ctrl+← 左端向左（外扩）；</li>
 * <li>Shift+→ 左端向右、Shift+← 右端向左（内收/缩短）；</li>
 * <li>↑/↓ 裸按：改音高；</li>
 * <li>Alt+方向键、文本控件内、其它窗口活动时一律不接管。</li>
 * </ul>
 */
public final class ArrowKeyDispatcher implements KeyEventDispatcher {

    private final AppContext app;

    public ArrowKeyDispatcher(AppContext app) {
        this.app = app;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getID() != KeyEvent.KEY_PRESSED || e.isAltDown() || TextFocusGuard.isTextEditing()) {
            return false;
        }
        if (!isMainWindowActive()) {
            return false;
        }
        String id = commandIdFor(e);
        if (id == null) {
            return false;
        }
        CommandContext context = app.router().context();
        AppCommand command = app.registry().get(id);
        if (context == null || command == null || !command.isEnabled(context)) {
            return false;
        }
        app.router().dispatch(id, context);
        e.consume();
        return true;
    }

    private boolean isMainWindowActive() {
        Window focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
        // 没有焦点窗口（例如无头/自动化环境）时也允许，交给上面的启用态判断；
        // 已经 dispose 掉的窗口不可能真的持有焦点（FocusManager 会短暂保留它），同样按「无焦点」处理
        if (focused == null || !focused.isDisplayable()) {
            return true;
        }
        return focused == app.mainFrame();
    }

    /** 按键 → 命令 id；返回 null 表示这条组合键不归我们管。 */
    static String commandIdFor(KeyEvent e) {
        boolean ctrl = e.isControlDown();
        boolean shift = e.isShiftDown() && !ctrl;
        return switch (e.getKeyCode()) {
            // Ctrl = 外扩（动箭头那一侧）；Shift = 内收（动另一侧）
            case KeyEvent.VK_RIGHT -> ctrl ? AppCommandIds.NOTE_EXTEND_RIGHT
                    : shift ? AppCommandIds.NOTE_TRIM_LEFT
                            : AppCommandIds.NOTE_MOVE_RIGHT;
            case KeyEvent.VK_LEFT -> ctrl ? AppCommandIds.NOTE_EXTEND_LEFT
                    : shift ? AppCommandIds.NOTE_TRIM_RIGHT
                            : AppCommandIds.NOTE_MOVE_LEFT;
            case KeyEvent.VK_UP -> ctrl || shift ? null : AppCommandIds.NOTE_MOVE_UP;
            case KeyEvent.VK_DOWN -> ctrl || shift ? null : AppCommandIds.NOTE_MOVE_DOWN;
            default -> null;
        };
    }
}
