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
 * 长文件（音符多、空隙多）不能出现平方级的编辑路径。
 *
 * <p>
 * 这里只卡一个很宽松的上限（2.5 秒），用来兜住「算法退化成 O(n²)」这类事故：
 * 修好之后 2 万音符的一次拖拽全过程是几十毫秒量级，而平方级实现会是数秒。
 * </p>
 */
class LongFileEditPerformanceTest {

    private static final int NOTES = 20000;

    @Test
    void draggingInALongFileStaysFast() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView[] viewHolder = new MidiDocumentView[1];
            long buildStart = System.nanoTime();
            SwingUtilities.invokeAndWait(() -> {
                holder[0] = new MainFrame(app);
                holder[0].setVisible(false);
                app.documents().newDocument();
                MidiDocumentView view = app.documents().activeView();
                viewHolder[0] = view;
                Track track = view.activeTrack();
                for (int i = 0; i < NOTES; i++) {
                    track.addNote(new Note(60, i * 240L, 120, 100)); // 每两条之间都留一段空隙 → 大量休止符
                }
                track.sortNotes();
                view.afterEdit(true);
            });
            MidiDocumentView view = viewHolder[0];
            long buildMs = (System.nanoTime() - buildStart) / 1_000_000;

            long start = System.nanoTime();
            SwingUtilities.invokeAndWait(() -> {
                Track track = view.activeTrack();
                view.selection().setAll(List.of(track.notes().get(0)));
                view.beginSelectionEdit();
                for (int step = 1; step <= 3; step++) {
                    view.applySelectionMove(240L * step, 0);
                }
                view.commitSelectionEdit("拖动");
            });
            long dragMs = (System.nanoTime() - start) / 1_000_000;

            assertTrue(buildMs < 2500, "2 万音符的建表 + 休止符实体化不该超过 2.5 秒，实际 " + buildMs + " ms");
            assertTrue(dragMs < 2500, "2 万音符的一次拖拽（开始 + 3 步 + 提交）不该超过 2.5 秒，实际 " + dragMs + " ms");
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
