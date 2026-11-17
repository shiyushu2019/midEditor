package com.example.mideditor.ui.pianoroll;

import java.awt.Color;
import java.awt.Cursor;
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

/**
 * 时间标尺：顶部「循环条」拖动设置循环域，下方点击 / 拖动定位播放头。
 *
 * <p>
 * 循环条交互：拖空白处 = 新建区间；拖区间内 = 整体平移；拖两端 = 调整起点 / 终点；
 * 双击 = 关闭循环（区间保留，Ctrl+L 再开启仍在原处）。
 * </p>
 */
public final class TimeRuler extends JComponent {

    /** 顶部循环条高度（像素）。 */
    public static final int LOOP_STRIP_HEIGHT = 10;

    private static final int EDGE_TOLERANCE = 5;

    private enum Mode {
        NONE, SEEK, LOOP_NEW, LOOP_MOVE, LOOP_START, LOOP_END
    }

    private final PianoRollView view;
    private Mode mode = Mode.NONE;
    private long anchorTick;
    private long moveAnchorTick;
    private long moveStart;
    private long moveEnd;

    public TimeRuler(PianoRollView view) {
        this.view = view;
        setPreferredSize(new java.awt.Dimension(1200, Theme.RULER_HEIGHT));
        setToolTipText("顶部循环条：拖动设置循环域（双击关闭）；下方：点击或拖动定位播放头");
        MouseAdapter adapter = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (inLoopStrip(e)) {
                    pressLoop(e);
                } else {
                    mode = Mode.SEEK;
                    seek(e);
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                switch (mode) {
                    case SEEK -> seek(e);
                    case LOOP_NEW -> {
                        long tick = snapTick(e.getX());
                        view.host().setLoopRange(Math.min(anchorTick, tick), Math.max(anchorTick, tick));
                    }
                    case LOOP_MOVE -> {
                        long delta = snapTick(e.getX()) - moveAnchorTick;
                        long start = Math.max(0, moveStart + delta);
                        view.host().setLoopRange(start, start + (moveEnd - moveStart));
                    }
                    case LOOP_START -> {
                        long tick = snapTick(e.getX());
                        view.host().setLoopRange(Math.min(tick, view.host().loopEnd() - 1),
                                view.host().loopEnd());
                    }
                    case LOOP_END -> {
                        long tick = snapTick(e.getX());
                        view.host().setLoopRange(view.host().loopStart(),
                                Math.max(tick, view.host().loopStart() + 1));
                    }
                    default -> {
                    }
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (mode == Mode.LOOP_NEW
                        && view.host().loopEnd() - view.host().loopStart() < Math.max(1, snapStep() / 2)) {
                    // 只是点了一下：给一个小节的标准区间，别留下看不见的 1 tick 循环
                    MidiDocument document = view.host().document();
                    long bar = Math.max(1, TimeMath.barTicks(document, anchorTick));
                    view.host().setLoopRange(anchorTick, anchorTick + bar);
                }
                mode = Mode.NONE;
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && inLoopStrip(e)) {
                    view.host().setLoopEnabled(false);
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                if (!inLoopStrip(e) || !view.host().loopEnabled()) {
                    setCursor(Cursor.getDefaultCursor());
                    return;
                }
                int ppq = view.host().document().ppq();
                int startX = view.timeline().xAtTick(view.host().loopStart(), ppq);
                int endX = view.timeline().xAtTick(view.host().loopEnd(), ppq);
                if (Math.abs(e.getX() - startX) <= EDGE_TOLERANCE || Math.abs(e.getX() - endX) <= EDGE_TOLERANCE) {
                    setCursor(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR));
                } else if (e.getX() > startX && e.getX() < endX) {
                    setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                } else {
                    setCursor(Cursor.getDefaultCursor());
                }
            }

            private void pressLoop(MouseEvent e) {
                MidiDocumentView host = view.host();
                anchorTick = snapTick(e.getX());
                if (!host.loopEnabled()) {
                    mode = Mode.LOOP_NEW;
                    return;
                }
                int ppq = host.document().ppq();
                int startX = view.timeline().xAtTick(host.loopStart(), ppq);
                int endX = view.timeline().xAtTick(host.loopEnd(), ppq);
                if (Math.abs(e.getX() - startX) <= EDGE_TOLERANCE) {
                    mode = Mode.LOOP_START;
                } else if (Math.abs(e.getX() - endX) <= EDGE_TOLERANCE) {
                    mode = Mode.LOOP_END;
                } else if (e.getX() > startX && e.getX() < endX) {
                    mode = Mode.LOOP_MOVE;
                    moveAnchorTick = anchorTick;
                    moveStart = host.loopStart();
                    moveEnd = host.loopEnd();
                } else {
                    mode = Mode.LOOP_NEW;
                }
            }

            private void seek(MouseEvent e) {
                MidiDocument document = view.host().document();
                view.host().seekTick(view.timeline().tickAtX(e.getX(), document.ppq()));
            }
        };
        addMouseListener(adapter);
        addMouseMotionListener(adapter);
    }

    private boolean inLoopStrip(MouseEvent e) {
        return e.getY() < LOOP_STRIP_HEIGHT;
    }

    private long snapTick(int x) {
        MidiDocumentView host = view.host();
        long tick = view.timeline().tickAtX(x, host.document().ppq());
        return Math.max(0, host.grid().snap(tick, host.document().ppq()));
    }

    private long snapStep() {
        MidiDocumentView host = view.host();
        long step = host.grid().step(host.document().ppq());
        return step > 0 ? step : Math.max(1, host.document().ppq() / 4);
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
            int height = getHeight();

            g2.setColor(new Color(0x2A313A));
            g2.fillRect(clip.x, 0, clip.width, height);

            long first = timeline.tickAtX(clip.x, ppq);
            long last = timeline.tickAtX(clip.x + clip.width, ppq);
            long beat = TimeMath.beatTicks(document, first);
            long bar = TimeMath.barTicks(document, first);

            // ---- 顶部循环条
            g2.setColor(new Color(0x1D232B));
            g2.fillRect(clip.x, 0, clip.width, LOOP_STRIP_HEIGHT);
            if (host.loopEnabled()) {
                int x1 = timeline.xAtTick(host.loopStart(), ppq);
                int x2 = timeline.xAtTick(host.loopEnd(), ppq);
                g2.setColor(new Color(0x3D, 0x8B, 0xFD, 0xCC));
                g2.fillRect(x1, 1, Math.max(2, x2 - x1), LOOP_STRIP_HEIGHT - 2);
                g2.setColor(new Color(0x8FB8FF));
                g2.drawLine(x1, 0, x1, LOOP_STRIP_HEIGHT - 1);
                int edge = Math.max(x1, x2 - 1);
                g2.drawLine(edge, 0, edge, LOOP_STRIP_HEIGHT - 1);
                g2.setColor(new Color(0x3D, 0x8B, 0xFD, 0x22));
                g2.fillRect(x1, LOOP_STRIP_HEIGHT, Math.max(1, x2 - x1), height - LOOP_STRIP_HEIGHT);
            }

            // ---- 小节 / 拍线
            long start = (first / beat) * beat;
            for (long tick = start; tick <= last; tick += beat) {
                int x = timeline.xAtTick(tick, ppq);
                boolean isBar = tick % bar == 0;
                g2.setColor(isBar ? Theme.BAR_LINE : Theme.BEAT_LINE);
                g2.drawLine(x, isBar ? LOOP_STRIP_HEIGHT + 9 : LOOP_STRIP_HEIGHT + 15, x, height - 1);
                if (isBar) {
                    g2.setColor(Theme.TEXT_ON_DARK);
                    g2.setFont(Theme.SMALL_FONT);
                    g2.drawString(String.valueOf(tick / bar + 1), x + 3, LOOP_STRIP_HEIGHT + 11);
                }
            }

            // ---- 播放头（画布里的播放头只有竖线，顶端的「向下三角」由标尺负责）
            int playheadX = timeline.xAtTick(host.playheadTick(), ppq);
            g2.setColor(Theme.PLAYHEAD);
            g2.drawLine(playheadX, LOOP_STRIP_HEIGHT, playheadX, height - 1);
            int[] xs = { playheadX - 5, playheadX + 5, playheadX };
            int[] ys = { LOOP_STRIP_HEIGHT, LOOP_STRIP_HEIGHT, LOOP_STRIP_HEIGHT + 7 };
            g2.fillPolygon(xs, ys, 3);

            g2.setColor(Theme.BORDER);
            g2.drawLine(clip.x, height - 1, clip.x + clip.width, height - 1);
        } finally {
            g2.dispose();
        }
    }
}
