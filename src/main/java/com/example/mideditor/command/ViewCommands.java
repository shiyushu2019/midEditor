package com.example.mideditor.command;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.function.Predicate;

import javax.swing.JOptionPane;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.ui.dock.DockIds;

/** 视图 / 窗口 / 帮助命令。 */
final class ViewCommands {

    private ViewCommands() {
    }

    private static Predicate<CommandContext> dockShowing(String id) {
        return ctx -> ctx.app().dock() != null && ctx.app().dock().isShowing(id);
    }

    static void install(AppContext app) {
        var registry = app.registry();

        registry.register(new AppCommand(AppCommandIds.VIEW_TRACKS, "音轨面板", CommandScope.APPLICATION,
                null, ctx -> toggleDock(ctx.app(), DockIds.TRACKS), ctx -> true,
                dockShowing(DockIds.TRACKS)));
        registry.register(new AppCommand(AppCommandIds.VIEW_INSTRUMENTS, "乐器面板", CommandScope.APPLICATION,
                null, ctx -> toggleDock(ctx.app(), DockIds.INSTRUMENTS), ctx -> true,
                dockShowing(DockIds.INSTRUMENTS)));
        registry.register(new AppCommand(AppCommandIds.VIEW_MIXER, "混音器", CommandScope.APPLICATION,
                null, ctx -> toggleDock(ctx.app(), DockIds.MIXER), ctx -> true,
                dockShowing(DockIds.MIXER)));
        registry.register(new AppCommand(AppCommandIds.VIEW_OUTPUT, "输出", CommandScope.APPLICATION,
                null, ctx -> toggleDock(ctx.app(), DockIds.OUTPUT), ctx -> true,
                dockShowing(DockIds.OUTPUT)));
        registry.register(new AppCommand(AppCommandIds.VIEW_DIAGNOSTICS, "诊断", CommandScope.APPLICATION,
                null, ctx -> toggleDock(ctx.app(), DockIds.DIAGNOSTICS), ctx -> true,
                dockShowing(DockIds.DIAGNOSTICS)));
        registry.register(new AppCommand(AppCommandIds.VIEW_PROPERTIES, "属性", CommandScope.APPLICATION,
                null, ctx -> toggleDock(ctx.app(), DockIds.PROPERTIES), ctx -> true,
                dockShowing(DockIds.PROPERTIES)));
        registry.register(new AppCommand(AppCommandIds.VIEW_GRID_SETTINGS, "网格设置", CommandScope.APPLICATION,
                null, ctx -> toggleDock(ctx.app(), DockIds.GRID), ctx -> true,
                dockShowing(DockIds.GRID)));

        registry.register(new AppCommand(AppCommandIds.VIEW_ZOOM_IN, "放大", CommandScope.DOCUMENT,
                FileCommands.key(KeyEvent.VK_EQUALS, InputEvent.CTRL_DOWN_MASK),
                ctx -> {
                    ctx.document().pianoRoll().timeline().zoomIn();
                    ctx.document().refresh();
                    ctx.app().refreshApplicationState();
                }, CommandContext::hasDocument, null));

        registry.register(new AppCommand(AppCommandIds.VIEW_ZOOM_OUT, "缩小", CommandScope.DOCUMENT,
                FileCommands.key(KeyEvent.VK_MINUS, InputEvent.CTRL_DOWN_MASK),
                ctx -> {
                    ctx.document().pianoRoll().timeline().zoomOut();
                    ctx.document().refresh();
                    ctx.app().refreshApplicationState();
                }, CommandContext::hasDocument, null));

        registry.register(new AppCommand(AppCommandIds.VIEW_ZOOM_RESET, "重置缩放", CommandScope.DOCUMENT,
                FileCommands.key(KeyEvent.VK_0, InputEvent.CTRL_DOWN_MASK),
                ctx -> {
                    ctx.document().pianoRoll().timeline().reset();
                    ctx.document().refresh();
                    ctx.app().refreshApplicationState();
                }, CommandContext::hasDocument, null));

        registry.register(new AppCommand(AppCommandIds.VIEW_TOGGLE_GRID, "显示网格", CommandScope.DOCUMENT,
                FileCommands.key(KeyEvent.VK_G, InputEvent.CTRL_DOWN_MASK),
                ctx -> {
                    ctx.document().grid().setVisible(!ctx.document().grid().visible());
                    ctx.document().pianoRoll().repaint();
                    ctx.app().refreshApplicationState();
                }, CommandContext::hasDocument,
                ctx -> ctx.hasDocument() && ctx.document().grid().visible()));

        registry.register(new AppCommand(AppCommandIds.VIEW_FOLLOW_PLAYHEAD, "播放头跟随", CommandScope.DOCUMENT,
                null,
                ctx -> {
                    ctx.document().setFollowPlayhead(!ctx.document().followPlayhead());
                    ctx.app().refreshApplicationState();
                }, CommandContext::hasDocument,
                ctx -> ctx.hasDocument() && ctx.document().followPlayhead()));

        registry.register(new AppCommand(AppCommandIds.VIEW_KEYBOARD_RULER, "键盘标尺", CommandScope.DOCUMENT,
                FileCommands.key(KeyEvent.VK_K, InputEvent.CTRL_DOWN_MASK),
                ctx -> ctx.document().toggleKeyboardRuler(),
                CommandContext::hasDocument,
                ctx -> ctx.hasDocument() && ctx.document().keyboardRulerVisible()));

        registry.register(new AppCommand(AppCommandIds.VIEW_C_HIGHLIGHT, "Do（C）高亮", CommandScope.DOCUMENT,
                FileCommands.key(KeyEvent.VK_K, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
                ctx -> ctx.document().toggleCHighlight(),
                CommandContext::hasDocument,
                ctx -> ctx.hasDocument() && ctx.document().cHighlightVisible()));

        // ---- 频率轴（音域）：滚轮 / 快捷键 / 菜单都能缩放与滑动
        Predicate<CommandContext> hasActiveTrack = CommandContext::hasTrack;
        registry.register(new AppCommand(AppCommandIds.VIEW_PITCH_ZOOM_IN, "音域放大", CommandScope.DOCUMENT,
                FileCommands.key(KeyEvent.VK_EQUALS, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK),
                ctx -> ctx.document().pitchZoomIn(), hasActiveTrack, null));
        registry.register(new AppCommand(AppCommandIds.VIEW_PITCH_ZOOM_OUT, "音域缩小", CommandScope.DOCUMENT,
                FileCommands.key(KeyEvent.VK_MINUS, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK),
                ctx -> ctx.document().pitchZoomOut(), hasActiveTrack, null));
        registry.register(new AppCommand(AppCommandIds.VIEW_PITCH_ZOOM_RESET, "音域重置", CommandScope.DOCUMENT,
                FileCommands.key(KeyEvent.VK_0, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK),
                ctx -> ctx.document().pitchZoomReset(), hasActiveTrack, null));
        registry.register(new AppCommand(AppCommandIds.VIEW_PITCH_FIT, "音域适配平行线", CommandScope.DOCUMENT,
                null,
                ctx -> ctx.document().pitchFitToNotes(), hasActiveTrack, null));
        registry.register(new AppCommand(AppCommandIds.VIEW_PITCH_SHOW_ALL, "显示全部音域", CommandScope.DOCUMENT,
                null,
                ctx -> ctx.document().pitchShowAll(), CommandContext::hasDocument, null));

        // ---- 窗口
        Predicate<CommandContext> hasSessions = ctx -> ctx.app().documents().sessionCount() > 0;
        registry.register(new AppCommand(AppCommandIds.WINDOW_CASCADE, "层叠", CommandScope.APPLICATION,
                null, ctx -> ctx.app().mainFrame().workspace().cascade(), hasSessions, null));
        registry.register(new AppCommand(AppCommandIds.WINDOW_TILE, "平铺", CommandScope.APPLICATION,
                null, ctx -> ctx.app().mainFrame().workspace().tile(), hasSessions, null));
        registry.register(new AppCommand(AppCommandIds.WINDOW_MINIMIZE_ALL, "全部最小化", CommandScope.APPLICATION,
                null, ctx -> ctx.app().mainFrame().workspace().minimizeAll(), hasSessions, null));
        registry.register(new AppCommand(AppCommandIds.WINDOW_RESTORE_ALL, "全部还原", CommandScope.APPLICATION,
                null, ctx -> ctx.app().mainFrame().workspace().restoreAll(), hasSessions, null));
        registry.register(new AppCommand(AppCommandIds.WINDOW_CLOSE, "关闭文档窗口", CommandScope.DOCUMENT,
                null, ctx -> ctx.app().documents().requestClose(FileCommands.sessionOf(ctx)),
                CommandContext::hasDocument, null));

        // ---- 帮助
        registry.register(new AppCommand(AppCommandIds.HELP_ABOUT, "关于 midEditor", CommandScope.APPLICATION,
                null, ctx -> showAbout(ctx.app()), ctx -> true, null));
    }

    private static void toggleDock(AppContext app, String id) {
        if (app.dock() == null) {
            return;
        }
        app.dock().toggle(id);
        app.refreshApplicationState();
    }

    private static void showAbout(AppContext app) {
        String message = """
                midEditor — 经典 MDI 多音轨 MIDI 编辑器

                窗口架构：Main Frame / MDI Client Area / MDI Child Window
                编辑对象：「平行线」（平行于横轴，粗细=力度）
                音符解析：javax.sound.midi
                音频后端：%s
                运行环境：Java %s

                快捷键：空格 播放/暂停，Delete 删除平行线，
                Ctrl+Z/Ctrl+Shift+Z 撤销/重做，Alt+↑↓←→ 移动平行线。
                滚轮：频谱上下滚动（音轨头上滚轮 = 滚动放不下的音轨）；
                Shift+滚轮 左右滚动，Alt+滚轮 左右缩放，Ctrl+滚轮 上下缩放。
                """.formatted(app.synth().description(), System.getProperty("java.version"));
        JOptionPane.showMessageDialog(app.mainFrame(), message, "关于 midEditor",
                JOptionPane.INFORMATION_MESSAGE);
    }
}
