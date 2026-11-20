package com.example.mideditor.app;

import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.KeyEvent;

import com.example.mideditor.command.AppCommand;
import com.example.mideditor.command.AppCommandIds;
import com.example.mideditor.command.CommandContext;
import com.example.mideditor.command.TextFocusGuard;
import com.example.mideditor.document.MidiDocumentView;

/**
 * 方向键 / WASD 的兜底分发。
 *
 * <p>
 * Swing 的键绑定只在「有焦点」时生效：焦点停在工具栏、状态栏或干脆没人持有焦点时，
 * WHEN_IN_FOCUSED_WINDOW / WHEN_FOCUSED 全部不触发，表现为“按键完全没反应”。
 * 这里在主窗口活动时直接接管，语义固定为：
 * </p>
 * <ul>
 * <li><b>←/→/↑/↓ 裸按</b>：移动<b>选中框</b>——换到时间上相邻 / 音高上相邻的那一条，
 * 换完立刻试听新选中的那条（不动任何平行线的位置）；</li>
 * <li><b>W/A/S/D</b>：非输入模式下移动选中的平行线（上下改音高、左右挪一格）；
 * 输入模式里归 {@link InputModeDispatcher} 管（光标），这里不插手；</li>
 * <li>Ctrl+→ 右端向右、Ctrl+← 左端向左（外扩）；</li>
 * <li>Shift+→ 左端向右、Shift+← 右端向左（内收/缩短）；</li>
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
        TextFocusGuard.noteKeyboardEvent(e);
        if (e.getID() != KeyEvent.KEY_PRESSED) {
            return false;
        }
        // Esc 先处理：它不产生字符，所以焦点停在 BPM / 网格等控件上时也应生效
        // （输入模式下归 InputModeDispatcher 负责退出输入模式，这里不插手）
        if (e.getKeyCode() == KeyEvent.VK_ESCAPE && !e.isAltDown() && !e.isControlDown()
                && !e.isShiftDown() && isMainWindowActive()) {
            MidiDocumentView view = app.documents().activeView();
            if (view != null && !view.inputMode()) {
                CommandContext context = app.router().context();
                AppCommand command = app.registry().get(AppCommandIds.EDIT_DESELECT);
                if (context != null && command != null && command.isEnabled(context)) {
                    app.router().dispatch(AppCommandIds.EDIT_DESELECT, context);
                    e.consume();
                    return true;
                }
            }
            return false;
        }
        if (e.isAltDown() || TextFocusGuard.isTextEditing()) {
            return false;
        }
        if (!isMainWindowActive()) {
            return false;
        }
        String id = commandIdFor(e);
        if (id == null) {
            id = wasdCommandIdFor(e);
        }
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
                            : AppCommandIds.NOTE_SELECT_RIGHT;
            case KeyEvent.VK_LEFT -> ctrl ? AppCommandIds.NOTE_EXTEND_LEFT
                    : shift ? AppCommandIds.NOTE_TRIM_RIGHT
                            : AppCommandIds.NOTE_SELECT_LEFT;
            case KeyEvent.VK_UP, KeyEvent.VK_DOWN -> null; // 上下键不再参与选中（只留左右）
            default -> null;
        };
    }

    /**
     * W/A/S/D → 移动选中的平行线（输入模式下不接管：那时它们是光标）。
     */
    private String wasdCommandIdFor(KeyEvent e) {
        if (e.isControlDown() || e.isShiftDown() || e.isAltDown()) {
            return null;
        }
        MidiDocumentView view = app.documents().activeView();
        if (view == null || view.inputMode()) {
            return null;
        }
        return switch (e.getKeyCode()) {
            case KeyEvent.VK_W -> AppCommandIds.NOTE_MOVE_UP;
            case KeyEvent.VK_S -> AppCommandIds.NOTE_MOVE_DOWN;
            case KeyEvent.VK_A -> AppCommandIds.NOTE_MOVE_LEFT;
            case KeyEvent.VK_D -> AppCommandIds.NOTE_MOVE_RIGHT;
            default -> null;
        };
    }
}
