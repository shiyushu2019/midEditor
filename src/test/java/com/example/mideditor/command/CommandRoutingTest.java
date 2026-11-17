package com.example.mideditor.command;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import com.example.mideditor.app.AppContext;

class CommandRoutingTest {

    @Test
    void menuToolbarAndKeyboardShareTheSameActions() {
        JPanel keyTarget = new JPanel();
        AppContext app = new AppContext();
        app.initialize(keyTarget);
        app.refreshCommandStates();

        Action newDocument = app.registry().action(AppCommandIds.FILE_NEW);
        assertTrue(newDocument.isEnabled(), "应用级命令无文档时也应可用");
        assertFalse(app.registry().action(AppCommandIds.EDIT_UNDO).isEnabled());
        assertFalse(app.registry().action(AppCommandIds.EDIT_DELETE).isEnabled());
        assertFalse(app.registry().action(AppCommandIds.PLAY_PLAY_PAUSE).isEnabled());

        KeyStroke ctrlN = KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK);
        Object bound = keyTarget.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(ctrlN);
        assertEquals(AppCommandIds.FILE_NEW, bound);

        var map = keyTarget.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        assertEquals(AppCommandIds.FILE_SAVE, map.get(KeyStroke.getKeyStroke(KeyEvent.VK_S,
                InputEvent.CTRL_DOWN_MASK)), "Ctrl+S 必须保存");
        assertEquals(AppCommandIds.FILE_SAVE_AS, map.get(KeyStroke.getKeyStroke(KeyEvent.VK_S,
                InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK)));
        assertEquals(AppCommandIds.FILE_SAVE_AS, map.get(KeyStroke.getKeyStroke(KeyEvent.VK_F12, 0)),
                "F12 也应另存为（Word 习惯）");
        assertEquals(AppCommandIds.EDIT_REDO, map.get(KeyStroke.getKeyStroke(KeyEvent.VK_Y,
                InputEvent.CTRL_DOWN_MASK)), "Ctrl+Y 也应重做");

        KeyStroke space = KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0);
        assertEquals(AppCommandIds.PLAY_PLAY_PAUSE,
                keyTarget.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(space));

        assertFalse(app.registry().all().isEmpty());
    }

    @Test
    void commandErrorsAreRecordedInsteadOfCrashing() {
        JPanel keyTarget = new JPanel();
        AppContext app = new AppContext();
        app.initialize(keyTarget);
        app.registry().register(new AppCommand("test.boom", "boom", CommandScope.APPLICATION,
                null, ctx -> {
                    throw new Error("模拟运行时编译错误");
                }, ctx -> true, null));

        app.router().dispatch("test.boom");

        assertTrue(com.example.mideditor.util.Log.lines().stream()
                .anyMatch(line -> line.contains("test.boom")),
                "命令抛出的 Error 必须记入日志，不能静默消失");
    }
}
