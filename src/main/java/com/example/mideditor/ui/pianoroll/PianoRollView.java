package com.example.mideditor.ui.pianoroll;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Rectangle;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;

import com.example.mideditor.document.MidiDocumentView;
import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.PitchRange;
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

    /** 左侧频率轴（钢琴键盘标尺），滚轮可滑动 / 缩放音域。 */
    public PianoKeysColumn keysColumn() {
        return keysColumn;
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

    /** 该 Y 位置对应的音轨（越界时退回活动音轨 / 第一轨）。 */
    public Track activeTrackOrNearest(int y) {
        var tracks = host.document().tracks();
        int lane = laneIndexAt(y);
        if (lane >= 0 && lane < tracks.size()) {
            return tracks.get(lane);
        }
        Track active = host.activeTrack();
        if (active != null) {
            return active;
        }
        return tracks.isEmpty() ? null : tracks.get(0);
    }

    /** 音域缩放档位（可见半音数，由放大到缩小）。 */
    private static final int[] PITCH_ROW_STEPS = { 12, 15, 20, 24, 30, 40, 48, 60, 80, 96, 128 };

    /** 音高行顶部 Y。行高随音域行数缩放，泳道高度固定。 */
    public int rowTop(Track track, int laneIndex, int pitch) {
        PitchRange range = track.yRange();
        int rows = Math.max(1, range.rows());
        int rowFromTop = range.high() - 1 - pitch;
        return laneTop(laneIndex)
                + (int) Math.round(rowFromTop * (double) laneHeight() / rows);
    }

    /** 音高行底部 Y（不含），用于绘制行高随缩放变化的背景与平行线。 */
    public int rowBottom(Track track, int laneIndex, int pitch) {
        PitchRange range = track.yRange();
        int rows = Math.max(1, range.rows());
        int rowFromTop = range.high() - pitch;
        return laneTop(laneIndex)
                + (int) Math.round(rowFromTop * (double) laneHeight() / rows);
    }

    /** 该音轨单行像素高度（四舍五入，仅供文字 / 平行线粗细参照）。 */
    public int rowHeight(Track track) {
        int rows = Math.max(1, track.yRange().rows());
        return Math.max(1, (int) Math.round(laneHeight() / (double) rows));
    }

    public int pitchAt(Track track, int laneIndex, int y) {
        PitchRange range = track.yRange();
        int rows = Math.max(1, range.rows());
        int offset = Math.max(0, y - laneTop(laneIndex));
        int row = (int) Math.min(rows - 1, (long) offset * rows / Math.max(1, laneHeight()));
        return range.high() - 1 - row;
    }

    /** 按音域行数缩放（in = 放大音域，行数减少 / 每行变高）。返回新的可见半音数。 */
    public int zoomPitch(Track track, boolean in) {
        if (track == null) {
            return 0;
        }
        int current = Math.max(1, track.yRange().rows());
        int target = in ? previousStep(current) : nextStep(current);
        int applied = track.setPitchRows(target);
        afterPitchChange();
        return applied;
    }

    /** 直接设置音域行数（下拉 / 微调框 / 面板）。 */
    public int setPitchRows(Track track, int rows) {
        if (track == null) {
            return 0;
        }
        int applied = track.setPitchRows(rows);
        afterPitchChange();
        return applied;
    }

    /** 音轨列表是否超过一屏（超过时可在音轨头上用滚轮滚动音轨）。 */
    public boolean laneStackScrollable() {
        int content = Math.max(1, host.document().tracks().size() * laneHeight());
        int viewport = scroll.getViewport().getExtentSize().height;
        return viewport > 0 && content > viewport;
    }

    /** 滚轮一格滑动的半音数：随缩放级别加快（30 行 = 1 个半音，128 行 = 4 个）。 */
    public int pitchSlideStep(Track track) {
        int rows = track == null ? Track.VISIBLE_ROWS : Math.max(1, track.pitchRows());
        return Math.max(1, (int) Math.round(rows / (double) Track.VISIBLE_ROWS));
    }

    /** 滚轮格数归一化（部分设备只上报精确滚动量）。 */
    public static int wheelNotches(java.awt.event.MouseWheelEvent e) {
        int rotation = e.getWheelRotation();
        if (rotation != 0) {
            return rotation;
        }
        double precise = e.getPreciseWheelRotation();
        if (precise == 0) {
            return 0;
        }
        return (int) Math.signum(precise) * Math.max(1, (int) Math.round(Math.abs(precise)));
    }

    // ---------------------------------------------------------------- 统一滚轮语义

    /**
     * 裸滚轮：上下滚动频谱（滑动音域窗口）。
     *
     * @param laneIndex 鼠标所在泳道（&lt;0 时用活动音轨所在泳道）
     * @param notches   正数向下
     */
    public void wheelScrollSpectrum(Track track, int laneIndex, int notches) {
        if (track == null || notches == 0) {
            return;
        }
        slidePitch(track, pitchSlideStep(track) * (notches > 0 ? -1 : 1));
    }

    /** Shift+滚轮：左右滚动。notches 正数向右。 */
    public void wheelScrollTime(int notches) {
        javax.swing.JScrollBar bar = scroll.getHorizontalScrollBar();
        if (notches == 0 || !bar.isVisible()) {
            return;
        }
        int unit = Math.max(16, bar.getUnitIncrement());
        bar.setValue(bar.getValue() + notches * unit);
    }

    /** Alt+滚轮：左右缩放，锚点为鼠标所在 tick（该点像素位置保持不动）。 */
    public void wheelZoomTime(int anchorXInCanvas, int notches) {
        if (notches == 0) {
            return;
        }
        int ppq = host.document().ppq();
        long anchorTick = timeline.tickAtX(anchorXInCanvas, ppq);
        java.awt.Rectangle visible = scroll.getViewport().getViewRect();
        double factor = notches < 0 ? 1.15 : 1 / 1.15;
        timeline.setPxPerQuarter(timeline.pxPerQuarter() * factor);
        int newX = timeline.xAtTick(anchorTick, ppq);
        scroll.getViewport().setViewPosition(
                new java.awt.Point(Math.max(0, visible.x + (newX - anchorXInCanvas)), visible.y));
    }

    /**
     * Ctrl+滚轮：上下缩放（音域行数），锚点为鼠标所在音高。
     * 缩放后鼠标下的音高仍停在同一像素位置，画面不会乱跳。
     *
     * @return 新的可见半音数
     */
    public int wheelZoomPitch(Track track, int laneIndex, int anchorYInCanvas, int notches) {
        if (track == null || notches == 0) {
            return track == null ? 0 : track.yRange().rows();
        }
        PitchRange range = track.yRange();
        int current = Math.max(1, range.rows());
        int target = notches < 0 ? previousStep(current) : nextStep(current);
        if (target == current) {
            return current;
        }
        int top = laneTop(Math.max(0, laneIndex));
        double pixelsFromTop = Math.max(0, Math.min(laneHeight(), anchorYInCanvas - top));
        double anchorPitch = range.high() - pixelsFromTop * current / (double) laneHeight();
        double desiredHigh = anchorPitch + pixelsFromTop * target / (double) laneHeight();
        track.setYRange(PitchRange.aligned(target, desiredHigh));
        afterPitchChange();
        return track.yRange().rows();
    }

    /** 音轨列表一屏装不下时，裸滚轮滚动音轨列表。 */
    public void wheelScrollLanes(int notches) {
        javax.swing.JScrollBar bar = scroll.getVerticalScrollBar();
        if (notches == 0 || !bar.isVisible()) {
            return;
        }
        int unit = Math.max(16, bar.getUnitIncrement());
        bar.setValue(bar.getValue() + notches * unit);
    }

    /** 音域上下滑动（滚轮），semitones 为正向上。 */
    public void slidePitch(Track track, int semitones) {
        if (track == null || semitones == 0) {
            return;
        }
        track.shiftPitchRange(semitones);
        afterPitchChange();
    }

    /** 音域适配本音轨已有平行线（并保证全部可见）。 */
    public void fitPitchToNotes(Track track) {
        if (track == null) {
            return;
        }
        track.autoRangeFromNotes();
        afterPitchChange();
    }

    /** 频率轴变化后：重绘行头 / 键盘标尺、画布，并刷新传输条上的音域读数。 */
    private void afterPitchChange() {
        refreshPitchLabels();
        repaint();
        host.refresh();
    }

    /** 把行数作为「缩放档位」使用，保证放大 / 缩小总能落在整齐的档位上。 */
    private static int previousStep(int rows) {
        for (int i = PITCH_ROW_STEPS.length - 1; i >= 0; i--) {
            if (PITCH_ROW_STEPS[i] < rows) {
                return PITCH_ROW_STEPS[i];
            }
        }
        return PITCH_ROW_STEPS[0];
    }

    private static int nextStep(int rows) {
        for (int step : PITCH_ROW_STEPS) {
            if (step > rows) {
                return step;
            }
        }
        return PITCH_ROW_STEPS[PITCH_ROW_STEPS.length - 1];
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
