package com.example.mideditor.command;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.document.MidiDocumentView;
import com.example.mideditor.domain.Note;
import com.example.mideditor.ui.theme.Icons;

/** 编辑 / 平行线 / 音轨命令。 */
final class EditCommands {

    private EditCommands() {
    }

    private static long gridStep(MidiDocumentView view) {
        long step = view.grid().step(view.document().ppq());
        return step > 0 ? step : Math.max(1, view.document().ppq() / 4);
    }

    static void install(AppContext app) {
        var registry = app.registry();

        AppCommand undo = new AppCommand(AppCommandIds.EDIT_UNDO, "撤销", CommandScope.DOCUMENT,
                FileCommands.key(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK),
                ctx -> ctx.document().undo(),
                ctx -> ctx.hasDocument() && ctx.document().editStack().canUndo(), null);
        undo.putIcon(Icons.undo());
        registry.register(undo);

        AppCommand redo = new AppCommand(AppCommandIds.EDIT_REDO, "重做", CommandScope.DOCUMENT,
                FileCommands.key(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
                ctx -> ctx.document().redo(),
                ctx -> ctx.hasDocument() && ctx.document().editStack().canRedo(), null);
        redo.putIcon(Icons.redo());
        registry.register(redo);
        // Windows/Word 习惯：Ctrl+Y 也可重做
        registry.bindAlias(AppCommandIds.EDIT_REDO, FileCommands.key(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK));

        AppCommand cut = new AppCommand(AppCommandIds.EDIT_CUT, "剪切", CommandScope.SELECTION,
                FileCommands.key(KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK),
                ctx -> ctx.document().cutSelection(), CommandContext::hasSelection, null);
        cut.putIcon(Icons.cut());
        registry.register(cut);

        AppCommand copy = new AppCommand(AppCommandIds.EDIT_COPY, "复制", CommandScope.SELECTION,
                FileCommands.key(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK),
                ctx -> ctx.document().copySelection(), CommandContext::hasSelection, null);
        copy.putIcon(Icons.copy());
        registry.register(copy);

        AppCommand paste = new AppCommand(AppCommandIds.EDIT_PASTE, "粘贴", CommandScope.TRACK,
                FileCommands.key(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK),
                ctx -> ctx.document().paste(),
                ctx -> ctx.hasTrack() && !ctx.app().clipboard().isEmpty(), null);
        paste.putIcon(Icons.paste());
        registry.register(paste);

        AppCommand delete = new AppCommand(AppCommandIds.EDIT_DELETE, "删除", CommandScope.SELECTION,
                FileCommands.key(KeyEvent.VK_DELETE, 0),
                ctx -> {
                    if (TextFocusGuard.isTextEditing()) {
                        return;
                    }
                    ctx.document().deleteSelection();
                }, CommandContext::hasSelection, null);
        delete.putIcon(Icons.delete());
        registry.register(delete);

        registry.register(new AppCommand(AppCommandIds.EDIT_SELECT_ALL, "全选", CommandScope.TRACK,
                FileCommands.key(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK),
                ctx -> ctx.document().selectAll(), CommandContext::hasTrack, null));

        AppCommand quantize = new AppCommand(AppCommandIds.EDIT_QUANTIZE, "量化到网格", CommandScope.SELECTION,
                FileCommands.key(KeyEvent.VK_Q, InputEvent.CTRL_DOWN_MASK),
                ctx -> ctx.document().quantizeSelection(), CommandContext::hasSelection, null);
        quantize.putIcon(Icons.grid());
        registry.register(quantize);
        registry.register(new AppCommand(AppCommandIds.EDIT_VELOCITY_UP, "力度 +10", CommandScope.SELECTION,
                FileCommands.key(KeyEvent.VK_CLOSE_BRACKET, InputEvent.CTRL_DOWN_MASK),
                ctx -> ctx.document().adjustVelocity(10), CommandContext::hasSelection, null));

        registry.register(new AppCommand(AppCommandIds.EDIT_VELOCITY_DOWN, "力度 -10", CommandScope.SELECTION,
                FileCommands.key(KeyEvent.VK_OPEN_BRACKET, InputEvent.CTRL_DOWN_MASK),
                ctx -> ctx.document().adjustVelocity(-10), CommandContext::hasSelection, null));

        registry.register(new AppCommand(AppCommandIds.EDIT_SPLIT_AT_PLAYHEAD, "在播放头拆分", CommandScope.SELECTION,
                FileCommands.key(KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK),
                ctx -> ctx.document().splitSelectedAt(ctx.document().playheadTick()),
                CommandContext::hasSelection, null));

        // 输入模式：WASD 移动光标、Q 试听、空格按住多久就输入多长（Esc 退出）
        // Ctrl+I 三态循环：关 → 输入模式＋节奏辅助 → 输入模式 → 关
        AppCommand inputMode = new AppCommand(AppCommandIds.EDIT_INPUT_MODE, "输入模式", CommandScope.DOCUMENT,
                FileCommands.key(KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK),
                ctx -> {
                    if (TextFocusGuard.isTextEditing()) {
                        return;
                    }
                    MidiDocumentView view = ctx.document();
                    if (!view.inputMode()) {
                        view.setInputMode(true);
                        view.setInputBeatSnap(true);
                    } else if (view.inputBeatSnap()) {
                        view.setInputBeatSnap(false);
                    } else {
                        view.setInputMode(false);
                    }
                },
                CommandContext::hasDocument,
                ctx -> ctx.hasDocument() && ctx.document().inputMode());
        inputMode.putIcon(Icons.inputMode());
        registry.register(inputMode);

        // 输入模式的节奏辅助（可选开关）：起点 / 时值吸附到拍
        AppCommand beatSnap = new AppCommand(AppCommandIds.EDIT_INPUT_BEAT_SNAP, "节奏辅助", CommandScope.DOCUMENT,
                null,
                ctx -> ctx.document().toggleInputBeatSnap(),
                ctx -> ctx.hasDocument() && ctx.document().inputMode(),
                ctx -> ctx.hasDocument() && ctx.document().inputBeatSnap());
        beatSnap.putIcon(Icons.beatSnap());
        beatSnap.putShortDescription("节奏辅助：输入的音符吸附到当前网格分辨率（要八分音符就把网格选成 1/8）");
        registry.register(beatSnap);

        // ---- 平行线
        registry.register(new AppCommand(AppCommandIds.NOTE_ADD, "添加平行线", CommandScope.TRACK,
                null,
                ctx -> ctx.document().addNoteAt(ctx.activeTrack(),
                        ctx.document().pendingAddTick(), ctx.document().pendingAddPitch()),
                CommandContext::hasTrack, null));

        registry.register(new AppCommand(AppCommandIds.NOTE_LOCK, "收尾锁定", CommandScope.SELECTION,
                null, ctx -> ctx.document().lockSelection(), CommandContext::hasSelection, null));

        registry.register(new AppCommand(AppCommandIds.NOTE_UNLOCK, "解除锁定", CommandScope.SELECTION,
                null, ctx -> ctx.document().unlockSelection(),
                EditCommands::anyLockTouchesSelection, null));

        registry.register(new AppCommand(AppCommandIds.NOTE_DELETE, "删除平行线", CommandScope.SELECTION,
                null, ctx -> ctx.document().deleteSelection(), CommandContext::hasSelection, null));

        registry.register(new AppCommand(AppCommandIds.NOTE_SPLIT, "拆分平行线", CommandScope.SELECTION,
                null, ctx -> ctx.document().splitSelectedAt(ctx.document().pendingSplitTick()),
                CommandContext::hasSelection, null));

        registry.register(new AppCommand(AppCommandIds.NOTE_MOVE_UP, "上移半音", CommandScope.SELECTION,
                null,
                ctx -> {
                    if (TextFocusGuard.isTextEditing()) {
                        return;
                    }
                    ctx.document().moveSelection(0, 1);
                }, CommandContext::hasSelection, null));

        registry.register(new AppCommand(AppCommandIds.NOTE_MOVE_DOWN, "下移半音", CommandScope.SELECTION,
                null,
                ctx -> {
                    if (TextFocusGuard.isTextEditing()) {
                        return;
                    }
                    ctx.document().moveSelection(0, -1);
                }, CommandContext::hasSelection, null));

        registry.register(new AppCommand(AppCommandIds.NOTE_MOVE_LEFT, "左移网格", CommandScope.SELECTION,
                null,
                ctx -> {
                    if (TextFocusGuard.isTextEditing()) {
                        return;
                    }
                    ctx.document().moveSelection(-gridStep(ctx.document()), 0);
                },
                CommandContext::hasSelection, null));

        registry.register(new AppCommand(AppCommandIds.NOTE_MOVE_RIGHT, "右移网格", CommandScope.SELECTION,
                null,
                ctx -> {
                    if (TextFocusGuard.isTextEditing()) {
                        return;
                    }
                    ctx.document().moveSelection(gridStep(ctx.document()), 0);
                },
                CommandContext::hasSelection, null));

        // 方向键：移动选中框（换选中的对象），每换一次就试听新选中的那条
        registry.register(new AppCommand(AppCommandIds.NOTE_SELECT_LEFT, "选中左一条", CommandScope.DOCUMENT,
                FileCommands.key(KeyEvent.VK_LEFT, 0),
                ctx -> {
                    if (TextFocusGuard.isTextEditing()) {
                        return;
                    }
                    ctx.document().stepSelection(-1, 0);
                }, CommandContext::hasDocument, null));

        registry.register(new AppCommand(AppCommandIds.NOTE_SELECT_RIGHT, "选中右一条", CommandScope.DOCUMENT,
                FileCommands.key(KeyEvent.VK_RIGHT, 0),
                ctx -> {
                    if (TextFocusGuard.isTextEditing()) {
                        return;
                    }
                    ctx.document().stepSelection(1, 0);
                }, CommandContext::hasDocument, null));

        registry.register(new AppCommand(AppCommandIds.NOTE_SELECT_UP, "选中上一条", CommandScope.DOCUMENT,
                FileCommands.key(KeyEvent.VK_UP, 0),
                ctx -> {
                    if (TextFocusGuard.isTextEditing()) {
                        return;
                    }
                    ctx.document().stepSelection(0, 1);
                }, CommandContext::hasDocument, null));

        registry.register(new AppCommand(AppCommandIds.NOTE_SELECT_DOWN, "选中下一条", CommandScope.DOCUMENT,
                FileCommands.key(KeyEvent.VK_DOWN, 0),
                ctx -> {
                    if (TextFocusGuard.isTextEditing()) {
                        return;
                    }
                    ctx.document().stepSelection(0, -1);
                }, CommandContext::hasDocument, null));

        registry.register(new AppCommand(AppCommandIds.NOTE_LENGTHEN, "拉长平行线", CommandScope.SELECTION,
                FileCommands.key(KeyEvent.VK_RIGHT, InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
                ctx -> ctx.document().resizeSelection(gridStep(ctx.document())),
                CommandContext::hasSelection, null));

        registry.register(new AppCommand(AppCommandIds.NOTE_SHORTEN, "缩短平行线", CommandScope.SELECTION,
                FileCommands.key(KeyEvent.VK_LEFT, InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
                ctx -> ctx.document().resizeSelection(-gridStep(ctx.document())),
                CommandContext::hasSelection, null));

        // Ctrl+←/→：只动选区的外边界（内侧接缝不动，相邻的几条当成一个整体）
        registry.register(new AppCommand(AppCommandIds.NOTE_EXTEND_RIGHT, "右端延长", CommandScope.SELECTION,
                FileCommands.key(KeyEvent.VK_RIGHT, InputEvent.CTRL_DOWN_MASK),
                ctx -> {
                    if (TextFocusGuard.isTextEditing()) {
                        return;
                    }
                    ctx.document().extendSelectionEdge(true);
                },
                CommandContext::hasSelection, null));

        registry.register(new AppCommand(AppCommandIds.NOTE_EXTEND_LEFT, "左端延长", CommandScope.SELECTION,
                FileCommands.key(KeyEvent.VK_LEFT, InputEvent.CTRL_DOWN_MASK),
                ctx -> {
                    if (TextFocusGuard.isTextEditing()) {
                        return;
                    }
                    ctx.document().extendSelectionEdge(false);
                },
                CommandContext::hasSelection, null));

        // Shift+→：左端向右收；Shift+←：右端向左收（都是“缩短”，与 Ctrl 的外扩配对）
        registry.register(new AppCommand(AppCommandIds.NOTE_TRIM_LEFT, "左端右移（缩短）", CommandScope.SELECTION,
                FileCommands.key(KeyEvent.VK_RIGHT, InputEvent.SHIFT_DOWN_MASK),
                ctx -> {
                    if (TextFocusGuard.isTextEditing()) {
                        return;
                    }
                    ctx.document().shrinkSelectionEdge(true);
                },
                CommandContext::hasSelection, null));

        registry.register(new AppCommand(AppCommandIds.NOTE_TRIM_RIGHT, "右端左移（缩短）", CommandScope.SELECTION,
                FileCommands.key(KeyEvent.VK_LEFT, InputEvent.SHIFT_DOWN_MASK),
                ctx -> {
                    if (TextFocusGuard.isTextEditing()) {
                        return;
                    }
                    ctx.document().shrinkSelectionEdge(false);
                },
                CommandContext::hasSelection, null));

        // 备用键位：保留旧的 Alt+方向键（平移/改音高）
        registry.bindAlias(AppCommandIds.NOTE_MOVE_UP, FileCommands.key(KeyEvent.VK_UP, InputEvent.ALT_DOWN_MASK));
        registry.bindAlias(AppCommandIds.NOTE_MOVE_DOWN, FileCommands.key(KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK));
        registry.bindAlias(AppCommandIds.NOTE_MOVE_LEFT, FileCommands.key(KeyEvent.VK_LEFT, InputEvent.ALT_DOWN_MASK));
        registry.bindAlias(AppCommandIds.NOTE_MOVE_RIGHT,
                FileCommands.key(KeyEvent.VK_RIGHT, InputEvent.ALT_DOWN_MASK));
        // 方向键语义（最终版）：
        // Ctrl+→ 右端向右（左端不动） Ctrl+← 左端向左（右端不动） —— 外扩
        // Shift+→ 左端向右（右端不动） Shift+← 右端向左（左端不动） —— 内收
        // 裸方向键留给平移/改音高，Alt 变体只是兼容旧习惯。

        registry.register(new AppCommand(AppCommandIds.REST_INSERT, "插入休止", CommandScope.TRACK,
                null,
                ctx -> ctx.document().insertRest(
                        ctx.document().grid().snap(ctx.document().pendingAddTick(), ctx.document().document().ppq()),
                        gridStep(ctx.document())),
                CommandContext::hasTrack, null));

        registry.register(new AppCommand(AppCommandIds.REST_DELETE, "删除休止", CommandScope.TRACK,
                null,
                ctx -> ctx.document().deleteRest(
                        ctx.document().grid().snap(ctx.document().pendingAddTick(), ctx.document().document().ppq()),
                        gridStep(ctx.document())),
                CommandContext::hasTrack, null));

        // ---- 音轨
        AppCommand addTrack = new AppCommand(AppCommandIds.TRACK_ADD, "添加音轨", CommandScope.DOCUMENT,
                FileCommands.key(KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
                ctx -> ctx.document().addTrack(), CommandContext::hasDocument, null);
        addTrack.putIcon(Icons.add());
        registry.register(addTrack);
        registry.register(new AppCommand(AppCommandIds.TRACK_REMOVE, "删除音轨", CommandScope.TRACK,
                null, ctx -> ctx.document().removeTrack(ctx.activeTrack()),
                ctx -> ctx.hasTrack() && ctx.document().document().tracks().size() > 1, null));

        registry.register(new AppCommand(AppCommandIds.TRACK_MOVE_UP, "音轨上移", CommandScope.TRACK,
                null, ctx -> ctx.document().moveTrack(ctx.activeTrack(), -1), CommandContext::hasTrack, null));

        registry.register(new AppCommand(AppCommandIds.TRACK_MOVE_DOWN, "音轨下移", CommandScope.TRACK,
                null, ctx -> ctx.document().moveTrack(ctx.activeTrack(), 1), CommandContext::hasTrack, null));

        registry.register(new AppCommand(AppCommandIds.TRACK_TOGGLE_MUTE, "静音音轨", CommandScope.TRACK,
                null, ctx -> ctx.document().toggleMute(ctx.activeTrack()), CommandContext::hasTrack,
                ctx -> ctx.hasTrack() && ctx.activeTrack().mute()));

        registry.register(new AppCommand(AppCommandIds.TRACK_TOGGLE_SOLO, "独奏音轨", CommandScope.TRACK,
                null, ctx -> ctx.document().toggleSolo(ctx.activeTrack()), CommandContext::hasTrack,
                ctx -> ctx.hasTrack() && ctx.activeTrack().solo()));
    }

    /** 选中项是否与任何收尾锁定相关（自己锁着别人，或被别人锁着）→ 决定「解除锁定」是否可点。 */
    private static boolean anyLockTouchesSelection(CommandContext ctx) {
        if (!ctx.hasSelection()) {
            return false;
        }
        java.util.Set<java.util.UUID> ids = new java.util.HashSet<>();
        for (Note note : ctx.selection()) {
            ids.add(note.id());
        }
        for (var track : ctx.document().document().tracks()) {
            for (Note note : track.notes()) {
                if (note.lockLeaderId() == null) {
                    continue;
                }
                if (ids.contains(note.id()) || ids.contains(note.lockLeaderId())) {
                    return true;
                }
            }
        }
        return false;
    }
}
