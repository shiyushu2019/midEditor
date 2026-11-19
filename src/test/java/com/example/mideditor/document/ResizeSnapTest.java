package com.example.mideditor.document;

import java.awt.GraphicsEnvironment;
import java.util.List;

import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import org.junit.jupiter.api.Test;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.app.MainFrame;
import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.Track;

/** 缩放平行线（拖右端 / 键盘改长度）时，被拉的那条边必须吸附到网格上。 */
class ResizeSnapTest {

    @Test
    void draggedEdgeSnapsToGrid() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder);
            Track track = view.activeTrack();
            long step = gridStep(view);
            Note note = new Note(60, 0, 457, 100);
            SwingUtilities.invokeAndWait(() -> {
                track.addNote(note);
                view.selection().setAll(List.of(note));
                view.beginSelectionEdit();
                view.applySelectionResize(37);
                view.commitSelectionEdit("拉右端");
            });
            assertEquals(0, note.endTick() % step, "拖动结束后末尾必须在网格线上");
            assertEquals(0, note.startTick(), "起点不动");
        } finally {
            cleanup(app, holder[0]);
        }
    }

    @Test
    void multiSelectionEdgesAllLandOnGrid() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder);
            Track track = view.activeTrack();
            long step = gridStep(view);
            Note first = new Note(60, 0, 457, 100);
            Note second = new Note(61, 457, 470, 100);
            Note third = new Note(62, 927, 461, 100);
            SwingUtilities.invokeAndWait(() -> {
                track.addNote(first);
                track.addNote(second);
                track.addNote(third);
                track.sortNotes();
                view.selection().setAll(List.of(first, second, third));
                view.beginSelectionEdit();
                view.applySelectionResize(37);
                view.commitSelectionEdit("拉右端");
            });
            for (Note note : track.notes()) {
                assertEquals(0, note.startTick() % step, "起点应在网格线上");
                assertEquals(0, note.endTick() % step, "末尾应在网格线上");
            }
        } finally {
            cleanup(app, holder[0]);
        }
    }

    @Test
    void keyboardResizeAlsoSnaps() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder);
            Track track = view.activeTrack();
            long step = gridStep(view);
            Note note = new Note(60, 0, 450, 100);
            SwingUtilities.invokeAndWait(() -> {
                track.addNote(note);
                view.selection().setAll(List.of(note));
                view.resizeSelection(37);
            });
            assertEquals(0, note.endTick() % step, "键盘改长度后末尾也应落在网格线上");
        } finally {
            cleanup(app, holder[0]);
        }
    }

    @Test
    void snapOffKeepsRawEdge() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder);
            Track track = view.activeTrack();
            Note note = new Note(60, 0, 457, 100);
            SwingUtilities.invokeAndWait(() -> {
                view.grid().setSnapEnabled(false);
                track.addNote(note);
                view.selection().setAll(List.of(note));
                view.beginSelectionEdit();
                view.applySelectionResize(37);
                view.commitSelectionEdit("拉右端");
            });
            assertEquals(494, note.endTick(), "关闭吸附后保持原始位移");
            assertTrue(track.notes().size() == 1, "不应凭空长出休止符");
        } finally {
            cleanup(app, holder[0]);
        }
    }

    private static long gridStep(MidiDocumentView view) {
        return view.grid().resolution().ticksPerGrid(view.document().ppq());
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
