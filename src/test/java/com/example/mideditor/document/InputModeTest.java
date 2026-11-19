package com.example.mideditor.document;

import java.awt.GraphicsEnvironment;
import java.awt.event.KeyEvent;

import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import org.junit.jupiter.api.Test;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.app.InputModeDispatcher;
import com.example.mideditor.app.MainFrame;
import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.Track;
import com.example.mideditor.ui.theme.Theme;

/**
 * 输入模式：红色圆点光标 + WASD 移动 + Q 试听 + 空格「按多久就输入多长」。
 */
class InputModeTest {

    @Test
    void wasdMovesTheCursor() throws Exception {
        withDocument((app, view, dispatcher) -> {
            SwingUtilities.invokeAndWait(() -> view.setInputMode(true));
            assertTrue(view.inputMode(), "输入模式应已打开");
            long step = view.grid().step(view.document().ppq());
            long tick0 = view.inputCursorTick();
            int pitch0 = view.inputCursorPitch();

            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_W, 0));
            assertEquals(pitch0 + 1, view.inputCursorPitch(), "W 上移半音");
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_S, 0));
            assertEquals(pitch0, view.inputCursorPitch(), "S 下移半音");

            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_D, 0));
            assertEquals(tick0 + step, view.inputCursorTick(), "D 右移一格");
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_A, 0));
            assertEquals(tick0, view.inputCursorTick(), "A 左移一格");

            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_ESCAPE, 0));
            assertFalse(view.inputMode(), "Esc 退出输入模式");
        });
    }

    @Test
    void heldSpaceBecomesTheNoteLengthInRealTime() throws Exception {
        withDocument((app, view, dispatcher) -> {
            SwingUtilities.invokeAndWait(() -> view.setInputMode(true));

            long pressNanos = System.nanoTime();
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_SPACE, 0));
            Thread.sleep(150);
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_SPACE, 0));
            long heldMicros = (System.nanoTime() - pressNanos) / 1000;

            Note entered = firstSounding(view.activeTrack());
            assertTrue(entered != null, "空格松手后应当落成一条平行线");
            long expected = view.microsToTicks(heldMicros, entered.startTick());
            // 计时误差允许 ±1 格（150ms 在 120BPM/480ppq 下约 144 tick）
            long tolerance = Math.max(2, view.grid().step(view.document().ppq()) / 2);
            assertTrue(Math.abs(entered.durationTicks() - expected) <= tolerance,
                    "按住时长应换算成真实时长：期望 " + expected + " 实际 " + entered.durationTicks());
            assertTrue(entered.durationTicks() > 50, "150ms 不该只落成一两个 tick");
            assertEquals(entered.endTick(), view.inputCursorTick(), "光标应跟到这条线的末尾");
            assertFalse(app.playback().isPlaying(), "空格被输入模式吃掉，不该触发播放");
        });
    }

    @Test
    void microsToTicksFollowsTempo() throws Exception {
        withDocument((app, view, dispatcher) -> {
            int ppq = view.document().ppq();
            long quarterMicros = view.document().tempoMap().microsPerQuarterAt(0);
            assertEquals(ppq, view.microsToTicks(quarterMicros, 0), "一个四分音符的时长 = ppq 个 tick");
            assertEquals(ppq * 2, view.microsToTicks(quarterMicros * 2, 0), "两拍");
            assertEquals(1, view.microsToTicks(1, 0), "再短也至少 1 tick");
        });
    }

    @Test
    void spaceIsNotConsumedWhenInputModeIsOff() throws Exception {
        withDocument((app, view, dispatcher) -> {
            KeyEvent space = key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_SPACE, 0);
            assertFalse(dispatcher.dispatchKeyEvent(space),
                    "输入模式未开启时，空格必须交还给「播放/暂停」");
            assertFalse(space.isConsumed());
        });
    }

    @Test
    void redDotIsPaintedAtTheCursor() throws Exception {
        withDocument((app, view, dispatcher) -> {
            SwingUtilities.invokeAndWait(() -> {
                view.setInputMode(true);
                view.moveInputCursor(4, 0); // 离开播放头（x=0），免得和播放头红色竖线混在一起
            });
            int[] probe = new int[2];
            SwingUtilities.invokeAndWait(() -> {
                var roll = view.pianoRoll();
                var canvas = roll.canvas();
                int width = 900;
                int height = Math.max(300, roll.laneHeight());
                canvas.setSize(width, height);
                canvas.doLayout();
                int ppq = view.document().ppq();
                int x = roll.timeline().xAtTick(view.inputCursorTick(), ppq);
                int pitch = view.inputCursorPitch();
                int y = (roll.rowTop(view.activeTrack(), 0, pitch)
                        + roll.rowBottom(view.activeTrack(), 0, pitch)) / 2;
                probe[0] = x;
                probe[1] = y;

                java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                        width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = image.createGraphics();
                g.setColor(java.awt.Color.BLACK);
                g.fillRect(0, 0, width, height);
                canvas.paint(g);
                g.dispose();
                assertEquals(Theme.INPUT_CURSOR.getRGB(), image.getRGB(x, y),
                        "光标位置应当是一个红色圆点");
                assertFalse(image.getRGB(x + 40, y) == Theme.INPUT_CURSOR.getRGB(),
                        "红色圆点只出现在光标处");
            });
        });
    }

    @Test
    void movingTheCursorWhileHoldingDoesNotMoveTheNote() throws Exception {
        withDocument((app, view, dispatcher) -> {
            SwingUtilities.invokeAndWait(() -> view.setInputMode(true));
            long pressTick = view.inputCursorTick();
            int pressPitch = view.inputCursorPitch();

            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_SPACE, 0));
            // 按住期间乱动光标：音符应当落在按下的那个位置
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_W, 0));
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_D, 0));
            Thread.sleep(120);
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_SPACE, 0));

            Note entered = firstSounding(view.activeTrack());
            assertTrue(entered != null, "松手后应当落成一条平行线");
            assertEquals(pressTick, entered.startTick(), "起点取按下的位置");
            assertEquals(pressPitch, entered.pitch(), "音高取按下的位置");
            assertEquals(entered.endTick(), view.inputCursorTick(), "光标跟到音符末尾");
        });
    }

    @Test
    void leavingInputModeCancelsTheHeldNoteAndClearsTheState() throws Exception {
        withDocument((app, view, dispatcher) -> {
            SwingUtilities.invokeAndWait(() -> view.setInputMode(true));
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_SPACE, 0));
            assertTrue(view.inputNoteHeld(), "按下后应处于“按住”状态");

            SwingUtilities.invokeAndWait(() -> view.setInputMode(false));
            assertFalse(view.inputNoteHeld(), "退出输入模式不能把“按住”状态留在那里");
            assertTrue(firstSounding(view.activeTrack()) == null, "退出时按住的那个音作废，不该落成平行线");

            // 再进来还能正常输入（状态没有卡死）
            SwingUtilities.invokeAndWait(() -> view.setInputMode(true));
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_SPACE, 0));
            Thread.sleep(120);
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_SPACE, 0));
            assertTrue(firstSounding(view.activeTrack()) != null, "重新进入输入模式后应当还能输入");
        });
    }

    @Test
    void toolbarHasAnInputModeToggle() throws Exception {
        withDocument((app, view, dispatcher) -> {
            javax.swing.JToolBar bar = findToolBar(app.mainFrame());
            assertTrue(bar != null, "主窗口应该有工具栏");
            javax.swing.Action inputAction = app.registry().action(
                    com.example.mideditor.command.AppCommandIds.EDIT_INPUT_MODE);
            javax.swing.Action snapAction = app.registry().action(
                    com.example.mideditor.command.AppCommandIds.EDIT_INPUT_BEAT_SNAP);
            boolean inputToggle = false;
            boolean snapToggle = false;
            for (java.awt.Component component : bar.getComponents()) {
                if (component instanceof javax.swing.JToggleButton toggle) {
                    inputToggle |= toggle.getAction() == inputAction;
                    snapToggle |= toggle.getAction() == snapAction;
                }
            }
            assertTrue(inputToggle, "输入模式应当有顶部按钮（可切换）");
            assertTrue(snapToggle, "节奏辅助也应当有顶部开关");
        });
    }

    private static javax.swing.JToolBar findToolBar(java.awt.Container root) {
        for (java.awt.Component component : root.getComponents()) {
            if (component instanceof javax.swing.JToolBar bar) {
                return bar;
            }
            if (component instanceof java.awt.Container container) {
                javax.swing.JToolBar nested = findToolBar(container);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    @Test
    void undoPutsTheCursorBackAndRedoMovesItForwardAgain() throws Exception {
        withDocument((app, view, dispatcher) -> {
            SwingUtilities.invokeAndWait(() -> view.setInputMode(true));
            long beforeTick = view.inputCursorTick();
            int beforePitch = view.inputCursorPitch();

            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_SPACE, 0));
            Thread.sleep(120);
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_SPACE, 0));
            Note entered = firstSounding(view.activeTrack());
            assertTrue(entered != null, "应当已输入一条平行线");
            long endTick = entered.endTick();
            assertEquals(endTick, view.inputCursorTick(), "输入后光标在末尾");

            SwingUtilities.invokeAndWait(() -> view.undo());
            assertTrue(firstSounding(view.activeTrack()) == null, "撤销应当把这条平行线去掉");
            assertEquals(beforeTick, view.inputCursorTick(), "撤销后光标回到按下前的位置");
            assertEquals(beforePitch, view.inputCursorPitch(), "撤销后光标音高也要回去");

            SwingUtilities.invokeAndWait(() -> view.redo());
            assertTrue(firstSounding(view.activeTrack()) != null, "重做应当把它带回来");
            assertEquals(endTick, view.inputCursorTick(), "重做后光标重新跟到末尾");
        });
    }

    @Test
    void undoOfOtherEditsLeavesTheCursorAlone() throws Exception {
        withDocument((app, view, dispatcher) -> {
            SwingUtilities.invokeAndWait(() -> view.setInputMode(true));
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_SPACE, 0));
            Thread.sleep(120);
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_SPACE, 0));
            long cursorAfterInput = view.inputCursorTick();

            SwingUtilities.invokeAndWait(() -> view.addNoteAt(view.activeTrack(), 4000, 70));
            assertEquals(cursorAfterInput, view.inputCursorTick(), "别处的编辑不该动光标");
            SwingUtilities.invokeAndWait(() -> view.undo()); // 撤销的是 addNoteAt
            assertEquals(cursorAfterInput, view.inputCursorTick(), "撤销别处的编辑也不该动光标");
            assertTrue(firstSounding(view.activeTrack()) != null, "输入的这条还在（只撤销了后面的那条）");
        });
    }

    @Test
    void holdingQKeepsOneToneUntilRelease() throws Exception {
        withDocument((app, view, dispatcher) -> {
            SwingUtilities.invokeAndWait(() -> view.setInputMode(true));
            assertTrue(dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_Q, 0)),
                    "输入模式下 Q 应当被接住");
            assertTrue(view.inputAuditionHeld(), "按下 Q 应当开始一个持续的试听音");
            int pitch = view.inputCursorPitch();
            // 键盘自动重复：再按几次不能重新触发（否则就是一个音被反复敲）
            for (int i = 0; i < 3; i++) {
                assertTrue(dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_Q, 0)),
                        "自动重复也要被吃掉，别落到别的命令上");
                assertTrue(view.inputAuditionHeld(), "自动重复不该把音断掉或重新起一个");
                assertEquals(pitch, view.inputCursorPitch(), "试听不应动光标");
            }
            assertTrue(dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_Q, 0)),
                    "松手也要接住");
            assertFalse(view.inputAuditionHeld(), "松手结束试听");
        });
    }

    @Test
    void theCursorScrollsThePitchAxisInsteadOfStoppingAtTheEdge() throws Exception {
        withDocument((app, view, dispatcher) -> {
            SwingUtilities.invokeAndWait(() -> view.setInputMode(true));
            Track track = view.activeTrack();
            int low0 = track.yRange().low();
            int high0 = track.yRange().high();
            int top = high0 - 1;

            // 一直往上顶：音高要能越过窗口上边界，并且窗口跟着翻上去
            for (int i = view.inputCursorPitch(); i <= top + 3; i++) {
                dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_W, 0));
            }
            assertTrue(view.inputCursorPitch() > top, "光标应当越过原来的窗口上边界");
            assertTrue(track.yRange().contains(view.inputCursorPitch()),
                    "频谱要跟着翻过去，光标必须留在可见范围内");
            assertEquals(high0 - low0, track.yRange().rows(), "翻动时行数（缩放级别）不变");
            assertTrue(track.yRange().low() > low0, "窗口确实往上滑了");

            // 再一路往下走回原处附近
            for (int i = 0; i < (top + 3 - (low0 - 2)); i++) {
                dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_S, 0));
            }
            assertTrue(track.yRange().contains(view.inputCursorPitch()), "往下走同样要跟住");
            assertTrue(track.yRange().low() < low0, "窗口滑到了原来的下方");
        });
    }

    @Test
    void assistQuantisesInputToTheCurrentGridResolution() throws Exception {
        withDocument((app, view, dispatcher) -> {
            view.document().tempoMap().setBpm(0, 240); // 一拍 = 250ms = 480 tick
            SwingUtilities.invokeAndWait(() -> {
                // 网格选 1/8 → 辅助的吸附单位也应该是八分音符，而不是一拍
                view.grid().setResolution(com.example.mideditor.domain.GridSettings.Resolution.EIGHTH);
                view.setInputMode(true);
                view.setInputBeatSnap(true);
            });
            long eighth = view.grid().step(view.document().ppq()); // 240
            assertEquals(240, eighth, "前提：1/8 网格 = 240 tick");

            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_SPACE, 0));
            Thread.sleep(150); // ≈ 288 tick，靠近一个八分音符
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_SPACE, 0));

            Note entered = firstSounding(view.activeTrack());
            assertTrue(entered != null, "应当落成一条平行线");
            assertEquals(0, entered.durationTicks() % eighth, "时值必须是八分音符的整数倍");
            assertTrue(entered.durationTicks() >= eighth, "至少一个八分音符");
            assertTrue(entered.durationTicks() <= eighth * 2, "150ms 不该被放大到更多格");
        });
    }

    @Test
    void assistUsesTheGridForTheStartToo() throws Exception {
        withDocument((app, view, dispatcher) -> {
            view.document().tempoMap().setBpm(0, 240); // 一拍 = 250ms = 480 tick
            SwingUtilities.invokeAndWait(() -> view.setInputMode(true));
            long gridStep = view.grid().step(view.document().ppq());

            // 先在细网格上走到一个“不在整拍上”的位置
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_D, 0));
            assertEquals(gridStep, view.inputCursorTick());

            SwingUtilities.invokeAndWait(() -> {
                // 把网格换成 1/4：辅助的吸附单位随之变成 480，起点应当被拉回最近的 480 倍数
                view.grid().setResolution(com.example.mideditor.domain.GridSettings.Resolution.QUARTER);
                view.setInputBeatSnap(true);
            });
            long unit = view.grid().step(view.document().ppq());
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_SPACE, 0));
            Thread.sleep(120);
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_SPACE, 0));

            Note entered = firstSounding(view.activeTrack());
            assertTrue(entered != null, "应当落成一条平行线");
            assertEquals(0, entered.startTick(), "起点吸附到最近的网格线");
            assertEquals(0, entered.durationTicks() % unit, "时值是网格单位的整数倍");
            assertEquals(entered.endTick(), view.inputCursorTick(), "光标跟到末尾");
        });
    }

    @Test
    void assistFallsBackToTheBeatWhenTheGridIsOff() throws Exception {
        withDocument((app, view, dispatcher) -> {
            view.document().tempoMap().setBpm(0, 240); // 一拍 = 250ms = 480 tick
            SwingUtilities.invokeAndWait(() -> {
                view.grid().setResolution(com.example.mideditor.domain.GridSettings.Resolution.OFF);
                view.setInputMode(true);
                view.setInputBeatSnap(true);
            });
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_SPACE, 0));
            Thread.sleep(120);
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_SPACE, 0));

            Note entered = firstSounding(view.activeTrack());
            assertTrue(entered != null, "应当落成一条平行线");
            assertEquals(0, entered.durationTicks() % 480, "网格关闭时退回按一拍吸附");
        });
    }

    @Test
    void assistFollowsTheTappedRhythmNotTheHoldLength() throws Exception {
        withDocument((app, view, dispatcher) -> {
            long[] now = { 0 };
            view.setNanoClockForTesting(() -> now[0]);
            view.document().tempoMap().setBpm(0, 125); // 1 tick = 1000µs
            SwingUtilities.invokeAndWait(() -> {
                // 1/8 网格 = 240 tick = 240ms
                view.grid().setResolution(com.example.mideditor.domain.GridSettings.Resolution.EIGHTH);
                view.setInputMode(true);
                view.setInputBeatSnap(true);
            });

            // 断奏：每个音只按 100ms，但每 480ms（= 四分音符）敲一下
            long[] onsets = { 0, 480, 960 };
            for (long onsetMs : onsets) {
                now[0] = onsetMs * 1_000_000L;
                dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_SPACE, 0));
                now[0] = (onsetMs + 100) * 1_000_000L;
                dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_SPACE, 0));
            }

            java.util.List<Long> starts = new java.util.ArrayList<>();
            for (Note note : view.activeTrack().notes()) {
                if (!note.isRest()) {
                    starts.add(note.startTick());
                }
            }
            java.util.Collections.sort(starts);
            assertEquals(java.util.List.of(0L, 480L, 960L), starts,
                    "起点应当跟着“敲击间隔”落在网格上，而不是跟着按住时长一路漂");
        });
    }

    @Test
    void aLongPauseReanchorsAtTheCursor() throws Exception {
        withDocument((app, view, dispatcher) -> {
            long[] now = { 0 };
            view.setNanoClockForTesting(() -> now[0]);
            view.document().tempoMap().setBpm(0, 125); // 1 tick = 1000µs
            SwingUtilities.invokeAndWait(() -> {
                view.grid().setResolution(com.example.mideditor.domain.GridSettings.Resolution.EIGHTH);
                view.setInputMode(true);
                view.setInputBeatSnap(true);
            });

            now[0] = 0;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_SPACE, 0));
            now[0] = 100_000_000L;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_SPACE, 0));
            long cursorAfterFirst = view.inputCursorTick();

            // 走开 3 秒再回来：不该把音摆到老远右边，而是从光标处继续
            now[0] = 3_000_000_000L;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_SPACE, 0));
            now[0] = 3_100_000_000L;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_SPACE, 0));

            long maxStart = 0;
            for (Note note : view.activeTrack().notes()) {
                maxStart = Math.max(maxStart, note.startTick());
            }
            assertEquals(cursorAfterFirst, maxStart, "停顿之后应从光标处继续，而不是按 3 秒的时间差往后摆");
        });
    }

    // ---------------------------------------------------------------- 脚手架

    private interface Body {
        void run(AppContext app, MidiDocumentView view, InputModeDispatcher dispatcher) throws Exception;
    }

    private static void withDocument(Body body) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView[] viewHolder = new MidiDocumentView[1];
            SwingUtilities.invokeAndWait(() -> {
                holder[0] = new MainFrame(app);
                holder[0].setVisible(false);
                app.documents().newDocument();
                viewHolder[0] = app.documents().activeView();
            });
            body.run(app, viewHolder[0], new InputModeDispatcher(app));
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                for (DocumentSession session : app.documents().sessions()) {
                    session.view().disposeView();
                    if (session.frame() != null) {
                        session.frame().dispose();
                    }
                }
                holder[0].dispose();
                app.shutdown();
            });
        }
    }

    private static KeyEvent key(AppContext app, int id, int keyCode, int modifiers) {
        return new KeyEvent(app.mainFrame(), id, System.currentTimeMillis(), modifiers,
                keyCode, KeyEvent.CHAR_UNDEFINED);
    }

    private static Note firstSounding(Track track) {
        for (Note note : track.notes()) {
            if (!note.isRest()) {
                return note;
            }
        }
        return null;
    }
}
