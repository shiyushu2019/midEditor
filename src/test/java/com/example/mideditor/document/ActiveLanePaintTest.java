package com.example.mideditor.document;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import org.junit.jupiter.api.Test;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.app.MainFrame;
import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.Track;
import com.example.mideditor.ui.pianoroll.PianoRollCanvas;
import com.example.mideditor.ui.theme.Theme;

/**
 * 活动音轨的左侧高亮条必须固定在最左边，不能跟着“局部重绘的 clip 起点”跑，
 * 否则播放头每重绘一条窄带就会在那里留下一根竖线（用户看到的“竖线状尾迹”）。
 */
class ActiveLanePaintTest {

    @Test
    void accentBarStaysAtTheLeftEdgeDuringPartialRepaint() throws Exception {
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
                Track track = viewHolder[0].activeTrack();
                track.addNote(new Note(60, 0, 480, 100));
                track.addNote(new Note(64, 960, 480, 100));
                track.sortNotes();
                viewHolder[0].afterEdit(true);
            });
            MidiDocumentView view = viewHolder[0];
            BufferedImage[] full = new BufferedImage[1];
            BufferedImage[] band = new BufferedImage[1];
            SwingUtilities.invokeAndWait(() -> {
                PianoRollCanvas canvas = view.pianoRoll().canvas();
                canvas.setSize(900, 260);
                canvas.doLayout();
                full[0] = paint(canvas, 900, 260, null);
                // 播放头局部重绘：只刷中间一条窄带
                band[0] = paint(canvas, 900, 260, new Rectangle(400, 0, 60, 260));
            });
            int laneMidY = 120;
            assertEquals(Theme.ACCENT.getRGB(), full[0].getRGB(1, laneMidY),
                    "整幅重绘时，最左边应有活动音轨高亮条");
            assertNotEquals(Theme.ACCENT.getRGB(), band[0].getRGB(402, laneMidY),
                    "局部重绘时不能在窄带起点又画一根高亮条（那就是播放头尾迹）");
            assertTrue(hasNoAccentAround(band[0], 400, 460, laneMidY),
                    "窄带范围内都不该出现高亮条");
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

    private static boolean hasNoAccentAround(BufferedImage image, int x1, int x2, int y) {
        int accent = Theme.ACCENT.getRGB();
        for (int x = x1; x < x2; x++) {
            if (image.getRGB(x, y) == accent) {
                return false;
            }
        }
        return true;
    }

    private static BufferedImage paint(PianoRollCanvas canvas, int width, int height, Rectangle clip) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, width, height);
        if (clip != null) {
            g.setClip(clip);
        }
        canvas.paint(g);
        g.dispose();
        return image;
    }
}
