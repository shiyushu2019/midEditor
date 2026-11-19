package com.example.mideditor.app;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.ButtonGroup;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JRadioButtonMenuItem;

import com.example.mideditor.command.AppCommandIds;
import com.example.mideditor.document.DocumentSession;
import com.example.mideditor.document.MidiDocumentView;
import com.example.mideditor.domain.GmProgram;
import com.example.mideditor.domain.Track;
import com.example.mideditor.mdi.MdiWorkspace;
import com.example.mideditor.ui.dock.DockManager;
import com.example.mideditor.ui.frame.MenuBarBuilder;
import com.example.mideditor.ui.frame.StatusBar;
import com.example.mideditor.ui.frame.ToolBarBuilder;
import com.example.mideditor.ui.theme.Theme;
import com.example.mideditor.ui.tools.ToolPanels;

/** 主框架窗口：标题栏 / 菜单栏 / 工具栏 / 停靠区 + MDI 工作区 / 状态栏。 */
public final class MainFrame extends JFrame {

    private final AppContext app;
    private final MdiWorkspace workspace = new MdiWorkspace();
    private final StatusBar statusBar = new StatusBar();
    private final DockManager dock = new DockManager();
    private final JMenu windowMenu;

    public MainFrame(AppContext app) {
        super("midEditor");
        this.app = app;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setLayout(new BorderLayout());
        // 窗口必须整个落在「可用桌面区域」之内，否则底部状态栏会被任务栏/屏幕边缘挡掉
        java.awt.Rectangle usable = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();
        Dimension minSize = new Dimension(Math.min(1000, usable.width), Math.min(660, usable.height));
        setMinimumSize(minSize);
        int width = Math.min(1440, usable.width);
        int height = Math.min(920, usable.height);
        setSize(Math.max(minSize.width, width), Math.max(minSize.height, height));
        setLocation(usable.x + Math.max(0, (usable.width - getWidth()) / 2),
                usable.y + Math.max(0, (usable.height - getHeight()) / 2));
        setFont(Theme.BASE_FONT);

        app.initialize(getRootPane());

        MenuBarBuilder menuBuilder = new MenuBarBuilder(app);
        setJMenuBar(menuBuilder.build());
        windowMenu = menuBuilder.windowMenu();

        add(new ToolBarBuilder(app).build(), BorderLayout.NORTH);
        dock.setCenter(workspace);
        add(dock, BorderLayout.CENTER);
        add(statusBar, BorderLayout.SOUTH);

        // 默认最大化：桌面软件一打开就占满可用桌面，不需要先拉边框
        setExtendedState(getExtendedState() | JFrame.MAXIMIZED_BOTH);

        app.setDock(dock);
        registerSelf();
        installFileDrop();
        ToolPanels.install(app, dock);

        app.documents().addChangeListener(this::rebuildWindowMenu);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                exitApplication();
            }
        });

        updateTitle(null);
        updateStatus(null);
        rebuildWindowMenu();
        app.startAudio();
    }

    public MdiWorkspace workspace() {
        return workspace;
    }

    /** 把自己登记到 AppContext，并装上 Alt/滚轮兼容修复。 */
    private void registerSelf() {
        app.setMainFrame(this);
        AltReleaseWheelFix.install(this);
    }

    /** 允许把 .mid 文件直接拖进窗口打开（窗口任意位置都可放下）。 */
    private void installFileDrop() {
        FileDropHandler handler = new FileDropHandler(app);
        setTransferHandler(handler);
        getRootPane().setTransferHandler(handler);
        workspace.setTransferHandler(handler);
        dock.setTransferHandler(handler);
    }

    public StatusBar statusBar() {
        return statusBar;
    }

    public DockManager dockManager() {
        return dock;
    }

    public void exitApplication() {
        if (app.documents().requestExit()) {
            app.shutdown();
            dispose();
            System.exit(0);
        }
    }

    public void updateTitle(MidiDocumentView view) {
        setTitle(view == null ? "midEditor" : "midEditor — " + view.title());
    }

    public void updateStatus(MidiDocumentView view) {
        if (view == null) {
            statusBar.setDocument("（无文档）");
            statusBar.setTrack("-");
            statusBar.setPosition(0, 0, 480);
            statusBar.setZoom(1);
            statusBar.clearSavedState();
            statusBar.setInfo("音频 " + (app.synth().available() ? "就绪" : "不可用"));
            return;
        }
        Track track = view.activeTrack();
        var document = view.document();
        statusBar.setDocument(document.displayName());
        statusBar.setTrack(track == null ? "-"
                : track.name() + " · " + GmProgram.name(track.program()));
        long tick = view.playheadTick();
        statusBar.setPosition(tick, document.tempoMap().microsAtTick(tick), document.ppq());
        statusBar.setZoom(view.pianoRoll().timeline().zoom());
        statusBar.setDirty(view.isDirty());
        statusBar.setInfo(document.tracks().size() + " 音轨 · "
                + document.totalNoteCount() + " 平行线 · PPQ " + document.ppq()
                + " · 音频 " + (app.synth().available() ? "就绪" : "不可用"));
    }

    /** 重建「窗口」菜单中的文档列表（MDI 窗口切换）。 */
    public void rebuildWindowMenu() {
        windowMenu.removeAll();
        windowMenu.add(app.registry().action(AppCommandIds.WINDOW_CASCADE));
        windowMenu.add(app.registry().action(AppCommandIds.WINDOW_TILE));
        windowMenu.add(app.registry().action(AppCommandIds.WINDOW_MINIMIZE_ALL));
        windowMenu.add(app.registry().action(AppCommandIds.WINDOW_RESTORE_ALL));
        windowMenu.addSeparator();
        windowMenu.add(app.registry().action(AppCommandIds.WINDOW_CLOSE));

        if (app.documents().sessionCount() == 0) {
            return;
        }
        windowMenu.addSeparator();
        ButtonGroup group = new ButtonGroup();
        DocumentSession active = app.documents().active();
        int index = 1;
        for (DocumentSession session : app.documents().sessions()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(index++ + " " + session.title());
            item.setFont(Theme.BASE_FONT);
            item.setSelected(session == active);
            item.addActionListener(e -> {
                app.documents().activate(session);
                if (session.frame() != null) {
                    try {
                        session.frame().setIcon(false);
                        session.frame().setSelected(true);
                    } catch (java.beans.PropertyVetoException ignored) {
                        // 子窗口状态被否决时忽略
                    }
                }
            });
            group.add(item);
            windowMenu.add(item);
        }
    }
}
