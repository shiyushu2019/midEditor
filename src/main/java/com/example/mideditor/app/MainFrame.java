package com.example.mideditor.app;

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

import javax.swing.ButtonGroup;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JRadioButtonMenuItem;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

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
        setMinimumSize(new Dimension(1000, 660));
        setSize(1440, 920);
        setLocationRelativeTo(null);
        setFont(Theme.BASE_FONT);

        app.initialize(getRootPane());

        MenuBarBuilder menuBuilder = new MenuBarBuilder(app);
        setJMenuBar(menuBuilder.build());
        windowMenu = menuBuilder.windowMenu();

        add(new ToolBarBuilder(app).build(), BorderLayout.NORTH);
        dock.setCenter(workspace);
        add(dock, BorderLayout.CENTER);
        add(statusBar, BorderLayout.SOUTH);

        app.setDock(dock);
        app.setMainFrame(this);
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
            statusBar.setDirty(false);
            statusBar.setMessage("音频：" + app.synth().description());
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
        statusBar.setMessage("音轨 " + document.tracks().size()
                + " · 平行线 " + document.totalNoteCount()
                + " · PPQ " + document.ppq()
                + " · " + app.synth().description());
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
                    } catch (Exception ignored) {
                        // 忽略
                    }
                }
            });
            group.add(item);
            windowMenu.add(item);
        }
    }
}
