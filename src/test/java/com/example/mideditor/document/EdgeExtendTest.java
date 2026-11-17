package com.example.mideditor.document;

import java.util.List;

import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.app.MainFrame;
import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.Track;

/**
 * Ctrl+→ / Ctrl+←：只动选区外边界。
 * <ul>
 * <li>单条：左端不动右端外移（反之亦然）；</li>
 * <li>相邻多条：当成整体，只伸长最外侧，内侧接缝不交叉；</li>
 * <li>有空隙的多条：各自伸长；</li>
 * <li>不撞上未选中的邻居；</li>
 * <li>锁定：跟随者的头被钉住时左延跳过；锁住的一对整体右延；</li>
 * <li>撤销可用。</li>
 * </ul>
 */
class EdgeExtendTest {

    private static final long STEP = 120; // 默认 1/16 网格

    @Test
    void singleNoteKeepsLeftEdgeAndMovesRightEdge() throws Exception {
        Harness h = Harness.open();
        try {
            Note a = new Note(60, 480, 480, 100);
            h.add(a);
            h.run(() -> {
                h.view.selection().setAll(List.of(a));
                h.view.extendSelectionEdge(true);
            });
            assertEquals(480, a.startTick(), "左端不动");
            assertEquals(480 + STEP, a.durationTicks(), "右端右移一格");

            h.run(() -> h.view.extendSelectionEdge(false));
            assertEquals(480 - STEP, a.startTick(), "右端不动、左端左移一格");
            assertEquals(480 + 480 + STEP, a.endTick(), "右端保持不变（仍是上次延长后的尾巴）");
        } finally {
            h.close();
        }
    }

    @Test
    void adjacentPairGrowsAsOneUnitWithoutCrossing() throws Exception {
        Harness h = Harness.open();
        try {
            Note a = new Note(60, 0, 480, 100);
            Note b = new Note(60, 480, 480, 100); // 紧接 a
            h.add(a);
            h.add(b);
            h.run(() -> {
                h.view.selection().setAll(List.of(a, b));
                h.view.extendSelectionEdge(true);
            });
            assertEquals(480, a.durationTicks(), "内侧那条不动");
            assertEquals(480, b.startTick(), "接缝不动");
            assertEquals(480 + STEP, b.durationTicks(), "只伸长最外侧");
            assertEquals(a.endTick(), b.startTick(), "没有互相插进去");
        } finally {
            h.close();
        }
    }

    @Test
    void separatedNotesExtendIndividually() throws Exception {
        Harness h = Harness.open();
        try {
            Note a = new Note(60, 0, 240, 100);
            Note b = new Note(64, 1200, 240, 100); // 与 a 之间有空隙
            h.add(a);
            h.add(b);
            h.run(() -> {
                h.view.selection().setAll(List.of(a, b));
                h.view.extendSelectionEdge(true);
            });
            assertEquals(240 + STEP, a.durationTicks(), "各自伸长");
            assertEquals(240 + STEP, b.durationTicks(), "各自伸长");
        } finally {
            h.close();
        }
    }

    @Test
    void extensionStopsBeforeUnselectedNeighbour() throws Exception {
        Harness h = Harness.open();
        try {
            Note a = new Note(60, 0, 240, 100);
            Note b = new Note(60, 310, 240, 100); // 未选中，距离 a 尾巴只有 70 tick
            h.add(a);
            h.add(b);
            h.run(() -> {
                h.view.selection().setAll(List.of(a));
                h.view.extendSelectionEdge(true);
            });
            assertEquals(310, a.endTick(), "延长到邻居起点就停，不制造重叠");
        } finally {
            h.close();
        }
    }

    @Test
    void leftExtensionIsSkippedWhenHeadIsLocked() throws Exception {
        Harness h = Harness.open();
        try {
            Note leader = new Note(60, 0, 480, 100);
            Note follower = new Note(60, 480, 480, 100);
            h.add(leader);
            h.add(follower);
            h.run(() -> {
                h.view.selection().setAll(List.of(leader));
                h.view.lockSelection(); // follower 锁到 leader 尾巴上
                h.view.selection().setAll(List.of(follower));
                h.view.extendSelectionEdge(false);
            });
            assertEquals(480, follower.startTick(), "跟随者的头被锁点钉住，不能被左移");
            assertEquals(480, follower.durationTicks(), "长度也不该变");
        } finally {
            h.close();
        }
    }

