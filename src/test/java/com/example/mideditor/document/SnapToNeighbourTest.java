package com.example.mideditor.document;

import java.awt.GraphicsEnvironment;
import java.util.List;

import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import org.junit.jupiter.api.Test;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.app.MainFrame;
import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.Track;

/** 拖拽平行线时的吸附：网格 + 贴上相邻平行线。 */
class SnapToNeighbourTest {

    @Test
    void startSnapsToPreviousNoteEnd() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder);
            Track track = view.activeTrack();
            Note previous = new Note(60, 0, 200, 100); // 终点 200（不在 30 tick 网格上）
            Note moving = new Note(62, 480, 480, 100);
            SwingUtilities.invokeAndWait(() -> {
                track.addNote(previous);
                track.addNote(moving);
                track.sortNotes();
                view.selection().setAll(List.of(moving));
                view.beginSelectionEdit();
                // 目标起点 180：网格吸附会停在 180，但更靠近上一条的终点 200
                view.applySelectionMove(-300, 0);
            });
            assertEquals(200, moving.startTick(), "应吸附到上一条平行线的终点");
        } finally {
            cleanup(app, holder[0]);
        }
    }

    @Test
    void endSnapsToNextNoteStart() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder);
            Track track = view.activeTrack();
            Note moving = new Note(60, 0, 480, 100);
            Note next = new Note(64, 1000, 480, 100);
            SwingUtilities.invokeAndWait(() -> {
                track.addNote(moving);
                track.addNote(next);
                track.sortNotes();
                view.selection().setAll(List.of(moving));
                view.beginSelectionEdit();
                // 目标起点 510：终点 990 距下一条 1000 只差 10
                view.applySelectionMove(510, 0);
            });
            assertEquals(520, moving.startTick(), "终点应贴上下一条平行线的起点");
            assertEquals(1000, moving.endTick());
        } finally {
            cleanup(app, holder[0]);
        }
    }

    @Test
    void multiSelectionKeepsRelativeSpacing() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder);
            Track track = view.activeTrack();
            Note first = new Note(60, 0, 480, 100);
            Note second = new Note(62, 480, 480, 100);
            Note far = new Note(64, 3000, 480, 100);
            SwingUtilities.invokeAndWait(() -> {
                track.addNote(first);
                track.addNote(second);
                track.addNote(far);
                track.sortNotes();
                view.selection().setAll(List.of(first, second));
                view.beginSelectionEdit();
                view.applySelectionMove(600, 0);
            });
            assertEquals(600, first.startTick());
            assertEquals(1080, second.startTick(), "多选整体平移，不应各自乱吸");
            assertEquals(480, second.startTick() - first.startTick());
        } finally {
            cleanup(app, holder[0]);
        }
    }

    @Test
    void disabledSnapLeavesPositionRaw() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder);
            Track track = view.activeTrack();
            Note previous = new Note(60, 0, 200, 100);
            Note moving = new Note(62, 480, 480, 100);
            SwingUtilities.invokeAndWait(() -> {
                view.grid().setSnapEnabled(false);
                track.addNote(previous);
                track.addNote(moving);
                track.sortNotes();
                view.selection().setAll(List.of(moving));
                view.beginSelectionEdit();
                view.applySelectionMove(-300, 0);
            });
            assertEquals(180, moving.startTick(), "关闭吸附后位置应保持原样");
        } finally {
            cleanup(app, holder[0]);
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
