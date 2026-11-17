package com.example.mideditor.ui.pianoroll;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.JComponent;

import com.example.mideditor.document.MidiDocument;
import com.example.mideditor.document.MidiDocumentView;
import com.example.mideditor.document.TimeMath;
import com.example.mideditor.ui.theme.Theme;

/** 时间标尺：小节号 / 拍线 / 循环区间 / 播放头，点击或拖动定位。 */
public final class TimeRuler extends JComponent {

    private final PianoRollView view;

    public TimeRuler(PianoRollView view) {
        this.view = view;
        setPreferredSize(new java.awt.Dimension(1200, Theme.RULER_HEIGHT));
        setToolTipText("点击或拖动定位播放头");
        MouseAdapter adapter = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                seek(e);
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                seek(e);
            }

            private void seek(MouseEvent e) {
                MidiDocument document = view.host().document();
                view.host().seekTick(view.timeline().tickAtX(e.getX(), document.ppq()));
            }
        };
        addMouseListener(adapter);
        addMouseMotionListener(adapter);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            MidiDocumentView host = view.host();
            MidiDocument document = host.document();
            int ppq = document.ppq();
            TimelineModel timeline = view.timeline();
            Rectangle clip = g2.getClipBounds() == null ? getVisibleRect() : g2.getClipBounds();

            g2.setColor(new Color(0x2A313A));
            g2.fillRect(clip.x, 0, clip.width, getHeight());

            long first = timeline.tickAtX(clip.x, ppq);
            long last = timeline.tickAtX(clip.x + clip.width, ppq);
            long beat = TimeMath.beatTicks(document, first);
            long bar = TimeMath.barTicks(document, first);

            long start = (first / beat) * beat;
            for (long tick = start; tick <= last; tick += beat) {
                int x = timeline.xAtTick(tick, ppq);
                boolean isBar = tick % bar == 0;
                g2.setColor(isBar ? Theme.BAR_LINE : Theme.BEAT_LINE);
                g2.drawLine(x, isBar ? 9 : 21, x, getHeight() - 1);
                if (isBar) {
                    g2.setColor(Theme.TEXT_ON_DARK);
                    g2.setFont(Theme.SMALL_FONT);
                    g2.drawString(String.valueOf(tick / bar + 1), x + 3, 14);
                }
            }

            if (host.loopEnabled()) {
                int x1 = timeline.xAtTick(host.loopStart(), ppq);
                int x2 = timeline.xAtTick(host.loopEnd(), ppq);
                g2.setColor(new Color(0x3D, 0x8B, 0xFD, 0x44));
                g2.fillRect(x1, 16, Math.max(1, x2 - x1), getHeight() - 17);
            }

            int playheadX = timeline.xAtTick(host.playheadTick(), ppq);
            g2.setColor(Theme.PLAYHEAD);
            g2.drawLine(playheadX, 0, playheadX, getHeight() - 1);
            int[] xs = { playheadX - 5, playheadX + 5, playheadX };
            int[] ys = { 0, 0, 8 };
            g2.fillPolygon(xs, ys, 3);

            g2.setColor(Theme.BORDER);
            g2.drawLine(clip.x, getHeight() - 1, clip.x + clip.width, getHeight() - 1);
        } finally {
            g2.dispose();
        }
    }
}
