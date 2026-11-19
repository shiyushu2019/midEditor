package com.example.mideditor.document;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;

import javax.swing.JSpinner;
import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import org.junit.jupiter.api.Test;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.app.MainFrame;

/**
 * 改完 BPM 立刻按播放：那次改动必须真的生效（不能只是挂在输入框里的文字）。
 *
 * <p>
 * 用户报的现象：「每次调完 bpm，F5 就失效了，而且那次调的速度也不生效」——
 * 原因是 JSpinner 只在回车 / 失去焦点时才提交，而且焦点留在输入框里时
 * 播放命令被“正在打字”挡掉了（F5 已经不受影响，见 {@code TextFocusGuardTest}）。
 * </p>
 */
class BpmTempoTest {

    @Test
    void tempoTypedButNotCommittedIsAppliedBeforePlayback() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView view = openDocument(app, holder);
            assertEquals(60.0, view.document().tempoMap().bpmAt(0), 0.01, "前提：默认 60 BPM");

            JSpinner spinner = findSpinner(view.transportBar());
            assertNotNull(spinner, "传输条上应当有 BPM 输入框");
            // 用户在 BPM 框里敲了 90，还没按回车
            SwingUtilities
                    .invokeAndWait(() -> ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setText("90"));
            assertEquals(60.0, view.document().tempoMap().bpmAt(0), 0.01,
                    "前提：没提交之前速度还是旧的");

            // 直接播放：必须先把没回车的 BPM 落地
            SwingUtilities.invokeAndWait(view::togglePlayPause);
            assertEquals(90.0, view.document().tempoMap().bpmAt(0), 0.5,
                    "没按回车的 BPM 也要在播放前生效，实际 " + view.document().tempoMap().bpmAt(0));
        } finally {
            close(app, holder[0]);
        }
    }

    private static MidiDocumentView openDocument(AppContext app, MainFrame[] holder) throws Exception {
        MidiDocumentView[] holderView = new MidiDocumentView[1];
        SwingUtilities.invokeAndWait(() -> {
            holder[0] = new MainFrame(app);
            holder[0].setVisible(false);
            app.documents().newDocument();
            holderView[0] = app.documents().activeView();
        });
        return holderView[0];
    }

    private static JSpinner findSpinner(Container root) {
        if (root == null) {
            return null;
        }
        for (Component component : root.getComponents()) {
            if (component instanceof JSpinner spinner) {
                return spinner;
            }
            if (component instanceof Container container) {
                JSpinner nested = findSpinner(container);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
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
