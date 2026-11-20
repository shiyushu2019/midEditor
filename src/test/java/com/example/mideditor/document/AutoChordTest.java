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

/** 音轨 → 添加和弦：以当前音轨为旋律，和弦音按音级写到它下面的新音轨，且永远可以再点。 */
class AutoChordTest {

    private static final int[] TRIAD = { 2, 4 };

    @Test
    void chordTonesGoBelowTheMelodyTrackAndFollowTheMajorScale() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder,
                    List.of(new Note(60, 0, 240, 100), new Note(64, 480, 480, 80)));
            Track melody = view.activeTrack();
            int tracksBefore = view.document().tracks().size();

            int added = view.addChord(melody, TRIAD, false);
            assertEquals(4, added, "两个旋律音 → 三音、五音共 4 条和弦音");

            List<Track> tracks = view.document().tracks();
            assertEquals(tracksBefore + 2, tracks.size(), "三音、五音各一条新轨");
            assertEquals(0, tracks.indexOf(melody), "旋律原轨位置不变");

            Track third = tracks.get(tracks.indexOf(melody) + 1);
            Track fifth = tracks.get(tracks.indexOf(melody) + 2);
            assertEquals(melody.name() + MidiDocumentView.CHORD_TRACK_SEPARATOR + "三音", third.name());
            assertEquals(melody.name() + MidiDocumentView.CHORD_TRACK_SEPARATOR + "五音", fifth.name());

            assertEquals(List.of(60, 64), pitches(melody), "旋律原轨一条都不动");
            // C 大调：C→E、G，E→G、B
            assertEquals(List.of(64, 67), pitches(third));
            assertEquals(List.of(67, 71), pitches(fifth));

            Note firstThird = sounding(third).get(0);
            assertEquals(0, firstThird.startTick());
            assertEquals(240, firstThird.durationTicks());
            assertEquals(100, firstThird.velocity());
            assertEquals(4, view.selection().size(), "新生成的和弦音全部被选中");
        } finally {
            close(app, holder[0]);
        }
    }

    @Test
    void minorKeysUseTheNaturalMinorChord() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder, List.of(new Note(69, 0, 240, 100)));
            SwingUtilities.invokeAndWait(() -> view.setKeySignature(9, true)); // A 小调
            view.addChord(view.activeTrack(), TRIAD, false);

            // A 小调：A → C、E
            assertEquals(List.of(72), pitches(chordTrack(view, "三音")));
            assertEquals(List.of(76), pitches(chordTrack(view, "五音")));
        } finally {
            close(app, holder[0]);
        }
    }

    @Test
    void chromaticMelodyNotesFallBackToTheChordQuality() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder, List.of(new Note(61, 0, 240, 100)));
            view.addChord(view.activeTrack(), TRIAD, false); // C 大调：C#4 不在音阶上 → 大三和弦

            assertEquals(List.of(65), pitches(chordTrack(view, "三音")));
            assertEquals(List.of(68), pitches(chordTrack(view, "五音")));
        } finally {
            close(app, holder[0]);
        }
    }

    @Test
    void belowPutsTheChordUnderTheMelody() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder, List.of(new Note(60, 0, 240, 100)));
            view.addChord(view.activeTrack(), TRIAD, true);

            // C4 往下：三音 A3、五音 F3
            assertEquals(List.of(57), pitches(chordTrack(view, "三音")));
            assertEquals(List.of(53), pitches(chordTrack(view, "五音")));
        } finally {
            close(app, holder[0]);
        }
    }

    @Test
    void seventhAndSusChordsAreAvailable() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder, List.of(new Note(60, 0, 240, 100)));
            int tracksBefore = view.document().tracks().size();
            view.addChord(view.activeTrack(), new int[] { 2, 4, 6 }, false);
            assertEquals(List.of(64), pitches(chordTrack(view, "三音")));
            assertEquals(List.of(67), pitches(chordTrack(view, "五音")));
            assertEquals(List.of(71), pitches(chordTrack(view, "七音")), "七音的 C 是 B");
            assertEquals(tracksBefore + 3, view.document().tracks().size(), "七和弦三条轨");
        } finally {
            close(app, holder[0]);
        }
    }

    @Test
    void addingChordsAgainJustAddsAnotherSetOfTracks() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder, List.of(new Note(60, 0, 240, 100)));
            Track melody = view.activeTrack();
            int tracksBefore = view.document().tracks().size();

            view.addChord(melody, TRIAD, false);
            assertEquals(tracksBefore + 2, view.document().tracks().size());

            // 再点一次：就像再按一次「添加音轨」一样，老老实实再加一组，不删不改旧的
            assertEquals(2, view.addChord(melody, TRIAD, false), "又生成两个和弦音");
            assertEquals(tracksBefore + 4, view.document().tracks().size(), "两组和弦轨都在");
            assertEquals(2, countChordTracks(view, "三音"), "两条三音轨（新旧各一）");
            assertEquals(2, countChordTracks(view, "五音"));
            assertEquals(List.of(60), pitches(melody), "旋律还是一样");

            SwingUtilities.invokeAndWait(() -> view.undo());
            assertEquals(tracksBefore + 2, view.document().tracks().size(), "撤销只退掉后加的那一组");
            assertEquals(1, countChordTracks(view, "三音"));
        } finally {
            close(app, holder[0]);
        }
    }

    @Test
    void aChordTrackCanGetItsOwnChords() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder, List.of(new Note(60, 0, 240, 100)));
            view.addChord(view.activeTrack(), TRIAD, false);
            Track third = chordTrack(view, "三音");
            int tracksAfterFirst = view.document().tracks().size();

            // 和弦轨也是普通音轨：对它再加和弦，就在它下面再长出一组
            assertEquals(2, view.addChord(third, TRIAD, false));
            assertEquals(tracksAfterFirst + 2, view.document().tracks().size());
            int thirdIndex = view.document().tracks().indexOf(third);
            Track below = view.document().tracks().get(thirdIndex + 1);
            assertTrue(below.name().startsWith(third.name() + MidiDocumentView.CHORD_TRACK_SEPARATOR),
                    "新和弦轨挂在这条轨下面：" + below.name());
            assertEquals(List.of(67), pitches(below), "E 往上叠三音 = G");
        } finally {
            close(app, holder[0]);
        }
    }

    @Test
    void commandIsAlwaysEnabledForATrackEvenWhenItIsEmpty() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder, List.of());
            SwingUtilities.invokeAndWait(() -> app.refreshCommandStates());
            assertTrue(app.registry().action("track.addChord").isEnabled(), "空音轨也不发虚（点了就是什么都不做）");
            assertEquals(0, view.addChord(view.activeTrack(), TRIAD, false));
            assertFalse(view.editStack().canUndo(), "没有音就不产生空编辑");

            // 音轨面板右键菜单与顶部「音轨」菜单同款
            javax.swing.JPopupMenu popup = new com.example.mideditor.ui.frame.MenuBarBuilder(app).trackPopup();
            assertTrue(hasText(popup, "添加和弦…"), "音轨面板右键菜单里也有添加和弦");
        } finally {
            close(app, holder[0]);
        }
    }

    private static boolean hasText(java.awt.Container menu, String text) {
        for (java.awt.Component child : menu.getComponents()) {
            if (child instanceof javax.swing.JMenuItem item && text.equals(item.getText())) {
                return true;
            }
            if (child instanceof java.awt.Container nested && hasText(nested, text)) {
                return true;
            }
        }
        return false;
    }

    private static Track chordTrack(MidiDocumentView view, String role) {
        for (Track track : view.document().tracks()) {
            if (track.name().endsWith(MidiDocumentView.CHORD_TRACK_SEPARATOR + role)) {
                return track;
            }
        }
        return null;
    }

    private static int countChordTracks(MidiDocumentView view, String role) {
        int count = 0;
        for (Track track : view.document().tracks()) {
            if (track.name().endsWith(MidiDocumentView.CHORD_TRACK_SEPARATOR + role)) {
                count++;
            }
        }
        return count;
    }

    private static List<Integer> pitches(Track track) {
        assertNotNull(track, "和弦轨应该存在");
        return sounding(track).stream().map(Note::pitch).toList();
    }

    private static List<Note> sounding(Track track) {
        return track.notes().stream().filter(note -> !note.isRest()).toList();
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
