package com.example.mideditor.command;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.ui.theme.Icons;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/** 播放命令。 */
final class PlaybackCommands {

    private PlaybackCommands() {
    }

    static void install(AppContext app) {
        var registry = app.registry();

        AppCommand playPause = new AppCommand(AppCommandIds.PLAY_PLAY_PAUSE, "播放 / 暂停", CommandScope.DOCUMENT,
                FileCommands.key(KeyEvent.VK_F5, 0),
                ctx -> {
                    if (TextFocusGuard.isTextEditing()) {
                        return;
                    }
                    ctx.document().togglePlayPause();
                },
                CommandContext::hasDocument,
                ctx -> ctx.app().playback().isPlaying() && ctx.app().playback().owner() == ctx.document());
        playPause.putIcon(Icons.PLAY);
        registry.register(playPause);
        // 老习惯：空格也能播放 / 暂停（输入模式下空格归输入用）
        registry.bindAlias(AppCommandIds.PLAY_PLAY_PAUSE, FileCommands.key(KeyEvent.VK_SPACE, 0));

        AppCommand stop = new AppCommand(AppCommandIds.PLAY_STOP, "停止", CommandScope.DOCUMENT,
                FileCommands.key(KeyEvent.VK_F4, 0),
                ctx -> ctx.document().stopTransport(), CommandContext::hasDocument, null);
        stop.putIcon(Icons.STOP);
        registry.register(stop);
        registry.bindAlias(AppCommandIds.PLAY_STOP, FileCommands.key(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK));

        AppCommand rewind = new AppCommand(AppCommandIds.PLAY_REWIND, "回到开头", CommandScope.DOCUMENT,
                FileCommands.key(KeyEvent.VK_F6, 0),
                ctx -> ctx.document().rewind(), CommandContext::hasDocument, null);
        rewind.putIcon(Icons.REWIND);
        registry.register(rewind);
        registry.bindAlias(AppCommandIds.PLAY_REWIND, FileCommands.key(KeyEvent.VK_HOME, InputEvent.CTRL_DOWN_MASK));

        AppCommand loop = new AppCommand(AppCommandIds.PLAY_TOGGLE_LOOP, "循环播放", CommandScope.DOCUMENT,
                FileCommands.key(KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK),
                ctx -> ctx.document().toggleLoop(), CommandContext::hasDocument,
                ctx -> ctx.hasDocument() && ctx.document().loopEnabled());
        loop.putIcon(Icons.LOOP);
        registry.register(loop);

        registry.register(new AppCommand(AppCommandIds.PLAY_LOOP_TO_SELECTION, "循环域＝选区",
                CommandScope.DOCUMENT,
                FileCommands.key(KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
                ctx -> ctx.document().loopToSelection(), CommandContext::hasDocument, null));

        registry.register(new AppCommand(AppCommandIds.PLAY_LOOP_CLEAR, "清除循环域",
                CommandScope.DOCUMENT, null,
                ctx -> ctx.document().setLoopEnabled(false), CommandContext::hasDocument,
                ctx -> ctx.hasDocument() && ctx.document().loopEnabled()));

        AppCommand metronome = new AppCommand(AppCommandIds.PLAY_TOGGLE_METRONOME, "节拍器", CommandScope.DOCUMENT,
                FileCommands.key(KeyEvent.VK_M, InputEvent.CTRL_DOWN_MASK),
                ctx -> ctx.document().toggleMetronome(), CommandContext::hasDocument,
                ctx -> ctx.hasDocument() && ctx.document().metronome());
        metronome.putIcon(Icons.METRONOME);
        registry.register(metronome);
    }
}
