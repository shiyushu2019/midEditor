package com.example.mideditor.app;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import org.junit.jupiter.api.Test;

/**
 * 主窗口默认最大化，且「还原尺寸」必须整个落在可用桌面区域之内 ——
 * 否则取消最大化后底部那 20px 状态栏会被任务栏/屏幕边缘挡掉。
 */
class WindowFitsScreenTest {

    @Test
    void mainWindowStaysInsideUsableDesktop() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "需要图形环境");
        AppContext app = new AppContext();
        MainFrame[] holder = new MainFrame[1];
        try {
            SwingUtilities.invokeAndWait(() -> holder[0] = new MainFrame(app));
            assertEquals(JFrame.MAXIMIZED_BOTH, holder[0].getExtendedState() & JFrame.MAXIMIZED_BOTH,
                    "主窗口默认就是最大化");
            Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            // 注意：show() 之后最大化窗口的 bounds 会在四边各多出几个像素（Windows 的隐形边框），
            // 所以这里校验的是「还原尺寸」（未 show 时的 bounds）
            Rectangle bounds = holder[0].getBounds();
            assertTrue(usable.contains(bounds),
                    "还原尺寸必须整个在可用桌面内：窗口 " + bounds + " / 可用 " + usable);
            assertTrue(holder[0].getHeight() >= holder[0].getMinimumSize().height,
                    "窗口高度不能小于最小高度");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                holder[0].dispose();
                app.shutdown();
            });
        }
    }
}
