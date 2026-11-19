package com.example.mideditor.document;

import java.awt.GraphicsEnvironment;
import java.util.List;

import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import org.junit.jupiter.api.Test;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.app.MainFrame;
import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.Track;

/** 一起选中首尾相接的几条平行线缩放时，中间的接缝不能被拉开（否则会凭空长出休止符）。 */
class ContiguousResizeTest {

    @Test
    void shrinkingChainStaysContiguous() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder);
            Track track = view.activeTrack();
            List<Note> notes = addChain(track, 3, 480);
            SwingUtilities.invokeAndWait(() -> {
                view.selection().setAll(notes);
                view.beginSelectionEdit();
                view.applySelectionResize(-120);
                view.commitSelectionEdit("拉右端");
            });
            assertContiguousChain(track, 3);
        } finally {
            cleanup(app, holder[0]);
        }
    }

    @Test
    void growingChainStaysContiguous() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder);
            Track track = view.activeTrack();
            List<Note> notes = addChain(track, 3, 480);
            SwingUtilities.invokeAndWait(() -> {
                view.selection().setAll(notes);
                view.beginSelectionEdit();
                view.applySelectionResize(120);
                view.commitSelectionEdit("拉右端");
            });
            assertContiguousChain(track, 3);
        } finally {
            cleanup(app, holder[0]);
        }
    }

    @Test
    void keyboardResizeKeepsChainContiguous() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder);
            Track track = view.activeTrack();
            List<Note> notes = addChain(track, 4, 480);
            SwingUtilities.invokeAndWait(() -> {
                view.selection().setAll(notes);
                view.resizeSelection(-480);
            });
            assertContiguousChain(track, 4);
        } finally {
            cleanup(app, holder[0]);
        }
    }

    @Test
    void separatedNotesAreNotPulledTogether() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder);
            Track track = view.activeTrack();
            Note first = new Note(60, 0, 480, 100);
            Note far = new Note(62, 2400, 480, 100);
            SwingUtilities.invokeAndWait(() -> {
                track.addNote(first);
                track.addNote(far);
                track.sortNotes();
                view.selection().setAll(List.of(first, far));
                view.beginSelectionEdit();
                view.applySelectionResize(-120);
                view.commitSelectionEdit("拉右端");
            });
            assertEquals(0, first.startTick(), "第一条不动");
            assertEquals(2400, far.startTick(), "原本不挨着的不能被吸到一起");
        } finally {
            cleanup(app, holder[0]);
        }
    }

    private static List<Note> addChain(Track track, int count, long length) {
        List<Note> notes = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            notes.add(new Note(60 + i, i * length, length, 100));
        }
        for (Note note : notes) {
            track.addNote(note);
        }
        track.sortNotes();
        return notes;
    }

    /** 首尾相接的一组：中间没有休止符，音符数量不变，接缝严丝合缝。 */
    private static void assertContiguousChain(Track track, int expectedCount) {
        List<Note> notes = track.notes();
        assertEquals(expectedCount, notes.size(), "音符数量不应变化：" + describe(notes));
        for (Note note : notes) {
            assertFalse(note.isRest(), "连续的一组被拉开后会实体化出休止符：" + describe(notes));
        }
        for (int i = 1; i < notes.size(); i++) {
            assertEquals(notes.get(i - 1).endTick(), notes.get(i).startTick(),
                    "接缝不能有缝：" + describe(notes));
        }
    }

    private static String describe(List<Note> notes) {
        StringBuilder sb = new StringBuilder();
        for (Note note : notes) {
            sb.append(note.pitch()).append(':')
                    .append(note.startTick()).append("..").append(note.endTick()).append(' ');
        }
        return sb.toString();
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
