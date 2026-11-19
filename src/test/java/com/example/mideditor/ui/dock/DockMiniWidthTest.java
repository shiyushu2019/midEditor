package com.example.mideditor.ui.dock;

import java.awt.Component;

import javax.swing.JPanel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

/** 左侧工具窗口是迷你宽度：占窗口宽度四分之一，并夹在可读区间内。 */
class DockMiniWidthTest {

    /** 挂一个左区面板（区域没有面板时会被隐藏，宽度为 0），再按给定窗口尺寸布局。 */
    private static DockManager layoutAt(int width, int height) {
        DockManager dock = new DockManager();
        dock.register("probe", "音轨", new JPanel(), DockManager.Region.LEFT, true);
        dock.setSize(width, height);
        dock.doLayout();
        return dock;
    }

    private static Component leftOf(DockManager dock) {
        return dock.getComponent(0);
    }

    @Test
    void leftRegionIsAQuarterOfTheWindow() {
        DockManager dock = layoutAt(1200, 800);
        assertEquals(160, leftOf(dock).getWidth(), "1/4 是 300，超过可读上限 160 就夹住");
    }

    @Test
    void veryNarrowWindowKeepsAMinimumReadableWidth() {
        DockManager dock = layoutAt(400, 600);
        assertEquals(120, leftOf(dock).getWidth(), "1/4 是 100，太窄了要兜到 120");
    }

    @Test
    void wideningTheWindowDoesNotGrowTheMiniPanel() {
        DockManager dock = layoutAt(1600, 900);
        assertEquals(160, leftOf(dock).getWidth(), "再怎么拉宽窗口，迷你面板也是 160");
    }
}
