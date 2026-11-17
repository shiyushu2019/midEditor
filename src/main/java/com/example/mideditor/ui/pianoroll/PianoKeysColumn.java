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
        setToolTipText("钢琴键盘标尺（视图 → 键盘标尺 可隐藏）");
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
                int visibleRows = Math.min(range.rows(), Theme.LANE_ROWS);
                int lowestVisible = Math.max(range.low(), range.high() - visibleRows);
                boolean active = track == activeTrack;

                for (int pitch = range.high() - 1; pitch >= lowestVisible; pitch--) {
                    int y = view.rowTop(track, laneIndex, pitch);
                    boolean black = PianoRollPainter.isBlackKey(pitch);
                    g2.setColor(black ? Theme.KEY_BLACK : Theme.KEY_WHITE);
                    g2.fillRect(0, y, width, Theme.ROW_HEIGHT);
                    if (pitch % 12 == 0) {
                        g2.setColor(black ? new Color(0x8A939F) : new Color(0x5C6875));
                        g2.drawLine(0, y + Theme.ROW_HEIGHT - 1, width, y + Theme.ROW_HEIGHT - 1);
                    }
                }

                // 八度标注（每个 C 标注一次）
                g2.setFont(Theme.SMALL_FONT.deriveFont(9f));
                FontMetrics metrics = g2.getFontMetrics();
                for (int pitch = lowestVisible; pitch < range.high(); pitch++) {
                    if (pitch % 12 != 0) {
                        continue;
                    }
                    int y = view.rowTop(track, laneIndex, pitch);
                    String label = "C" + (pitch / 12 - 1);
                    g2.setColor(active ? new Color(0x1B3B6F) : new Color(0x3A4552));
                    g2.drawString(label, width - 6 - metrics.stringWidth(label),
                            y + Theme.ROW_HEIGHT - 1);
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