    @Test
    void lockedPairExtendsRightAsAWhole() throws Exception {
        Harness h = Harness.open();
        try {
            Note leader = new Note(60, 0, 480, 100);
            Note follower = new Note(60, 480, 480, 100);
            h.add(leader);
            h.add(follower);
            h.run(() -> {
                h.view.selection().setAll(List.of(leader));
                h.view.lockSelection();
                h.view.selection().setAll(List.of(leader, follower));
                h.view.extendSelectionEdge(true);
            });
            assertEquals(480, leader.durationTicks(), "锁住的前一条不动");
            assertEquals(480 + STEP, follower.durationTicks(), "整组只伸长最外侧");
            assertEquals(leader.endTick(), follower.startTick(), "锁点依旧贴合");
        } finally {
            h.close();
        }
    }

    @Test
    void leaderExtensionDragsItsLockedFollower() throws Exception {
        Harness h = Harness.open();
        try {
            Note leader = new Note(60, 0, 480, 100);
            Note follower = new Note(60, 480, 480, 100);
            h.add(leader);
            h.add(follower);
            h.run(() -> {
                h.view.selection().setAll(List.of(leader));
                h.view.lockSelection();
                h.view.extendSelectionEdge(true); // 只选中 leader
            });
            assertEquals(480 + STEP, leader.durationTicks());
            assertEquals(leader.endTick(), follower.startTick(), "跟随者随锁点右移，不被拉长");
            assertEquals(480, follower.durationTicks());
        } finally {
            h.close();
        }
    }

    @Test
    void extensionIsUndoable() throws Exception {
        Harness h = Harness.open();
        try {
            Note a = new Note(60, 0, 480, 100);
            h.add(a);
            h.run(() -> {
                h.view.selection().setAll(List.of(a));
                h.view.extendSelectionEdge(true);
            });
            assertEquals(480 + STEP, a.durationTicks());
            h.run(() -> {
                h.view.editStack().undo();
                h.view.afterEdit(true);
            });
            Note restored = h.track.noteById(a.id());
            assertEquals(480, restored.durationTicks(), "一步撤销回到原长度");
        } finally {
            h.close();
        }
    }

    @Test
    void nothingHappensWhenThereIsNoRoom() throws Exception {
        Harness h = Harness.open();
        try {
            Note a = new Note(60, 0, 480, 100);
            h.add(a);
            h.run(() -> {
                h.view.selection().setAll(List.of(a));
                h.view.extendSelectionEdge(false); // 左边已经是 0
            });
            assertEquals(0, a.startTick());
            assertEquals(480, a.durationTicks());
        } finally {
            h.close();
        }
    }

    /** 建一个带 GUI 的最小文档环境。 */
    private static final class Harness {
        final AppContext app = new AppContext();
        final MainFrame[] frame = new MainFrame[1];
        MidiDocumentView view;
        Track track;

        static Harness open() throws Exception {
            Harness h = new Harness();
            SwingUtilities.invokeAndWait(() -> {
                h.frame[0] = new MainFrame(h.app);
                h.frame[0].setVisible(false);
                h.app.documents().newDocument();
                h.view = h.app.documents().activeView();
                h.track = h.view.activeTrack();
                h.view.grid().setSnapEnabled(false);
            });
            return h;
        }

        void add(Note note) throws Exception {
            run(() -> {
                track.addNote(note);
                track.sortNotes();
                view.afterEdit(true);
            });
        }

        void run(Runnable action) throws Exception {
            SwingUtilities.invokeAndWait(action);
        }

        void close() {
            try {
                SwingUtilities.invokeAndWait(() -> {
                    for (DocumentSession session : app.documents().sessions()) {
                        session.view().disposeView();
                        if (session.frame() != null) {
                            session.frame().dispose();
                        }
                    }
                    frame[0].dispose();
                    app.shutdown();
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
