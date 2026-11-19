package com.example.mideditor.document;

import java.awt.GraphicsEnvironment;
import java.awt.event.KeyEvent;
import java.util.List;

import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import org.junit.jupiter.api.Test;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.app.ArrowKeyDispatcher;
import com.example.mideditor.app.InputModeDispatcher;
import com.example.mideditor.app.MainFrame;
import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.Track;

/**
 * 两套键盘语义：
 *
 * <pre>
 *   非输入模式：W/A/S/D = 移动选中的平行线（上下改音高、左右挪一格）
 *   输入模式：  W/A/S/D = 移动红色光标
 *   任意时候：  ←/→/↑/↓ = 移动选中框（换选中的那条），并试听新选中的对象
 * </pre>
 */
class SelectionStepTest {

    @Test
    void wasdMovesTheSelectedNoteOutsideInputMode() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            Note note = new Note(60, 480, 480, 100);
            MidiDocumentView view = openDocument(app, holder, List.of(note));
            ArrowKeyDispatcher dispatcher = new ArrowKeyDispatcher(app);
            long step = view.grid().resolution().ticksPerGrid(view.document().ppq());
            SwingUtilities.invokeAndWait(() -> view.selection().setSingle(note));

            press(app, dispatcher, KeyEvent.VK_W, 0);
            assertEquals(61, note.pitch(), "W 上移半音");
            press(app, dispatcher, KeyEvent.VK_S, 0);
            assertEquals(60, note.pitch(), "S 下移半音");

            long startBefore = note.startTick();
            press(app, dispatcher, KeyEvent.VK_D, 0);
            assertEquals(startBefore + step, note.startTick(), "D 右移一格");
            press(app, dispatcher, KeyEvent.VK_A, 0);
            assertEquals(startBefore, note.startTick(), "A 左移一格");
        } finally {
            close(app, holder[0]);
        }
    }

    @Test
    void wasdDrivesTheCursorInsteadOfTheNoteInInputMode() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            Note note = new Note(60, 480, 480, 100);
            MidiDocumentView view = openDocument(app, holder, List.of(note));
            ArrowKeyDispatcher arrows = new ArrowKeyDispatcher(app);
            InputModeDispatcher input = new InputModeDispatcher(app);
            SwingUtilities.invokeAndWait(() -> view.setInputMode(true));
            int cursorPitch = view.inputCursorPitch();

            // 输入模式里 W 归光标：方向键分发器不接管，真正动手的是 InputModeDispatcher
            press(app, arrows, KeyEvent.VK_W, 0);
            assertEquals(60, note.pitch(), "输入模式里平行线不动");
            press(app, input, KeyEvent.VK_W, 0);
            assertEquals(cursorPitch + 1, view.inputCursorPitch(), "输入模式里 W 动光标");
        } finally {
            close(app, holder[0]);
        }
    }

    @Test
    void arrowsWalkTheSelectionBoxWithoutMovingAnything() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            Note first = new Note(60, 0, 480, 100);
            Note second = new Note(64, 960, 480, 100);
            Note third = new Note(67, 1920, 480, 100);
            MidiDocumentView view = openDocument(app, holder, List.of(first, second, third));
            ArrowKeyDispatcher dispatcher = new ArrowKeyDispatcher(app);
            SwingUtilities.invokeAndWait(() -> view.selection().setSingle(first));

            press(app, dispatcher, KeyEvent.VK_RIGHT, 0);
            assertTrue(view.selection().contains(second), "→ 换到时间上的下一条");
            assertEquals(1, view.selection().size(), "选中框只有一条");

            press(app, dispatcher, KeyEvent.VK_RIGHT, 0);
            assertTrue(view.selection().contains(third), "再 → 到第三条");
            press(app, dispatcher, KeyEvent.VK_RIGHT, 0);
            assertTrue(view.selection().contains(third), "到底了不越界");

            press(app, dispatcher, KeyEvent.VK_LEFT, 0);
            assertTrue(view.selection().contains(second), "← 回上一条");
            press(app, dispatcher, KeyEvent.VK_LEFT, 0);
            assertTrue(view.selection().contains(first), "← 回到第一条");
            press(app, dispatcher, KeyEvent.VK_LEFT, 0);
            assertTrue(view.selection().contains(first), "到头了不越界");

            // 一个 tick 都没动
            assertEquals(0, first.startTick());
            assertEquals(960, second.startTick());
            assertEquals(1920, third.startTick());
            assertEquals(480, first.durationTicks());
        } finally {
            close(app, holder[0]);
        }
    }

    @Test
    void upAndDownWalkByPitch() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            Note low = new Note(60, 0, 480, 100);
            Note middle = new Note(62, 0, 480, 100);
            Note high = new Note(64, 0, 480, 100);
            MidiDocumentView view = openDocument(app, holder, List.of(low, middle, high));
            ArrowKeyDispatcher dispatcher = new ArrowKeyDispatcher(app);
            SwingUtilities.invokeAndWait(() -> view.selection().setSingle(middle));

            press(app, dispatcher, KeyEvent.VK_UP, 0);
            assertTrue(view.selection().contains(high), "↑ 到上面最近的那条");
            press(app, dispatcher, KeyEvent.VK_DOWN, 0);
            assertTrue(view.selection().contains(middle), "↓ 回到中间");
            press(app, dispatcher, KeyEvent.VK_DOWN, 0);
            assertTrue(view.selection().contains(low), "↓ 再往下一条");
        } finally {
            close(app, holder[0]);
        }
    }

    @Test
    void everyStepAuditionsTheNewSelectionWithItsOwnLength() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            // 默认 60 BPM：480 tick = 1 秒，所以 240 tick = 500ms
            Note first = new Note(60, 0, 960, 100);
            Note second = new Note(72, 960, 240, 100);
            MidiDocumentView view = openDocument(app, holder, List.of(first, second));
            ArrowKeyDispatcher dispatcher = new ArrowKeyDispatcher(app);
            SwingUtilities.invokeAndWait(() -> view.selection().setSingle(first));

            press(app, dispatcher, KeyEvent.VK_RIGHT, 0);
            long[] audition = view.lastAuditionInfo();
            assertNotNull(audition, "换选中对象就应当试听");
            assertEquals(72, audition[0], "响的是新选中的那条的音高");
            assertEquals(500, audition[1], "试听时长 = 它自己的时值");

            press(app, dispatcher, KeyEvent.VK_LEFT, 0);
            long[] back = view.lastAuditionInfo();
            assertNotNull(back);
            assertEquals(60, back[0]);
            assertEquals(2000, back[1], "960 tick = 2 秒，不再被压成 1.5 秒");
        } finally {
            close(app, holder[0]);
        }
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

    private static void press(AppContext app, java.awt.KeyEventDispatcher dispatcher, int keyCode,
            int modifiers) throws Exception {
        KeyEvent event = new KeyEvent(app.mainFrame(), KeyEvent.KEY_PRESSED, System.currentTimeMillis(),
                modifiers, keyCode, KeyEvent.CHAR_UNDEFINED);
        SwingUtilities.invokeAndWait(() -> dispatcher.dispatchKeyEvent(event));
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
