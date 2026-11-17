package com.example.mideditor.document;

import java.awt.GraphicsEnvironment;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;

import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import org.junit.jupiter.api.Test;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.app.MainFrame;
import com.example.mideditor.command.AppCommandIds;
import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.Track;
import com.example.mideditor.ui.pianoroll.PianoRollCanvas;

/**
 * 方向键绑定必须真的指向「外边界延长」：
 * → 动右端、← 动左端；Ctrl 和 Shift 都一样（以前 Shift+← 是“右端往左缩”，容易误解）。
 */
class EdgeKeyBindingTest {

    @Test
    void arrowWithCtrlOrShiftAllMapToEdgeExtend() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            PianoRollCanvas[] canvasHolder = new PianoRollCanvas[1];
            MidiDocumentView[] viewHolder = new MidiDocumentView[1];
            SwingUtilities.invokeAndWait(() -> {
                holder[0] = new MainFrame(app);
                holder[0].setVisible(false);
                app.documents().newDocument();
                viewHolder[0] = app.documents().activeView();
                canvasHolder[0] = viewHolder[0].pianoRoll().canvas();
            });
            PianoRollCanvas canvas = canvasHolder[0];

            // 画布局部绑定（有焦点时优先）
            assertEquals(AppCommandIds.NOTE_EXTEND_RIGHT,
                    canvas.getInputMap(JComponent.WHEN_FOCUSED)
                            .get(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, InputEvent.CTRL_DOWN_MASK)),
                    "Ctrl+→ 应绑到“右端延长”");
            assertEquals(AppCommandIds.NOTE_EXTEND_LEFT,
                    canvas.getInputMap(JComponent.WHEN_FOCUSED)
                            .get(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, InputEvent.CTRL_DOWN_MASK)),
                    "Ctrl+← 应绑到“左端延长”");
            assertEquals(AppCommandIds.NOTE_TRIM_LEFT,
                    canvas.getInputMap(JComponent.WHEN_FOCUSED)
                            .get(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, InputEvent.SHIFT_DOWN_MASK)),
                    "Shift+→ 应是“左端向右收”");
            assertEquals(AppCommandIds.NOTE_TRIM_RIGHT,
                    canvas.getInputMap(JComponent.WHEN_FOCUSED)
                            .get(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, InputEvent.SHIFT_DOWN_MASK)),
                    "Shift+← 应是“右端向左收”");

            // 全局绑定（焦点不在画布上时兜底）
            JComponent rootPane = holder[0].getRootPane();
            assertEquals(AppCommandIds.NOTE_EXTEND_RIGHT,
                    rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                            .get(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, InputEvent.CTRL_DOWN_MASK)));
            assertEquals(AppCommandIds.NOTE_EXTEND_LEFT,
                    rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                            .get(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, InputEvent.CTRL_DOWN_MASK)));
            assertEquals(AppCommandIds.NOTE_TRIM_LEFT,
                    rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                            .get(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, InputEvent.SHIFT_DOWN_MASK)));
            assertEquals(AppCommandIds.NOTE_TRIM_RIGHT,
                    rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                            .get(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, InputEvent.SHIFT_DOWN_MASK)));

            // 命令本身要能把“左端向左”做出来
            Note[] note = new Note[1];
            SwingUtilities.invokeAndWait(() -> {
                Track track = viewHolder[0].activeTrack();
                note[0] = new Note(60, 480, 480, 100);
                track.addNote(note[0]);
                track.sortNotes();
                viewHolder[0].afterEdit(true);
                viewHolder[0].selection().setAll(List.of(note[0]));
                viewHolder[0].grid().setSnapEnabled(false);
            });
            SwingUtilities.invokeAndWait(() -> app.registry().action(AppCommandIds.NOTE_EXTEND_LEFT)
                    .actionPerformed(new java.awt.event.ActionEvent(canvas, 0, "")));
            assertEquals(360, note[0].startTick(), "左端向左延长一格");
            assertEquals(960, note[0].endTick(), "右端不变");

            SwingUtilities.invokeAndWait(() -> app.registry().action(AppCommandIds.NOTE_EXTEND_RIGHT)
                    .actionPerformed(new java.awt.event.ActionEvent(canvas, 0, "")));
            assertEquals(360, note[0].startTick(), "右端延长时左端不动");
            assertEquals(1080, note[0].endTick(), "右端右移一格");
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
