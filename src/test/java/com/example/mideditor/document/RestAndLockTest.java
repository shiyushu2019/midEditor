package com.example.mideditor.document;

import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.List;

import javax.sound.midi.ShortMessage;
import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.app.MainFrame;
import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.Track;
import com.example.mideditor.engine.PlaybackPlan;
import com.example.mideditor.io.MidiFileCodec;

/** 休止符实体化 + 收尾锁定。 */
class RestAndLockTest {

    @Test
    void gapBecomesSelectableRestThatDoesNotSound() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder);
            Track track = view.activeTrack();
            SwingUtilities.invokeAndWait(() -> {
                track.addNote(new Note(60, 0, 480, 100));
                track.addNote(new Note(67, 960, 480, 100));
                track.sortNotes();
                view.afterEdit(true); // 相当于一次编辑之后的同步
            });
            Note rest = track.notes().stream().filter(Note::isRest).findFirst().orElse(null);
            assertNotNull(rest, "两条平行线之间的空隙应自动实体化成休止符");
            assertEquals(60, rest.pitch(), "休止符落在上一条所在的行");
            assertEquals(480, rest.startTick());
            assertEquals(480, rest.durationTicks());

            // 与平行线同地位：能被选中、能锁定
            SwingUtilities.invokeAndWait(() -> {
                view.selection().setAll(List.of(rest));
                assertEquals(1, view.selection().size(), "休止符应可被选中");
                view.lockSelection();
            });
            assertEquals(960, rest.endTick(), "锁定后休止符与下一条首尾相接");

            PlaybackPlan plan = PlaybackPlan.build(view.document(), false);
            assertTrue(plan.events().stream()
                    .filter(e -> e.command() == ShortMessage.NOTE_ON)
                    .allMatch(e -> e.tick() != 480),
                    "休止符不应产生 note-on");
            assertTrue(plan.events().stream()
                    .anyMatch(e -> e.command() == ShortMessage.NOTE_ON && e.data1() == 60),
                    "普通平行线仍应发声");
        } finally {
            cleanup(app, holder[0]);
        }
    }

    @Test
    void lockAlignsFollowerAndMovesTogether() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder);
            Track track = view.activeTrack();
            Note leader = new Note(60, 0, 480, 100);
            Note follower = new Note(60, 960, 480, 100);
            SwingUtilities.invokeAndWait(() -> {
                track.addNote(leader);
                track.addNote(follower);
                track.sortNotes();
                view.selection().setAll(List.of(leader));
                view.lockSelection();
            });
            assertEquals(leader.id(), follower.lockLeaderId(), "后一条应锁在前一条的尾上");
            assertEquals(leader.endTick(), follower.startTick(), "锁定后应首尾对齐");

            // 拖动前一条：两条一起动，锁定关系不散
            SwingUtilities.invokeAndWait(() -> {
                view.selection().setAll(List.of(leader));
                view.beginSelectionEdit();
                view.applySelectionMove(240, 0);
                view.commitSelectionEdit("移动平行线");
            });
            assertEquals(240, leader.startTick());
            assertEquals(leader.endTick(), follower.startTick(), "一起移动后仍应贴合");

            // 方向键移动同理：选跟随者也会带着前一条一起动
            long leaderStart = leader.startTick();
            long followerStart = follower.startTick();
            SwingUtilities.invokeAndWait(() -> {
                view.selection().setAll(List.of(follower));
                view.moveSelection(30, 0);
            });
            assertEquals(followerStart + 30, follower.startTick());
            assertEquals(leaderStart + 30, leader.startTick(), "移动跟随者时前一条也应一起移动");
            assertEquals(leader.endTick(), follower.startTick(), "移动跟随者后仍贴合");
        } finally {
            cleanup(app, holder[0]);
        }
    }

    @Test
    void resizingFollowerKeepsTheJointAndSparesTheLeader() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder);
            Track track = view.activeTrack();
            Note leader = new Note(60, 0, 480, 100);
            Note follower = new Note(60, 480, 480, 100);
            SwingUtilities.invokeAndWait(() -> {
                track.addNote(leader);
                track.addNote(follower);
                track.sortNotes();
                view.selection().setAll(List.of(leader));
                view.lockSelection();
                // 拖 B 的右端
                view.selection().setAll(List.of(follower));
                view.beginSelectionEdit();
                view.applySelectionResize(240);
                view.commitSelectionEdit("调整平行线长度");
            });
            assertEquals(720, follower.durationTicks(), "只有被拖的那条变长");
            assertEquals(480, follower.startTick(), "锁点不该动");
            assertEquals(480, leader.durationTicks(), "被锁住的前一条长度不变");
            assertEquals(0, leader.startTick());
        } finally {
            cleanup(app, holder[0]);
        }
    }

    @Test
    void resizingLeaderSlidesFollowerButDoesNotStretchIt() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder);
            Track track = view.activeTrack();
            Note leader = new Note(60, 0, 480, 100);
            Note follower = new Note(60, 480, 480, 100);
            SwingUtilities.invokeAndWait(() -> {
                track.addNote(leader);
                track.addNote(follower);
                track.sortNotes();
                view.selection().setAll(List.of(leader));
                view.lockSelection();
                // 拖 A 的右端：锁点是 A 的尾巴，所以 B 只能整体后移，但长度不变
                view.beginSelectionEdit();
                view.applySelectionResize(240);
                view.commitSelectionEdit("调整平行线长度");
            });
            assertEquals(720, leader.durationTicks(), "只有被拖的那条变长");
            assertEquals(720, follower.startTick(), "后一条跟着锁点平移");
            assertEquals(480, follower.durationTicks(), "后一条不该被拉长");
        } finally {
            cleanup(app, holder[0]);
        }
    }

    @Test
    void keyboardResizeAlsoSparesTheLockedPartner() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder);
            Track track = view.activeTrack();
            Note leader = new Note(60, 0, 480, 100);
            Note follower = new Note(60, 480, 480, 100);
            SwingUtilities.invokeAndWait(() -> {
                track.addNote(leader);
                track.addNote(follower);
                track.sortNotes();
                view.selection().setAll(List.of(leader));
                view.lockSelection();
                view.selection().setAll(List.of(follower));
                view.resizeSelection(120);
            });
            assertEquals(600, follower.durationTicks());
            assertEquals(480, follower.startTick(), "Shift+→ 调长度不该移动锁点");
            assertEquals(480, leader.durationTicks(), "Shift+→ 不该拉长被锁住的前一条");
        } finally {
            cleanup(app, holder[0]);
        }
    }

    @Test
    void unlockBreaksTheGroup() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder);
            Track track = view.activeTrack();
            Note leader = new Note(60, 0, 480, 100);
            Note follower = new Note(60, 480, 480, 100);
            SwingUtilities.invokeAndWait(() -> {
                track.addNote(leader);
                track.addNote(follower);
                track.sortNotes();
                view.selection().setAll(List.of(leader));
                view.lockSelection();
                view.unlockSelection();
            });
            assertNull(follower.lockLeaderId(), "解除锁定后关系应清空");

            long followerStart = follower.startTick();
            SwingUtilities.invokeAndWait(() -> view.moveSelection(0, 1));
            assertEquals(followerStart, follower.startTick(), "解锁后移动不再带着对方");
        } finally {
            cleanup(app, holder[0]);
        }
    }

    @Test
    void restSurvivesSaveAndLoad(@TempDir Path dir) throws Exception {
        MidiDocument document = new MidiDocument(480);
        Track track = new Track("钢琴", 0, 0, java.awt.Color.BLUE);
        track.addNote(new Note(60, 0, 480, 100));
        track.addNote(Note.rest(64, 480, 480));
        document.addTrack(track);

        Path file = dir.resolve("rests.mid");
        MidiFileCodec.write(document, file);
        MidiDocument loaded = MidiFileCodec.read(file);

        Track loadedTrack = loaded.tracks().get(0);
        Note rest = loadedTrack.notes().stream().filter(Note::isRest).findFirst().orElse(null);
        assertNotNull(rest, "重新打开后休止符应保留");
        assertEquals(64, rest.pitch());
        assertEquals(480, rest.startTick());
        assertEquals(480, rest.durationTicks());
        assertEquals(1, loadedTrack.notes().stream().filter(Note::isRest).count(), "休止符不应被重复保存");
        assertEquals(1, loadedTrack.notes().stream().filter(n -> !n.isRest()).count(),
                "普通平行线不应被误标成休止符");
        assertTrue(PlaybackPlan.build(loaded, false).events().stream()
                .noneMatch(e -> e.command() == ShortMessage.NOTE_ON && e.data1() == 64),
                "读回的休止符依旧不发声");
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
