package com.example.mideditor.document;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import org.junit.jupiter.api.Test;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.app.MainFrame;
import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.Track;
import com.example.mideditor.ui.pianoroll.PianoRollCanvas;
import com.example.mideditor.ui.pianoroll.PianoRollView;
import com.example.mideditor.ui.theme.Theme;

/**
 * 播放头的「向下三角」只能有一个：标尺里那个。
 *
 * <p>
 * 画布里任何贴着视口边界画的装饰都扛不住垂直滚动 —— Swing 滚屏是「位块拷贝 + 只补新露出的一条带」，
 * 旧箭头像素会被连同内容搬走，屏上就留下一串旧箭头（音轨多时向上翻特别明显）。
 * 所以画布只画竖线，三角由顶部标尺（列头，不参与垂直滚动）负责。
 * </p>
 */
class PlayheadArrowPaintTest {

    @Test
    void canvasHasNoViewportPinnedArrowAndRulerKeepsTheOnlyOne() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            MidiDocumentView[] viewHolder = new MidiDocumentView[1];
            int[] geometry = new int[4]; // scrollY, laneHeight, playheadX, bandY
            SwingUtilities.invokeAndWait(() -> {
                holder[0] = new MainFrame(app);
                holder[0].setVisible(false);
                app.documents().newDocument();
                MidiDocumentView view = app.documents().activeView();
                viewHolder[0] = view;
                PianoRollView roll = view.pianoRoll();
                int laneHeight = roll.laneHeight();

                // 造出远超一屏的音轨，保证可以向下滚动
                int wanted = Math.max(6, (240 * 4) / Math.max(1, laneHeight) + 2);
                Track first = view.document().tracks().get(0);
                first.addNote(new Note(60, 0, 120, 100));
                first.sortNotes();
                for (int i = 1; i < wanted; i++) {
                    Track track = new Track("音轨 " + (i + 1), i % 16, 0, Color.BLUE);
                    track.addNote(new Note(60, 0, 120, 100));
                    track.sortNotes();
                    view.document().addTrack(track);
                }
                view.afterEdit(true);
                roll.relayout(true);

                // 让滚动容器拿到真实尺寸后再滚下去两屏
                roll.scrollPane().setSize(900, 240);
                roll.scrollPane().doLayout();
                roll.scrollPane().getViewport()
                        .setViewPosition(new Point(0, laneHeight * 2));
                roll.scrollPane().doLayout();

                int ppq = view.document().ppq();
                view.seekTick(roll.timeline().tickAtX(500, ppq));
                int playheadX = roll.timeline().xAtTick(view.playheadTick(), ppq);

                geometry[0] = roll.visibleRect().y;
                geometry[1] = laneHeight;
                geometry[2] = playheadX;
                geometry[3] = roll.visibleRect().y + laneHeight;
            });
            MidiDocumentView view = viewHolder[0];
            int scrollY = geometry[0];
            int laneHeight = geometry[1];
            int x = geometry[2];
            int bandY = geometry[3];
            assertEquals(laneHeight * 2, scrollY, "测试前提：画布确实被向下滚动了");

            BufferedImage[] atTop = new BufferedImage[1];
            BufferedImage[] below = new BufferedImage[1];
            BufferedImage[] ruler = new BufferedImage[1];
            SwingUtilities.invokeAndWait(() -> {
                PianoRollView roll = view.pianoRoll();
                PianoRollCanvas canvas = roll.canvas();
                canvas.setSize(canvas.getPreferredSize());
                canvas.doLayout();
                // 包含可视区顶端的重绘带：以前这里会画出一个「贴在视口顶」的三角
                atTop[0] = paint(canvas, new Rectangle(0, scrollY - 20, canvas.getWidth(), 40));
                // 与可视区顶端无关的条带（滚动时出现的典型条带）
                below[0] = paint(canvas, new Rectangle(0, bandY, canvas.getWidth(), laneHeight));
                // 标尺（列头）里那个才是唯一的三角
                roll.ruler().setSize(1200, Theme.RULER_HEIGHT);
                roll.ruler().doLayout();
                ruler[0] = paint(roll.ruler(), new Rectangle(0, 0, 1200, Theme.RULER_HEIGHT));
            });

            assertEquals(Theme.PLAYHEAD.getRGB(), atTop[0].getRGB(x, scrollY + 15),
                    "画布里的播放头竖线仍要画（可视区顶端这一带）");
            assertNotEquals(Theme.PLAYHEAD.getRGB(), atTop[0].getRGB(x + 2, scrollY + 3),
                    "画布不能在视口顶端画三角：垂直滚动时它会被位块拷贝搬走，留下一串旧箭头");

            assertEquals(Theme.PLAYHEAD.getRGB(), below[0].getRGB(x, bandY + laneHeight / 2),
                    "播放头竖线仍应画在这条重绘带里");
            assertNotEquals(Theme.PLAYHEAD.getRGB(), below[0].getRGB(x + 2, bandY + 3),
                    "重绘带顶端也不该冒出三角");

            assertEquals(Theme.PLAYHEAD.getRGB(), ruler[0].getRGB(x + 1, 13),
                    "三角应当由顶部标尺绘制（列头不参与垂直滚动，不会被搬走）");
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

    private static BufferedImage paint(JComponent component, Rectangle clip) {
        BufferedImage image = new BufferedImage(component.getWidth(), clip.y + clip.height,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.setClip(clip);
        component.paint(g);
        g.dispose();
        return image;
    }
}
