package com.example.mideditor.document;

import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;

import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import org.junit.jupiter.api.Test;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.app.MainFrame;
import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.Track;

/**
 * 拆分后再拖动其中一半，另一半不能被带着一起走。
 *
 * <p>
 * 曾经的事故：拆分出来的两半共用同一个 id（`Note.copy()` 保留 id），而选区 / 锁定 / 拖动
 * 都是按 id 认人的 —— 选右半段拖动，左半段也被拖走，原地只留下一段（用户看到的）
 * “和左边同音高的红色休止符”。
 * </p>
 */
class SplitAndMoveTest {

    @Test
    void movingOneHalfOfASplitNoteLeavesTheOtherHalfAlone() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView[] viewHolder = new MidiDocumentView[1];
            SwingUtilities.invokeAndWait(() -> {
                holder[0] = new MainFrame(app);
                holder[0].setVisible(false);
                app.documents().newDocument();
                MidiDocumentView view = app.documents().activeView();
                viewHolder[0] = view;
                Track track = view.activeTrack();
                Note note = new Note(60, 0, 960, 100);
                track.addNote(note);
                track.sortNotes();
                view.afterEdit(true);
                view.selection().setSingle(note);
            });
            MidiDocumentView view = viewHolder[0];
            Track track = view.activeTrack();

            SwingUtilities.invokeAndWait(() -> view.splitSelectedAt(480));

            List<Note> halves = sounding(track);
            assertEquals(2, halves.size(), "应拆成两段");
            Note left = halves.get(0);
            Note right = halves.get(1);
            assertNotEquals(left.id(), right.id(), "两半必须是两条独立的平行线");

            SwingUtilities.invokeAndWait(() -> view.selection().setSingle(right));
            SwingUtilities.invokeAndWait(() -> {
                view.beginSelectionEdit();
                view.applySelectionMove(480, 0);
                view.commitSelectionEdit("移动右半段");
            });

            assertEquals(0, left.startTick(), "左半段的起点不能被带着走");
            assertEquals(480, left.endTick(), "左半段的长度也不能变");
            assertFalse(left.isRest(), "左半段还是发声的平行线，不是休止符");
            assertEquals(960, right.startTick(), "右半段按拖动的距离移动");
            assertEquals(1440, right.endTick());

            // 中间空出来的那段本来就该变成休止符 —— 这是「休止符 = 空隙」的既定语义
            boolean gapIsRest = false;
            for (Note note : track.notes()) {
                if (note.isRest() && note.startTick() == 480 && note.endTick() == 960) {
                    gapIsRest = true;
                    assertEquals(60, note.pitch(), "休止符画在上一条平行线所在行");
                }
            }
            assertTrue(gapIsRest, "被拖走后留下的空隙应当实体化成休止符");
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

    private static List<Note> sounding(Track track) {
        List<Note> result = new ArrayList<>();
        for (Note note : track.notes()) {
            if (!note.isRest()) {
                result.add(note);
            }
        }
        result.sort(Track.BY_START);
        return result;
    }
}
