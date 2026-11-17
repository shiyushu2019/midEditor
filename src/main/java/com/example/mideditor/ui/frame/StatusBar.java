package com.example.mideditor.ui.frame;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.Border;

import com.example.mideditor.ui.theme.Theme;

/** 主框架状态栏：文档 / 音轨 / 播放位置 / 缩放 / 脏状态 / 诊断信息。 */
public final class StatusBar extends JPanel {

    private final JLabel documentValue = valueLabel();
    private final JLabel trackValue = valueLabel();
    private final JLabel positionValue = valueLabel();
    private final JLabel zoomValue = valueLabel();
    private final JLabel dirtyValue = valueLabel();
    private final JLabel messageValue = valueLabel();

    public StatusBar() {
        super(new FlowLayout(FlowLayout.LEFT, 0, 0));
        setBackground(Theme.STATUS_BACKGROUND);
        setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER));
        add(cell("文档", documentValue, 210));
        add(separator());
        add(cell("音轨", trackValue, 250));
        add(separator());
        add(cell("播放位置", positionValue, 190));
        add(separator());
        add(cell("缩放", zoomValue, 90));
        add(separator());
        add(cell("状态", dirtyValue, 90));
        add(separator());
        add(cell("诊断", messageValue, 440));
        setDocument("-");
        setTrack("-");
        setPosition(0, 0, 480);
        setZoom(100);
        setDirty(false);
        setMessage("");
    }

    private static JLabel valueLabel() {
        JLabel label = new JLabel();
        label.setFont(Theme.BASE_FONT);
        return label;
    }

    private static JPanel cell(String title, JLabel value, int width) {
        JPanel panel = new JPanel(new java.awt.BorderLayout(4, 0));
        panel.setOpaque(false);
        panel.setPreferredSize(new Dimension(width, 24));
        panel.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        JLabel caption = new JLabel(title + "：");
        caption.setFont(Theme.SMALL_FONT);
        caption.setForeground(new Color(0x63707E));
        panel.add(caption, java.awt.BorderLayout.WEST);
        panel.add(value, java.awt.BorderLayout.CENTER);
        return panel;
    }

    private static JLabel separator() {
        JLabel sep = new JLabel("│");
        sep.setForeground(Theme.BORDER);
        return sep;
    }

    public void setDocument(String text) {
        documentValue.setText(text);
    }

    public void setTrack(String text) {
        trackValue.setText(text);
    }

    public void setPosition(long tick, long micros, int ppq) {
        String time = formatMicros(micros);
        positionValue.setText("tick " + tick + " · " + time);
    }

    public void setZoom(double zoomFactor) {
        zoomValue.setText(Math.round(zoomFactor * 100) + "%");
    }

    public void setDirty(boolean dirty) {
        dirtyValue.setText(dirty ? "未保存" : "已保存");
        dirtyValue.setForeground(dirty ? new Color(0xB03A48) : new Color(0x1F7A4D));
    }

    public void setMessage(String message) {
        messageValue.setText(message == null ? "" : message);
        messageValue.setToolTipText(message);
    }

    public void applyBorder(Border border) {
        setBorder(border);
    }

    public static String formatMicros(long micros) {
        long totalMs = Math.max(0, micros) / 1000;
        long minutes = totalMs / 60_000;
        long seconds = (totalMs / 1000) % 60;
        long millis = totalMs % 1000;
        return String.format("%02d:%02d.%03d", minutes, seconds, millis);
    }
}
