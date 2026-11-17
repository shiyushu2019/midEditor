package com.example.mideditor.document;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.app.MainFrame;
import com.example.mideditor.command.AppCommandIds;
import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.Track;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** 平行线编辑工作流：添加 / 拆分 / 移动 / 长度 / 量化 / 力度 / 休止 / 剪贴板 / 撤销。 */
class EditingWorkflowTest {

    private static void run(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }

    @Test
    void parallelLineEditingIsUndoable() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            run(() -> {
                holder[0] = new MainFrame(app);
                holder[0].setVisible(false);
            });
            DocumentSession[] session = new DocumentSession[1];
            run(() -> session[0] = app.documents().newDocument());
            MidiDocumentView view = session[0].view();
            Track track = view.activeTrack();

            // 添加平行线（默认长度 = 1/16 网格 = 120 ticks）
            run(() -> view.addNoteAt(track, 0, 60));
            assertEquals(1, track.notes().size());
            Note note = track.notes().get(0);
            assertEquals(120, note.durationTicks());
            assertEquals(100, note.velocity());

            // 拉长后再拆分
            run(() -> {
                view.selection().setSingle(note);
                view.resizeSelection(360);
            });
            assertEquals(480, track.notes().get(0).durationTicks());
            run(() -> {
                view.setPendingSplit(240);
                app.router().dispatch(AppCommandIds.NOTE_SPLIT);
            });
            assertEquals(2, track.notes().size());
            assertEquals(240, track.notes().get(0).endTick());

            // 批量移动（时间 + 音高）
            run(() -> {
                view.selection().setAll(new java.util.ArrayList<>(track.notes()));
                view.moveSelection(120, 2);
            });
            assertEquals(120, track.notes().get(0).startTick());
            assertEquals(62, track.notes().get(0).pitch());

            // 批量改长度
            run(() -> view.resizeSelection(120));
            assertEquals(360, track.notes().get(0).durationTicks());

            // 量化（先人为制造非网格起点）
            run(() -> {
                Note first = track.notes().get(0);
                first.setStartTick(133);
                view.selection().setSingle(first);
                view.quantizeSelection();
            });
            assertEquals(120, track.notes().get(0).startTick());

            // 力度（粗细）
            run(() -> view.adjustVelocity(10));
            assertEquals(110, track.notes().get(0).velocity());

            // 命令启用态跟随选区
            run(() -> app.refreshCommandStates());
            assertTrue(app.registry().action(AppCommandIds.EDIT_DELETE).isEnabled());
            assertTrue(app.registry().action(AppCommandIds.EDIT_UNDO).isEnabled());

            // 删除 + 撤销
            run(() -> view.deleteSelection());
            assertEquals(0, track.notes().size());
            run(() -> app.router().dispatch(AppCommandIds.EDIT_UNDO));
            assertEquals(2, track.notes().size());

            // 休止：插入休止使后续平行线右移，删除休止复原
            long startBefore = track.notes().get(0).startTick();
            run(() -> view.insertRest(0, 240));
            assertEquals(startBefore + 240, track.notes().get(0).startTick());
            run(() -> view.deleteRest(0, 240));
            assertEquals(startBefore, track.notes().get(0).startTick());

            // 剪贴板：复制后在播放头处粘贴
            run(() -> {
                view.selectAll();
                view.copySelection();
                view.seekTick(1920);
                view.paste();
            });
            assertEquals(4, track.notes().size());

            // 右键空白处添加（pending 坐标）
            run(() -> {
                view.setPendingAdd(2400, 72);
                app.router().dispatch(AppCommandIds.NOTE_ADD);
            });
            assertEquals(5, track.notes().size());
            assertTrue(track.notes().stream().anyMatch(n -> n.pitch() == 72 && n.startTick() == 2400));

            // 撤销全部后可回到初始状态
            run(() -> {
                while (view.editStack().canUndo()) {
                    view.editStack().undo();
                }
                view.afterEdit(true);
            });
            assertEquals(0, track.notes().size());
        } finally {
            run(() -> {
                for (DocumentSession session : app.documents().sessions()) {
                    session.view().disposeView();
                    if (session.frame() != null) {
                        session.frame().dispose();
                    }
                }
                if (holder[0] != null) {
                    holder[0].dispose();
                }
                app.shutdown();
            });
        }
    }

    @Test
    void pasteRequiresClipboardAndTrack() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            run(() -> {
                holder[0] = new MainFrame(app);
                holder[0].setVisible(false);
            });
            run(() -> app.documents().newDocument());
            run(() -> app.refreshCommandStates());
            assertFalse(app.registry().action(AppCommandIds.EDIT_PASTE).isEnabled());

            MidiDocumentView view = app.documents().activeView();
            Track track = view.activeTrack();
            run(() -> view.addNoteAt(track, 0, 60));
            run(() -> {
                view.selectAll();
                view.copySelection();
                app.refreshCommandStates();
            });
            assertTrue(app.registry().action(AppCommandIds.EDIT_PASTE).isEnabled());
            List<Note> notes = track.notes();
            assertEquals(1, notes.size());
        } finally {
            run(() -> {
                for (DocumentSession session : app.documents().sessions()) {
                    session.view().disposeView();
                    if (session.frame() != null) {
                        session.frame().dispose();
                    }
                }
                if (holder[0] != null) {
                    holder[0].dispose();
                }
                app.shutdown();
            });
        }
    }
}
