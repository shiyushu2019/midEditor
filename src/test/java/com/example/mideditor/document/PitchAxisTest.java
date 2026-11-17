package com.example.mideditor.document;

import java.awt.GraphicsEnvironment;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;

import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import org.junit.jupiter.api.Test;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.app.MainFrame;
import com.example.mideditor.domain.PitchRange;
import com.example.mideditor.domain.Track;
import com.example.mideditor.ui.pianoroll.PianoRollCanvas;
import com.example.mideditor.ui.pianoroll.PianoRollView;
import com.example.mideditor.ui.theme.Theme;

/** 频率轴（音域）：缩放 / 滑动 / 适配，以及鼠标滚轮交互。 */
class PitchAxisTest {

    @Test
    void pitchRangeZoomKeepsCentreAndClamps() {
        PitchRange base = PitchRange.centered(60, 30);
        assertEquals(30, base.rows());

        PitchRange wide = base.withRows(64);
        assertEquals(64, wide.rows(), "缩小音域：可见半音数增加");
        assertTrue(wide.contains(60), "缩放应保持中心音高可见");

        PitchRange narrow = base.withRows(12);
        assertEquals(PitchRange.MIN_ROWS, narrow.rows());
        assertTrue(narrow.contains(60));

        assertEquals(PitchRange.MAX_ROWS, base.withRows(500).rows(), "最多显示全部 128 个半音");
        assertEquals(PitchRange.MIN_ROWS, base.withRows(1).rows(), "最少 12 个半音");

        PitchRange full = PitchRange.full();
        assertEquals(0, full.low());
        assertEquals(128, full.high());
    }

    @Test
    void fittingCoversNotesBeyondDefaultRows() {
        // 跨度 60 个半音：默认 30 行窗口装不下，必须自动放大
        PitchRange fitted = PitchRange.fitting(30, 90, Track.VISIBLE_ROWS);
        assertTrue(fitted.contains(30), "最低平行线应可见");
        assertTrue(fitted.contains(90), "最高平行线应可见");
        assertTrue(fitted.rows() > Track.VISIBLE_ROWS);

        PitchRange narrow = PitchRange.fitting(60, 64, Track.VISIBLE_ROWS);
        assertEquals(Track.VISIBLE_ROWS, narrow.rows(), "窄音域仍保持默认行数");
    }

