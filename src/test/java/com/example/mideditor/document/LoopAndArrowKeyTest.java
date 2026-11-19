package com.example.mideditor.document;

import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import org.junit.jupiter.api.Test;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.app.MainFrame;
import com.example.mideditor.command.AppCommandIds;
import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.Track;
import com.example.mideditor.ui.pianoroll.PianoRollCanvas;
import com.example.mideditor.ui.pianoroll.TimeRuler;

/** 循环域可调 + 方向键移动平行线。 */
class LoopAndArrowKeyTest {

    @Test
    void loopRangeIsExplicitAndSurvivesToggle() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                holder[0] = new MainFrame(app);
                holder[0].setVisible(false);
                app.documents().newDocument();
            });
            MidiDocumentView view = app.documents().activeView();

            SwingUtilities.invokeAndWait(() -> view.setLoopRange(960, 480));
            assertTrue(view.loopEnabled(), "设置循环域后应自动开启循环");
            assertEquals(480, view.loopStart(), "起止应自动交换");
            assertEquals(960, view.loopEnd());

            // 关掉再打开不应被选区/整篇文档覆盖
            SwingUtilities.invokeAndWait(view::toggleLoop);
            assertFalse(view.loopEnabled());
            SwingUtilities.invokeAndWait(view::toggleLoop);
            assertTrue(view.loopEnabled());
            assertEquals(480, view.loopStart(), "重新开启后循环域应保持不变");
            assertEquals(960, view.loopEnd());
        } finally {
            cleanup(app, holder[0]);
        }
    }

    @Test
    void loopToSelectionUsesSelectedNotes() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                holder[0] = new MainFrame(app);
                holder[0].setVisible(false);
                app.documents().newDocument();
            });
            MidiDocumentView view = app.documents().activeView();
            Track track = view.activeTrack();
            SwingUtilities.invokeAndWait(() -> {
                view.addNoteAt(track, 480, 60);
                view.addNoteAt(track, 960, 64);
                view.selection().setAll(track.notes());
                view.loopToSelection();
            });
            assertEquals(480, view.loopStart());
            assertTrue(view.loopEnd() >= 960 + 1, "循环终点应覆盖最后一条平行线的结束 tick");
            assertTrue(view.loopEnabled());
        } finally {
            cleanup(app, holder[0]);
        }
    }

    @Test
    void draggingLoopStripSetsTheLoopRegion() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                holder[0] = new MainFrame(app);
                holder[0].setVisible(false);
                app.documents().newDocument();
            });
            MidiDocumentView view = app.documents().activeView();
            TimeRuler ruler = view.pianoRoll().ruler();
            int y = TimeRuler.LOOP_STRIP_HEIGHT / 2;

            SwingUtilities.invokeAndWait(() -> {
                long now = System.currentTimeMillis();
                ruler.dispatchEvent(new MouseEvent(ruler, MouseEvent.MOUSE_PRESSED, now, 0,
                        0, y, 1, false, MouseEvent.BUTTON1));
                ruler.dispatchEvent(new MouseEvent(ruler, MouseEvent.MOUSE_DRAGGED, now + 10,
                        InputEvent.BUTTON1_DOWN_MASK, 384, y, 1, false, MouseEvent.BUTTON1));
                ruler.dispatchEvent(new MouseEvent(ruler, MouseEvent.MOUSE_RELEASED, now + 20, 0,
                        384, y, 1, false, MouseEvent.BUTTON1));
            });

            assertTrue(view.loopEnabled(), "在循环条上拖动应建立循环域并开启循环");
            assertEquals(0, view.loopStart(), "起点应对齐到按下位置");
            assertTrue(view.loopEnd() >= 1800 && view.loopEnd() <= 2000,
                    "终点 ≈ 4 拍（实际 " + view.loopEnd() + "）");
        } finally {
            cleanup(app, holder[0]);
        }
    }

    @Test
    void arrowKeysMoveTheSelectionBoxAndWasdMovesTheNotes() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                holder[0] = new MainFrame(app);
                holder[0].setVisible(false);
                app.documents().newDocument();
            });
            MidiDocumentView view = app.documents().activeView();
            PianoRollCanvas canvas = view.pianoRoll().canvas();
            Track track = view.activeTrack();
            SwingUtilities.invokeAndWait(() -> {
                view.addNoteAt(track, 480, 60);
                view.selection().setAll(track.notes());
            });
            Note note = track.notes().get(0);

            // 画布 WHEN_FOCUSED 的裸方向键现在归「选中框」
            assertEquals(AppCommandIds.NOTE_SELECT_UP, canvas.getInputMap(JComponent.WHEN_FOCUSED)
                    .get(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0)));
            assertEquals(AppCommandIds.NOTE_SELECT_LEFT, canvas.getInputMap(JComponent.WHEN_FOCUSED)
                    .get(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0)));

            // 移动命令本身还在（WASD 走的就是它们），画布能通过 ActionMap 父链执行
            Action up = canvas.getActionMap().get(AppCommandIds.NOTE_MOVE_UP);
            assertNotNull(up, "画布应能通过 ActionMap 父链执行命令");

            SwingUtilities.invokeAndWait(() -> app.refreshCommandStates());
            SwingUtilities.invokeAndWait(() -> up.actionPerformed(
                    new ActionEvent(canvas, ActionEvent.ACTION_PERFORMED, "note.moveUp")));
            assertEquals(61, note.pitch(), "上移半音");

            Action down = canvas.getActionMap().get(AppCommandIds.NOTE_MOVE_DOWN);
            SwingUtilities.invokeAndWait(() -> down.actionPerformed(
                    new ActionEvent(canvas, ActionEvent.ACTION_PERFORMED, "note.moveDown")));
            assertEquals(60, note.pitch(), "下移半音");

            long startBefore = note.startTick();
            Action right = canvas.getActionMap().get(AppCommandIds.NOTE_MOVE_RIGHT);
            SwingUtilities.invokeAndWait(() -> right.actionPerformed(
                    new ActionEvent(canvas, ActionEvent.ACTION_PERFORMED, "note.moveRight")));
            assertTrue(note.startTick() > startBefore, "右移一个网格");

            Action left = canvas.getActionMap().get(AppCommandIds.NOTE_MOVE_LEFT);
            SwingUtilities.invokeAndWait(() -> left.actionPerformed(
                    new ActionEvent(canvas, ActionEvent.ACTION_PERFORMED, "note.moveLeft")));
            assertEquals(startBefore, note.startTick(), "左移回原位");
        } finally {
            cleanup(app, holder[0]);
        }
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
