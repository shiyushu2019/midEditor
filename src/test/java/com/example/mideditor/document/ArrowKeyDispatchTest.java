package com.example.mideditor.document;

import java.awt.GraphicsEnvironment;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;

import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import org.junit.jupiter.api.Test;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.app.ArrowKeyDispatcher;
import com.example.mideditor.app.MainFrame;
import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.Track;

/**
 * 四键语义（最终版）：
 * 
 * <pre>
 *   Ctrl+→  右端向右（左端不动）
 *   Ctrl+←  左端向左（右端不动）
 *   Shift+→ 左端向右（右端不动）
 *   Shift+← 右端向左（左端不动）
 *   裸方向键：移动选中框（换选中对象），不动任何平行线
 * </pre>
 * 
 * 事件走 {@link ArrowKeyDispatcher}，不依赖焦点落在卷帘上。
 */
class ArrowKeyDispatchTest {

    @Test
    void ctrlExtendsOutwardAndShiftShrinksInward() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            Note[] note = new Note[1];
            SwingUtilities.invokeAndWait(() -> {
                holder[0] = new MainFrame(app);
                holder[0].setVisible(false);
                app.documents().newDocument();
                var view = app.documents().activeView();
                Track track = view.activeTrack();
                note[0] = new Note(60, 480, 480, 100); // 480..960，网格 120
                track.addNote(note[0]);
                track.sortNotes();
                view.afterEdit(true);
                view.selection().setAll(List.of(note[0]));
                view.grid().setSnapEnabled(false);
            });
            ArrowKeyDispatcher dispatcher = new ArrowKeyDispatcher(app);

            press(app, dispatcher, KeyEvent.VK_RIGHT, InputEvent.CTRL_DOWN_MASK);
            assertEquals(480, note[0].startTick(), "Ctrl+→ 左端不动");
            assertEquals(1080, note[0].endTick(), "Ctrl+→ 右端 +1 格");

            press(app, dispatcher, KeyEvent.VK_LEFT, InputEvent.CTRL_DOWN_MASK);
            assertEquals(360, note[0].startTick(), "Ctrl+← 左端 -1 格");
            assertEquals(1080, note[0].endTick(), "Ctrl+← 右端不动");

            press(app, dispatcher, KeyEvent.VK_RIGHT, InputEvent.SHIFT_DOWN_MASK);
            assertEquals(480, note[0].startTick(), "Shift+→ 左端 +1 格");
            assertEquals(1080, note[0].endTick(), "Shift+→ 右端不动");

            press(app, dispatcher, KeyEvent.VK_LEFT, InputEvent.SHIFT_DOWN_MASK);
            assertEquals(480, note[0].startTick(), "Shift+← 左端不动");
            assertEquals(960, note[0].endTick(), "Shift+← 右端 -1 格");

            // 裸方向键不再移动平行线：它只挪选中框（这里只有一条，挪不动）
            press(app, dispatcher, KeyEvent.VK_RIGHT, 0);
            assertEquals(480, note[0].startTick(), "裸 → 不动平行线");
            assertEquals(960, note[0].endTick(), "裸 → 也不改长度");
        } finally {
            close(app, holder[0]);
        }
    }

    @Test
    void shrinkStopsAtOneTickAndNeverGoesNegative() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            Note[] note = new Note[1];
            SwingUtilities.invokeAndWait(() -> {
                holder[0] = new MainFrame(app);
                holder[0].setVisible(false);
                app.documents().newDocument();
                var view = app.documents().activeView();
                Track track = view.activeTrack();
                note[0] = new Note(60, 0, 120, 100); // 只有一格
                track.addNote(note[0]);
                track.sortNotes();
                view.afterEdit(true);
                view.selection().setAll(List.of(note[0]));
            });
            ArrowKeyDispatcher dispatcher = new ArrowKeyDispatcher(app);
            press(app, dispatcher, KeyEvent.VK_LEFT, InputEvent.SHIFT_DOWN_MASK);
            press(app, dispatcher, KeyEvent.VK_RIGHT, InputEvent.SHIFT_DOWN_MASK);
            assertEquals(1, note[0].durationTicks(), "最短保留 1 tick");
            assertEquals(0, note[0].startTick(), "起点不会变成负数");
        } finally {
            close(app, holder[0]);
        }
    }

    @Test
    void shrinkingAdjacentPairOnlyTouchesTheOuterEdge() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            Note[] a = new Note[1];
            Note[] b = new Note[1];
            SwingUtilities.invokeAndWait(() -> {
                holder[0] = new MainFrame(app);
                holder[0].setVisible(false);
                app.documents().newDocument();
                var view = app.documents().activeView();
                Track track = view.activeTrack();
                a[0] = new Note(60, 0, 480, 100);
                b[0] = new Note(60, 480, 480, 100);
                track.addNote(a[0]);
                track.addNote(b[0]);
                track.sortNotes();
                view.afterEdit(true);
                view.selection().setAll(List.of(a[0], b[0]));
            });
            ArrowKeyDispatcher dispatcher = new ArrowKeyDispatcher(app);
            press(app, dispatcher, KeyEvent.VK_LEFT, InputEvent.SHIFT_DOWN_MASK);
            assertEquals(480, a[0].durationTicks(), "内侧那条不动");
            assertEquals(840, b[0].endTick(), "只收最外侧的尾巴");
            assertEquals(a[0].endTick(), b[0].startTick(), "接缝没被破坏");
        } finally {
            close(app, holder[0]);
        }
    }

    private static void close(AppContext app, MainFrame frame) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (DocumentSession session : app.documents().sessions()) {
                session.view().disposeView();
                if (session.frame() != null) {
                    session.frame().dispose();
                }
            }
            frame.dispose();
            app.shutdown();
        });
    }

    private static void press(AppContext app, ArrowKeyDispatcher dispatcher, int keyCode, int modifiers)
            throws Exception {
        KeyEvent event = new KeyEvent(app.mainFrame(), KeyEvent.KEY_PRESSED, System.currentTimeMillis(),
                modifiers, keyCode, KeyEvent.CHAR_UNDEFINED);
        SwingUtilities.invokeAndWait(() -> dispatcher.dispatchKeyEvent(event));
    }
}
