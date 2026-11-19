package com.example.mideditor;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.app.MainFrame;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** 应用入口。 */
public final class MidiEditorApp {

    /** 应用版本（与 pom.xml 的 version 保持一致）。 */
    public static final String VERSION = "1.0.0";

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
            // 有的窗口管理器会在 show() 时清掉 show 之前设的 extended state，这里再确认一次
            frame.setExtendedState(frame.getExtendedState() | java.awt.Frame.MAXIMIZED_BOTH);
        });
    }
}
