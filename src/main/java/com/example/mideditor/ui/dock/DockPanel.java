package com.example.mideditor.ui.dock;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

import com.example.mideditor.ui.theme.Theme;

/** 可停靠工具窗口的外壳（标题栏 + 内容）。 */
public final class DockPanel extends JPanel {

    private final String id;
    private final String title;
    private final JComponent content;
    private final DockManager manager;

    DockPanel(DockManager manager, String id, String title, JComponent content) {
        super(new BorderLayout());
        this.manager = manager;
        this.id = id;
        this.title = title;
        this.content = content;
        setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER));

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(new java.awt.Color(0xE4E8EE));
        JLabel label = new JLabel(title);
        label.setFont(Theme.BOLD_FONT);
        label.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 4));
        header.add(label, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 1));
        buttons.setOpaque(false);
        JButton floatButton = smallButton("浮", "浮动 / 停靠");
        floatButton.addActionListener(e -> manager.floatPanel(this));
        JButton closeButton = smallButton("×", "关闭面板");
        closeButton.addActionListener(e -> manager.hide(this));
        buttons.add(floatButton);
        buttons.add(closeButton);
        header.add(buttons, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);
        add(content, BorderLayout.CENTER);
    }

    private static JButton smallButton(String text, String tooltip) {
        JButton button = new JButton(text);
        button.setFont(Theme.SMALL_FONT);
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setMargin(new java.awt.Insets(0, 4, 0, 4));
        button.setPreferredSize(new Dimension(24, 20));
        return button;
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public JComponent content() {
        return content;
    }
}
