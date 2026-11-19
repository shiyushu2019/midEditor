package com.example.mideditor.document;

import java.awt.GraphicsEnvironment;

import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import org.junit.jupiter.api.Test;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.app.MainFrame;
import com.example.mideditor.domain.Track;

/**
 * 软件是「纯净」的：关掉再开，不记忆上一次的速度与频谱缩放。
 * 新建文档永远是 60 BPM + 默认音域行数，任何状态都不落盘。
 */
class FreshInstanceStateTest {

    @Test
    void restartingForgetsTempoAndZoom() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");

        // 第一次运行：改成 150 BPM、把音域缩放到 60 行
        AppContext first = new AppContext();
        MainFrame[] firstFrame = new MainFrame[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                firstFrame[0] = new MainFrame(first);
                firstFrame[0].setVisible(false);
                first.documents().newDocument();
            });
            MidiDocumentView view = first.documents().activeView();
            SwingUtilities.invokeAndWait(() -> {
                view.document().tempoMap().setBpm(0, 150);
                view.setPitchRows(60);
            });
            assertEquals(400_000, view.document().tempoMap().microsPerQuarterAt(0), "150 BPM");
            assertEquals(60, view.activeTrack().pitchRows());
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                for (DocumentSession session : first.documents().sessions()) {
                    session.view().disposeView();
                    if (session.frame() != null) {
                        session.frame().dispose();
                    }
                }
                firstFrame[0].dispose();
                first.shutdown();
            });
        }

        // 第二次运行：全新实例，新建文档必须是默认值
        AppContext second = new AppContext();
        MainFrame[] secondFrame = new MainFrame[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                secondFrame[0] = new MainFrame(second);
                secondFrame[0].setVisible(false);
                second.documents().newDocument();
            });
            MidiDocumentView view = second.documents().activeView();
            assertEquals(1_000_000, view.document().tempoMap().microsPerQuarterAt(0),
                    "新实例的默认速度是 60 BPM，不记忆上次的 150 BPM");
            assertEquals(Track.VISIBLE_ROWS, view.activeTrack().pitchRows(),
                    "新实例的音域回到默认行数，不记忆上次的缩放");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                for (DocumentSession session : second.documents().sessions()) {
                    session.view().disposeView();
                    if (session.frame() != null) {
                        session.frame().dispose();
                    }
                }
                secondFrame[0].dispose();
                second.shutdown();
            });
        }
    }
}
