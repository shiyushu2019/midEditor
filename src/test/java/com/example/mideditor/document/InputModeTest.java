package com.example.mideditor.document;

import java.awt.GraphicsEnvironment;
import java.awt.event.KeyEvent;
import java.util.List;

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
    void assistSnapsStartAndDurationToTheGrid() throws Exception {
        withDocument((app, view, dispatcher) -> {
            long[] now = { 0 };
            view.setNanoClockForTesting(() -> now[0]);
            view.document().tempoMap().setBpm(0, 240); // 一拍 = 250ms = 480 tick
            SwingUtilities.invokeAndWait(() -> {
                // 先把光标挪到不在目标网格上的位置，再切到 1/8：确认起点吸附有效
                view.grid().setResolution(com.example.mideditor.domain.GridSettings.Resolution.THIRTY_SECOND);
                view.setInputMode(true);
                view.setInputBeatSnap(true);
            });
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_D, 0));
            long offGrid = view.inputCursorTick();
            SwingUtilities.invokeAndWait(() -> view.grid()
                    .setResolution(com.example.mideditor.domain.GridSettings.Resolution.EIGHTH));

            now[0] = 0;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_SPACE, 0));
            now[0] = 150_000_000L; // ≈ 288 tick，靠近一个八分音符
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_SPACE, 0));

            Note entered = firstSounding(view.activeTrack());
            assertTrue(entered != null, "应当落成一条平行线");
            assertEquals(0, entered.startTick() % 240, "起点吸附到 1/8 网格（原位置 " + offGrid + "）");
            assertEquals(0, entered.durationTicks() % 240, "时值吸附到 1/8 网格");
            assertTrue(entered.durationTicks() >= 240 && entered.durationTicks() <= 480,
                    "150ms 应当落在 1~2 个八分音符之间，实际 " + entered.durationTicks());
            assertEquals(entered.endTick(), view.inputCursorTick(), "光标跟到末尾");
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
            assertEquals(480, unit, "前提：1/4 网格 = 480 tick");
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_SPACE, 0));
            Thread.sleep(120);
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_SPACE, 0));

            Note entered = firstSounding(view.activeTrack());
            assertTrue(entered != null, "应当落成一条平行线");
            assertEquals(0, entered.startTick(), "起点吸附到最近的网格线");
            assertEquals(entered.endTick(), view.inputCursorTick(), "光标跟到末尾");
        });
    }

    @Test
    void assistFallsBackToTheBeatWhenTheGridIsOff() throws Exception {
        withDocument((app, view, dispatcher) -> {
            view.document().tempoMap().setBpm(0, 240); // 一拍 = 250ms = 480 tick
            SwingUtilities.invokeAndWait(() -> {
                view.grid().setResolution(com.example.mideditor.domain.GridSettings.Resolution.THIRTY_SECOND);
                view.setInputMode(true);
            });
            // 先走到一个不在拍线上的位置（1/32 = 60 tick）
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_D, 0));
            assertEquals(60, view.inputCursorTick(), "前提：光标停在 60 tick");
            SwingUtilities.invokeAndWait(() -> {
                view.grid().setResolution(com.example.mideditor.domain.GridSettings.Resolution.OFF);
                view.setInputBeatSnap(true);
            });
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_SPACE, 0));
            Thread.sleep(120);
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_SPACE, 0));

            Note entered = firstSounding(view.activeTrack());
            assertTrue(entered != null, "应当落成一条平行线");
            assertEquals(0, entered.startTick(), "网格关闭时退回按一拍吸附（60 → 0）");
        });
    }

    @Test
    void staccatoPressesAppendWithoutAnyRests() throws Exception {
        withDocument((app, view, dispatcher) -> {
            long[] now = { 0 };
            view.setNanoClockForTesting(() -> now[0]);
            view.document().tempoMap().setBpm(0, 125); // 1 tick = 1000µs
            SwingUtilities.invokeAndWait(() -> {
                view.grid().setResolution(com.example.mideditor.domain.GridSettings.Resolution.EIGHTH);
                view.setInputMode(true);
                view.setInputBeatSnap(true);
            });

            // 断奏：每个音只按 100ms，两次之间隔 380ms。间隔不该变成休止符，长度吸附成一个八分音符
            for (long onsetMs : new long[] { 0, 480, 960 }) {
                now[0] = onsetMs * 1_000_000L;
                dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_SPACE, 0));
                now[0] = (onsetMs + 100) * 1_000_000L;
                dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_SPACE, 0));
            }

            java.util.List<Note> sounding = new java.util.ArrayList<>();
            for (Note note : view.activeTrack().notes()) {
                assertFalse(note.isRest(), "输入模式不该自己插休止符：" + describe(view.activeTrack()));
                sounding.add(note);
            }
            assertEquals(3, sounding.size(), describe(view.activeTrack()));
            assertEquals(java.util.List.of(0L, 240L, 480L),
                    java.util.List.of(sounding.get(0).startTick(), sounding.get(1).startTick(),
                            sounding.get(2).startTick()),
                    "三条音应当首尾相接且落在网格上：" + describe(view.activeTrack()));
            for (Note note : sounding) {
                assertEquals(240, note.durationTicks(),
                        "100ms 吸附成一个八分音符：" + describe(view.activeTrack()));
            }
        });
    }

    @Test
    void aLongPauseDoesNotPushTheNextNoteAway() throws Exception {
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

    @Test
    void backToBackPressesStayConnectedUnderAssist() throws Exception {
        withDocument((app, view, dispatcher) -> {
            long[] now = { 0 };
            view.setNanoClockForTesting(() -> now[0]);
            view.document().tempoMap().setBpm(0, 125); // 1 tick = 1000µs
            SwingUtilities.invokeAndWait(() -> {
                view.grid().setResolution(com.example.mideditor.domain.GridSettings.Resolution.EIGHTH);
                view.setInputMode(true);
                view.setInputBeatSnap(true);
            });

            // 按住 2.9 秒 → 松开 → 150ms 后再按住 2.9 秒（同一个音）
            now[0] = 0;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_SPACE, 0));
            now[0] = 2_900_000_000L;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_SPACE, 0));
            now[0] = 3_050_000_000L;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_SPACE, 0));
            now[0] = 5_950_000_000L;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_SPACE, 0));

            java.util.List<Note> sounding = new java.util.ArrayList<>();
            for (Note note : view.activeTrack().notes()) {
                if (note.isRest()) {
                    continue;
                }
                sounding.add(note);
            }
            assertEquals(2, sounding.size(), "应当正好两条平行线");
            assertEquals(sounding.get(0).endTick(), sounding.get(1).startTick(),
                    "第二下应当直接接在上一条末尾：" + describe(view.activeTrack()));
        });
    }

    @Test
    void doubleTapStaysConnectedAcrossTypicalSpeeds() throws Exception {
        withDocument((app, view, dispatcher) -> {
            long[] now = { 0 };
            view.setNanoClockForTesting(() -> now[0]);
            // 60 BPM：1 tick = 2083µs，1/16 网格 = 120 tick ≈ 250ms
            SwingUtilities.invokeAndWait(() -> {
                view.grid().setResolution(com.example.mideditor.domain.GridSettings.Resolution.SIXTEENTH);
            });

            long[] holds = { 300, 900, 3000 };
            // 间隔多长都不算休止符：输入模式只按“按住时长”下音，不拿间隔做文章
            long[] gaps = { 0, 60, 150, 250, 400 };
            for (long holdMs : holds) {
                for (long gapMs : gaps) {
                    Track track = newTrack(view);
                    SwingUtilities.invokeAndWait(() -> {
                        view.setInputMode(true);
                        view.setInputBeatSnap(true);
                    });
                    now[0] = 0;
                    dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_SPACE, 0));
                    now[0] = holdMs * 1_000_000L;
                    dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_SPACE, 0));
                    now[0] = (holdMs + gapMs) * 1_000_000L;
                    dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_SPACE, 0));
                    now[0] = (holdMs * 2 + gapMs) * 1_000_000L;
                    dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_SPACE, 0));

                    java.util.List<Note> sounding = new java.util.ArrayList<>();
                    for (Note note : track.notes()) {
                        if (!note.isRest()) {
                            sounding.add(note);
                        }
                    }
                    String where = "hold=" + holdMs + "ms gap=" + gapMs + "ms -> " + describe(track);
                    assertEquals(2, sounding.size(), where);
                    for (Note note : track.notes()) {
                        assertFalse(note.isRest(), "按两下不该冒出休止符：" + where);
                    }
                    assertEquals(sounding.get(0).endTick(), sounding.get(1).startTick(),
                            "两下必须首尾相连：" + where);
                    SwingUtilities.invokeAndWait(() -> view.setInputMode(false));
                }
            }
        });
    }

    /**
     * 长音之后紧接着再按同一个音：人工的「松开 → 再按下」间隔有零点几秒很正常，
     * 连奏容差跟着音长走，这段空隙不能被拆成休止符。
     */
    @Test
    void longHeldNotesStayConnectedWhateverThePause() throws Exception {
        withDocument((app, view, dispatcher) -> {
            long[] now = { 0 };
            view.setNanoClockForTesting(() -> now[0]);
            // 60 BPM（默认）：3 秒 = 1440 tick = 1/16 的 12 格
            com.example.mideditor.domain.GridSettings.Resolution[] resolutions = {
                    com.example.mideditor.domain.GridSettings.Resolution.SIXTEENTH,
                    com.example.mideditor.domain.GridSettings.Resolution.THIRTY_SECOND,
                    com.example.mideditor.domain.GridSettings.Resolution.QUARTER,
            };
            long[] gaps = { 0, 200, 400, 600, 800, 1100, 1400 };
            for (var resolution : resolutions) {
                for (long gapMs : gaps) {
                    Track track = newTrack(view);
                    SwingUtilities.invokeAndWait(() -> {
                        view.grid().setResolution(resolution);
                        view.setInputMode(true);
                        view.setInputBeatSnap(true);
                    });
                    long holdMs = 3000;
                    now[0] = 0;
                    dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_SPACE, 0));
                    now[0] = holdMs * 1_000_000L;
                    dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_SPACE, 0));
                    now[0] = (holdMs + gapMs) * 1_000_000L;
                    dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_SPACE, 0));
                    now[0] = (holdMs * 2 + gapMs) * 1_000_000L;
                    dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_SPACE, 0));
                    SwingUtilities.invokeAndWait(() -> view.setInputMode(false));

                    String where = resolution.label() + " gap=" + gapMs + "ms -> " + describe(track);
                    java.util.List<Note> sounding = new java.util.ArrayList<>();
                    for (Note note : track.notes()) {
                        assertFalse(note.isRest(), "长音之后紧接着再按不该冒出休止符：" + where);
                        sounding.add(note);
                    }
                    assertEquals(2, sounding.size(), where);
                    assertEquals(sounding.get(0).endTick(), sounding.get(1).startTick(),
                            "两条必须首尾相连：" + where);
                }
            }
        });
    }

    /**
     * 细网格（1/32）下两点：连奏容差不能被「两格」的宽度卡死——两格只有 250ms，
     * 人手按得快也要 300~400ms。
     */
    @Test
    void fineGridDoesNotSplitQuickDoubleTapsWithRests() throws Exception {
        withDocument((app, view, dispatcher) -> {
            long[] now = { 0 };
            view.setNanoClockForTesting(() -> now[0]);
            for (long gapMs : new long[] { 300, 400, 500, 600 }) {
                Track track = newTrack(view);
                SwingUtilities.invokeAndWait(() -> {
                    view.grid().setResolution(
                            com.example.mideditor.domain.GridSettings.Resolution.THIRTY_SECOND);
                    view.setInputMode(true);
                    view.setInputBeatSnap(true);
                });
                now[0] = 0;
                dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_SPACE, 0));
                now[0] = 200_000_000L;
                dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_SPACE, 0));
                now[0] = (200 + gapMs) * 1_000_000L;
                dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_SPACE, 0));
                now[0] = (400 + gapMs) * 1_000_000L;
                dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_SPACE, 0));
                SwingUtilities.invokeAndWait(() -> view.setInputMode(false));

                String where = "1/32 gap=" + gapMs + "ms -> " + describe(track);
                java.util.List<Note> sounding = new java.util.ArrayList<>();
                for (Note note : track.notes()) {
                    assertFalse(note.isRest(), "细网格下快按两下不该冒出休止符：" + where);
                    sounding.add(note);
                }
                assertEquals(2, sounding.size(), where);
                assertEquals(sounding.get(0).endTick(), sounding.get(1).startTick(),
                        "两条必须首尾相连：" + where);
            }
        });
    }

    /** 在新的音轨上跑一个组合，免得上一组的音/休止符混进来。 */
    private static Track newTrack(MidiDocumentView view) throws Exception {
        Track[] holder = new Track[1];
        SwingUtilities.invokeAndWait(() -> {
            view.addTrack();
            holder[0] = view.activeTrack();
        });
        return holder[0];
    }

    /**
     * 不变量审计：在各种网格档位下敲一串节奏，文档里不允许出现
     * ①任何自动生成的休止符；②互相重叠的平行线；③比按住时长更长的音。
     */
    @Test
    void assistNeverInsertsRestsOrOverlaps() throws Exception {
        withDocument((app, view, dispatcher) -> {
            long[] now = { 0 };
            view.setNanoClockForTesting(() -> now[0]);
            // {按住 ms, 间隔 ms} —— 混着长音、断奏、快连按
            long[][] script = {
                    { 300, 80 }, { 1200, 0 }, { 450, 250 }, { 2000, 120 },
                    { 700, 400 }, { 150, 60 }, { 900, 300 }, { 2400, 150 },
            };
            com.example.mideditor.domain.GridSettings.Resolution[] resolutions = {
                    com.example.mideditor.domain.GridSettings.Resolution.QUARTER,
                    com.example.mideditor.domain.GridSettings.Resolution.EIGHTH,
                    com.example.mideditor.domain.GridSettings.Resolution.SIXTEENTH,
                    com.example.mideditor.domain.GridSettings.Resolution.EIGHTH_TRIPLET,
            };
            for (com.example.mideditor.domain.GridSettings.Resolution resolution : resolutions) {
                Track track = newTrack(view);
                SwingUtilities.invokeAndWait(() -> {
                    view.grid().setResolution(resolution);
                    view.setInputMode(true);
                    view.setInputBeatSnap(true);
                });
                long unit = resolution.ticksPerGrid(view.document().ppq());
                long timeMs = 0;
                for (long[] step : script) {
                    now[0] = timeMs * 1_000_000L;
                    dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_SPACE, 0));
                    now[0] = (timeMs + step[0]) * 1_000_000L;
                    dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_SPACE, 0));
                    timeMs += step[0] + step[1];
                }
                SwingUtilities.invokeAndWait(() -> view.setInputMode(false));

                String where = resolution.label() + " -> " + describe(track);
                for (Note note : track.notes()) {
                    assertFalse(note.isRest(), "输入模式不得自己插休止符（单位 " + unit + " tick）：" + where);
                    assertEquals(0, note.durationTicks() % unit,
                            "时值必须吸附成整数格（" + unit + " tick）：" + where);
                    assertEquals(0, note.startTick() % unit,
                            "起点必须落在网格线上（" + unit + " tick）：" + where);
                }
                long previousEnd = 0;
                for (Note note : track.notes()) {
                    assertTrue(note.startTick() >= previousEnd, "平行线不能互相重叠：" + where);
                    previousEnd = note.endTick();
                }
            }
        });
    }

    private static String describe(Track track) {
        StringBuilder sb = new StringBuilder();
        for (Note note : track.notes()) {
            sb.append(note.isRest() ? "休止" : "音").append('[')
                    .append(note.startTick()).append("..").append(note.endTick()).append("] ");
        }
        return sb.toString();
    }

    // ------------------------------------------------ 小键盘数字输入（简谱）

    /** 小键盘 1~7 就是简谱的 do~si：按下立刻切音高并开始输入，松手按按住时长落成平行线。 */
    @Test
    void numpadDigitsEnterTheDegreeInTheCursorOctave() throws Exception {
        withDocument((app, view, dispatcher) -> {
            long[] now = { 0 };
            view.setNanoClockForTesting(() -> now[0]);
            SwingUtilities.invokeAndWait(() -> view.setInputMode(true));
            int base = Math.floorDiv(view.inputCursorPitch(), 12) * 12;
            int[] semitones = { 0, 2, 4, 5, 7, 9, 11 };

            long timeMs = 0;
            for (int degree = 1; degree <= 7; degree++) {
                int code = KeyEvent.VK_NUMPAD0 + degree;
                now[0] = timeMs * 1_000_000L;
                assertTrue(dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, code, 0)),
                        "输入模式下小键盘数字应当被接住");
                assertEquals(base + semitones[degree - 1], view.inputCursorPitch(),
                        "按下 " + degree + " 光标立刻切到这个简谱音");
                assertTrue(view.inputNoteHeld(), "不用按空格：按下数字就开始输入");
                now[0] = (timeMs + 240) * 1_000_000L;
                assertTrue(dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, code, 0)),
                        "松手也要接住");
                timeMs += 240;
            }

            java.util.List<Note> sounding = soundingNotes(view.activeTrack());
            assertEquals(7, sounding.size(), "七个数字应当落成七条平行线：" + describe(view.activeTrack()));
            long previousEnd = 0;
            for (int i = 0; i < sounding.size(); i++) {
                Note note = sounding.get(i);
                assertEquals(base + semitones[i], note.pitch(), "第 " + (i + 1) + " 个数字的音高");
                assertTrue(note.durationTicks() > 50,
                        "按住 240ms 不该只落成一两个 tick：" + note.durationTicks());
                if (i > 0) {
                    assertEquals(previousEnd, note.startTick(), "小键盘连敲应当首尾相连、不留缝");
                }
                previousEnd = note.endTick();
            }
            assertEquals(previousEnd, view.inputCursorTick(), "光标跟到最后一条的末尾");
        });
    }

    /** 小键盘 +/- 挪八度，而且只作用于接下来按下的那个数字：输完一个音就归零。 */
    @Test
    void numpadPlusMinusShiftTheOctaveAndResetAfterEachNote() throws Exception {
        withDocument((app, view, dispatcher) -> {
            long[] now = { 0 };
            view.setNanoClockForTesting(() -> now[0]);
            SwingUtilities.invokeAndWait(() -> view.setInputMode(true));
            int octave = Math.floorDiv(view.inputCursorPitch(), 12);

            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_ADD, 0));
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_ADD, 0));
            assertEquals(2, view.inputOctaveJump(), "小键盘 + 每按一次加一个八度");

            now[0] = 0;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_NUMPAD5, 0));
            assertEquals((octave + 2) * 12 + 7, view.inputCursorPitch(), "5 = sol，再高两个八度");
            now[0] = 240_000_000L;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_NUMPAD5, 0));
            assertEquals(0, view.inputOctaveJump(), "输完一个音 jump 归零，不复用");

            // 光标已经停在高两个八度的那个八度里：这一下不该再往上跑
            now[0] = 400_000_000L;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_NUMPAD3, 0));
            assertEquals((octave + 2) * 12 + 4, view.inputCursorPitch(),
                    "jump 若没归零，这里会跑到 (octave+4) 去");
            now[0] = 640_000_000L;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_NUMPAD3, 0));

            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_SUBTRACT, 0));
            assertEquals(-1, view.inputOctaveJump(), "小键盘 - 降一个八度");
            now[0] = 800_000_000L;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_NUMPAD1, 0));
            assertEquals((octave + 1) * 12, view.inputCursorPitch(), "1 = do，降回来一个八度");
            now[0] = 1_040_000_000L;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_NUMPAD1, 0));

            java.util.List<Note> sounding = soundingNotes(view.activeTrack());
            assertEquals(3, sounding.size(), describe(view.activeTrack()));
            assertEquals((octave + 2) * 12 + 7, sounding.get(0).pitch());
            assertEquals((octave + 2) * 12 + 4, sounding.get(1).pitch());
            assertEquals((octave + 1) * 12, sounding.get(2).pitch());
        });
    }

    /** 按住大键盘 +/= 时数字高一个八度，-/_ 低一个八度；再按住 Ctrl 则两个八度。 */
    @Test
    void holdingMainKeyboardPlusMinusShiftsTheNumpadOctave() throws Exception {
        withDocument((app, view, dispatcher) -> {
            long[] now = { 0 };
            view.setNanoClockForTesting(() -> now[0]);
            SwingUtilities.invokeAndWait(() -> view.setInputMode(true));
            int base = Math.floorDiv(view.inputCursorPitch(), 12) * 12;
            int ctrl = java.awt.event.InputEvent.CTRL_DOWN_MASK;

            // 按一下 =（+）：紧接着的那一个数字高一个八度
            assertTrue(dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_EQUALS, 0)));
            assertEquals(1, view.inputOctaveHold(), "按 = 应当挂上 +1 八度");
            now[0] = 0;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_NUMPAD1, 0));
            assertEquals(base + 12, view.inputCursorPitch(), "= 之后的 1 落在高八度");
            now[0] = 240_000_000L;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_NUMPAD1, 0));
            assertEquals(0, view.inputOctaveHold(), "临时偏移用完就撤，不用按 + 扳回来");

            // = 还按着也一样：下一个数字回到原八度
            moveCursorPitchTo(view, base);
            now[0] = 400_000_000L;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_NUMPAD2, 0));
            assertEquals(base + 2, view.inputCursorPitch(), "第二个数字回到原来的八度");
            now[0] = 640_000_000L;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_NUMPAD2, 0));

            // 按住不放的自动重复不能再把偏移挂回来
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_EQUALS, 0));
            assertEquals(0, view.inputOctaveHold(), "自动重复不算新的一次按键");
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_EQUALS, 0));
            // 松开后重新按才算（挂在临时的这一次上）
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_EQUALS, 0));
            assertEquals(1, view.inputOctaveHold(), "重新按下才重新挂上");

            // Ctrl + = + 数字 = 两个八度（用的还是刚挂上的那一次）
            moveCursorPitchTo(view, base);
            now[0] = 1_200_000_000L;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_NUMPAD1, ctrl));
            assertEquals(base + 24, view.inputCursorPitch(), "Ctrl + = + 1 = 高两个八度");
            now[0] = 1_440_000_000L;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_NUMPAD1, ctrl));
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_EQUALS, 0));
            assertEquals(0, view.inputOctaveHold(), "两个八度也是临时的");

            // 用户场景：大键盘 - + 小键盘 7 = 低音 7；再敲 7 回到原来的八度（不用按 + 扳回来）
            moveCursorPitchTo(view, base);
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_MINUS, 0));
            assertEquals(-1, view.inputOctaveHold(), "按住 - 应当挂上 -1 八度");
            now[0] = 800_000_000L;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_NUMPAD7, 0));
            assertEquals(base - 1, view.inputCursorPitch(), "- 之后的 7 落在低八度（低八度的 si）");
            now[0] = 1_040_000_000L;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_NUMPAD7, 0));
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_MINUS, 0));

            now[0] = 1_200_000_000L;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_NUMPAD7, 0));
            assertEquals(base + 11, view.inputCursorPitch(), "下一个 7 回到原来的八度");
            now[0] = 1_440_000_000L;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_NUMPAD7, 0));

            // Ctrl + - + 数字 = 低两个八度
            moveCursorPitchTo(view, base);
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_MINUS, ctrl));
            now[0] = 1_600_000_000L;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_NUMPAD1, ctrl));
            assertEquals(base - 24, view.inputCursorPitch(), "Ctrl + - + 1 = 低两个八度");
            now[0] = 1_840_000_000L;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_NUMPAD1, ctrl));
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_MINUS, 0));

            // 小键盘 +/- 那套不受影响：jump 可以累积，输完一个音才归零
            moveCursorPitchTo(view, base);
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_ADD, 0));
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_ADD, 0));
            assertEquals(2, view.inputOctaveJump(), "小键盘 + 照样能累积两个八度");
            now[0] = 2_000_000_000L;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_NUMPAD3, 0));
            assertEquals(base + 24 + 4, view.inputCursorPitch(), "3（mi）高两个八度");
            now[0] = 2_240_000_000L;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_NUMPAD3, 0));
            assertEquals(0, view.inputOctaveJump(), "输完一个音 jump 归零");

            java.util.List<Note> sounding = soundingNotes(view.activeTrack());
            int[] expected = { base + 12, base + 2, base + 24, base - 1, base + 11, base - 24, base + 28 };
            assertEquals(expected.length, sounding.size(), describe(view.activeTrack()));
            for (int i = 0; i < expected.length; i++) {
                assertEquals(expected[i], sounding.get(i).pitch(), "第 " + (i + 1) + " 条音高");
            }
        });
    }

    /** 每输入一条，刚落的这条就是当前选中（换选中，不累加）。 */
    @Test
    void everyInputNoteBecomesTheSelection() throws Exception {
        withDocument((app, view, dispatcher) -> {
            long[] now = { 0 };
            view.setNanoClockForTesting(() -> now[0]);
            SwingUtilities.invokeAndWait(() -> view.setInputMode(true));

            now[0] = 0;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_SPACE, 0));
            now[0] = 240_000_000L;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_SPACE, 0));
            java.util.List<Note> first = view.selection().resolve(view.document());
            assertEquals(1, first.size(), "空格输入的这条应当被选中");
            Note firstNote = soundingNotes(view.activeTrack()).get(0);
            assertEquals(firstNote.id(), first.get(0).id(), "选中的就是刚输入的那条");

            // 小键盘输入同样选中新的一条，且只换不累加
            now[0] = 400_000_000L;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_NUMPAD3, 0));
            now[0] = 640_000_000L;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_NUMPAD3, 0));
            java.util.List<Note> second = view.selection().resolve(view.document());
            assertEquals(1, second.size(), "选中应当只跟着最新那条");
            assertEquals(soundingNotes(view.activeTrack()).get(1).id(), second.get(0).id(),
                    "小键盘输入的这条成了新的选中");
            assertFalse(view.selection().contains(firstNote), "上一条不该还选着");

            // 退出输入模式不清选中（Esc 的语义不变）
            SwingUtilities.invokeAndWait(() -> view.setInputMode(false));
            assertEquals(1, view.selection().resolve(view.document()).size(),
                    "退出输入模式不该顺手清掉刚输入的选中");
        });
    }

    /** 一个音都没有时，进入输入模式光标落在时间轴最左边（tick 0）的 C 上。 */
    @Test
    void enteringInputModeWithNoNotesPutsTheCursorAtTheStart() throws Exception {
        withDocument((app, view, dispatcher) -> {
            SwingUtilities.invokeAndWait(() -> {
                var roll = view.pianoRoll();
                roll.scrollPane().getViewport().setSize(1000, 700);
                roll.canvas().setSize(6000, 800);
                roll.scrollPane().getViewport().setViewPosition(new java.awt.Point(2000, 0));
            });
            java.awt.Rectangle visible = view.pianoRoll().visibleRect();
            assertTrue(visible.width > 100, "前提：视口要有尺寸，实际 " + visible);

            SwingUtilities.invokeAndWait(() -> view.setInputMode(true));
            assertEquals(0, view.inputCursorTick(), "空文档：光标回到最左边（tick 0）");
            assertEquals(60, view.inputCursorPitch(), "音高落在 C（默认音域中央就是 C4）");
            assertEquals(0, view.inputCursorPitch() % 12, "确实是个 C");
            assertEquals(0, view.pianoRoll().visibleRect().x, "频谱也跟着拉回开头");
        });
    }

    /** 进入输入模式时光标接在最后一个音后面。 */
    @Test
    void enteringInputModePutsTheCursorAfterTheLastNote() throws Exception {
        withDocument((app, view, dispatcher) -> {
            SwingUtilities.invokeAndWait(() -> {
                Track track = view.activeTrack();
                track.addNote(new Note(64, 0, 240, 100));
                track.addNote(new Note(67, 480, 240, 100));
                track.sortNotes();
                view.afterEdit(true);
            });
            SwingUtilities.invokeAndWait(() -> view.setInputMode(true));
            assertEquals(720, view.inputCursorTick(), "光标接在最后一个音的末尾");
            assertEquals(67, view.inputCursorPitch(), "音高也取最后一个音");
        });
    }

    /** Ctrl+I 只有两态：关 ⇄ 输入模式＋节奏辅助。 */
    @Test
    void ctrlITogglesInputModeWithBeatSnapInTwoStates() throws Exception {
        withDocument((app, view, dispatcher) -> {
            SwingUtilities.invokeAndWait(
                    () -> app.router().dispatch(com.example.mideditor.command.AppCommandIds.EDIT_INPUT_MODE));
            assertTrue(view.inputMode(), "第一下进入输入模式");
            assertTrue(view.inputBeatSnap(), "并且带节奏辅助");
            SwingUtilities.invokeAndWait(
                    () -> app.router().dispatch(com.example.mideditor.command.AppCommandIds.EDIT_INPUT_MODE));
            assertFalse(view.inputMode(), "第二下直接关掉（没有中间态）");
        });
    }

    /** 调号是每个文档自己的属性，默认 C，小键盘简谱按调号取音。 */
    @Test
    void keySignatureIsPerDocumentAndDrivesTheDegrees() throws Exception {
        withDocument((app, view, dispatcher) -> {
            long[] now = { 0 };
            view.setNanoClockForTesting(() -> now[0]);
            assertEquals(0, view.document().keyTonic(), "默认 C");
            SwingUtilities.invokeAndWait(() -> {
                view.setKeySignature(7); // G 调（传输条上那个下拉框走的就是这条路径）
                view.setInputMode(true);
            });
            int octave = Math.floorDiv(view.inputCursorPitch(), 12);
            now[0] = 0;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_NUMPAD1, 0));
            assertEquals(octave * 12 + 7, view.inputCursorPitch(), "1 = 调主音 G");
            assertEquals("1", view.inputBadgeDegree(), "简谱数字跟着调号走");
            now[0] = 240_000_000L;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_NUMPAD1, 0));

            SwingUtilities.invokeAndWait(app.documents()::newDocument);
            assertEquals(0, app.documents().activeView().document().keyTonic(), "新文档各自一份（默认 C）");
        });
    }

    /** 按住空格 / Q / 小键盘数字时显示「音名 + 简谱数字」浮牌，松手后 1 秒才消失。 */
    @Test
    void pitchBadgeShowsWhileHoldingAndLingersOneSecond() throws Exception {
        withDocument((app, view, dispatcher) -> {
            long[] now = { 0 };
            view.setNanoClockForTesting(() -> now[0]);
            SwingUtilities.invokeAndWait(() -> view.setInputMode(true));
            int pitch = view.inputCursorPitch();
            long tick = view.inputCursorTick();
            assertTrue(view.inputBadgeVisible(), "刚进输入模式也会提示一下当前音");
            now[0] = 1_500_000_000L;
            assertFalse(view.inputBadgeVisible(), "这个提示 1 秒后自动消失");

            now[0] = 2_000_000_000L;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_SPACE, 0));
            assertTrue(view.inputBadgeVisible(), "按住空格就显示");
            assertEquals(pitch, view.inputBadgePitch(), "音高取按下的光标");
            assertEquals(tick, view.inputBadgeTick(), "贴在按下之前的光标位置");
            assertEquals("C4", view.inputBadgePitchName(), "左格：科学记音法");
            assertEquals("1", view.inputBadgeDegree(), "右格：当前调的简谱数字");

            now[0] = 2_300_000_000L;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_SPACE, 0));
            assertTrue(view.inputBadgeVisible(), "松手后还没到 1 秒，仍然显示");
            now[0] = 2_900_000_000L;
            assertTrue(view.inputBadgeVisible(), "0.9 秒时还在");
            now[0] = 3_400_000_000L;
            assertFalse(view.inputBadgeVisible(), "超过 1 秒就消失");

            // 小键盘数字：音名跟着音高走，简谱数字跟着级数走
            now[0] = 4_000_000_000L;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_NUMPAD7, 0));
            assertTrue(view.inputBadgeVisible(), "小键盘数字也显示");
            assertEquals("B4", view.inputBadgePitchName(), "7 = si");
            assertEquals("7", view.inputBadgeDegree());
            now[0] = 4_240_000_000L;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_NUMPAD7, 0));

            // Q 试听同样显示
            now[0] = 4_600_000_000L;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_Q, 0));
            assertTrue(view.inputBadgeVisible(), "Q 试听也显示浮牌");
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_Q, 0));
            assertTrue(view.inputBadgeVisible(), "Q 松手后也停留一会儿");
        });
    }

    /** 光标换到一个新音（W/S、Ctrl+U/L）时也亮一下牌子，1 秒后消失；只挪时间不亮。 */
    @Test
    void switchingToOneNoteLightsThePitchBadgeForASecond() throws Exception {
        withDocument((app, view, dispatcher) -> {
            long[] now = { 0 };
            view.setNanoClockForTesting(() -> now[0]);
            SwingUtilities.invokeAndWait(() -> view.setInputMode(true));
            int pitch = view.inputCursorPitch();

            now[0] = 1_500_000_000L;
            assertFalse(view.inputBadgeVisible(), "前提：进场提示已经过了 1 秒");

            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_W, 0));
            assertTrue(view.inputBadgeVisible(), "换到一个新音就亮牌子");
            assertEquals(pitch + 1, view.inputBadgePitch(), "亮的是新音高");
            assertEquals("C#4", view.inputBadgePitchName());

            now[0] = 2_000_000_000L;
            assertTrue(view.inputBadgeVisible(), "0.5 秒时还在");
            now[0] = 2_600_000_000L;
            assertFalse(view.inputBadgeVisible(), "1 秒后消失");

            // 只左右挪时间（音高没变）不亮
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_D, 0));
            assertFalse(view.inputBadgeVisible(), "只挪时间不该亮牌子");

            // Ctrl+U 整八度也算“换到一个新音”
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_U,
                    java.awt.event.InputEvent.CTRL_DOWN_MASK));
            assertTrue(view.inputBadgeVisible(), "Ctrl+U 也亮");
            assertEquals(pitch + 13, view.inputBadgePitch());
            assertEquals("C#5", view.inputBadgePitchName());
        });
    }

    /** 浮牌真的画在画布上了（边框颜色出现在图里）。 */
    @Test
    void pitchBadgeIsPainted() throws Exception {
        withDocument((app, view, dispatcher) -> {
            SwingUtilities.invokeAndWait(() -> view.setInputMode(true));
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_SPACE, 0));
            try {
                SwingUtilities.invokeAndWait(() -> {
                    var roll = view.pianoRoll();
                    var canvas = roll.canvas();
                    int width = 900;
                    int height = Math.max(300, roll.laneHeight());
                    canvas.setSize(width, height);
                    canvas.doLayout();
                    java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                            width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
                    java.awt.Graphics2D g = image.createGraphics();
                    g.setColor(java.awt.Color.BLACK);
                    g.fillRect(0, 0, width, height);
                    canvas.paint(g);
                    g.dispose();
                    int border = Theme.INPUT_BADGE_BORDER.getRGB();
                    int hits = 0;
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            if (image.getRGB(x, y) == border) {
                                hits++;
                            }
                        }
                    }
                    assertTrue(hits > 10, "按住输入时浮牌应当画出来（边框像素 " + hits + " 个）");
                });
            } finally {
                dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_SPACE, 0));
            }
        });
    }

    /** 小键盘 0 输入休止符：按住多久就留多长的空，光标跟着走。 */
    @Test
    void numpadZeroEntersARest() throws Exception {
        withDocument((app, view, dispatcher) -> {
            long[] now = { 0 };
            view.setNanoClockForTesting(() -> now[0]);
            SwingUtilities.invokeAndWait(() -> view.setInputMode(true));

            long time = 0;
            now[0] = time;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_NUMPAD1, 0));
            time += 240;
            now[0] = time;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_NUMPAD1, 0));

            now[0] = time;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_NUMPAD0, 0));
            assertTrue(view.inputNoteHeld(), "0 也是“按住输入”");
            assertEquals("0", view.inputBadgeDegree(), "浮牌右格写 0");
            time += 240;
            now[0] = time;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_NUMPAD0, 0));

            now[0] = time;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_NUMPAD5, 0));
            time += 240;
            now[0] = time;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_NUMPAD5, 0));

            List<Note> notes = view.activeTrack().notes();
            assertEquals(3, notes.size(), "音 · 休止 · 音：" + describe(view.activeTrack()));
            assertFalse(notes.get(0).isRest());
            assertTrue(notes.get(1).isRest(), "中间那条是休止符");
            assertFalse(notes.get(2).isRest());
            assertEquals(notes.get(0).endTick(), notes.get(1).startTick(), "休止符接在第一个音后面");
            assertEquals(notes.get(1).endTick(), notes.get(2).startTick(), "第三个音接在休止符后面");
        });
    }

    /** 小调用的是自然小调音阶：简谱 3 是 ♭3（A 小调的 3 = C）。 */
    @Test
    void minorKeysUseTheNaturalMinorScale() throws Exception {
        withDocument((app, view, dispatcher) -> {
            long[] now = { 0 };
            view.setNanoClockForTesting(() -> now[0]);
            SwingUtilities.invokeAndWait(() -> {
                view.setKeySignature(9, true); // A 小调
                view.setInputMode(true);
            });
            int octave = Math.floorDiv(view.inputCursorPitch(), 12);
            now[0] = 0;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_NUMPAD3, 0));
            assertEquals(octave * 12 + 9 + 3, view.inputCursorPitch(), "A 小调的 3 是 C（♭3）");
            assertEquals("3", view.inputBadgeDegree(), "浮牌也按小调算");
            now[0] = 240_000_000L;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_NUMPAD3, 0));
        });
    }

    /** 时间轴不是“有限的一段”：光标一路往右走，画布要跟着变长，不然会跑出可见区域。 */
    @Test
    void movingTheCursorFarRightKeepsTheTimelineGrowing() throws Exception {
        withDocument((app, view, dispatcher) -> {
            SwingUtilities.invokeAndWait(() -> {
                var roll = view.pianoRoll();
                roll.scrollPane().getViewport().setSize(1000, 700);
                roll.canvas().setSize(6000, 800);
                view.setInputMode(true);
            });
            int before = view.pianoRoll().canvas().getPreferredSize().width;

            SwingUtilities.invokeAndWait(() -> {
                for (int i = 0; i < 400; i++) {
                    view.moveInputCursor(1, 0);
                }
            });

            int after = view.pianoRoll().canvas().getPreferredSize().width;
            int cursorX = view.pianoRoll().timeline()
                    .xAtTick(view.inputCursorTick(), view.document().ppq());
            assertTrue(after > before, "画布要跟着光标加长：" + before + " → " + after);
            assertTrue(cursorX + 200 <= after, "光标右边还要留出滚动余量：x=" + cursorX + " 宽度 " + after);
        });
    }

    /** 把红色光标挪到指定音高（测试里用来把八度基准摆正）。 */
    private static void moveCursorPitchTo(MidiDocumentView view, int pitch) throws Exception {
        int delta = pitch - view.inputCursorPitch();
        if (delta != 0) {
            SwingUtilities.invokeAndWait(() -> view.moveInputCursor(0, delta));
        }
    }

    /** 两套输入并存：空格写的音和小键盘写的音接着往下写，不打架。 */
    @Test
    void numpadAndSpaceInputsChainTogether() throws Exception {
        withDocument((app, view, dispatcher) -> {
            long[] now = { 0 };
            view.setNanoClockForTesting(() -> now[0]);
            SwingUtilities.invokeAndWait(() -> view.setInputMode(true));
            int base = Math.floorDiv(view.inputCursorPitch(), 12) * 12;

            now[0] = 0;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_SPACE, 0));
            now[0] = 240_000_000L;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_SPACE, 0));

            now[0] = 400_000_000L;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_NUMPAD3, 0));
            now[0] = 640_000_000L;
            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_RELEASED, KeyEvent.VK_NUMPAD3, 0));

            java.util.List<Note> sounding = soundingNotes(view.activeTrack());
            assertEquals(2, sounding.size(), "空格和小键盘各写一条：" + describe(view.activeTrack()));
            assertEquals(base, sounding.get(0).pitch(), "空格那条还是光标原来的音高");
            assertEquals(base + 4, sounding.get(1).pitch(), "小键盘 3 = mi");
            assertEquals(sounding.get(0).endTick(), sounding.get(1).startTick(),
                    "小键盘输入接着空格输入继续往下写，中间不留缝");
        });
    }

    @Test
    void numpadIsNotConsumedWhenInputModeIsOff() throws Exception {
        withDocument((app, view, dispatcher) -> {
            KeyEvent press = key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_NUMPAD3, 0);
            assertFalse(dispatcher.dispatchKeyEvent(press), "输入模式没开时小键盘数字要放行");
            assertFalse(press.isConsumed());
            assertTrue(soundingNotes(view.activeTrack()).isEmpty(), "没开输入模式不该写进音符");
        });
    }

    /** Ctrl+U / Ctrl+L：光标整体上 / 下移一个八度（只挪光标，不动时间、不出音符）。 */
    @Test
    void ctrlUMovesTheCursorUpAnOctaveAndCtrlLDown() throws Exception {
        withDocument((app, view, dispatcher) -> {
            SwingUtilities.invokeAndWait(() -> view.setInputMode(true));
            int ctrl = java.awt.event.InputEvent.CTRL_DOWN_MASK;
            int pitch0 = view.inputCursorPitch();
            long tick0 = view.inputCursorTick();

            KeyEvent up = key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_U, ctrl);
            assertTrue(dispatcher.dispatchKeyEvent(up), "输入模式下 Ctrl+U 应当被接住");
            assertTrue(up.isConsumed());
            assertEquals(pitch0 + 12, view.inputCursorPitch(), "Ctrl+U 上移一个八度");
            assertEquals(tick0, view.inputCursorTick(), "只动音高，不动时间");

            dispatcher.dispatchKeyEvent(key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_L, ctrl));
            assertEquals(pitch0, view.inputCursorPitch(), "Ctrl+L 下移一个八度");
            assertEquals(tick0, view.inputCursorTick(), "仍然不动时间");

            // 带 Shift 的 Ctrl+Shift+L 仍是「循环域＝选区」，输入模式不抢
            KeyEvent loopToSelection = key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_L,
                    ctrl | java.awt.event.InputEvent.SHIFT_DOWN_MASK);
            assertFalse(dispatcher.dispatchKeyEvent(loopToSelection), "Ctrl+Shift+L 要放行");
            assertEquals(pitch0, view.inputCursorPitch(), "Ctrl+Shift+L 不该动光标");

            // 裸 U / L 不归输入模式管（留给命令系统）
            KeyEvent plain = key(app, KeyEvent.KEY_PRESSED, KeyEvent.VK_U, 0);
            assertFalse(dispatcher.dispatchKeyEvent(plain), "不带 Ctrl 的 U 要放行");
            assertEquals(pitch0, view.inputCursorPitch(), "裸 U 不该动光标");
            assertTrue(soundingNotes(view.activeTrack()).isEmpty(), "挪光标不该写出音符");
        });
    }

    private static java.util.List<Note> soundingNotes(Track track) {
        java.util.List<Note> list = new java.util.ArrayList<>();
        for (Note note : track.notes()) {
            if (!note.isRest()) {
                list.add(note);
            }
        }
        return list;
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
