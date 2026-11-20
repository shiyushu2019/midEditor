package com.example.mideditor.document;

import java.awt.GraphicsEnvironment;
import java.util.List;

import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import org.junit.jupiter.api.Test;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.app.MainFrame;
import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.Track;

/** 编辑 → 一键移调：音符整体平移、调号跟着转，而且能撤销 / 重做。 */
class TransposeTest {

    @Test
    void transposingShiftsEveryNoteAndTheKeyAndCanBeUndone() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder,
                    List.of(new Note(60, 0, 240, 100), new Note(64, 480, 240, 100)));

            SwingUtilities.invokeAndWait(() -> view.transposeAll(2));
            assertEquals(List.of(62, 66), pitches(view.activeTrack()), "所有音平移两个半音");
            assertEquals(2, view.document().keyTonic(), "调号也跟着转：C → D");

            SwingUtilities.invokeAndWait(() -> view.undo());
            assertEquals(List.of(60, 64), pitches(view.activeTrack()), "撤销把音高退回去");
            assertEquals(0, view.document().keyTonic(), "撤销也把调号退回去");

            SwingUtilities.invokeAndWait(() -> view.redo());
            assertEquals(List.of(62, 66), pitches(view.activeTrack()), "重做再平移一次");
            assertEquals(2, view.document().keyTonic(), "重做也把调号带回来");
        } finally {
            close(app, holder[0]);
        }
    }

    @Test
    void transposingClampsAtTheEdgesOfTheMidiRange() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder,
                    List.of(new Note(126, 0, 240, 100), new Note(1, 480, 240, 100)));

            SwingUtilities.invokeAndWait(() -> view.transposeAll(5));
            assertEquals(List.of(127, 6), pitches(view.activeTrack()), "超出 0..127 的音高被夹住");
        } finally {
            close(app, holder[0]);
        }
    }

    @Test
    void transposingByZeroDoesNothing() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder, List.of(new Note(60, 0, 240, 100)));
            assertFalse(view.editStack().canUndo(), "前提：刚打开没有可撤销的编辑");
            SwingUtilities.invokeAndWait(() -> view.transposeAll(0));
            assertFalse(view.editStack().canUndo(), "0 半音不该产生一条空编辑");
        } finally {
            close(app, holder[0]);
        }
    }

    /** 只换调式（0 半音）：音高不动，调号变成小调 / 再变回来。 */
    @Test
    void transposingCanSwitchBetweenMajorAndMinor() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder, List.of(new Note(60, 0, 240, 100)));
            SwingUtilities.invokeAndWait(() -> view.transposeAll(0, Boolean.TRUE));
            assertTrue(view.document().keyMinor(), "换成小调");
            assertEquals(List.of(60), pitches(view.activeTrack()), "音高一个都没动");

            SwingUtilities.invokeAndWait(() -> view.undo());
            assertFalse(view.document().keyMinor(), "撤销把调式退回去");

            SwingUtilities.invokeAndWait(() -> view.transposeAll(3, Boolean.FALSE));
            assertFalse(view.document().keyMinor());
            assertEquals(3, view.document().keyTonic(), "C 小调基础上再上移 3 个半音 → D#");
            assertEquals(List.of(63), pitches(view.activeTrack()));
        } finally {
            close(app, holder[0]);
        }
    }

    private static List<Integer> pitches(Track track) {
        return track.notes().stream().filter(note -> !note.isRest()).map(Note::pitch).toList();
    }

    private static MidiDocumentView openDocument(AppContext app, MainFrame[] holder, List<Note> notes)
            throws Exception {
        MidiDocumentView[] holderView = new MidiDocumentView[1];
        SwingUtilities.invokeAndWait(() -> {
            holder[0] = new MainFrame(app);
            holder[0].setVisible(false);
            app.documents().newDocument();
            MidiDocumentView view = app.documents().activeView();
            holderView[0] = view;
            Track track = view.activeTrack();
            for (Note note : notes) {
                track.addNote(note);
            }
            track.sortNotes();
            view.afterEdit(true);
        });
        return holderView[0];
    }

    private static void close(AppContext app, MainFrame frame) throws Exception {
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
