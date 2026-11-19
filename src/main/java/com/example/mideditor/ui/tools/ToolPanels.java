package com.example.mideditor.ui.tools;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.app.Refreshable;
import com.example.mideditor.ui.dock.DockIds;
import com.example.mideditor.ui.dock.DockManager;

import javax.swing.JComponent;

/** 注册全部工具窗口。 */
public final class ToolPanels {

    private ToolPanels() {
    }

    public static void install(AppContext app, DockManager dock) {
        register(app, dock, DockIds.TRACKS, "音轨", new TrackToolsPanels.TracksPanel(app),
                DockManager.Region.LEFT, true);
        register(app, dock, DockIds.INSTRUMENTS, "乐器", new TrackToolsPanels.InstrumentsPanel(app),
                DockManager.Region.LEFT, true);
        register(app, dock, DockIds.MIXER, "混音器", new TrackToolsPanels.MixerPanel(app),
                DockManager.Region.RIGHT, false);
        register(app, dock, DockIds.PROPERTIES, "属性", new InspectPanels.PropertiesPanel(app),
                DockManager.Region.RIGHT, false);
        register(app, dock, DockIds.GRID, "网格设置", new InspectPanels.GridPanel(app),
                DockManager.Region.RIGHT, false);
        register(app, dock, DockIds.DIAGNOSTICS, "诊断", new InspectPanels.DiagnosticsPanel(app),
                DockManager.Region.BOTTOM, false);
        register(app, dock, DockIds.OUTPUT, "输出", new InspectPanels.OutputPanel(),
                DockManager.Region.BOTTOM, false);
    }

    private static void register(AppContext app, DockManager dock, String id, String title,
            JComponent content, DockManager.Region region, boolean visible) {
        dock.register(id, title, content, region, visible);
        if (content instanceof Refreshable refreshable) {
            app.registerToolPanel(refreshable);
        }
    }
}
