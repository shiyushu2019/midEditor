package com.example.mideditor;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.app.MainFrame;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** 应用入口。 */
public final class MidiEditorApp {

    private MidiEditorApp() {
    }

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (ClassNotFoundException | InstantiationException
                    | IllegalAccessException | javax.swing.UnsupportedLookAndFeelException ignored) {
                // 系统外观不可用时保持默认 Look and Feel
            }
            AppContext app = new AppContext();
            MainFrame frame = new MainFrame(app);
            frame.setVisible(true);
        });
    }
}
