package com.example.mideditor.ui.frame;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import javax.swing.border.Border;

import com.example.mideditor.ui.theme.Theme;

/**
 * 主框架底部状态栏：Word 风格的一行低栏。
 *
 * <p>
 * 左边是「状态」——平时显示 <b>就绪</b>；有提示（例如“无法再缩短”）时临时占用，几秒后自动回到“就绪”。
 * 右边是基础信息（文档 / 音轨 / 位置 / 缩放 / 轨道与平行线数 / PPQ / 保存状态）。
 * </p>
 */
public final class StatusBar extends JPanel {

    /** 状态栏高度：刻意做得很低（Word 那种一行）。 */
    private static final int BAR_HEIGHT = 20;
    /** 临时提示多久后回到「就绪」。 */
    private static final int IDLE_RESET_MILLIS = 4000;

    private static final Color STATE_DEFAULT = new Color(0x2C3540);
    private static final Color STATE_IDLE = new Color(0x1B6B44);
    private static final Color INFO_COLOR = new Color(0x44505E);

    private final JLabel stateValue = new JLabel("就绪");
    private final JLabel infoValue = new JLabel("");
    private final Timer idleTimer;

    // 分项保存，任何一项变化都能立刻重排出完整信息行
    private String document = "-";
    private String track = "-";
    private String position = "";
    private String zoom = "";
    private String dirty = "";
    private String extra = "";

    public StatusBar() {
        super(new BorderLayout(8, 0));
        setBackground(Theme.STATUS_BACKGROUND);
        setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER));
        setPreferredSize(new Dimension(100, BAR_HEIGHT));

        stateValue.setFont(Theme.SMALL_FONT);
        stateValue.setForeground(STATE_IDLE);
        stateValue.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));

        infoValue.setFont(Theme.SMALL_FONT);
        infoValue.setForeground(INFO_COLOR);
        infoValue.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
        infoValue.setHorizontalAlignment(JLabel.RIGHT);

        add(stateValue, BorderLayout.WEST);
        add(infoValue, BorderLayout.CENTER);

        idleTimer = new Timer(IDLE_RESET_MILLIS, e -> showIdle());
        idleTimer.setRepeats(false);
        setDocument("-");
        setTrack("-");
        setPosition(0, 0, 480);
        setZoom(1);
        setDirty(false);
    }

    private void rebuildInfo() {
        List<String> parts = new ArrayList<>();
        parts.add(document);
        if (!"-".equals(track)) {
            parts.add(track);
        }
        if (!position.isBlank()) {
            parts.add(position);
        }
        if (!zoom.isBlank()) {
            parts.add(zoom);
        }
        if (!extra.isBlank()) {
            parts.add(extra);
        }
        if (!dirty.isBlank()) {
            parts.add(dirty);
        }
        infoValue.setText(String.join("  ·  ", parts));
        infoValue.setToolTipText(infoValue.getText());
    }

    public void setDocument(String text) {
        document = text == null || text.isBlank() ? "-" : text;
        rebuildInfo();
    }

    public void setTrack(String text) {
        track = text == null || text.isBlank() ? "-" : text;
        rebuildInfo();
    }

    public void setPosition(long tick, long micros, int ppq) {
        position = "tick " + tick + "  " + formatMicros(micros);
        rebuildInfo();
    }

    public void setZoom(double zoomFactor) {
        zoom = Math.round(zoomFactor * 100) + "%";
        rebuildInfo();
    }

    public void setDirty(boolean isDirty) {
        dirty = isDirty ? "未保存" : "已保存";
        rebuildInfo();
    }

    /** 无文档时不要把「已保存」这种无意义状态写上去。 */
    public void clearSavedState() {
        dirty = "";
        rebuildInfo();
    }

    /** 右侧信息里的一段附加文字（音轨数 / 平行线数 / PPQ / 音频后端）。 */
    public void setInfo(String text) {
        extra = text == null ? "" : text;
        rebuildInfo();
    }

    /** 临时状态提示（例如「无法再缩短」），几秒后自动回到「就绪」。 */
    public void setMessage(String message) {
        if (message == null || message.isBlank()) {
            showIdle();
            return;
        }
        stateValue.setText(message);
        stateValue.setForeground(STATE_DEFAULT);
        idleTimer.restart();
    }

    /** 当前状态文字（测试用）。 */
    public String stateText() {
        return stateValue.getText();
    }

    /** 右侧信息行（测试用）。 */
    public String infoText() {
        return infoValue.getText();
    }

    private void showIdle() {
        stateValue.setText("就绪");
        stateValue.setForeground(STATE_IDLE);
        idleTimer.stop();
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
