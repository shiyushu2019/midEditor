package com.example.mideditor.ui.frame;

import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import org.junit.jupiter.api.Test;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.app.MainFrame;
import com.example.mideditor.document.DocumentSession;

/** 帮助 → 快捷键：文档随程序打包、能解出，并且菜单里真的有这一项。 */
class ShortcutDocTest {

    @Test
    void shortcutDocumentIsBundledAndCanBeExtracted() throws Exception {
        Path file = ShortcutDoc.materialize();
        assertTrue(Files.exists(file), "应当能把快捷键文档解出来：" + file);
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(text.contains("Ctrl+Z"), "文档里要有撤销");
        assertTrue(text.contains("F5"), "文档里要有播放 / 暂停");
        assertTrue(text.contains("F4"), "文档里要有停止");
        assertTrue(text.contains("小键盘 1~7"), "文档里要有输入模式的小键盘输入");
        assertTrue(text.length() > 1500, "文档不能是空壳：" + text.length());
    }

    @Test
    void helpMenuHasAShortcutItem() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                holder[0] = new MainFrame(app);
                holder[0].setVisible(false);
            });
            JMenu help = findHelpMenu(holder[0].getJMenuBar());
            assertNotNull(help, "主窗口应当有「帮助」菜单");
            boolean shortcuts = false;
            boolean about = false;
            for (Component component : help.getMenuComponents()) {
                if (component instanceof JMenuItem item && item.getText() != null) {
                    shortcuts |= item.getText().contains("快捷键");
                    about |= item.getText().contains("关于");
                }
            }
            assertTrue(shortcuts, "帮助菜单里应当有「快捷键」");
            assertTrue(about, "「关于」不能丢");
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

    private static JMenu findHelpMenu(JMenuBar bar) {
        if (bar == null) {
            return null;
        }
        for (int i = 0; i < bar.getMenuCount(); i++) {
            JMenu menu = bar.getMenu(i);
            if (menu != null && menu.getText() != null && menu.getText().startsWith("帮助")) {
                return menu;
            }
        }
        return null;
    }
}
