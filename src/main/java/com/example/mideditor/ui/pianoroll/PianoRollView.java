package com.example.mideditor.ui.pianoroll;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Rectangle;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;

import com.example.mideditor.document.MidiDocumentView;
import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.Track;
import com.example.mideditor.ui.theme.Theme;

/** 钢琴卷帘视图装配：标尺（列头）+ 音轨头（行头）+ 画布。 */
public final class PianoRollView extends JPanel {

    private static final int EDGE_TOLERANCE = 6;

    private final MidiDocumentView host;
    private final TimelineModel timeline = new TimelineModel();
    private final PianoRollCanvas canvas;
    private final TimeRuler ruler;
    private final TrackHeaderColumn headers;
    private final PianoKeysColumn keysColumn;
    private final JPanel rowHeader = new JPanel(new BorderLayout());
    private final JScrollPane scroll;
    private boolean keyboardRulerVisible = true;

    public PianoRollView(MidiDocumentView host) {
        super(new BorderLayout());
        this.host = host;
        canvas = new PianoRollCanvas(this);
        ruler = new TimeRuler(this);
        headers = new TrackHeaderColumn(this);
        keysColumn = new PianoKeysColumn(this);
        rowHeader.add(keysColumn, BorderLayout.WEST);
        rowHeader.add(headers, BorderLayout.CENTER);

        scroll = new JScrollPane(canvas,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setColumnHeaderView(ruler);
        scroll.setRowHeaderView(rowHeader);
        JPanel corner = new JPanel();
        corner.setBackground(Theme.MENU_BACKGROUND);
        corner.setBorder(javax.swing.BorderFactory.createMatteBorder(0, 0, 1, 1, Theme.BORDER));
        scroll.setCorner(ScrollPaneConstants.UPPER_LEFT_CORNER, corner);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        scroll.getHorizontalScrollBar().setUnitIncrement(24);
        scroll.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);

        timeline.addChangeListener(this::onTimelineChanged);
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                relayout(false);
            }
        });
        relayout(true);
    }

    public MidiDocumentView host() {
        return host;
    }

    public TimelineModel timeline() {
        return timeline;
    }

    public PianoRollCanvas canvas() {
        return canvas;
    }

    public JScrollPane scrollPane() {
        return scroll;
    }

    public int laneHeight() {
        return Theme.LANE_HEIGHT;
    }

    public int laneTop(int index) {
        return index * laneHeight();
    }

    public int laneIndexAt(int y) {
        return y < 0 ? -1 : y / laneHeight();
    }

    /** 音高行顶部 Y（pitch 必须落在可见音域内）。 */
    public int rowTop(Track track, int laneIndex, int pitch) {
        int rowFromTop = track.yRange().high() - 1 - pitch;
        return laneTop(laneIndex) + rowFromTop * Theme.ROW_HEIGHT;
    }

    public int pitchAt(Track track, int laneIndex, int y) {
        int row = (y - laneTop(laneIndex)) / Theme.ROW_HEIGHT;
        return track.yRange().high() - 1 - row;
    }

    public HitInfo hitTest(int x, int y) {
        var document = host.document();
        int laneIndex = laneIndexAt(y);
        if (laneIndex < 0 || laneIndex >= document.tracks().size()) {
            return new HitInfo(laneIndex, null, null, false,
                    timeline.tickAtX(x, document.ppq()), -1);
        }
        Track track = document.tracks().get(laneIndex);
        int pitch = pitchAt(track, laneIndex, y);
        long tick = timeline.tickAtX(x, document.ppq());
        Note hit = null;
        for (Note note : track.notes()) {
            if (note.pitch() != pitch) {
                continue;
            }
            if (tick >= note.startTick() && tick <= note.endTick()) {
                hit = note;
                break;
            }
        }
        boolean rightEdge = hit != null
                && Math.abs(x - timeline.xAtTick(hit.endTick(), document.ppq())) <= EDGE_TOLERANCE;
        return new HitInfo(laneIndex, track, hit, rightEdge, tick, pitch);
    }

    public void relayout(boolean rebuildHeaders) {
        var document = host.document();
        int ppq = document.ppq();
        long endTick = Math.max(document.endTick() + ppq * 16L, ppq * 64L);
        int width = Math.max(timeline.widthOf(endTick, ppq), 1200);
        int height = Math.max(document.tracks().size() * laneHeight(), 1);
        int viewportHeight = scroll.getViewport().getHeight();
        int canvasHeight = Math.max(height, Math.max(200, viewportHeight));

        canvas.setPreferredSize(new Dimension(width, canvasHeight));
        ruler.setPreferredSize(new Dimension(width, Theme.RULER_HEIGHT));
        headers.setPreferredSize(new Dimension(Theme.HEADER_WIDTH, canvasHeight));
        keysColumn.setVisible(keyboardRulerVisible);
        int rowHeaderWidth = Theme.HEADER_WIDTH
                + (keyboardRulerVisible ? Theme.PITCH_STRIP_WIDTH : 0);
        rowHeader.setPreferredSize(new Dimension(rowHeaderWidth, canvasHeight));
        if (rebuildHeaders) {
            headers.rebuild();
        }
        scroll.revalidate();
        canvas.revalidate();
        rowHeader.revalidate();
        repaint();
    }

    /** 画布高度（含至少一屏），供行头组件对齐使用。 */
    public int canvasHeight() {
        return Math.max(canvas.getPreferredSize().height, canvas.getHeight());
    }

    public boolean keyboardRulerVisible() {
        return keyboardRulerVisible;
    }

    public void setKeyboardRulerVisible(boolean visible) {
        if (keyboardRulerVisible == visible) {
            return;
        }
        keyboardRulerVisible = visible;
        keysColumn.setVisible(visible);
        relayout(false);
    }

    /** 切换键盘标尺并返回新状态。 */
    public boolean toggleKeyboardRuler() {
        setKeyboardRulerVisible(!keyboardRulerVisible);
        return keyboardRulerVisible;
    }

    /** 将指定泳道滚动到可见区域（添加 / 切换音轨时使用，优先把该泳道顶部对齐）。 */
    public void scrollToLane(int laneIndex) {
        if (laneIndex < 0) {
            return;
        }
        Rectangle visible = scroll.getViewport().getViewRect();
        int top = laneTop(laneIndex);
        int bottom = top + laneHeight();
        if (top < visible.y || bottom > visible.y + visible.height) {
            scroll.getViewport().setViewPosition(new java.awt.Point(visible.x, Math.max(0, top - 4)));
        }
    }

    private void onTimelineChanged() {
        relayout(false);
    }

    public void refreshTrackStructure() {
        relayout(true);
    }

    /** 音域 / 通道 / 属性变化后刷新音轨头与键盘标尺。 */
    public void refreshPitchLabels() {
        headers.refreshRows();
        keysColumn.repaint();
    }

    /** 只重绘播放头附近的窄条，避免整幅重绘。 */
    public void repaintPlayhead(long previousTick, long currentTick) {
        int ppq = host.document().ppq();
        int x1 = timeline.xAtTick(previousTick, ppq);
        int x2 = timeline.xAtTick(currentTick, ppq);
        canvas.repaint(Math.min(x1, x2) - 8, 0, Math.abs(x2 - x1) + 16, canvas.getHeight());
        ruler.repaint(Math.min(x1, x2) - 8, 0, Math.abs(x2 - x1) + 16, ruler.getHeight());
    }

    public void scrollToTick(long tick) {
        int x = timeline.xAtTick(tick, host.document().ppq());
        java.awt.Rectangle view = scroll.getViewport().getViewRect();
        if (x < view.x + 20 || x > view.x + view.width - 60) {
            scroll.getViewport().setViewPosition(new java.awt.Point(Math.max(0, x - 80), view.y));
        }
    }

    public Rectangle visibleRect() {
        return scroll.getViewport().getViewRect();
    }
}
