package com.example.mideditor.ui.frame;

import javax.swing.Action;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.command.AppCommandIds;
import com.example.mideditor.ui.theme.Theme;

/** 经典菜单栏：文件 / 编辑 / 视图 / 窗口 / 帮助，支持 Alt 激活与助记符。 */
public final class MenuBarBuilder {

    private final AppContext app;
    private final JMenu windowMenu = new JMenu("窗口(W)");

    public MenuBarBuilder(AppContext app) {
        this.app = app;
        windowMenu.setMnemonic('W');
        windowMenu.setFont(Theme.BASE_FONT);
    }

    public JMenu windowMenu() {
        return windowMenu;
    }

    public JMenuBar build() {
        JMenuBar bar = new JMenuBar();
        bar.setFont(Theme.BASE_FONT);
        bar.add(fileMenu());
        bar.add(editMenu());
        bar.add(trackMenu());
        bar.add(viewMenu());
        bar.add(windowMenu);
        bar.add(helpMenu());
        return bar;
    }

    private JMenu menu(String text, char mnemonic) {
        JMenu menu = new JMenu(text);
        menu.setMnemonic(mnemonic);
        menu.setFont(Theme.BASE_FONT);
        return menu;
    }

    /** 支持「新建(N)」文本：保留经典显示并设置助记符。 */
    private static void applyMnemonic(JMenuItem item, String text) {
        if (text == null) {
            return;
        }
        item.setText(text);
        if (text.length() >= 3 && text.endsWith(")")) {
            char mnemonic = Character.toUpperCase(text.charAt(text.length() - 2));
            if (Character.isLetterOrDigit(mnemonic)) {
                item.setMnemonic(mnemonic);
            }
        }
    }

    private JMenuItem item(JMenu menu, String commandId, String text) {
        Action action = app.registry().action(commandId);
        JMenuItem item = new JMenuItem(action);
        applyMnemonic(item, text);
        item.setFont(Theme.BASE_FONT);
        menu.add(item);
        return item;
    }

    private JMenuItem checkItem(JMenu menu, String commandId, String text) {
        Action action = app.registry().action(commandId);
        JCheckBoxMenuItem item = new JCheckBoxMenuItem(action);
        applyMnemonic(item, text);
        item.setFont(Theme.BASE_FONT);
        menu.add(item);
        return item;
    }

    private JMenu fileMenu() {
        JMenu menu = menu("文件(F)", 'F');
        item(menu, AppCommandIds.FILE_NEW, "新建(N)");
        item(menu, AppCommandIds.FILE_OPEN, "打开(O)...");
        menu.addSeparator();
        item(menu, AppCommandIds.FILE_SAVE, "保存(S)");
        item(menu, AppCommandIds.FILE_SAVE_AS, "另存为(A)...");
        item(menu, AppCommandIds.FILE_EXPORT, "导出 .mid(E)...");
        menu.addSeparator();
        item(menu, AppCommandIds.FILE_CLOSE, "关闭(C)");
        item(menu, AppCommandIds.FILE_EXIT, "退出(X)");
        return menu;
    }

    private JMenu editMenu() {
        JMenu menu = menu("编辑(E)", 'E');
        item(menu, AppCommandIds.EDIT_UNDO, "撤销(U)");
        item(menu, AppCommandIds.EDIT_REDO, "重做(R)");
        menu.addSeparator();
        item(menu, AppCommandIds.EDIT_CUT, "剪切(T)");
        item(menu, AppCommandIds.EDIT_COPY, "复制(C)");
        item(menu, AppCommandIds.EDIT_PASTE, "粘贴(P)");
        item(menu, AppCommandIds.EDIT_DELETE, "删除(D)");
        item(menu, AppCommandIds.EDIT_SELECT_ALL, "全选(L)");
        item(menu, AppCommandIds.EDIT_DESELECT, "取消选择");
        menu.addSeparator();
        item(menu, AppCommandIds.EDIT_QUANTIZE, "量化到网格(Q)");
        item(menu, AppCommandIds.EDIT_VELOCITY_UP, "力度 +10");
        item(menu, AppCommandIds.EDIT_VELOCITY_DOWN, "力度 -10");
        item(menu, AppCommandIds.EDIT_SPLIT_AT_PLAYHEAD, "在播放头拆分");
        menu.addSeparator();
        item(menu, AppCommandIds.EDIT_TRANSPOSE, "一键移调…");
        menu.addSeparator();
        checkItem(menu, AppCommandIds.EDIT_INPUT_MODE, "输入模式");
        checkItem(menu, AppCommandIds.EDIT_INPUT_BEAT_SNAP, "节奏辅助");

        JMenu lines = new JMenu("平行线");
        lines.setFont(Theme.BASE_FONT);
        item(lines, AppCommandIds.NOTE_ADD, "添加平行线");
        item(lines, AppCommandIds.NOTE_LOCK, "收尾锁定");
        item(lines, AppCommandIds.NOTE_UNLOCK, "解除锁定");
        item(lines, AppCommandIds.NOTE_DELETE, "删除平行线");
        item(lines, AppCommandIds.NOTE_SPLIT, "拆分平行线");
        lines.addSeparator();
        item(lines, AppCommandIds.NOTE_MOVE_UP, "上移半音");
        item(lines, AppCommandIds.NOTE_MOVE_DOWN, "下移半音");
        item(lines, AppCommandIds.NOTE_MOVE_LEFT, "左移一格");
        item(lines, AppCommandIds.NOTE_MOVE_RIGHT, "右移一格");
        lines.addSeparator();
        item(lines, AppCommandIds.NOTE_SELECT_LEFT, "选中左一条");
        item(lines, AppCommandIds.NOTE_SELECT_RIGHT, "选中右一条");
        item(lines, AppCommandIds.NOTE_SELECT_UP, "选中上一条");
        item(lines, AppCommandIds.NOTE_SELECT_DOWN, "选中下一条");
        lines.addSeparator();
        item(lines, AppCommandIds.NOTE_EXTEND_LEFT, "左端延长");
        item(lines, AppCommandIds.NOTE_EXTEND_RIGHT, "右端延长");
        lines.addSeparator();
        item(lines, AppCommandIds.NOTE_LENGTHEN, "拉长");
        item(lines, AppCommandIds.NOTE_SHORTEN, "缩短");
        lines.addSeparator();
        item(lines, AppCommandIds.REST_INSERT, "插入休止");
        item(lines, AppCommandIds.REST_DELETE, "删除休止");
        menu.addSeparator();
        menu.add(lines);
        return menu;
    }

