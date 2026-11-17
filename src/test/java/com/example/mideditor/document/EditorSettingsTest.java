package com.example.mideditor.document;

import java.awt.GraphicsEnvironment;

import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import org.junit.jupiter.api.Test;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.app.MainFrame;
import com.example.mideditor.domain.GridSettings;
import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.Track;

/** 编辑偏好：新建平行线默认长度、键盘标尺开关。 */
class EditorSettingsTest {

    @Test
    void defaultNoteDurationHonoursSettings() throws Exception {
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
            var grid = view.grid();

            SwingUtilities.invokeAndWait(() -> grid.setResolution(GridSettings.Resolution.SIXTEENTH));
            SwingUtilities.invokeAndWait(() -> grid.setNoteLength(GridSettings.NoteLength.FOLLOW_GRID));
            assertEquals(120, view.defaultNoteDuration());

            SwingUtilities.invokeAndWait(() -> grid.setNoteLength(GridSettings.NoteLength.QUARTER));
            assertEquals(480, view.defaultNoteDuration());

            SwingUtilities.invokeAndWait(() -> grid.setNoteLength(GridSettings.NoteLength.BAR));
            assertEquals(1920, view.defaultNoteDuration(), "4/4 下一小节 = 4 拍");

            SwingUtilities.invokeAndWait(() -> {
                grid.setCustomNoteTicks(333);
                grid.setNoteLength(GridSettings.NoteLength.CUSTOM);
            });
            assertEquals(333, view.defaultNoteDuration());

            // 新建平行线使用该默认长度
            SwingUtilities.invokeAndWait(() -> view.addNoteAt(view.activeTrack(), 0, 60));
            Note note = view.activeTrack().notes().get(0);
            assertEquals(333, note.durationTicks());
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                for (DocumentSession session : app.documents().sessions()) {
                    session.view().disposeView();
                    if (session.frame() != null) {
                        session.frame().dispose();
                    }
                }
                if (holder[0] != null) {
                    holder[0].dispose();
                }
                app.shutdown();
            });
        }
    }

    @Test
    void auditionSendsNoteThroughSynth() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                holder[0] = new MainFrame(app);
                holder[0].setVisible(false);
                app.documents().newDocument();
            });
            SwingUtilities.invokeAndWait(() -> app.synth().open());
            org.junit.jupiter.api.Assumptions.assumeTrue(app.synth().available(), "当前环境无可用音频设备");

            MidiDocumentView view = app.documents().activeView();
            Track track = view.activeTrack();
            SwingUtilities.invokeAndWait(() -> view.addNoteAt(track, 0, 60));
            Note note = track.notes().get(0);
            long before = app.synth().noteEventsSent();
            SwingUtilities.invokeAndWait(() -> view.auditionNote(track, note));
            assertTrue(app.synth().noteEventsSent() > before, "单击平行线应向合成器投递音符事件");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                for (DocumentSession session : app.documents().sessions()) {
                    session.view().disposeView();
                    if (session.frame() != null) {
                        session.frame().dispose();
                    }
                }
                if (holder[0] != null) {
                    holder[0].dispose();
                }
                app.shutdown();
            });
        }
    }

    @Test
    void keyboardRulerCanBeToggled() throws Exception {
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
            assertTrue(view.keyboardRulerVisible(), "默认应显示键盘标尺");
            SwingUtilities.invokeAndWait(view::toggleKeyboardRuler);
            assertFalse(view.keyboardRulerVisible());
            SwingUtilities.invokeAndWait(view::toggleKeyboardRuler);
            assertTrue(view.keyboardRulerVisible());
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                for (DocumentSession session : app.documents().sessions()) {
                    session.view().disposeView();
                    if (session.frame() != null) {
                        session.frame().dispose();
                    }
                }
                if (holder[0] != null) {
                    holder[0].dispose();
                }
                app.shutdown();
            });
        }
    }
}
