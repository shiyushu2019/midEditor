package com.example.mideditor.app;

import com.example.mideditor.command.AppCommandIds;
import com.example.mideditor.command.CommandContext;
import com.example.mideditor.command.CommandInstaller;
import com.example.mideditor.command.CommandRegistry;
import com.example.mideditor.command.CommandRouter;
import com.example.mideditor.document.DocumentManager;
import com.example.mideditor.document.MidiDocumentView;
import com.example.mideditor.document.NoteClipboard;
import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.Track;
import com.example.mideditor.engine.PlaybackEngine;
import com.example.mideditor.engine.SynthService;
import com.example.mideditor.ui.dock.DockManager;
import com.example.mideditor.util.Log;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;

/** 组合根：集中持有应用级服务，避免全局静态状态。 */
public final class AppContext {

    private final SynthService synth = new SynthService();
    private final PlaybackEngine playback = new PlaybackEngine(synth);
    private final DocumentManager documents = new DocumentManager(this);
    private final NoteClipboard clipboard = new NoteClipboard();
    private final List<Refreshable> toolPanels = new ArrayList<>();

    private CommandRegistry registry;
    private CommandRouter router;
    private MainFrame mainFrame;
    private DockManager dock;
    private boolean refreshing;

    public void initialize(JComponent keyTarget) {
        registry = new CommandRegistry(keyTarget);
        router = new CommandRouter(registry);
        router.setContextSupplier(this::currentContext);
        CommandInstaller.install(this);
        documents.addChangeListener(this::refreshApplicationState);
        playback.addListener(engine -> {
            refreshApplicationState();
            MidiDocumentView view = documents.activeView();
            if (view != null) {
                view.refresh();
            }
        });
    }

    public SynthService synth() {
        return synth;
    }

    public PlaybackEngine playback() {
        return playback;
    }

    public DocumentManager documents() {
        return documents;
    }

    public NoteClipboard clipboard() {
        return clipboard;
    }

    public CommandRegistry registry() {
        return registry;
    }

    public CommandRouter router() {
        return router;
    }

    public MainFrame mainFrame() {
        return mainFrame;
    }

    public void setMainFrame(MainFrame mainFrame) {
        this.mainFrame = mainFrame;
    }

    public DockManager dock() {
        return dock;
    }

    public void setDock(DockManager dock) {
        this.dock = dock;
    }

    public void registerToolPanel(Refreshable panel) {
        toolPanels.add(panel);
    }

    public MidiDocumentView activeView() {
        return documents.activeView();
    }

    public CommandContext currentContext() {
        MidiDocumentView view = documents.activeView();
        if (view == null) {
            return new CommandContext(this, null, null, List.of());
        }
        Track track = view.activeTrack();
        List<Note> selection = view.selection().resolve(view.document());
        return new CommandContext(this, view, track, selection);
    }

    public void refreshCommandStates() {
        if (router != null) {
            router.refreshStates();
        }
    }

    /** 文档 / 选区 / 活动音轨 / 播放状态变化后的统一刷新。 */
    public void refreshApplicationState() {
        if (refreshing) {
            return;
        }
        refreshing = true;
        try {
            if (router != null) {
                router.refreshStates();
            }
            javax.swing.Action playAction = router == null ? null : registry.action(AppCommandIds.PLAY_PLAY_PAUSE);
            if (playAction != null) {
                playAction.putValue(javax.swing.Action.SMALL_ICON,
                        playback.isPlaying() ? com.example.mideditor.ui.theme.Icons.PAUSE
                                : com.example.mideditor.ui.theme.Icons.PLAY);
            }
            MidiDocumentView view = documents.activeView();
            if (mainFrame != null) {
                mainFrame.updateTitle(view);
                mainFrame.updateStatus(view);
            }
            for (Refreshable panel : List.copyOf(toolPanels)) {
                try {
                    panel.refresh();
                } catch (Exception e) {
                    Log.warn("面板刷新失败：" + e.getMessage());
                }
            }
        } finally {
            refreshing = false;
        }
    }

    /** 后台打开音频后端，避免启动卡顿。 */
    public void startAudio() {
        Thread thread = new Thread(() -> {
            synth.open();
            Log.info("音频后端：" + synth.description());
            SwingUtilities.invokeLater(this::refreshApplicationState);
        }, "midEditor-audio-init");
        thread.setDaemon(true);
        thread.start();
    }

    public void shutdown() {
        playback.stop();
        synth.close();
    }
}
