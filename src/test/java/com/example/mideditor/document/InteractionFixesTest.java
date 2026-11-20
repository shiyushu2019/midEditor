package com.example.mideditor.document;

import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.List;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import org.junit.jupiter.api.Test;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.app.MainFrame;
import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.Track;
import com.example.mideditor.ui.pianoroll.PianoRollCanvas;

/**
 * 交互细节：粘贴落点、框选自动滚动、删除后选中框的落点、输入模式按格删除、回到开头跟着滚。
 */
class InteractionFixesTest {

    @Test
    void pasteLandsWhereYouLastClicked() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            Note source = new Note(60, 0, 480, 100);
            MidiDocumentView view = openDocument(app, holder, List.of(source));

            SwingUtilities.invokeAndWait(() -> {
                view.selection().setSingle(source);
                view.copySelection();
                // 用户在 1015 tick（16 分网格上取整 960）的位置点了一下
                view.setPasteAnchor(1015);
                view.paste();
            });

            List<Note> sounding = sounding(view.activeTrack());
            assertEquals(2, sounding.size(), "应当多出一条粘贴出来的平行线：" + describe(view.activeTrack()));
            Note pasted = sounding.get(1);
            assertEquals(60, pasted.pitch(), "音高保持剪贴板原样，不跟着点击位置平移");
            assertEquals(960, pasted.startTick(), "起点落在点击处吸附后的网格上");
            assertEquals(480, pasted.durationTicks(), "时值保持不变");
        } finally {
            close(app, holder[0]);
        }
    }

    @Test
    void deleteLeavesTheSelectionOnTheNextNote() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            Note first = new Note(60, 0, 240, 100);
            Note second = new Note(60, 480, 240, 100);
            Note third = new Note(60, 960, 240, 100);
            MidiDocumentView view = openDocument(app, holder, List.of(first, second, third));

            SwingUtilities.invokeAndWait(() -> {
                view.selection().setSingle(second);
                view.deleteSelection();
            });

            assertTrue(view.selection().contains(third),
                    "删掉中间那条后，选中框应当落到后面那条，而不是清空：" + describe(view.activeTrack()));
            assertEquals(1, view.selection().resolve(view.document()).size(), "只选中一条");
        } finally {
            close(app, holder[0]);
        }
    }

    @Test
    void inputModeDeleteRemovesOneGridStepRightOfTheCursor() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            Note longNote = new Note(60, 0, 960, 100);
            MidiDocumentView view = openDocument(app, holder, List.of(longNote));
            long step = view.grid().resolution().ticksPerGrid(view.document().ppq());
            SwingUtilities.invokeAndWait(() -> {
                view.setInputMode(true); // 光标落在最后一个音的末尾（960）
                view.moveInputCursor(-(int) (480 / step), 0); // 走到 480
                view.deleteInputStep(1); // Delete：删右边一格 [480, 480+step)
            });

            List<Note> sounding = sounding(view.activeTrack());
            assertEquals(2, sounding.size(), "中间挖掉一格后应当拆成两段：" + describe(view.activeTrack()));
            assertEquals(0, sounding.get(0).startTick());
            assertEquals(480, sounding.get(0).endTick());
            assertEquals(480 + step, sounding.get(1).startTick());
            assertEquals(960, sounding.get(1).endTick());
        } finally {
            close(app, holder[0]);
        }
    }

    @Test
    void inputModeBackspaceRemovesOneGridStepLeftOfTheCursorAndStepsBack() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            Note note = new Note(60, 0, 480, 100);
            MidiDocumentView view = openDocument(app, holder, List.of(note));
            long step = view.grid().resolution().ticksPerGrid(view.document().ppq());
            SwingUtilities.invokeAndWait(() -> {
                view.setInputMode(true); // 光标落在最后一个音的末尾（480）
                view.deleteInputStep(-1); // Backspace：删左边一格 [480-step, 480)
            });

            List<Note> sounding = sounding(view.activeTrack());
            assertEquals(1, sounding.size(), describe(view.activeTrack()));
            assertEquals(480 - step, sounding.get(0).endTick());
            assertEquals(480 - step, view.inputCursorTick(), "光标跟着左移一格");
        } finally {
            close(app, holder[0]);
        }
    }

    @Test
    void rewindPullsTheTimelineBackToTheStart() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder, List.of(new Note(60, 0, 960, 100)));
            layOut(view);
            SwingUtilities.invokeAndWait(() -> view.pianoRoll().scrollPane().getViewport()
                    .setViewPosition(new Point(400, 0)));
            assertEquals(400, view.pianoRoll().visibleRect().x, "前提：频谱已向右滚开");

            SwingUtilities.invokeAndWait(view::rewind);

            assertEquals(0, view.pianoRoll().visibleRect().x, "回到开头时频谱也要拉回开头");
            assertEquals(0, view.playheadTick(), "播放头回到 0");
        } finally {
            close(app, holder[0]);
        }
    }

    @Test
    void marqueeDragNearTheLeftEdgeScrollsOnItsOwn() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder, List.of(new Note(60, 1920, 480, 100)));
            layOut(view);
            PianoRollCanvas canvas = view.pianoRoll().canvas();
            SwingUtilities.invokeAndWait(() -> view.pianoRoll().scrollPane().getViewport()
                    .setViewPosition(new Point(400, 0)));
            int before = view.pianoRoll().visibleRect().x;
            assertEquals(400, before, "前提：频谱已向右滚开");

            long now = System.currentTimeMillis();
            int laneY = 100;
            SwingUtilities.invokeAndWait(() -> {
                // 按下在画布 x=700（视口里 300），拖到画布 x=405（视口里 5 —— 贴着左边缘）
                canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, now, 0,
                        700, laneY, 1, false, MouseEvent.BUTTON1));
                canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_DRAGGED, now + 10,
                        InputEvent.BUTTON1_DOWN_MASK, 405, laneY, 1, false, MouseEvent.BUTTON1));
            });
            Thread.sleep(250); // 定时器在 EDT 上滚动
            int after = view.pianoRoll().visibleRect().x;
            SwingUtilities.invokeAndWait(() -> canvas.dispatchEvent(new MouseEvent(canvas,
                    MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0,
                    405, laneY, 1, false, MouseEvent.BUTTON1)));

            assertTrue(after < before, "贴着左边缘拖框应当自动向左滚动（" + before + " → " + after + "）");
        } finally {
            close(app, holder[0]);
        }
    }

    @Test
    void wasdMovingTheNoteScrolledTheTimelineToFollowIt() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder, List.of(new Note(60, 0, 480, 100)));
            layOut(view);
            int ppq = view.document().ppq();
            // 把音放到屏幕右侧之外，再从视图最左边开始
            long farTick = ppq * 40L;
            SwingUtilities.invokeAndWait(() -> {
                Note note = sounding(view.activeTrack()).get(0);
                note.setStartTick(farTick);
                view.selection().setSingle(note);
                view.pianoRoll().scrollPane().getViewport().setViewPosition(new Point(0, 0));
            });
            int noteX = view.pianoRoll().timeline().xAtTick(farTick, ppq);
            assertTrue(noteX > view.pianoRoll().visibleRect().width,
                    "前提：这条音应当在屏幕右侧之外（x=" + noteX + "）");
            assertEquals(0, view.pianoRoll().visibleRect().x);

            long step = view.grid().resolution().ticksPerGrid(ppq);
            SwingUtilities.invokeAndWait(() -> view.moveSelection(step, 0));

            assertTrue(view.pianoRoll().visibleRect().x > 0,
                    "WASD 移动后频谱应跟着滚动，实际 x=" + view.pianoRoll().visibleRect().x);
        } finally {
            close(app, holder[0]);
        }
    }

    @Test
    void escapeClearsTheWholeSelection() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            Note first = new Note(60, 0, 240, 100);
            Note second = new Note(64, 480, 240, 100);
            MidiDocumentView view = openDocument(app, holder, List.of(first, second));
            SwingUtilities.invokeAndWait(() -> view.selection().setAll(List.of(first, second)));
            assertEquals(2, view.selection().size(), "前提：两条都选中");

            KeyStroke esc = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
            assertEquals(com.example.mideditor.command.AppCommandIds.EDIT_DESELECT,
                    holder[0].getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(esc),
                    "Esc 应当绑定到「取消选择」");

            // 真实路径：Esc 由全局分发器接管（焦点在工具栏 / 状态栏时也要能用）
            com.example.mideditor.app.ArrowKeyDispatcher arrows = new com.example.mideditor.app.ArrowKeyDispatcher(app);
            SwingUtilities.invokeAndWait(() -> app.refreshCommandStates());
            press(holder[0], arrows, KeyEvent.VK_ESCAPE);

            assertTrue(view.selection().isEmpty(), "Esc 之后不该还有选中项");

            // 命令本身也能单独执行（编辑菜单用）
            SwingUtilities.invokeAndWait(() -> view.selection().setSingle(first));
            Action deselect = app.registry()
                    .action(com.example.mideditor.command.AppCommandIds.EDIT_DESELECT);
            SwingUtilities.invokeAndWait(() -> {
                app.refreshCommandStates();
                deselect.actionPerformed(new ActionEvent(deselect, ActionEvent.ACTION_PERFORMED, "esc"));
            });
            assertTrue(view.selection().isEmpty(), "菜单里的「取消选择」也要能用");
        } finally {
            close(app, holder[0]);
        }
    }

    @Test
    void arrowKeysPickTheFirstNoteWhenNothingIsSelected() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            Note first = new Note(60, 960, 240, 100);
            Note second = new Note(60, 1440, 240, 100);
            MidiDocumentView view = openDocument(app, holder, List.of(first, second));
            com.example.mideditor.app.ArrowKeyDispatcher arrows = new com.example.mideditor.app.ArrowKeyDispatcher(app);

            SwingUtilities.invokeAndWait(() -> view.selection().clear());
            press(holder[0], arrows, KeyEvent.VK_RIGHT);
            assertTrue(view.selection().contains(first), "无选中时 → 应当选中第一条");

            // ↑ 不再参与选中（只保留左右）
            SwingUtilities.invokeAndWait(() -> view.selection().clear());
            press(holder[0], arrows, KeyEvent.VK_UP);
            assertTrue(view.selection().isEmpty(), "↑ 不选任何东西");

            // 有选中时方向键照旧在相邻对象之间走位（不是每次回到第一条）
            SwingUtilities.invokeAndWait(() -> view.selection().setSingle(first));
            press(holder[0], arrows, KeyEvent.VK_RIGHT);
            assertTrue(view.selection().contains(second), "有选中时 → 走到下一条");
        } finally {
            close(app, holder[0]);
        }
    }

    @Test
    void clickingTheRightEdgeStillAuditionsTheNote() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder, List.of(new Note(60, 0, 480, 100)));
            layOut(view);
            com.example.mideditor.ui.pianoroll.PianoRollCanvas canvas = view.pianoRoll().canvas();
            Track track = view.activeTrack();
            Note note = track.notes().get(0);
            int ppq = view.document().ppq();
            int x = view.pianoRoll().timeline().xAtTick(note.endTick(), ppq) - 2;
            int y = (view.pianoRoll().rowTop(track, 0, note.pitch())
                    + view.pianoRoll().rowBottom(track, 0, note.pitch())) / 2;
            long now = System.currentTimeMillis();
            SwingUtilities.invokeAndWait(() -> {
                canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, now, 0,
                        x, y, 1, false, MouseEvent.BUTTON1));
                canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, now + 20, 0,
                        x, y, 1, false, MouseEvent.BUTTON1));
            });

            long[] audition = view.lastAuditionInfo();
            assertNotNull(audition, "点右边缘（缩放区）也要试听");
            assertEquals(60, audition[0], "试听的就是那条音");
            assertTrue(view.inputBadgeVisible(), "单击也要亮浮牌");
            assertEquals(60, view.inputBadgePitch());
            assertEquals("C4", view.inputBadgePitchName());
        } finally {
            close(app, holder[0]);
        }
    }

    @Test
    void wasdMoveAuditionsTheNoteAndFlashesTheBadge() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            Note note = new Note(60, 0, 480, 100);
            MidiDocumentView view = openDocument(app, holder, List.of(note));
            SwingUtilities.invokeAndWait(() -> view.selection().setSingle(note));
            assertFalse(view.inputBadgeVisible(), "前提：移动前没有牌子");

            SwingUtilities.invokeAndWait(() -> view.moveSelection(0, 1));

            assertEquals(61, note.pitch(), "W 上移半音");
            long[] audition = view.lastAuditionInfo();
            assertNotNull(audition, "WASD 移动后要试听");
            assertEquals(61, audition[0], "听的是挪完之后那个音");
            assertTrue(view.inputBadgeVisible(), "同时亮 1 秒浮牌");
            assertEquals(61, view.inputBadgePitch());
            assertEquals("C#4", view.inputBadgePitchName());
        } finally {
            close(app, holder[0]);
        }
    }

    @Test
    void mouseDragFlashesTheBadgeWithoutAuditioning() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            Note note = new Note(60, 0, 480, 100);
            MidiDocumentView view = openDocument(app, holder, List.of(note));
            SwingUtilities.invokeAndWait(() -> {
                view.selection().setSingle(note);
                view.beginSelectionEdit();
            });
            long[] before = view.lastAuditionInfo();

            SwingUtilities.invokeAndWait(() -> view.applySelectionMove(0, 2));

            assertEquals(62, note.pitch(), "拖动把音高挪上去了");
            assertTrue(view.inputBadgeVisible(), "拖动要有浮牌");
            assertEquals(62, view.inputBadgePitch());
            assertEquals("D4", view.inputBadgePitchName());
            assertSame(before, view.lastAuditionInfo(), "拖动不试听");
        } finally {
            close(app, holder[0]);
        }
    }

    @Test
    void seekingOutsideTheViewportScrollsToFollow() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder, List.of(new Note(60, 0, 480, 100)));
            layOut(view);
            int ppq = view.document().ppq();

            SwingUtilities.invokeAndWait(() -> view.seekTick(ppq * 2L));
            assertEquals(0, view.pianoRoll().visibleRect().x, "视野内的定位不该乱滚");

            SwingUtilities.invokeAndWait(() -> view.seekTick(ppq * 40L));
            assertTrue(view.pianoRoll().visibleRect().x > 0, "拖到视野外要跟着滚过去");
            int afterFar = view.pianoRoll().visibleRect().x;
            int playheadX = view.pianoRoll().timeline().xAtTick(view.playheadTick(), ppq);
            assertTrue(playheadX >= afterFar && playheadX <= afterFar + view.pianoRoll().visibleRect().width,
                    "滚动后播放头应该在可见范围内");
        } finally {
            close(app, holder[0]);
        }
    }

    @Test
    void stopKeepsTheViewportWhereItIs() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder, List.of(new Note(60, 1920, 480, 100)));
            layOut(view);
            SwingUtilities.invokeAndWait(() -> view.pianoRoll().scrollPane().getViewport()
                    .setViewPosition(new Point(400, 0)));
            assertEquals(400, view.pianoRoll().visibleRect().x, "前提：视野已经向右滚开");

            SwingUtilities.invokeAndWait(view::stopTransport);

            assertEquals(400, view.pianoRoll().visibleRect().x, "停止不跳视野");
            assertEquals(0, view.playheadTick(), "播放头还是回到 0");
        } finally {
            close(app, holder[0]);
        }
    }

    @Test
    void batchMovesStaySilentWithoutAnyBadge() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            Note first = new Note(60, 0, 480, 100);
            Note second = new Note(64, 480, 480, 100);
            MidiDocumentView view = openDocument(app, holder, List.of(first, second));
            SwingUtilities.invokeAndWait(() -> view.selection().setAll(List.of(first, second)));
            long[] before = view.lastAuditionInfo();

            // 键盘批量移动：不出声、不出牌
            SwingUtilities.invokeAndWait(() -> view.moveSelection(0, 2));
            assertEquals(62, first.pitch(), "两条一起上移");
            assertEquals(66, second.pitch());
            assertSame(before, view.lastAuditionInfo(), "批量 WASD 不试听");
            assertFalse(view.inputBadgeVisible(), "批量 WASD 不出浮牌");

            // 鼠标批量拖动：同样不出牌
            SwingUtilities.invokeAndWait(view::beginSelectionEdit);
            SwingUtilities.invokeAndWait(() -> view.applySelectionMove(120, 1));
            assertSame(before, view.lastAuditionInfo(), "批量拖动不试听");
            assertFalse(view.inputBadgeVisible(), "批量拖动不出浮牌");
        } finally {
            close(app, holder[0]);
        }
    }

    @Test
    void batchOfOnePitchStillGetsFeedback() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            Note first = new Note(60, 0, 480, 100);
            Note second = new Note(60, 480, 480, 100);
            MidiDocumentView view = openDocument(app, holder, List.of(first, second));
            SwingUtilities.invokeAndWait(() -> view.selection().setAll(List.of(first, second)));

            SwingUtilities.invokeAndWait(() -> view.moveSelection(0, 3));

            assertEquals(63, first.pitch(), "两条一起上移");
            assertEquals(63, second.pitch());
            long[] audition = view.lastAuditionInfo();
            assertNotNull(audition, "音高都一样 → 照样试听");
            assertEquals(63, audition[0]);
            assertTrue(view.inputBadgeVisible(), "音高都一样 → 照样亮牌");
            assertEquals(63, view.inputBadgePitch());
        } finally {
            close(app, holder[0]);
        }
    }

    // ---------------------------------------------------------------- 脚手架

    private static MidiDocumentView openDocument(AppContext app, MainFrame[] holder, List<Note> notes)
            throws Exception {
        MidiDocumentView[] holderView = new MidiDocumentView[1];
        SwingUtilities.invokeAndWait(() -> {
            holder[0] = new MainFrame(app);
            holder[0].setVisible(false);
            app.documents().newDocument();
            MidiDocumentView view = app.documents().activeView();
            holderView[0] = view;
            Track track = view.activeTrack();
            for (Note note : notes) {
                track.addNote(note);
            }
            track.sortNotes();
            view.afterEdit(true);
        });
        return holderView[0];
    }

    /**
     * 没有真实窗口时滚动面板拿不到尺寸（getViewRect 恒为 0），
     * 所以直接给视口和内容定尺寸，让「滚到哪儿」这件事有真实数值可言。
     */
    private static void layOut(MidiDocumentView view) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            javax.swing.JViewport viewport = view.pianoRoll().scrollPane().getViewport();
            viewport.setSize(1000, 700);
            view.pianoRoll().canvas().setSize(6000, 800);
        });
        Rectangle visible = view.pianoRoll().visibleRect();
        assertTrue(visible.width > 100 && visible.height > 100,
                "前提：视口应当已经有尺寸，实际 " + visible);
    }

    private static List<Note> sounding(Track track) {
        List<Note> list = new java.util.ArrayList<>();
        for (Note note : track.notes()) {
            if (!note.isRest()) {
                list.add(note);
            }
        }
        return list;
    }

    private static String describe(Track track) {
        StringBuilder sb = new StringBuilder();
        for (Note note : track.notes()) {
            sb.append(note.isRest() ? "休止" : "音").append('[')
                    .append(note.startTick()).append("..").append(note.endTick()).append("] ");
        }
        return sb.toString();
    }

    private static void press(MainFrame frame, java.awt.KeyEventDispatcher dispatcher, int keyCode)
            throws Exception {
        KeyEvent event = new KeyEvent(frame, KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(), 0, keyCode, KeyEvent.CHAR_UNDEFINED);
        SwingUtilities.invokeAndWait(() -> dispatcher.dispatchKeyEvent(event));
    }

    private static void close(AppContext app, MainFrame frame) throws Exception {
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