    private JMenu trackMenu() {
        JMenu menu = menu("音轨(T)", 'T');
        item(menu, AppCommandIds.TRACK_ADD, "添加音轨(A)");
        item(menu, AppCommandIds.TRACK_REMOVE, "删除音轨(D)");
        menu.addSeparator();
        item(menu, AppCommandIds.TRACK_MOVE_UP, "音轨上移(U)");
        item(menu, AppCommandIds.TRACK_MOVE_DOWN, "音轨下移(N)");
        menu.addSeparator();
        checkItem(menu, AppCommandIds.TRACK_TOGGLE_MUTE, "静音(M)");
        checkItem(menu, AppCommandIds.TRACK_TOGGLE_SOLO, "独奏(S)");
        return menu;
    }

    private JMenu viewMenu() {
        JMenu menu = menu("视图(V)", 'V');
        JMenu panels = new JMenu("工具窗口");
        panels.setFont(Theme.BASE_FONT);
        checkItem(panels, AppCommandIds.VIEW_TRACKS, "音轨面板");
        checkItem(panels, AppCommandIds.VIEW_INSTRUMENTS, "乐器面板");
        checkItem(panels, AppCommandIds.VIEW_MIXER, "混音器");
        checkItem(panels, AppCommandIds.VIEW_OUTPUT, "输出");
        checkItem(panels, AppCommandIds.VIEW_DIAGNOSTICS, "诊断");
        checkItem(panels, AppCommandIds.VIEW_PROPERTIES, "属性");
        checkItem(panels, AppCommandIds.VIEW_GRID_SETTINGS, "网格设置");
        menu.add(panels);

        JMenu zoom = new JMenu("缩放");
        zoom.setFont(Theme.BASE_FONT);
        item(zoom, AppCommandIds.VIEW_ZOOM_IN, "放大");
        item(zoom, AppCommandIds.VIEW_ZOOM_OUT, "缩小");
        item(zoom, AppCommandIds.VIEW_ZOOM_RESET, "重置");
        menu.add(zoom);

        checkItem(menu, AppCommandIds.VIEW_TOGGLE_GRID, "显示网格");
        checkItem(menu, AppCommandIds.VIEW_FOLLOW_PLAYHEAD, "播放头跟随");
        checkItem(menu, AppCommandIds.VIEW_KEYBOARD_RULER, "键盘标尺(K)");
        checkItem(menu, AppCommandIds.VIEW_C_HIGHLIGHT, "C 高亮");
        JMenu pitch = new JMenu("音域");
        pitch.setFont(Theme.BASE_FONT);
        item(pitch, AppCommandIds.VIEW_PITCH_ZOOM_IN, "放大");
        item(pitch, AppCommandIds.VIEW_PITCH_ZOOM_OUT, "缩小");
        item(pitch, AppCommandIds.VIEW_PITCH_ZOOM_RESET, "重置");
        pitch.addSeparator();
        item(pitch, AppCommandIds.VIEW_PITCH_FIT, "适配平行线");
        item(pitch, AppCommandIds.VIEW_PITCH_SHOW_ALL, "显示全部音域");
        menu.add(pitch);
        menu.addSeparator();

        JMenu arrange = new JMenu("子窗口排列");
        arrange.setFont(Theme.BASE_FONT);
        item(arrange, AppCommandIds.WINDOW_CASCADE, "层叠");
        item(arrange, AppCommandIds.WINDOW_TILE, "平铺");
        item(arrange, AppCommandIds.WINDOW_MINIMIZE_ALL, "全部最小化");
        item(arrange, AppCommandIds.WINDOW_RESTORE_ALL, "全部还原");
        menu.add(arrange);

        item(menu, AppCommandIds.PLAY_TOGGLE_LOOP, "循环播放");
        item(menu, AppCommandIds.PLAY_LOOP_TO_SELECTION, "循环域设为选区");
        item(menu, AppCommandIds.PLAY_LOOP_CLEAR, "清除循环域");
        item(menu, AppCommandIds.PLAY_TOGGLE_METRONOME, "节拍器");
        return menu;
    }

    private JMenu helpMenu() {
        JMenu menu = menu("帮助(H)", 'H');
        item(menu, AppCommandIds.HELP_SHORTCUTS, "快捷键");
        item(menu, AppCommandIds.HELP_ABOUT, "关于 midEditor");
        return menu;
    }
}