    @Test
    void rowGeometryStaysInsideLaneForEveryZoomLevel() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                holder[0] = new MainFrame(app);
                holder[0].setVisible(false);
                app.documents().newDocument();
            });
            MidiDocumentView view = app.documents().activeView();
            PianoRollView pianoRoll = view.pianoRoll();
            Track track = view.activeTrack();

            for (int rows : new int[] { 12, 30, 60, 128 }) {
                SwingUtilities.invokeAndWait(() -> view.setPitchRows(rows));
                assertEquals(rows, track.pitchRows(), "音域行数应生效");

                int laneTop = pianoRoll.laneTop(0);
                int laneBottom = laneTop + Theme.LANE_HEIGHT;
                PitchRange range = track.yRange();
                int previousTop = Integer.MIN_VALUE;
                for (int pitch = range.high() - 1; pitch >= range.low(); pitch--) {
                    int top = pianoRoll.rowTop(track, 0, pitch);
                    int bottom = pianoRoll.rowBottom(track, 0, pitch);
                    assertTrue(top >= laneTop && bottom <= laneBottom,
                            "行 " + pitch + " 应落在泳道内（rows=" + rows + "）");
                    assertTrue(bottom > top, "每行至少有 1 像素（rows=" + rows + "）");
                    assertTrue(top > previousTop, "行起始位置应严格递增（rows=" + rows + "）");
                    previousTop = top;
                    // 命中测试应能反解出同一音高（取行中线，规避取整边界）
                    assertEquals(pitch, pianoRoll.pitchAt(track, 0, (top + bottom) / 2),
                            "pitchAt 应反解行范围");
                }
            }
        } finally {
            cleanup(app, holder[0]);
        }
    }

    @Test
    void wheelModifiersFollowUnifiedSemantics() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                holder[0] = new MainFrame(app);
                holder[0].setVisible(false);
                app.documents().newDocument();
            });
            MidiDocumentView view = app.documents().activeView();
            PianoRollCanvas canvas = view.pianoRoll().canvas();
            Track track = view.activeTrack();
            int startRows = track.pitchRows();
            double pxStart = view.pianoRoll().timeline().pxPerQuarter();

            // Ctrl+滚轮向上：上下缩放（放大音域，行数减少）
            SwingUtilities.invokeAndWait(() -> wheel(canvas, InputEvent.CTRL_DOWN_MASK, -1));
            assertTrue(track.pitchRows() < startRows,
                    "Ctrl+滚轮向上应放大音域：" + startRows + " -> " + track.pitchRows());
            assertEquals(pxStart, view.pianoRoll().timeline().pxPerQuarter(), 0.001,
                    "Ctrl+滚轮不应改变水平缩放");

            // Ctrl+滚轮向下：缩回原档位
            SwingUtilities.invokeAndWait(() -> wheel(canvas, InputEvent.CTRL_DOWN_MASK, 1));
            assertEquals(startRows, track.pitchRows(), "反向滚动回到原音域档位");

            // Alt+滚轮：左右缩放（时间轴），不动音域
            int rowsBeforeAlt = track.pitchRows();
            SwingUtilities.invokeAndWait(() -> wheel(canvas, InputEvent.ALT_DOWN_MASK, -1));
            assertTrue(view.pianoRoll().timeline().pxPerQuarter() > pxStart, "Alt+滚轮向上应放大时间轴");
            assertEquals(rowsBeforeAlt, track.pitchRows(), "Alt+滚轮不应改变音域");

            // Shift+滚轮：左右滚动，既不缩放也不动音域
            double pxBeforeShift = view.pianoRoll().timeline().pxPerQuarter();
            int lowBeforeShift = track.yRange().low();
            SwingUtilities.invokeAndWait(() -> wheel(canvas, InputEvent.SHIFT_DOWN_MASK, 1));
            assertEquals(pxBeforeShift, view.pianoRoll().timeline().pxPerQuarter(), 0.001,
                    "Shift+滚轮只滚动，不缩放");
            assertEquals(lowBeforeShift, track.yRange().low(), "Shift+滚轮不动频谱");
            assertEquals(rowsBeforeAlt, track.pitchRows(), "Shift+滚轮不动音域行数");
        } finally {
            cleanup(app, holder[0]);
        }
    }

    @Test
    void pitchZoomKeepsPitchUnderCursorInPlace() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                holder[0] = new MainFrame(app);
                holder[0].setVisible(false);
                app.documents().newDocument();
            });
            MidiDocumentView view = app.documents().activeView();
            PianoRollCanvas canvas = view.pianoRoll().canvas();
            Track track = view.activeTrack();

            int probeY = 90; // 鼠标所在位置（泳道 0 内部）
            int pitchBefore = view.pianoRoll().pitchAt(track, 0, probeY);
            SwingUtilities.invokeAndWait(() -> canvas.dispatchEvent(
                    wheelEventAt(canvas, InputEvent.CTRL_DOWN_MASK, -1, probeY)));
            assertEquals(pitchBefore, view.pianoRoll().pitchAt(track, 0, probeY),
                    "放大后鼠标下的音高应保持在原位置，画面不能乱跳");

            int pitchMid = view.pianoRoll().pitchAt(track, 0, probeY);
            SwingUtilities.invokeAndWait(() -> canvas.dispatchEvent(
                    wheelEventAt(canvas, InputEvent.CTRL_DOWN_MASK, 1, probeY)));
            assertEquals(pitchMid, view.pianoRoll().pitchAt(track, 0, probeY),
                    "缩小后鼠标下的音高同样应保持不动");
        } finally {
            cleanup(app, holder[0]);
        }
    }

    @Test
    void pitchRangeCoversEveryMidiPitch() {
        PitchRange full = PitchRange.full();
        assertEquals(0, full.low());
        assertEquals(128, full.high());
        assertEquals(128, full.rows(), "MIDI 共 128 个半音（0-127）");
        assertTrue(full.contains(0), "最低音 C-1（MIDI 0）应可见");
        assertTrue(full.contains(127), "最高音 G9（MIDI 127）应可见");
        assertTrue(full.contains(21), "钢琴最低键 A0（MIDI 21）应可见");
        assertTrue(full.contains(108), "钢琴最高键 C8（MIDI 108）应可见");

        PitchRange aligned = PitchRange.aligned(128, 127);
        assertEquals(0, aligned.low(), "行数拉满时应从 MIDI 0 开始");
        assertEquals(128, aligned.high());

        // 领域模型也不截断：0-127 全部合法
        assertEquals(0, new com.example.mideditor.domain.Note(0, 0, 10, 100).pitch());
        assertEquals(127, new com.example.mideditor.domain.Note(127, 0, 10, 100).pitch());
        assertEquals(0, new com.example.mideditor.domain.Note(-5, 0, 10, 100).pitch(), "超范围向下夹紧");
        assertEquals(127, new com.example.mideditor.domain.Note(200, 0, 10, 100).pitch(), "超范围向上夹紧");
    }

    @Test
    void cHighlightCanBeToggled() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                holder[0] = new MainFrame(app);
                holder[0].setVisible(false);
                app.documents().newDocument();
            });
            MidiDocumentView view = app.documents().activeView();
            assertTrue(view.cHighlightVisible(), "默认应高亮 Do（C）行");
            SwingUtilities.invokeAndWait(view::toggleCHighlight);
            assertFalse(view.cHighlightVisible());
            SwingUtilities.invokeAndWait(view::toggleCHighlight);
            assertTrue(view.cHighlightVisible());
        } finally {
            cleanup(app, holder[0]);
        }
    }

    @Test
    void fitToNotesMakesEveryParallelLineVisible() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                holder[0] = new MainFrame(app);
                holder[0].setVisible(false);
                app.documents().newDocument();
            });
            MidiDocumentView view = app.documents().activeView();
            Track track = view.activeTrack();
            SwingUtilities.invokeAndWait(() -> {
                view.addNoteAt(track, 0, 24);
                view.addNoteAt(track, 480, 96);
            });
            assertEquals(2, soundingCount(track));

            SwingUtilities.invokeAndWait(view::pitchFitToNotes);
            assertTrue(track.yRange().contains(24), "低音平行线应可见");
            assertTrue(track.yRange().contains(96), "高音平行线应可见");

            SwingUtilities.invokeAndWait(view::pitchZoomReset);
            assertEquals(Track.VISIBLE_ROWS, track.pitchRows(), "重置回默认 30 行");
        } finally {
            cleanup(app, holder[0]);
        }
    }

    @Test
    void keysColumnWheelDrivesTheFrequencyAxis() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                holder[0] = new MainFrame(app);
                holder[0].setVisible(false);
                app.documents().newDocument();
            });
            MidiDocumentView view = app.documents().activeView();
            var keys = view.pianoRoll().keysColumn();
            Track track = view.activeTrack();

            // 键盘标尺上直接滚轮 = 滑动频率轴
            int lowBefore = track.yRange().low();
            SwingUtilities.invokeAndWait(() -> keys.dispatchEvent(wheelEvent(keys, 0, -1)));
            assertEquals(lowBefore + 1, track.yRange().low(), "滚轮在键盘标尺上应上滑音域");
            assertEquals(Track.VISIBLE_ROWS, track.pitchRows(), "滑动不改变行数");

            // Ctrl+滚轮在键盘标尺上 = 缩放频率轴（滚轮已不再被 JScrollPane 吃掉）
            SwingUtilities.invokeAndWait(() -> keys.dispatchEvent(wheelEvent(keys, InputEvent.CTRL_DOWN_MASK, -1)));
            assertTrue(track.pitchRows() < Track.VISIBLE_ROWS,
                    "Ctrl+滚轮应放大音域：" + track.pitchRows());
        } finally {
            cleanup(app, holder[0]);
        }
    }

    @Test
    void plainWheelSlidesPitchWhenAllLanesFitOnScreen() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                holder[0] = new MainFrame(app);
                holder[0].setVisible(false);
                app.documents().newDocument();
            });
            MidiDocumentView view = app.documents().activeView();
            PianoRollCanvas canvas = view.pianoRoll().canvas();
            Track track = view.activeTrack();

            // 全部音轨都装得下时，裸滚轮 = 在频谱上直接滑动（不需要修饰键）
            int lowBefore = track.yRange().low();
            SwingUtilities.invokeAndWait(() -> wheel(canvas, 0, -1));
            assertEquals(lowBefore + 1, track.yRange().low(), "裸滚轮向上应上滑音域");
            SwingUtilities.invokeAndWait(() -> wheel(canvas, 0, 1));
            assertEquals(lowBefore, track.yRange().low(), "裸滚轮向下应下滑音域");
            assertEquals(Track.VISIBLE_ROWS, track.pitchRows(), "裸滚轮滑动不改变行数");
        } finally {
            cleanup(app, holder[0]);
        }
    }

    private static MouseWheelEvent wheelEvent(java.awt.Component source, int modifiers, int rotation) {
        return wheelEventAt(source, modifiers, rotation, 100);
    }

    private static MouseWheelEvent wheelEventAt(java.awt.Component source, int modifiers, int rotation,
            int y) {
        return new MouseWheelEvent(source, MouseEvent.MOUSE_WHEEL,
                System.currentTimeMillis(), modifiers,
                120, y, 0, false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation);
    }

    private static void wheel(PianoRollCanvas canvas, int modifiers, int rotation) {
        canvas.dispatchEvent(wheelEvent(canvas, modifiers, rotation));
    }

    private static long soundingCount(Track track) {
        return track.notes().stream().filter(n -> !n.isRest()).count();
    }

    private static void cleanup(AppContext app, MainFrame frame) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (DocumentSession session : app.documents().sessions()) {
                session.view().disposeView();
                if (session.frame() != null) {
                    session.frame().dispose();
                }
            }
            if (frame != null) {
                frame.dispose();
            }
            app.shutdown();
        });
    }
}
