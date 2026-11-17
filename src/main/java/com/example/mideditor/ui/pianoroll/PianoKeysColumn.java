package com.example.mideditor.ui.pianoroll;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.JComponent;

import com.example.mideditor.document.MidiDocument;
import com.example.mideditor.domain.PitchRange;
import com.example.mideditor.domain.Track;
import com.example.mideditor.ui.theme.Theme;

/**
 * Y 轴钢琴键盘标尺：每个音轨泳道都画出对应音高的白键/黑键，
 * 与音轨使用的 GM 音色无关（始终按钢琴键盘显示）。
 */
public final class PianoKeysColumn extends JComponent {

    private final PianoRollView view;

    public PianoKeysColumn(PianoRollView view) {
        this.view = view;
        setOpaque(true);
        setBackground(Theme.MENU_BACKGROUND);
        addMouseWheelListener(this::onWheel);
    }

    /** 与画布完全一致的滚轮语义（键盘标尺属于频率轴，裸滚轮即滚动频谱）。 */
    private void onWheel(java.awt.event.MouseWheelEvent e) {
        int notches = PianoRollView.wheelNotches(e);
        if (notches == 0) {
            return;
        }
        Track track = trackAt(e.getY());
        int lane = Math.max(0, view.laneIndexAt(e.getY()));
        if (e.isControlDown()) {
            view.wheelZoomPitch(track, lane, e.getY(), notches);
        } else if (e.isAltDown()) {
            var visible = view.visibleRect();
            view.wheelZoomTime(visible.x + visible.width / 2, notches);
        } else if (e.isShiftDown()) {
            view.wheelScrollTime(notches);
        } else {
            view.wheelScrollSpectrum(track, lane, notches);
        }
        view.host().app().refreshApplicationState();
        e.consume();
    }

    private Track trackAt(int y) {
        return view.activeTrackOrNearest(y);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            MidiDocument document = view.host().document();
            Track activeTrack = view.host().activeTrack();
            int width = getWidth();

            g2.setColor(Theme.MENU_BACKGROUND);
            g2.fillRect(0, 0, width, getHeight());

            for (int laneIndex = 0; laneIndex < document.tracks().size(); laneIndex++) {
                Track track = document.tracks().get(laneIndex);
                int laneTop = view.laneTop(laneIndex);
                PitchRange range = track.yRange();
                boolean active = track == activeTrack;
                boolean cHighlight = view.cHighlightVisible();
                int rowHeight = view.rowHeight(track);

                for (int pitch = range.high() - 1; pitch >= range.low(); pitch--) {
                    int y = view.rowTop(track, laneIndex, pitch);
                    int bottom = view.rowBottom(track, laneIndex, pitch);
                    int height = Math.max(1, bottom - y);
                    boolean black = PianoRollPainter.isBlackKey(pitch);
                    boolean isC = pitch % 12 == 0;
                    g2.setColor(black ? Theme.KEY_BLACK : Theme.KEY_WHITE);
                    g2.fillRect(0, y, width, height);
                    if (isC) {
                        if (cHighlight) {
                            // 键盘上也只在键内部涂半透明黄色
                            g2.setColor(Theme.C_TINT_KEY);
                            g2.fillRect(0, y, width, height);
                        } else {
                            g2.setColor(black ? new Color(0x8A939F) : new Color(0x5C6875));
                            g2.drawLine(0, bottom - 1, width, bottom - 1);
                        }
                    }
                }

                // 八度标注（每个 C 标注一次；行高过小时省略以免重叠）
                if (rowHeight >= 7) {
                    g2.setFont(Theme.SMALL_FONT.deriveFont(Math.min(9f, rowHeight * 0.9f)));
                    FontMetrics metrics = g2.getFontMetrics();
                    for (int pitch = range.low(); pitch < range.high(); pitch++) {
                        if (pitch % 12 != 0) {
                            continue;
                        }
                        int y = view.rowTop(track, laneIndex, pitch);
                        String label = "C" + (pitch / 12 - 1);
                        g2.setColor(cHighlight ? Theme.KEY_C_TEXT
                                : (active ? new Color(0x1B3B6F) : new Color(0x3A4552)));
                        g2.drawString(label, width - 6 - metrics.stringWidth(label),
                                y + Math.min(rowHeight - 1, metrics.getAscent() + 1));
                    }
                }

                g2.setColor(Theme.LANE_SEPARATOR);
                g2.drawLine(0, laneTop + view.laneHeight() - 1, width, laneTop + view.laneHeight() - 1);
                if (active) {
                    g2.setColor(Theme.ACCENT);
                    g2.drawLine(0, laneTop, 0, laneTop + view.laneHeight() - 1);
                }
            }

            g2.setColor(Theme.BORDER);
            g2.drawLine(width - 1, 0, width - 1, getHeight());
        } finally {
            g2.dispose();
        }
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(Theme.PITCH_STRIP_WIDTH, Math.max(1, view.canvasHeight()));
    }
}
