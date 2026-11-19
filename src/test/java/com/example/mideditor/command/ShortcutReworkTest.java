package com.example.mideditor.command;

import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import org.junit.jupiter.api.Test;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.app.MainFrame;
import com.example.mideditor.document.DocumentSession;
import com.example.mideditor.document.MidiDocumentView;

/** 播放键改成 F5 / Ctrl+F5 / F6；Ctrl+I 在三种输入状态之间循环。 */
class ShortcutReworkTest {

    @Test
    void playbackKeysAreF5AndF6() {
        JPanel keyTarget = new JPanel();
        AppContext app = new AppContext();
        app.initialize(keyTarget);
        var map = keyTarget.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);

        assertEquals(AppCommandIds.PLAY_PLAY_PAUSE, map.get(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0)),
                "F5 = 播放 / 暂停");
        assertEquals(AppCommandIds.PLAY_STOP,
                map.get(KeyStroke.getKeyStroke(KeyEvent.VK_F5, InputEvent.CTRL_DOWN_MASK)),
                "Ctrl+F5 = 停止");
        assertEquals(AppCommandIds.PLAY_REWIND, map.get(KeyStroke.getKeyStroke(KeyEvent.VK_F6, 0)),
                "F6 = 回到开头");
        assertEquals(AppCommandIds.PLAY_PLAY_PAUSE, map.get(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0)),
                "空格仍可播放 / 暂停");
        app.shutdown();
    }

    @Test
    void ctrlICyclesThroughThreeInputStates() throws Exception {
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
            Action input = app.registry().action(AppCommandIds.EDIT_INPUT_MODE);
            SwingUtilities.invokeAndWait(() -> app.refreshCommandStates());

            assertFalse(view.inputMode(), "初始不在输入模式");
            press(input);
            assertTrue(view.inputMode(), "第一下：进输入模式");
            assertTrue(view.inputBeatSnap(), "第一下同时带节奏辅助");

            press(input);
            assertTrue(view.inputMode(), "第二下：还在输入模式");
            assertFalse(view.inputBeatSnap(), "但节奏辅助关掉");

            press(input);
            assertFalse(view.inputMode(), "第三下：退出输入模式");
            assertFalse(view.inputBeatSnap());

            press(input);
            assertTrue(view.inputMode(), "第四下又从带辅助的那档开始");
            assertTrue(view.inputBeatSnap());
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

    private static void press(Action action) {
        action.actionPerformed(new ActionEvent(action, ActionEvent.ACTION_PERFORMED, "shortcutTest"));
    }
}
