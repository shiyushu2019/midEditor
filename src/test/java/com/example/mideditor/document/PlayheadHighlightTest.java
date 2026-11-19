package com.example.mideditor.document;

import java.awt.GraphicsEnvironment;
import java.util.List;

import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import org.junit.jupiter.api.Test;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.app.MainFrame;
import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.Track;

/**
 * 播放头滑过平行线时，它的「发声高亮」要整条翻转，不能只翻转被窄带重绘盖住的那 8px。
 *
 * <p>
 * 曾经的现象：窄带重绘只刷播放头附近 ±8px，跨过一条线的尾部时，只把那一小段刷回了不亮，
 * 于是每条线末尾都留着固定一小段颜色不一致（用户看到的“最后总有一小段不会发亮”）。
 * </p>
 */
class PlayheadHighlightTest {

    @Test
    void crossingTheHeadOrTailIsDetectedSoTheWholeLineGetsRepainted() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView[] viewHolder = new MidiDocumentView[1];
            Note[] note = new Note[1];
            SwingUtilities.invokeAndWait(() -> {
                holder[0] = new MainFrame(app);
                holder[0].setVisible(false);
                app.documents().newDocument();
                MidiDocumentView view = app.documents().activeView();
                viewHolder[0] = view;
                Track track = view.activeTrack();
                note[0] = new Note(60, 480, 480, 100); // 480..960
                track.addNote(note[0]);
                track.sortNotes();
                view.afterEdit(true);
            });
            MidiDocumentView view = viewHolder[0];
            var roll = view.pianoRoll();

            assertTrue(roll.highlightChangedNotes(900, 970).contains(note[0]),
                    "跨过尾部 → 整条线要重刷");
            assertTrue(roll.highlightChangedNotes(400, 500).contains(note[0]),
                    "跨过头部 → 整条线要重刷");

            List<Note> inside = roll.highlightChangedNotes(600, 700);
            assertTrue(!inside.contains(note[0]), "线中间穿行不会改变高亮状态，不该整条重刷");

            assertTrue(!roll.highlightChangedNotes(1000, 1200).contains(note[0]),
                    "已经过去之后再走不会再有状态变化");
            assertTrue(roll.highlightChangedNotes(700, 700).isEmpty(), "播放头没动就什么都不用刷");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                for (DocumentSession session : app.documents().sessions()) {
                    session.view().disposeView();
                    if (session.frame() != null) {
                        session.frame().dispose();
                    }
                }
                holder[0].dispose();
                app.shutdown();
            });
        }
    }
}
