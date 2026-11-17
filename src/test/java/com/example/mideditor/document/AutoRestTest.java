package com.example.mideditor.document;

import java.awt.GraphicsEnvironment;
import java.util.List;

import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import org.junit.jupiter.api.Test;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.app.MainFrame;
import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.Track;
import com.example.mideditor.io.MidiFileCodec;

/** 空隙自动实体化成休止符：与平行线同地位（可选中 / 拖动 / 删除 / 撤销）。 */
class AutoRestTest {

    @Test
    void gapIsMaterializedAsRestAfterEveryEdit() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder);
            Track track = view.activeTrack();
            SwingUtilities.invokeAndWait(() -> {
                track.addNote(new Note(60, 0, 480, 100));
                track.addNote(new Note(64, 960, 480, 100));
                track.addNote(new Note(67, 1920, 480, 100));
                track.sortNotes();
                view.afterEdit(true);
            });
            List<Note> rests = track.notes().stream().filter(Note::isRest).toList();
            assertEquals(2, rests.size(), "两处空隙都应该各自成为一条休止符");
            assertEquals(480, rests.get(0).startTick());
            assertEquals(480, rests.get(0).durationTicks());
            assertEquals(60, rests.get(0).pitch());
            assertEquals(1440, rests.get(1).startTick());
            assertEquals(480, rests.get(1).durationTicks());
        } finally {
            cleanup(app, holder[0]);
        }
    }

    @Test
    void draggingRestPushesFollowingNotesAndKeepsItsHead() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder);
            Track track = view.activeTrack();
            Note[] rest = new Note[1];
            Note[] next = new Note[1];
            SwingUtilities.invokeAndWait(() -> {
                view.grid().setSnapEnabled(false);
                Note first = new Note(60, 0, 480, 100);
                next[0] = new Note(64, 960, 480, 100);
                track.addNote(first);
                track.addNote(next[0]);
                track.sortNotes();
                view.afterEdit(true);
                rest[0] = track.notes().stream().filter(Note::isRest).findFirst().orElseThrow();
                view.selection().setAll(List.of(rest[0]));
                view.beginSelectionEdit();
                view.applySelectionMove(240, 0);
                view.commitSelectionEdit("移动休止符");
            });
            assertNotNull(rest[0]);
            assertEquals(480, rest[0].startTick(), "休止符的头（锁点）不动");
            assertEquals(720, rest[0].durationTicks(), "拖动休止符 = 把这段静音拉长");
            assertEquals(1200, next[0].startTick(), "后面的音被一起推开");
        } finally {
            cleanup(app, holder[0]);
        }
    }

    @Test
    void closingTheGapRemovesTheRest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder);
            Track track = view.activeTrack();
            Note[] next = new Note[1];
            SwingUtilities.invokeAndWait(() -> {
                view.grid().setSnapEnabled(false);
                track.addNote(new Note(60, 0, 480, 100));
                next[0] = new Note(64, 960, 480, 100);
                track.addNote(next[0]);
                track.sortNotes();
                view.afterEdit(true);
                view.selection().setAll(List.of(next[0]));
                view.beginSelectionEdit();
                view.applySelectionMove(-480, 0);
                view.commitSelectionEdit("移动平行线");
            });
            assertEquals(480, next[0].startTick());
            assertTrue(track.notes().stream().noneMatch(Note::isRest), "接上以后就没有休止符了");
        } finally {
            cleanup(app, holder[0]);
        }
    }

    @Test
    void deletingRestPullsFollowingNotesBack() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder);
            Track track = view.activeTrack();
            Note[] next = new Note[1];
            SwingUtilities.invokeAndWait(() -> {
                track.addNote(new Note(60, 0, 480, 100));
                next[0] = new Note(64, 960, 480, 100);
                track.addNote(next[0]);
                track.sortNotes();
                view.afterEdit(true);
                Note rest = track.notes().stream().filter(Note::isRest).findFirst().orElseThrow();
                view.selection().setAll(List.of(rest));
                view.deleteSelection();
            });
            assertEquals(480, next[0].startTick(), "删掉休止符 = 把后面的音提上来补齐");
            assertTrue(track.notes().stream().noneMatch(Note::isRest));
        } finally {
            cleanup(app, holder[0]);
        }
    }

    @Test
    void undoTurnsTheGapBackIntoARest() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder);
            Track track = view.activeTrack();
            Note[] next = new Note[1];
            SwingUtilities.invokeAndWait(() -> {
                view.grid().setSnapEnabled(false);
                track.addNote(new Note(60, 0, 480, 100));
                next[0] = new Note(64, 960, 480, 100);
                track.addNote(next[0]);
                track.sortNotes();
                view.afterEdit(true);
                view.selection().setAll(List.of(next[0]));
                view.beginSelectionEdit();
                view.applySelectionMove(-480, 0);
                view.commitSelectionEdit("移动平行线");
            });
            assertFalse(track.notes().stream().anyMatch(Note::isRest));

            SwingUtilities.invokeAndWait(() -> {
                view.editStack().undo();
                view.afterEdit(true);
            });
            // 撤销会用快照（副本）恢复，所以要按 id 重新取当前对象
            Note restored = track.noteById(next[0].id());
            assertNotNull(restored);
            assertEquals(960, restored.startTick(), "撤销后后面那条音回到原位");
            List<Note> rests = track.notes().stream().filter(Note::isRest).toList();
            assertEquals(1, rests.size(), "空隙重新出现，休止符也跟着回来");
            assertEquals(480, rests.get(0).startTick());
            assertEquals(480, rests.get(0).durationTicks());
        } finally {
            cleanup(app, holder[0]);
        }
    }

    @Test
    void openingAFileMaterializesRestsWithoutAnyEdit() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        java.nio.file.Path file = java.nio.file.Files.createTempFile("mideditor-gap", ".mid");
        // 先把一个「中间有空洞」的 .mid 写出来（不经过任何编辑器视图）
        MidiDocument doc = new MidiDocument(480);
        Track track = new Track("旋律", 0, 0, java.awt.Color.BLUE);
        track.addNote(new Note(60, 0, 480, 100));
        track.addNote(new Note(64, 960, 480, 100));
        doc.addTrack(track);
        MidiFileCodec.write(doc, file);

        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                holder[0] = new MainFrame(app);
                holder[0].setVisible(false);
                app.documents().openPath(file);
            });
            Track opened = app.documents().activeView().activeTrack();
            List<Note> rests = opened.notes().stream().filter(Note::isRest).toList();
            assertEquals(1, rests.size(), "打开文件后空隙就应该是休止符，不用等第一次编辑");
            assertEquals(480, rests.get(0).startTick());
            assertEquals(480, rests.get(0).durationTicks());
        } finally {
            cleanup(app, holder[0]);
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    private static MidiDocumentView openDocument(AppContext app, MainFrame[] holder) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            holder[0] = new MainFrame(app);
            holder[0].setVisible(false);
            app.documents().newDocument();
        });
        return app.documents().activeView();
    }

    private static void cleanup(AppContext app, MainFrame frame) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (DocumentSession session : app.documents().sessions()) {
                session.view().disposeView();
                if (session.frame() != null) {
                    session.frame().dispose();
                }
            }
            if (frame != null) {
                frame.dispose();
            }
            app.shutdown();
        });
    }
}
